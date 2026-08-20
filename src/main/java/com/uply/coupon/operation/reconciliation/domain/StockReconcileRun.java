package com.uply.coupon.operation.reconciliation.domain;

import com.uply.coupon.operation.verification.domain.RuleResult;
import java.time.LocalDateTime;

public record StockReconcileRun(
        ReconciliationStatus status, String detail, LocalDateTime snapshotAt, RuleResult result) {

    public static StockReconcileRun notApplicable(String detail) {
        return new StockReconcileRun(ReconciliationStatus.NOT_APPLICABLE, detail, null, null);
    }

    public static StockReconcileRun notSettled(String detail) {
        return new StockReconcileRun(ReconciliationStatus.SKIPPED_NOT_SETTLED, detail, null, null);
    }
}
