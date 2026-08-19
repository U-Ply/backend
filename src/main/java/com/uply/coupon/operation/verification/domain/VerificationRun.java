package com.uply.coupon.operation.verification.domain;

import java.time.LocalDateTime;
import java.util.List;

public record VerificationRun(String runId, LocalDateTime snapshotAt, List<RuleResult> results) {

    /** CLOCK-01/02 나 REC- 는 판정에서 제외한다 (WHERE rule_code LIKE 'INV-%' 와 같은 기준). */
    public List<RuleResult> failedInvariants() {
        return results.stream().filter(RuleResult::isInvariant).filter(r -> !r.passed()).toList();
    }

    public boolean invariantsPassed() {
        return failedInvariants().isEmpty();
    }

    /**
     * 앱과 DB 의 시계가 어긋나면 어느 시점을 본 것인지 알 수 없으므로 회차 전체가 무효다.
     *
     * <p>CLOCK-02(Redis)는 포함하지 않는다. INV 규칙은 MySQL 만 보므로 Redis 시계가 틀려도 정합성 판정 자체는 유효하고, Lua 경로의 만료
     * 판정 해석에만 영향을 준다.
     */
    public boolean clockValid() {
        return results.stream()
                .filter(r -> "CLOCK-01".equals(r.code()))
                .allMatch(RuleResult::passed);
    }
}
