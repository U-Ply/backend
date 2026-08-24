package idempotency;

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
import com.uply.coupon.common.idempotency.IdempotencyCache;
import com.uply.coupon.common.idempotency.RedisIdempotencyChecker;
import java.time.Duration;
import java.util.Optional;
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
    }

    @Nested
    @DisplayName("getCachedResponse 메서드 테스트")
    class GetCachedResponseTest {

        // 멱등성 키가 없거나 공백이면 Redis에 접근하지 않고 빈 결과를 반환하는지 확인
        @Test
        @DisplayName("key가 null이거나 공백이면 Optional.empty()를 반환한다")
        void getCachedResponse_nullOrBlankKey_returnsEmpty() {
            assertThat(idempotencyChecker.getCachedResponse(null)).isEmpty();
            assertThat(idempotencyChecker.getCachedResponse("")).isEmpty();
            assertThat(idempotencyChecker.getCachedResponse("   ")).isEmpty();

            verifyNoInteractions(redisTemplate);
        }

        // 최초 요청이 Redis PROCESSING 키 선점에 성공하고 비즈니스 로직 진행 신호를 받는지 확인
        @Test
        @DisplayName("최초 요청 시 SETNX 선점에 성공하고 Optional.empty()를 반환한다")
        void getCachedResponse_firstRequest_returnsEmpty() throws Exception {
            // given
            given(objectMapper.writeValueAsString(any())).willReturn("{\"status\":\"PROCESSING\"}");
            given(
                            valueOperations.setIfAbsent(
                                    eq(REDIS_KEY), anyString(), eq(Duration.ofSeconds(30))))
                    .willReturn(true);

            // when
            Optional<String> result = idempotencyChecker.getCachedResponse(IDEMPOTENCY_KEY);

            // then
            assertThat(result).isEmpty();
            verify(valueOperations, times(1))
                    .setIfAbsent(eq(REDIS_KEY), anyString(), eq(Duration.ofSeconds(30)));
            verify(valueOperations, never()).get(anyString());
        }

        // 동일한 키의 요청이 처리 중이면 중복 실행을 막는 전용 예외가 발생하는지 확인
        @Test
        @DisplayName("중복 요청 시 상태가 PROCESSING이면 전용 예외를 던진다")
        void getCachedResponse_processingState_throwsException() throws Exception {
            // given
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

            // when & then
            assertThatThrownBy(() -> idempotencyChecker.getCachedResponse(IDEMPOTENCY_KEY))
                    .isInstanceOf(IdempotencyRequestInProgressException.class)
                    .hasMessageContaining("already in progress");
        }

        // SETNX 선점 실패 후 캐시를 읽지 못해도 신규 요청으로 오인하지 않는지 확인
        @Test
        @DisplayName("선점 실패 후 캐시 데이터가 없으면 처리 중 예외를 던진다")
        void getCachedResponse_claimFailedAndCacheMissing_throwsException() throws Exception {
            given(objectMapper.writeValueAsString(any())).willReturn("{\"status\":\"PROCESSING\"}");
            given(
                            valueOperations.setIfAbsent(
                                    eq(REDIS_KEY), anyString(), eq(Duration.ofSeconds(30))))
                    .willReturn(false);
            given(valueOperations.get(REDIS_KEY)).willReturn(null);

            assertThatThrownBy(() -> idempotencyChecker.getCachedResponse(IDEMPOTENCY_KEY))
                    .isInstanceOf(IdempotencyRequestInProgressException.class)
                    .hasMessageContaining("already in progress");

            verify(objectMapper, never()).readValue(anyString(), eq(IdempotencyCache.class));
        }

        // 동일한 키의 처리가 완료됐다면 비즈니스 로직을 재실행하지 않고 최초 응답을 반환하는지 확인
        @Test
        @DisplayName("중복 요청 시 상태가 COMPLETED이면 캐시된 응답 body를 반환한다")
        void getCachedResponse_completedState_returnsCachedBody() throws Exception {
            // given
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

            // when
            Optional<String> result = idempotencyChecker.getCachedResponse(IDEMPOTENCY_KEY);

            // then
            assertThat(result).isPresent().contains("{\"couponId\":\"100\"}");
        }

        // 같은 멱등성 키의 requestHash가 다르면 다른 요청으로 판단하여 재사용 예외를 발생시키는지 확인
        @Test
        @DisplayName("같은 키가 다른 요청에 재사용되면 전용 예외를 던진다")
        void getCachedResponse_differentRequestHash_throwsReusedException() throws Exception {
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

            assertThatThrownBy(
                            () -> idempotencyChecker.getCachedResponse(IDEMPOTENCY_KEY, "hash-b"))
                    .isInstanceOf(IdempotencyKeyReusedException.class);
        }

        // 선점 실패 후 캐시 역직렬화에 실패해도 신규 요청으로 오인하지 않는지 확인
        @Test
        @DisplayName("선점 실패 후 캐시 역직렬화에 실패하면 처리 중 예외를 던진다")
        void getCachedResponse_claimFailedAndJsonInvalid_throwsException() throws Exception {
            // given
            String invalidJson = "invalid-json";

            given(objectMapper.writeValueAsString(any())).willReturn("{\"status\":\"PROCESSING\"}");
            given(
                            valueOperations.setIfAbsent(
                                    eq(REDIS_KEY), anyString(), eq(Duration.ofSeconds(30))))
                    .willReturn(false);
            given(valueOperations.get(REDIS_KEY)).willReturn(invalidJson);
            given(objectMapper.readValue(invalidJson, IdempotencyCache.class))
                    .willThrow(new JsonProcessingException("Deserialization failed") {});

            // when & then
            assertThatThrownBy(() -> idempotencyChecker.getCachedResponse(IDEMPOTENCY_KEY))
                    .isInstanceOf(IdempotencyRequestInProgressException.class)
                    .hasMessageContaining("already in progress");
        }
    }

    @Nested
    @DisplayName("cacheResponse 메서드 테스트")
    class CacheResponseTest {

        // 완료 응답을 Redis에 COMPLETED 상태와 10분 TTL로 저장하는지 확인
        @Test
        @DisplayName("정상 호출 시 10분 TTL과 함께 COMPLETED 데이터를 Redis에 저장한다")
        void cacheResponse_success() throws Exception {
            // given
            String responseBody = "{\"couponId\":\"100\"}";
            String cacheJson = "{\"status\":\"COMPLETED\",\"body\":\"...\"}";

            given(objectMapper.writeValueAsString(any(IdempotencyCache.class)))
                    .willReturn(cacheJson);

            // when
            idempotencyChecker.cacheResponse(IDEMPOTENCY_KEY, responseBody, 200);

            // then
            verify(valueOperations, times(1))
                    .set(eq(REDIS_KEY), eq(cacheJson), eq(Duration.ofMinutes(10)));
        }

        // 완료 응답을 저장할 때 최초 요청의 requestHash와 상태 코드·본문을 함께 저장하는지 확인
        @Test
        @DisplayName("완료 응답을 저장할 때 최초 요청의 requestHash도 함께 저장한다")
        void cacheResponse_withRequestHash_savesHash() throws Exception {
            String responseBody = "{\"campaignId\":10,\"revokedCount\":2}";
            String cacheJson = "{\"status\":\"COMPLETED\"}";
            org.mockito.ArgumentCaptor<IdempotencyCache> cacheCaptor =
                    org.mockito.ArgumentCaptor.forClass(IdempotencyCache.class);
            given(objectMapper.writeValueAsString(cacheCaptor.capture())).willReturn(cacheJson);

            idempotencyChecker.cacheResponse(IDEMPOTENCY_KEY, "request-hash", responseBody, 200);

            assertThat(cacheCaptor.getValue().getRequestHash()).isEqualTo("request-hash");
            assertThat(cacheCaptor.getValue().getBody()).isEqualTo(responseBody);
            assertThat(cacheCaptor.getValue().getHttpStatus()).isEqualTo(200);
            verify(valueOperations).set(eq(REDIS_KEY), eq(cacheJson), eq(Duration.ofMinutes(10)));
        }

        // 멱등성 키가 null이면 완료 응답을 Redis에 저장하지 않는지 확인
        @Test
        @DisplayName("key가 null이면 Redis에 저장하지 않는다")
        void cacheResponse_nullKey_doesNothing() {
            // when
            idempotencyChecker.cacheResponse(null, "body", 200);

            // then
            verifyNoInteractions(redisTemplate);
        }
    }

    @Nested
    @DisplayName("clearProgress 메서드 테스트")
    class ClearProgressTest {

        // 처리 실패 후 재시도를 허용하기 위해 Redis PROCESSING 키를 삭제하는지 확인
        @Test
        @DisplayName("키 삭제 요청 시 redisTemplate.delete를 호출한다")
        void clearProgress_success() {
            // when
            idempotencyChecker.clearProgress(IDEMPOTENCY_KEY);

            // then
            verify(redisTemplate, times(1)).delete(REDIS_KEY);
        }

        // 멱등성 키가 null이면 Redis 삭제를 실행하지 않는지 확인
        @Test
        @DisplayName("key가 null이면 delete를 호출하지 않는다")
        void clearProgress_nullKey_doesNothing() {
            // when
            idempotencyChecker.clearProgress(null);

            // then
            verifyNoInteractions(redisTemplate);
        }
    }
}
