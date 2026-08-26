package com.uply.coupon.coupon.infrastructure;

import com.uply.coupon.coupon.repository.CouponIssuanceProgressRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Consumer 까지 도달하지 못해 오래 남아있는 {@code coupon:pending:{couponId}} 키 개수를 주기적으로 세어 {@code
 * coupon.pending.stale.count} Gauge로 노출한다.
 *
 * <p>기본 비활성화({@code coupon.pending-monitor.scheduler-enabled=false}). SCAN·TTL 조회만 하는 읽기 전용 집계라 여러
 * 인스턴스에서 동시에 켜도 안전하지만, 같은 카운트를 반복 계산할 필요는 없으므로 배치 담당 인스턴스 한 대에서만 켜는 것을 권장한다({@link
 * com.uply.coupon.operation.reconciliation.batch.RedisStockReconcileScheduler}와 같은 방침).
 *
 * <p>Gauge는 Prometheus 스크레이프 시점마다 Redis를 스캔하는 대신, 이 스케줄러가 갱신해둔 값만 읽는다 — pending 키가 많을 때 스크레이프 주기(보통
 * 수십 초)마다 SCAN을 도는 비용을 피하기 위함이다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "coupon.pending-monitor.scheduler-enabled", havingValue = "true")
public class CouponPendingMonitor {

    private final CouponIssuanceProgressRepository progressRepository;
    private final Duration staleThreshold;
    private final AtomicLong staleCount = new AtomicLong(0);

    public CouponPendingMonitor(
            CouponIssuanceProgressRepository progressRepository,
            MeterRegistry meterRegistry,
            @Value("${coupon.pending-monitor.stale-threshold:PT10M}") Duration staleThreshold) {
        this.progressRepository = progressRepository;
        this.staleThreshold = staleThreshold;

        Gauge.builder("coupon.pending.stale.count", staleCount, AtomicLong::get)
                .description("Consumer까지 도달하지 못해 staleThreshold 이상 남아있는 pending 건 개수")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${coupon.pending-monitor.fixed-delay:PT5M}")
    public void refresh() {
        try {
            long count = progressRepository.countStale(staleThreshold);
            staleCount.set(count);
            if (count > 0) {
                log.warn("오래된 pending 키가 {}개 감지됐습니다 (임계치: {})", count, staleThreshold);
            }
        } catch (Exception e) {
            // 집계 실패가 발급/Consumer 경로에 영향을 주면 안 되므로 예외를 삼킨다.
            log.error("pending 키 stale 카운트 갱신 실패", e);
        }
    }
}
