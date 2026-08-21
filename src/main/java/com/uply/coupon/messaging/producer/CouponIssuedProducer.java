package com.uply.coupon.messaging.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.repository.CouponIssuanceProgressRepository;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import com.uply.coupon.coupon.strategy.save.CouponSaveStrategy;
import com.uply.coupon.messaging.event.CouponIssuedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.RetriableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * CouponSaveStrategy 전략 中 1 : Kafka 이벤트 발행
 *
 * <p>[발행 실패 및 보상 정책] 1. 확실한 실패 (직렬화 오류 등): KAFKA_PUBLISH_FAILED 발생 -> 상위 레이어에서 Redis 선점 재고 즉시
 * 복구(Rollback) 2. 불명확한 결과 (TimeoutException): KAFKA_PUBLISH_UNKNOWN 발생 -> Redis 재고 즉시 복구 금지 (초과 발급
 * 방지) - 브로커에 메시지가 들어갔으나 ACK만 유실되었을 가능성 존재. - 사후 대사(Reconciliation) 스케줄러를 통해 정합성 최종 보정.
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
    private final CouponIssuanceProgressRepository progressRepository;

    @Override
    public void save(
            Long couponId,
            Long userId,
            Long campaignId,
            Long stockId,
            String idempotencyKey,
            LocalDateTime issuedAt,
            LocalDateTime expireAt) {

        // #1. 이벤트 생성
        // issuedAt   - 전략이 Redis TIME으로 확정한 발급 시각. 컨슈머가 coupons.issued_at으로 저장한다.
        // publishedAt - 지금 이 순간의 발행 시각. E2E 레이턴시 측정 전용이며 저장하지 않는다.
        CouponIssuedEvent event =
                new CouponIssuedEvent(
                        couponId,
                        userId,
                        campaignId,
                        stockId,
                        idempotencyKey,
                        issuedAt.toInstant(ZoneOffset.UTC),
                        Instant.now());

        // #2. 직렬화 (확실한 실패 지점)
        String jsonPayload = toJson(event);

        // #3. Kafka 발행 전에 MySQL 반영 대기 상태 저장
        markPending(couponId);

        long startTime = System.currentTimeMillis();

        // #4. Kafka 동기 전송 및 예외 성격별 분기 처리
        try {
            // Key로 couponId를 전달하여 동일 파티션 보장 및 idempotent producer 동작
            kafkaTemplate
                    .send(TOPIC_NAME, String.valueOf(couponId), jsonPayload)
                    .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // 성공 메트릭 수집 (소요시간 + 성공 카운트)
            recordSuccessMetrics(System.currentTimeMillis() - startTime);

            log.info(
                    "[Kafka 이벤트 발행 성공] couponId: {}, idempotencyKey: {}", couponId, idempotencyKey);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailureMetrics("interrupted");
            log.error("[Kafka 발행 결과 불명확 - 스레드 인터럽트] couponId: {}", couponId, e);
            // 발행 여부가 불분명하므로 즉시 Redis 복구를 수행하지 않는 예외 전파
            throw new CouponIssueException(IssueFailReason.SAVE_RESULT_UNKNOWN, e);

        } catch (TimeoutException e) {
            recordFailureMetrics("timeout");
            log.error(
                    "[Kafka 발행 결과 불명확 - Timeout {}초 초과] couponId: {}",
                    PUBLISH_TIMEOUT_SECONDS,
                    couponId,
                    e);
            // ACK만 유실되고 브로커에는 저장되었을 수 있으므로 즉시 Redis 복구 금지
            throw new CouponIssueException(IssueFailReason.SAVE_RESULT_UNKNOWN, e);

        } catch (ExecutionException e) {

            Throwable cause = e.getCause();

            // e.getCause() 기반 예외 분류: 타임아웃/네트워크 계열 및 재시도 가능 예외는 결과 불명확(UNKNOWN)으로 처리
            if (isUnknownCause(cause)) {
                recordFailureMetrics("execution_unknown");
                log.warn(
                        "[Kafka 발행 결과 불명확 - 원인: {}] couponId: {}",
                        cause != null ? cause.getClass().getSimpleName() : "null",
                        couponId,
                        e);
                throw new CouponIssueException(IssueFailReason.SAVE_RESULT_UNKNOWN, e);
            }

            // 확실한 실패 (직렬화 문제, 토픽 부재, 메시지 용량 초과 등 브로커 거절 확정)
            recordFailureMetrics("execution_failed");
            log.error(
                    "[Kafka 메시지 발행 확정 실패 - 원인: {}] couponId: {}",
                    cause != null ? cause.getClass().getSimpleName() : "null",
                    couponId,
                    e);
            clearPending(couponId);
            throw new CouponIssueException(IssueFailReason.KAFKA_PUBLISH_FAILED, e);

        } catch (Exception e) {
            log.error("[Kafka 발행 결과 불명확] couponId: {}", couponId, e);
            throw new CouponIssueException(IssueFailReason.SAVE_RESULT_UNKNOWN, e);
        }
    }

    private void markPending(Long couponId) {
        try {
            progressRepository.markPending(couponId);
        } catch (RuntimeException e) {
            log.error("[발급 진행 상태 저장 실패] couponId: {}", couponId, e);
            throw new CouponIssueException(IssueFailReason.SYSTEM_ERROR, e);
        }
    }

    private void clearPending(Long couponId) {
        try {
            progressRepository.clear(couponId);
        } catch (RuntimeException e) {
            log.error("[발급 진행 상태 삭제 실패] couponId: {}", couponId, e);
        }
    }

    /** ExecutionException 내부 cause가 결과 불명확(UNKNOWN)에 해당하는지 검사 */
    private boolean isUnknownCause(Throwable cause) {
        if (cause == null) {
            return false;
        }
        return cause instanceof TimeoutException
                || cause instanceof org.apache.kafka.common.errors.TimeoutException
                || cause instanceof NetworkException
                || cause instanceof DisconnectException
                || cause instanceof RetriableException;
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
