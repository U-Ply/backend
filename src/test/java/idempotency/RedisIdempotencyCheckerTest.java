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

        @Test
        @DisplayName("key가 null이거나 공백이면 Optional.empty()를 반환한다")
        void getCachedResponse_nullOrBlankKey_returnsEmpty() {
            assertThat(idempotencyChecker.getCachedResponse(null)).isEmpty();
            assertThat(idempotencyChecker.getCachedResponse("")).isEmpty();
            assertThat(idempotencyChecker.getCachedResponse("   ")).isEmpty();

            verifyNoInteractions(redisTemplate);
        }

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

        @Test
        @DisplayName("역직렬화 실패 시 예외를 던지지 않고 Optional.empty()를 반환한다")
        void getCachedResponse_jsonException_returnsEmpty() throws Exception {
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

            // when
            Optional<String> result = idempotencyChecker.getCachedResponse(IDEMPOTENCY_KEY);

            // then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("cacheResponse 메서드 테스트")
    class CacheResponseTest {

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

        @Test
        @DisplayName("키 삭제 요청 시 redisTemplate.delete를 호출한다")
        void clearProgress_success() {
            // when
            idempotencyChecker.clearProgress(IDEMPOTENCY_KEY);

            // then
            verify(redisTemplate, times(1)).delete(REDIS_KEY);
        }

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
