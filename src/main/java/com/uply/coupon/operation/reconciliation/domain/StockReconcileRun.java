package com.uply.coupon.operation.reconciliation.domain;

import com.uply.coupon.operation.verification.domain.RuleResult;
import java.time.LocalDateTime;

public record StockReconcileRun(
        ReconciliationStatus status, String detail, LocalDateTime snapshotAt, RuleResult result) {

    private static final String RULE_CODE = "REC-01";
    private static final String RULE_NAME = "Redis-DB 재고 일치";

    /** 이 회차에 REC-01 이 해당하지 않는다. 결과 행은 남긴다. */
    public static StockReconcileRun notApplicable(String detail, LocalDateTime snapshotAt) {
        return new StockReconcileRun(
                ReconciliationStatus.NOT_APPLICABLE,
                detail,
                snapshotAt,
                RuleResult.notApplicable(RULE_CODE, RULE_NAME + " — " + detail));
    }

    /**
     * 검증을 실행할 수 없어 REC-01을 SKIPPED로 기록한다.
     *
     * <p>현재 ReconciliationStatus의 기존 SKIPPED_NOT_SETTLED 값은 Kafka 미정착과 Redis 장애 양쪽에서 "대사를 완료하지 못함"을
     * 나타내는 내부 실행 상태로 사용한다. 외부 검증 결과의 RuleStatus는 반드시 SKIPPED로 기록된다.
     */
    public static StockReconcileRun skipped(String detail, LocalDateTime snapshotAt) {
        return new StockReconcileRun(
                ReconciliationStatus.SKIPPED_NOT_SETTLED,
                detail,
                snapshotAt,
                RuleResult.skipped(RULE_CODE, RULE_NAME + " — " + detail));
    }

    /** Kafka 정착 전이라 대사하지 못했다. 통과가 아니라 미실행이다. */
    public static StockReconcileRun notSettled(String detail, LocalDateTime snapshotAt) {
        return skipped(detail, snapshotAt);
    }
}
