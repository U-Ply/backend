package com.uply.coupon.coupon.strategy;

import org.springframework.stereotype.Component;

/**
 * 동시성 제어를 전혀 하지 않은 baseline 전략
 * "왜 동시성 제어가 필요한가"를 증명하기 위한 대조군으로,
 * 부하테스트 시 재고 초과 발급이 실제로 발생하는 것을 보여주는 용도
 */
@Component("noLockIssueStrategy")
public class NoLockIssueStrategy implements CouponIssueStrategy {

    @Override
    public IssueResult issue(Long campaignId, Long userId, Long stockId, String idempotencyKey) {
        // TODO: 락/원자적 연산 없이 "재고 확인 → 차감"을 그대로 구현
        // 예: SELECT remaining_stock ... 확인 후 별도 UPDATE (조건 없이)
        throw new UnsupportedOperationException("구현 예정");
    }

    @Override
    public String name() {
        return "NO_LOCK";
    }
}
