package com.uply.coupon.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.common.exception.IdempotencyKeyReusedException;
import com.uply.coupon.common.exception.IdempotencyRequestInProgressException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis 없이 Mockito로 재현 가능한 acquire()의 상태 검증 로직(2-1)과, complete()/release()/renew()가 올바른 Lua 스크립트를
 * 올바른 인자로 호출하는지를 검증한다. ownerToken 비교·CAS 원자성 자체(Lua 내부 로직)는 Mock으로 검증할 수 없으므로 실제 Redis가 필요하다 -
 * {@link RedisIdempotencyCheckerConcurrencyTest}가 Testcontainers로 그 부분을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class RedisIdempotencyCheckerTest {

    @InjectMocks private RedisIdempotencyChecker idempotencyChecker;

    @Mock private StringRedisTemplate redisTemplate;

    @Mock private ValueOperations<String, String> valueOperations;

    @Mock private ObjectMapper objectMapper;

    private static final String IDEMPOTENCY_KEY = "test-key-1234";
    private static final String REDIS_KEY = "idempotency:" + IDEMPOTENCY_KEY;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        idempotencyChecker.init();
    }

    @Nested
    @DisplayName("acquire 메서드 테스트")
    class AcquireTest {

        // 멱등성 키가 없거나 공백이면 Redis에 접근하지 않고 그냥 선점된 것으로 취급한다.
        @Test
        @DisplayName("key가 null이거나 공백이면 Redis 접근 없이 acquired를 반환한다")
        void acquire_nullOrBlankKey_returnsAcquiredWithoutRedis() {
            assertThat(idempotencyChecker.acquire(null, "hash").acquired()).isTrue();
            assertThat(idempotencyChecker.acquire("", "hash").acquired()).isTrue();
            assertThat(idempotencyChecker.acquire("   ", "hash").acquired()).isTrue();

            verifyNoInteractions(redisTemplate);
        }

        // 최초 요청이 Redis PROCESSING 키 선점에 성공하고 ownerToken을 받는지 확인
        @Test
        @DisplayName("최초 요청 시 SETNX 선점에 성공하고 ownerToken이 채워진 acquired 결과를 반환한다")
        void acquire_firstRequest_returnsAcquiredWithOwnerToken() throws Exception {
            given(objectMapper.writeValueAsString(any())).willReturn("{\"status\":\"PROCESSING\"}");
            given(
                            valueOperations.setIfAbsent(
                                    eq(REDIS_KEY), anyString(), eq(Duration.ofSeconds(30))))
                    .willReturn(true);

            IdempotencyClaim result = idempotencyChecker.acquire(IDEMPOTENCY_KEY, "hash");

            assertThat(result.acquired()).isTrue();
            assertThat(result.ownerToken()).isNotBlank();
            assertThat(result.hasCachedResponse()).isFalse();
            verify(valueOperations, times(1))
                    .setIfAbsent(eq(REDIS_KEY), anyString(), eq(Duration.ofSeconds(30)));
            verify(valueOperations, never()).get(anyString());
        }

        // 동일한 키의 요청이 처리 중이면 중복 실행을 막는 전용 예외가 발생하는지 확인
        @Test
        @DisplayName("중복 요청 시 상태가 PROCESSING이면 전용 예외를 던진다")
        void acquire_processingState_throwsException() throws Exception {
            String processingJson = "{\"status\":\"PROCESSING\"}";
            IdempotencyCache processingCache =
                    IdempotencyCache.builder().status("PROCESSING").build();

            given(objectMapper.writeValueAsString(any())).willReturn(processingJson);
            given(
                            valueOperations.setIfAbsent(
                                    eq(REDIS_KEY), anyString(), eq(Duration.ofSeconds(30))))
                    .willReturn(false);
            given(valueOperations.get(REDIS_KEY)).willReturn(processingJson);
            given(objectMapper.readValue(processingJson, IdempotencyCache.class))
                    .willReturn(processingCache);

            assertThatThrownBy(() -> idempotencyChecker.acquire(IDEMPOTENCY_KEY, ""))
                    .isInstanceOf(IdempotencyRequestInProgressException.class);
        }

        // SETNX 선점 실패 후 캐시를 읽지 못해도 신규 요청으로 오인하지 않는지 확인
        @Test
        @DisplayName("선점 실패 후 캐시 데이터가 없으면 처리 중 예외를 던진다")
        void acquire_claimFailedAndCacheMissing_throwsException() throws Exception {
            given(objectMapper.writeValueAsString(any())).willReturn("{\"status\":\"PROCESSING\"}");
            given(
                            valueOperations.setIfAbsent(
                                    eq(REDIS_KEY), anyString(), eq(Duration.ofSeconds(30))))
                    .willReturn(false);
            given(valueOperations.get(REDIS_KEY)).willReturn(null);

            assertThatThrownBy(() -> idempotencyChecker.acquire(IDEMPOTENCY_KEY, ""))
                    .isInstanceOf(IdempotencyRequestInProgressException.class);

            verify(objectMapper, never()).readValue(anyString(), eq(IdempotencyCache.class));
        }

        // 처리가 완료됐고 body가 있으면 비즈니스 로직을 재실행하지 않고 최초 응답을 반환하는지 확인
        @Test
        @DisplayName("COMPLETED + body 존재 시 최초 응답을 담은 completed 결과를 반환한다")
        void acquire_completedWithBody_returnsCachedResponse() throws Exception {
            String completedJson =
                    "{\"status\":\"COMPLETED\",\"body\":\"{\\\"couponId\\\":\\\"100\\\"}\"}";
            IdempotencyCache completedCache =
                    IdempotencyCache.builder()
                            .status("COMPLETED")
                            .body("{\"couponId\":\"100\"}")
                            .build();

            given(objectMapper.writeValueAsString(any())).willReturn("{\"status\":\"PROCESSING\"}");
            given(
                            valueOperations.setIfAbsent(
                                    eq(REDIS_KEY), anyString(), eq(Duration.ofSeconds(30))))
                    .willReturn(false);
            given(valueOperations.get(REDIS_KEY)).willReturn(completedJson);
            given(objectMapper.readValue(completedJson, IdempotencyCache.class))
                    .willReturn(completedCache);

            IdempotencyClaim result = idempotencyChecker.acquire(IDEMPOTENCY_KEY, "");

            assertThat(result.hasCachedResponse()).isTrue();
            assertThat(result.cachedResponse()).isEqualTo("{\"couponId\":\"100\"}");
            assertThat(result.acquired()).isFalse();
        }

        // COMPLETED인데 body가 없는 비정상 캐시는 재실행을 차단하는지 확인 (2-1 핵심 수정 사항)
        @Test
        @DisplayName("COMPLETED인데 body가 없으면 재실행을 차단한다")
        void acquire_completedWithoutBody_blocksReexecution() throws Exception {
            String completedJson = "{\"status\":\"COMPLETED\"}";
            IdempotencyCache completedCache =
                    IdempotencyCache.builder().status("COMPLETED").body(null).build();

            given(objectMapper.writeValueAsString(any())).willReturn("{\"status\":\"PROCESSING\"}");
            given(
                            valueOperations.setIfAbsent(
                                    eq(REDIS_KEY), anyString(), eq(Duration.ofSeconds(30))))
                    .willReturn(false);
            given(valueOperations.get(REDIS_KEY)).willReturn(completedJson);
            given(objectMapper.readValue(completedJson, IdempotencyCache.class))
                    .willReturn(completedCache);

            assertThatThrownBy(() -> idempotencyChecker.acquire(IDEMPOTENCY_KEY, ""))
                    .isInstanceOf(IdempotencyRequestInProgressException.class);
        }

        // 알 수 없는 status는 재실행을 차단하는지 확인 (2-1 핵심 수정 사항)
        @Test
        @DisplayName("알 수 없는 status면 재실행을 차단한다")
        void acquire_unknownStatus_blocksReexecution() throws Exception {
            String weirdJson = "{\"status\":\"WEIRD\"}";
            IdempotencyCache weirdCache = IdempotencyCache.builder().status("WEIRD").build();

            given(objectMapper.writeValueAsString(any())).willReturn("{\"status\":\"PROCESSING\"}");
            given(
                            valueOperations.setIfAbsent(
                                    eq(REDIS_KEY), anyString(), eq(Duration.ofSeconds(30))))
                    .willReturn(false);
            given(valueOperations.get(REDIS_KEY)).willReturn(weirdJson);
            given(objectMapper.readValue(weirdJson, IdempotencyCache.class)).willReturn(weirdCache);

            assertThatThrownBy(() -> idempotencyChecker.acquire(IDEMPOTENCY_KEY, ""))
                    .isInstanceOf(IdempotencyRequestInProgressException.class);
        }

        // 같은 멱등성 키의 requestHash가 다르면 다른 요청으로 판단하여 재사용 예외를 발생시키는지 확인
        @Test
        @DisplayName("같은 키가 다른 요청에 재사용되면 전용 예외를 던진다")
        void acquire_differentRequestHash_throwsReusedException() throws Exception {
            String cachedJson = "{\"status\":\"PROCESSING\",\"requestHash\":\"hash-a\"}";
            IdempotencyCache processingCache =
                    IdempotencyCache.builder().status("PROCESSING").requestHash("hash-a").build();

            given(objectMapper.writeValueAsString(any())).willReturn(cachedJson);
            given(
                            valueOperations.setIfAbsent(
                                    eq(REDIS_KEY), anyString(), eq(Duration.ofSeconds(30))))
                    .willReturn(false);
            given(valueOperations.get(REDIS_KEY)).willReturn(cachedJson);
            given(objectMapper.readValue(cachedJson, IdempotencyCache.class))
                    .willReturn(processingCache);

            assertThatThrownBy(() -> idempotencyChecker.acquire(IDEMPOTENCY_KEY, "hash-b"))
                    .isInstanceOf(IdempotencyKeyReusedException.class);
        }

        // 선점 실패 후 캐시 역직렬화에 실패해도 신규 요청으로 오인하지 않는지 확인
        @Test
        @DisplayName("선점 실패 후 캐시 역직렬화에 실패하면 처리 중 예외를 던진다")
        void acquire_claimFailedAndJsonInvalid_throwsException() throws Exception {
            String invalidJson = "invalid-json";

            given(objectMapper.writeValueAsString(any())).willReturn("{\"status\":\"PROCESSING\"}");
            given(
                            valueOperations.setIfAbsent(
                                    eq(REDIS_KEY), anyString(), eq(Duration.ofSeconds(30))))
                    .willReturn(false);
            given(valueOperations.get(REDIS_KEY)).willReturn(invalidJson);
            given(objectMapper.readValue(invalidJson, IdempotencyCache.class))
                    .willThrow(new JsonProcessingException("Deserialization failed") {});

            assertThatThrownBy(() -> idempotencyChecker.acquire(IDEMPOTENCY_KEY, ""))
                    .isInstanceOf(IdempotencyRequestInProgressException.class);
        }
    }

    @Nested
    @DisplayName("complete/release/renew 메서드 테스트")
    class MutationTest {

        // null 키/ownerToken이면 Lua를 실행하지 않고 false를 반환하는지 확인
        @Test
        @DisplayName("key나 ownerToken이 없으면 Redis에 접근하지 않고 false를 반환한다")
        void complete_release_renew_missingArgs_doNothing() {
            assertThat(idempotencyChecker.complete(null, "owner", "hash", "{}", 200)).isFalse();
            assertThat(idempotencyChecker.complete(IDEMPOTENCY_KEY, null, "hash", "{}", 200))
                    .isFalse();
            assertThat(idempotencyChecker.release(null, "owner")).isFalse();
            assertThat(idempotencyChecker.release(IDEMPOTENCY_KEY, null)).isFalse();
            assertThat(idempotencyChecker.renew(null, "owner")).isFalse();
            assertThat(idempotencyChecker.renew(IDEMPOTENCY_KEY, null)).isFalse();

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("complete는 완료 JSON을 만들어 idempotency_complete Lua를 실행한다")
        void complete_executesCompleteScriptWithCorrectArgs() throws Exception {
            given(objectMapper.writeValueAsString(any(IdempotencyCache.class)))
                    .willReturn("{\"status\":\"COMPLETED\"}");
            given(
                            redisTemplate.execute(
                                    any(DefaultRedisScript.class),
                                    eq(List.of(REDIS_KEY)),
                                    eq("owner-1"),
                                    eq("hash-1"),
                                    eq("{\"status\":\"COMPLETED\"}"),
                                    eq(String.valueOf(Duration.ofMinutes(10).toMillis()))))
                    .willReturn(1L);

            boolean result =
                    idempotencyChecker.complete(
                            IDEMPOTENCY_KEY, "owner-1", "hash-1", "{\"body\":true}", 200);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("complete Lua가 0을 반환하면(소유권 상실) false를 반환한다")
        void complete_scriptReturnsZero_returnsFalse() throws Exception {
            given(objectMapper.writeValueAsString(any(IdempotencyCache.class)))
                    .willReturn("{\"status\":\"COMPLETED\"}");
            given(
                            redisTemplate.execute(
                                    any(DefaultRedisScript.class),
                                    anyList(),
                                    any(),
                                    any(),
                                    any(),
                                    any()))
                    .willReturn(0L);

            boolean result =
                    idempotencyChecker.complete(IDEMPOTENCY_KEY, "owner-1", "hash-1", "{}", 200);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("release는 idempotency_release Lua를 ownerToken과 함께 실행한다")
        void release_executesReleaseScriptWithOwnerToken() {
            given(
                            redisTemplate.execute(
                                    any(DefaultRedisScript.class),
                                    eq(List.of(REDIS_KEY)),
                                    eq("owner-1")))
                    .willReturn(1L);

            boolean result = idempotencyChecker.release(IDEMPOTENCY_KEY, "owner-1");

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("release Lua가 0을 반환하면(소유권 불일치) false를 반환한다")
        void release_scriptReturnsZero_returnsFalse() {
            given(
                            redisTemplate.execute(
                                    any(DefaultRedisScript.class),
                                    eq(List.of(REDIS_KEY)),
                                    eq("owner-1")))
                    .willReturn(0L);

            boolean result = idempotencyChecker.release(IDEMPOTENCY_KEY, "owner-1");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("renew는 idempotency_renew Lua를 PROCESSING_TTL(30초)과 함께 실행한다")
        void renew_executesRenewScriptWithProcessingTtl() {
            given(
                            redisTemplate.execute(
                                    any(DefaultRedisScript.class),
                                    eq(List.of(REDIS_KEY)),
                                    eq("owner-1"),
                                    eq(String.valueOf(Duration.ofSeconds(30).toMillis()))))
                    .willReturn(1L);

            boolean result = idempotencyChecker.renew(IDEMPOTENCY_KEY, "owner-1");

            assertThat(result).isTrue();
        }
    }
}
