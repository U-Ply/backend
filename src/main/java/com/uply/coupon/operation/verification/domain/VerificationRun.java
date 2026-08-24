package com.uply.coupon.operation.verification.domain;

import java.time.LocalDateTime;
import java.util.List;

public record VerificationRun(
        String runId, RoundVersion round, LocalDateTime snapshotAt, List<RuleResult> results) {

    /** CLOCK-01/02 나 REC- 는 판정에서 제외한다 (WHERE rule_code LIKE 'INV-%' 와 같은 기준). */
    public List<RuleResult> failedInvariants() {
        return results.stream().filter(RuleResult::isInvariant).filter(r -> !r.passed()).toList();
    }

    public boolean invariantsPassed() {
        return failedInvariants().isEmpty();
    }

    /**
     * 시계가 어긋나면 어느 시점을 본 것인지 알 수 없어 회차 전체가 무효다.
     *
     * <p>CLOCK-02 를 포함하도록 바꿨다. Lua 경로의 issued_at 기준이 Redis TIME 으로 확정되면서 Redis-DB drift 가 INV-04(이력
     * 순서) · INV-06(시각 순서) · INV-11(캠페인 기간)의 오차로 직접 이어진다. Redis 를 쓰지 않는 회차에서는 CLOCK-02 가 N/A(위반 0)로
     * 기록되므로 이 조건이 그 회차를 막지 않는다.
     */
    public boolean clockValid() {
        return results.stream()
                .filter(r -> r.code().startsWith("CLOCK-"))
                .allMatch(RuleResult::passed);
    }
}
