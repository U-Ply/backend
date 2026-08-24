package com.uply.coupon.operation.reconciliation.domain;

import com.uply.coupon.operation.verification.domain.RuleResult;
import java.time.LocalDateTime;

public record StockReconcileRun(
        ReconciliationStatus status, String detail, LocalDateTime snapshotAt, RuleResult result) {

    private static final String RULE_CODE = "REC-01";

    /** 이 회차에 REC-01 이 해당하지 않는다. 결과 행은 남긴다. */
    public static StockReconcileRun notApplicable(String detail, LocalDateTime snapshotAt) {
        return new StockReconcileRun(
                ReconciliationStatus.NOT_APPLICABLE,
                detail,
                snapshotAt,
                RuleResult.notApplicable(RULE_CODE, "Redis·DB 재고 대사 — " + detail));
    }

    /** Kafka 정착 전이라 대사하지 못했다. 통과가 아니라 미실행이다. */
    public static StockReconcileRun notSettled(String detail, LocalDateTime snapshotAt) {
        return new StockReconcileRun(
                ReconciliationStatus.SKIPPED_NOT_SETTLED,
                detail,
                snapshotAt,
                RuleResult.skipped(RULE_CODE, "Redis·DB 재고 대사 — " + detail));
    }
}
