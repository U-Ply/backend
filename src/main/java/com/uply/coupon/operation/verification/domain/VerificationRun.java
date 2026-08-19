package com.uply.coupon.operation.verification.domain;

import java.time.LocalDateTime;
import java.util.List;

public record VerificationRun(String runId, LocalDateTime snapshotAt, List<RuleResult> results) {

    public List<RuleResult> failedInvariants() {
        return results.stream().filter(RuleResult::isInvariant).filter(r -> !r.passed()).toList();
    }

    public boolean invariantsPassed() {
        return failedInvariants().isEmpty();
    }
}
