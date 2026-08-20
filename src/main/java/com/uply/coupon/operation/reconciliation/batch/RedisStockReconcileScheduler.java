package com.uply.coupon.operation.reconciliation.batch;

import com.uply.coupon.operation.admin.BatchLaunchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 기본 비활성화. 멀티 인스턴스 환경에서는 배치 담당 인스턴스 한 대에서만 활성화한다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "coupon.reconciliation.scheduler-enabled", havingValue = "true")
public class RedisStockReconcileScheduler {

    private final BatchLaunchService batchLaunchService;

    @Scheduled(
            initialDelayString = "${coupon.reconciliation.initial-delay:PT1M}",
            fixedDelayString = "${coupon.reconciliation.fixed-delay:PT5M}")
    public void launch() {
        try {
            batchLaunchService.launch("stockReconcileJob", null, true);
        } catch (IllegalStateException exception) {
            log.info("REC-01 스케줄 실행을 건너뜁니다 — {}", exception.getMessage());
        } catch (Exception exception) {
            log.error("REC-01 스케줄 실행 접수 실패", exception);
        }
    }
}
