package com.uply.coupon.messaging.producer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import com.uply.coupon.messaging.event.CouponIssuedEvent;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class CouponIssuedProducerTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private ObjectMapper objectMapper;

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
    @DisplayName("JSON 직렬화 실패 시 IllegalStateException 예외가 발생한다")
    void save_SerializationFailed_ThrowsException() throws Exception {
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
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Kafka 메시지 직렬화 중 오류가 발생했습니다.");
    }

    @Test
    @DisplayName("Kafka 전송 타임아웃 또는 실패 시 KAFKA_PUBLISH_FAILED 예외가 발생한다")
    void save_KafkaPublishFailed_ThrowsException() throws Exception {
        // given
        Long couponId = 1000L;
        Long userId = 100L;
        Long campaignId = 1L;
        Long stockId = 10L;
        String idempotencyKey = "idempotency-key-123";
        String expectedJson = "{\"couponId\":1000}";

        CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> future =
                new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Kafka Broker Unavailable"));

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
}
