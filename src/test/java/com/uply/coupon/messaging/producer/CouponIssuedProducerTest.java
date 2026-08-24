package com.uply.coupon.messaging.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.repository.CouponIssuanceProgressRepository;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import com.uply.coupon.messaging.event.CouponIssuedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.CannotCreateTransactionException;

@ExtendWith(MockitoExtension.class)
class CouponIssuedProducerTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private ObjectMapper objectMapper;
    @Mock private CouponIssuanceProgressRepository progressRepository;

    // Micrometer 메트릭 수집 테스트를 위해 SimpleMeterRegistry 주입
    @Spy private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks private CouponIssuedProducer couponIssuedProducer;

    private long expireAtEpochMillis = 1780000000000L;
    private LocalDateTime expireAt =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(expireAtEpochMillis), ZoneOffset.UTC);

    // 상위 전략이 전달하는 발급 시각. 이벤트의 issuedAt으로 그대로 실린다.
    private long issuedAtEpochMillis = 1770000000000L;
    private LocalDateTime issuedAt =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(issuedAtEpochMillis), ZoneOffset.UTC);

    @Test
    @DisplayName("JSON 직렬화 및 Kafka 동기 발행에 성공한다")
    void save_Success() throws Exception {
        // given
        Long couponId = 1000L;
        Long userId = 100L;
        Long campaignId = 1L;
        Long stockId = 10L;
        String idempotencyKey = "idempotency-key-123";
        String expectedJson = "{\"couponId\":1000}";

        given(objectMapper.writeValueAsString(any(CouponIssuedEvent.class)))
                .willReturn(expectedJson);
        given(
                        kafkaTemplate.send(
                                eq("coupon-issued"),
                                eq(String.valueOf(couponId)),
                                eq(expectedJson)))
                .willReturn(CompletableFuture.completedFuture(null));

        // when
        couponIssuedProducer.save(
                couponId, userId, campaignId, stockId, idempotencyKey, issuedAt, expireAt);

        // then
        verify(progressRepository).markPending(couponId);
        verify(progressRepository, never()).clear(couponId);
        verify(kafkaTemplate)
                .send(eq("coupon-issued"), eq(String.valueOf(couponId)), eq(expectedJson));

        // 이벤트의 두 시각 필드는 출처가 다르다 (D-7)
        ArgumentCaptor<CouponIssuedEvent> eventCaptor =
                ArgumentCaptor.forClass(CouponIssuedEvent.class);
        verify(objectMapper).writeValueAsString(eventCaptor.capture());
        CouponIssuedEvent published = eventCaptor.getValue();

        // 1. Long 타입 필드들의 순서 및 값 일치 검증 (인자 섞임 방지)
        assertThat(published.couponId()).isEqualTo(couponId);
        assertThat(published.userId()).isEqualTo(userId);
        assertThat(published.campaignId()).isEqualTo(campaignId);
        assertThat(published.stockId()).isEqualTo(stockId);

        // 2. 멱등성 키 및 만료 시각(expireAt) 변환 값 검증
        assertThat(published.idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(published.expireAt()).isEqualTo(expireAt.toInstant(ZoneOffset.UTC));

        // issuedAt은 전략이 확정한 발급 시각 그대로여야 한다 (여기서 새로 만들면 D-1이 깨진다)
        assertThat(published.issuedAt()).isEqualTo(issuedAt.toInstant(ZoneOffset.UTC));

        // publishedAt은 발행 시점의 JVM 시각이므로 issuedAt과 달라야 한다 (E2E 측정 기준점)
        assertThat(published.publishedAt()).isNotNull();
        assertThat(published.publishedAt()).isNotEqualTo(published.issuedAt());
    }

    @Test
    @DisplayName("JSON 직렬화 실패 시 100% 미발행이므로 KAFKA_PUBLISH_FAILED 예외가 발생한다")
    void save_SerializationFailed_ThrowsKafkaPublishFailed() throws Exception {
        // given
        Long couponId = 1000L;
        Long userId = 100L;
        Long campaignId = 1L;
        Long stockId = 10L;
        String idempotencyKey = "idempotency-key-123";

        given(objectMapper.writeValueAsString(any(CouponIssuedEvent.class)))
                .willThrow(new JsonProcessingException("Serialization Error") {});

        // when & then
        assertThatThrownBy(
                        () ->
                                couponIssuedProducer.save(
                                        couponId,
                                        userId,
                                        campaignId,
                                        stockId,
                                        idempotencyKey,
                                        issuedAt,
                                        expireAt))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.KAFKA_PUBLISH_FAILED);
    }

    @Test
    @DisplayName("Kafka 전송 중 TimeoutException 발생 시 결과 불명확 상태인 KAFKA_PUBLISH_UNKNOWN 예외가 발생한다")
    void save_Timeout_ThrowsKafkaPublishUnknown() throws Exception {
        // given
        Long couponId = 1000L;
        Long userId = 100L;
        Long campaignId = 1L;
        Long stockId = 10L;
        String idempotencyKey = "idempotency-key-123";
        String expectedJson = "{\"couponId\":1000}";

        @SuppressWarnings("unchecked")
        CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> future =
                mock(CompletableFuture.class);

        given(objectMapper.writeValueAsString(any(CouponIssuedEvent.class)))
                .willReturn(expectedJson);
        given(
                        kafkaTemplate.send(
                                eq("coupon-issued"),
                                eq(String.valueOf(couponId)),
                                eq(expectedJson)))
                .willReturn(future);

        // get() 호출 시 TimeoutException 발생
        given(future.get(anyLong(), any())).willThrow(new TimeoutException("Kafka Timeout"));

        // when & then
        assertThatThrownBy(
                        () ->
                                couponIssuedProducer.save(
                                        couponId,
                                        userId,
                                        campaignId,
                                        stockId,
                                        idempotencyKey,
                                        issuedAt,
                                        expireAt))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.SAVE_RESULT_UNKNOWN);
        verify(progressRepository).markPending(couponId);
        verify(progressRepository, never()).clear(couponId);
    }

    @Test
    @DisplayName("Kafka 전송 중 InterruptedException 발생 시 결과 불명확 상태인 KAFKA_PUBLISH_UNKNOWN 예외가 발생한다")
    void save_Interrupted_ThrowsKafkaPublishUnknown() throws Exception {
        // given
        Long couponId = 1000L;
        Long userId = 100L;
        Long campaignId = 1L;
        Long stockId = 10L;
        String idempotencyKey = "idempotency-key-123";
        String expectedJson = "{\"couponId\":1000}";

        @SuppressWarnings("unchecked")
        CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> future =
                mock(CompletableFuture.class);

        given(objectMapper.writeValueAsString(any(CouponIssuedEvent.class)))
                .willReturn(expectedJson);
        given(
                        kafkaTemplate.send(
                                eq("coupon-issued"),
                                eq(String.valueOf(couponId)),
                                eq(expectedJson)))
                .willReturn(future);

        // get() 호출 시 InterruptedException 발생
        given(future.get(anyLong(), any())).willThrow(new InterruptedException("Interrupted"));

        // when & then
        assertThatThrownBy(
                        () ->
                                couponIssuedProducer.save(
                                        couponId,
                                        userId,
                                        campaignId,
                                        stockId,
                                        idempotencyKey,
                                        issuedAt,
                                        expireAt))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.SAVE_RESULT_UNKNOWN);
    }

    @Test
    @DisplayName("Kafka 전송 중 ExecutionException(브로커 거절) 발생 시 KAFKA_PUBLISH_FAILED 예외가 발생한다")
    void save_ExecutionException_ThrowsKafkaPublishFailed() throws Exception {
        // given
        Long couponId = 1000L;
        Long userId = 100L;
        Long campaignId = 1L;
        Long stockId = 10L;
        String idempotencyKey = "idempotency-key-123";
        String expectedJson = "{\"couponId\":1000}";

        CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> future =
                new CompletableFuture<>();
        future.completeExceptionally(
                new ExecutionException("Broker Error", new RuntimeException()));

        given(objectMapper.writeValueAsString(any(CouponIssuedEvent.class)))
                .willReturn(expectedJson);
        given(
                        kafkaTemplate.send(
                                eq("coupon-issued"),
                                eq(String.valueOf(couponId)),
                                eq(expectedJson)))
                .willReturn(future);

        // when & then
        assertThatThrownBy(
                        () ->
                                couponIssuedProducer.save(
                                        couponId,
                                        userId,
                                        campaignId,
                                        stockId,
                                        idempotencyKey,
                                        issuedAt,
                                        expireAt))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.KAFKA_PUBLISH_FAILED);
        verify(progressRepository).markPending(couponId);
        verify(progressRepository).clear(couponId);
    }

    @Test
    @DisplayName("Kafka 발행 성공 시 성공 카운트 메트릭이 1 증가한다")
    void save_Success_IncrementsSuccessMetric() throws Exception {
        // given
        Long couponId = 1000L;
        String expectedJson = "{\"couponId\":1000}";

        given(objectMapper.writeValueAsString(any(CouponIssuedEvent.class)))
                .willReturn(expectedJson);
        given(
                        kafkaTemplate.send(
                                eq("coupon-issued"),
                                eq(String.valueOf(couponId)),
                                eq(expectedJson)))
                .willReturn(CompletableFuture.completedFuture(null));

        // when
        couponIssuedProducer.save(
                couponId, 100L, 1L, 10L, "idempotency-key-123", issuedAt, expireAt);

        // then: SimpleMeterRegistry에 기록된 카운터 값 직접 검증
        double count =
                meterRegistry
                        .get("kafka.producer.publish.count")
                        .tag("result", "success")
                        .counter()
                        .count();

        assertThat(count).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Kafka Timeout 발생 시 failure 및 cause=timeout 메트릭이 1 증가한다")
    void save_Timeout_IncrementsFailureMetric() throws Exception {
        // given
        Long couponId = 1000L;
        String expectedJson = "{\"couponId\":1000}";

        @SuppressWarnings("unchecked")
        CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> future =
                mock(CompletableFuture.class);

        given(objectMapper.writeValueAsString(any(CouponIssuedEvent.class)))
                .willReturn(expectedJson);
        given(kafkaTemplate.send(any(), any(), any())).willReturn(future);
        given(future.get(anyLong(), any())).willThrow(new TimeoutException("Timeout"));

        // when & then
        assertThatThrownBy(
                        () ->
                                couponIssuedProducer.save(
                                        couponId, 100L, 1L, 10L, "key-123", issuedAt, expireAt))
                .isInstanceOf(CouponIssueException.class);

        // then: 실패 메트릭 검증
        double count =
                meterRegistry
                        .get("kafka.producer.publish.count")
                        .tag("result", "failure")
                        .tag("cause", "timeout")
                        .counter()
                        .count();

        assertThat(count).isEqualTo(1.0);
    }

    @Test
    @DisplayName("markPending 단계의 커넥션 풀 획득 실패는 CONNECTION_UNAVAILABLE로 변환되고 Kafka는 발행되지 않는다")
    void save_MarkPendingCannotCreateTransactionException_ThrowsConnectionUnavailable() {
        // given
        Long couponId = 1000L;
        Long userId = 100L;
        Long campaignId = 1L;
        Long stockId = 10L;
        String idempotencyKey = "idempotency-key-123";

        willThrow(
                        new CannotCreateTransactionException(
                                "Could not open JPA EntityManager for transaction"))
                .given(progressRepository)
                .markPending(couponId);

        // when & then
        assertThatThrownBy(
                        () ->
                                couponIssuedProducer.save(
                                        couponId,
                                        userId,
                                        campaignId,
                                        stockId,
                                        idempotencyKey,
                                        issuedAt,
                                        expireAt))
                .isInstanceOf(CouponIssueException.class)
                .hasCauseInstanceOf(CannotCreateTransactionException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.CONNECTION_UNAVAILABLE);

        verify(kafkaTemplate, never())
                .send(any(String.class), any(String.class), any(String.class));
    }
}
