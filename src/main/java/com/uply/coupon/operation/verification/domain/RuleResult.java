package com.uply.coupon.operation.verification.domain;

import java.util.List;

public record RuleResult(
        String code,
        String name,
        RuleStatus status,
        long violationCount,
        int sampledCount,
        Long checkedRows,
        int elapsedMs,
        List<Violation> samples) {

    /** 실제로 검사한 결과. */
    public static RuleResult checked(
            String code,
            String name,
            long violationCount,
            int sampledCount,
            Long checkedRows,
            int elapsedMs,
            List<Violation> samples) {
        return new RuleResult(
                code,
                name,
                RuleStatus.CHECKED,
                violationCount,
                sampledCount,
                checkedRows,
                elapsedMs,
                samples);
    }

    /** 이 회차에 해당하지 않는 규칙. 위반 0 이지만 통과가 아니다. */
    public static RuleResult notApplicable(String code, String name) {
        return new RuleResult(code, name, RuleStatus.NOT_APPLICABLE, 0L, 0, null, 0, List.of());
    }

    /** 전제 조건 미충족으로 실행하지 못한 규칙. 회차 결론이 불완전해진다. */
    public static RuleResult skipped(String code, String name) {
        return new RuleResult(code, name, RuleStatus.SKIPPED, 0L, 0, null, 0, List.of());
    }

    public boolean passed() {
        return violationCount == 0;
    }

    public boolean isInvariant() {
        return code.startsWith("INV-");
    }

    public boolean wasChecked() {
        return status == RuleStatus.CHECKED;
    }
}
