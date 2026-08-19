package com.uply.coupon.operation.verification.domain;

import java.util.List;

public record RuleResult(
        String code,
        String name,
        long violationCount,
        int sampledCount,
        Long checkedRows,
        int elapsedMs,
        List<Violation> samples) {

    public boolean passed() {
        return violationCount == 0;
    }

    public boolean isInvariant() {
        return code.startsWith("INV-");
    }
}
