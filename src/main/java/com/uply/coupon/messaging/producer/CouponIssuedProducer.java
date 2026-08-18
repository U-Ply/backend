package com.uply.coupon.messaging.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import com.uply.coupon.coupon.strategy.save.CouponSaveStrategy;
import com.uply.coupon.messaging.event.CouponIssuedEvent;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** CouponSaveStrategy 전략 中 1 : Kafka 이벤트 발행 */
@Slf4j
@Component
@ConditionalOnProperty(name = "coupon.save.strategy", havingValue = "kafka", matchIfMissing = true)
@RequiredArgsConstructor
public class CouponIssuedProducer implements CouponSaveStrategy {

    private static final String TOPIC_NAME = "coupon-issued";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /** Kafka 쿠폰 발행 이벤트 전송 */
    @Override
    public void save(
            Long couponId, Long userId, Long campaignId, Long stockId, String idempotencyKey) {
        // #1. 이벤트 객체 생성
        CouponIssuedEvent event =
                new CouponIssuedEvent(
                        couponId, userId, campaignId, stockId, idempotencyKey, Instant.now());

        // #2. CouponIssuedEvent 객체를 JSON 문자열로 직렬화
        String jsonPayload = toJson(event);

        // #3. Kafka 전송 및 발행 실패 보상 로직 (Timeout 3초)
        try {
            // 1. Kafka 전송
            kafkaTemplate
                    .send(TOPIC_NAME, String.valueOf(couponId), jsonPayload)
                    .get(3, TimeUnit.SECONDS);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Kafka 메시지 전송 중 스레드 인터럽트 발생 - couponId: {}", couponId, e);
            throw new CouponIssueException(IssueFailReason.KAFKA_PUBLISH_FAILED);

        } catch (ExecutionException | TimeoutException e) {
            log.error("Kafka 메시지 발행 실패 또는 타임아웃 발생 - couponId: {}", couponId, e);

            // 2. 상위 계층(CouponService)으로 예외를 전파하여 Redis 보상 로직(Rollback) 유도
            throw new CouponIssueException(IssueFailReason.KAFKA_PUBLISH_FAILED);
        }
    }

    /** 이벤트 -> JSON 직렬화 헬퍼 메소드 */
    private String toJson(CouponIssuedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("[Kafka 이벤트 JSON 직렬화 실패] - couponId: {}", event.couponId(), e);
            throw new IllegalStateException("Kafka 메시지 직렬화 중 오류가 발생했습니다.", e);
        }
    }
}
