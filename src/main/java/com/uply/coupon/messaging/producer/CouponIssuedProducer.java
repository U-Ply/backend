package com.uply.coupon.messaging.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import com.uply.coupon.coupon.strategy.save.CouponSaveStrategy;
import com.uply.coupon.messaging.event.CouponIssuedEvent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * CouponSaveStrategy 전략 中 1 : Kafka 이벤트 발행
 * 
 * [발행 실패 및 보상 정책]
 * 1. 확실한 실패 (직렬화 오류 등): KAFKA_PUBLISH_FAILED 발생 -> 상위 레이어에서 Redis 선점 재고 즉시 복구(Rollback)
 * 2. 불명확한 결과 (TimeoutException): KAFKA_PUBLISH_UNKNOWN 발생 -> Redis 재고 즉시 복구 금지 (초과 발급 방지)
 *    - 브로커에 메시지가 들어갔으나 ACK만 유실되었을 가능성 존재.
 *    - 사후 대사(Reconciliation) 스케줄러를 통해 정합성 최종 보정.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "coupon.save.strategy", havingValue = "kafka", matchIfMissing = true)
@RequiredArgsConstructor
public class CouponIssuedProducer implements CouponSaveStrategy {

    private static final String TOPIC_NAME = "coupon-issued";
    private static final long PUBLISH_TIMEOUT_SECONDS = 3L;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry; // Micrometer 메트릭 등록 객체

    @Override
    public void save(
            Long couponId, Long userId, Long campaignId, Long stockId, String idempotencyKey, LocalDateTime expireAt) {

        // #1. E2E Latency 측정을 위한 publishedAt(Instant.now()) 포함 이벤트 생성
        CouponIssuedEvent event =
                new CouponIssuedEvent(
                        couponId, userId, campaignId, stockId, idempotencyKey, Instant.now(), expireAt);

        // #2. 직렬화 (확실한 실패 지점)
        String jsonPayload = toJson(event);
        
        long startTime = System.currentTimeMillis();

        // #3. Kafka 동기 전송 및 예외 성격별 분기 처리
        try {
            // Key로 couponId를 전달하여 동일 파티션 보장 및 idempotent producer 동작
            kafkaTemplate
                    .send(TOPIC_NAME, String.valueOf(couponId), jsonPayload)
                    .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // 성공 메트릭 수집 (소요시간 + 성공 카운트)
            recordSuccessMetrics(System.currentTimeMillis() - startTime);
            
            log.info("[Kafka 이벤트 발행 성공] couponId: {}, idempotencyKey: {}", couponId, idempotencyKey);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailureMetrics("interrupted");
            log.error("[Kafka 발행 결과 불명확 - 스레드 인터럽트] couponId: {}", couponId, e);
            // 발행 여부가 불분명하므로 즉시 Redis 복구를 수행하지 않는 예외 전파
            throw new CouponIssueException(IssueFailReason.KAFKA_PUBLISH_UNKNOWN, e);

        } catch (TimeoutException e) {
        	recordFailureMetrics("timeout");
            log.error("[Kafka 발행 결과 불명확 - Timeout {}초 초과] couponId: {}", PUBLISH_TIMEOUT_SECONDS, couponId, e);
            // ACK만 유실되고 브로커에는 저장되었을 수 있으므로 즉시 Redis 복구 금지
            throw new CouponIssueException(IssueFailReason.KAFKA_PUBLISH_UNKNOWN, e);

        } catch (ExecutionException e) {
        	recordFailureMetrics("execution_error");
            log.error("[Kafka 메시지 발행 확정 실패] couponId: {}", couponId, e);
            // 브로커에서 거절되었거나 전송 불가능한 상태가 확정된 경우 -> Redis 재고 복구 유도
            throw new CouponIssueException(IssueFailReason.KAFKA_PUBLISH_FAILED, e);
        }
    }

    /** 이벤트 -> JSON 직렬화 헬퍼 메소드 (확실한 실패) */
    private String toJson(CouponIssuedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("[Kafka 이벤트 JSON 직렬화 실패 - 100% 미발행] couponId: {}", event.couponId(), e);
            // 전송 전 실패이므로 즉시 Redis 재고 복구가 가능한 KAFKA_PUBLISH_FAILED 예외 던짐
            throw new CouponIssueException(IssueFailReason.KAFKA_PUBLISH_FAILED, e);
        }
    }
    
    /** 프로듀서 성공 메트릭 기록 */
    private void recordSuccessMetrics(long elapsedMs) {
        Counter.builder("kafka.producer.publish.count")
                .tag("result", "success")
                .register(meterRegistry)
                .increment();

        Timer.builder("kafka.producer.publish.latency")
                .register(meterRegistry)
                .record(elapsedMs, TimeUnit.MILLISECONDS);
    }

    /** 프로듀서 실패 메트릭 기록 */
    private void recordFailureMetrics(String cause) {
        Counter.builder("kafka.producer.publish.count")
                .tag("result", "failure")
                .tag("cause", cause)
                .register(meterRegistry)
                .increment();
    }
}