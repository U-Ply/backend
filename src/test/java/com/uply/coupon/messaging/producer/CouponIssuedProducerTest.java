package com.uply.coupon.messaging.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import com.uply.coupon.messaging.event.CouponIssuedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class CouponIssuedProducerTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private ObjectMapper objectMapper;

    // Micrometer 메트릭 수집 테스트를 위해 SimpleMeterRegistry 주입
    @Spy private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks private CouponIssuedProducer couponIssuedProducer;

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
        couponIssuedProducer.save(couponId, userId, campaignId, stockId, idempotencyKey);

        // then
        verify(objectMapper).writeValueAsString(any(CouponIssuedEvent.class));
        verify(kafkaTemplate)
                .send(eq("coupon-issued"), eq(String.valueOf(couponId)), eq(expectedJson));
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
                                        couponId, userId, campaignId, stockId, idempotencyKey))
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
                                        couponId, userId, campaignId, stockId, idempotencyKey))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.KAFKA_PUBLISH_UNKNOWN);
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
                                        couponId, userId, campaignId, stockId, idempotencyKey))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.KAFKA_PUBLISH_UNKNOWN);
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
        future.completeExceptionally(new ExecutionException("Broker Error", new RuntimeException()));

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
                                        couponId, userId, campaignId, stockId, idempotencyKey))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.KAFKA_PUBLISH_FAILED);
    }
    
    @Test
    @DisplayName("Kafka 발행 성공 시 성공 카운트 메트릭이 1 증가한다")
    void save_Success_IncrementsSuccessMetric() throws Exception {
        // given
        Long couponId = 1000L;
        String expectedJson = "{\"couponId\":1000}";

        given(objectMapper.writeValueAsString(any(CouponIssuedEvent.class)))
                .willReturn(expectedJson);
        given(kafkaTemplate.send(eq("coupon-issued"), eq(String.valueOf(couponId)), eq(expectedJson)))
                .willReturn(CompletableFuture.completedFuture(null));

        // when
        couponIssuedProducer.save(couponId, 100L, 1L, 10L, "idempotency-key-123");

        // then: SimpleMeterRegistry에 기록된 카운터 값 직접 검증
        double count = meterRegistry.get("kafka.producer.publish.count")
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
        CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> future = mock(CompletableFuture.class);

        given(objectMapper.writeValueAsString(any(CouponIssuedEvent.class))).willReturn(expectedJson);
        given(kafkaTemplate.send(any(), any(), any())).willReturn(future);
        given(future.get(anyLong(), any())).willThrow(new TimeoutException("Timeout"));

        // when & then
        assertThatThrownBy(() -> couponIssuedProducer.save(couponId, 100L, 1L, 10L, "key-123"))
                .isInstanceOf(CouponIssueException.class);

        // then: 실패 메트릭 검증
        double count = meterRegistry.get("kafka.producer.publish.count")
                .tag("result", "failure")
                .tag("cause", "timeout")
                .counter()
                .count();

        assertThat(count).isEqualTo(1.0);
    }
}