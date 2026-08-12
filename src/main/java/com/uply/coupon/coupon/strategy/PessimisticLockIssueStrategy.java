package com.uply.coupon.coupon.strategy;

import org.springframework.stereotype.Component;

/**
 * DB 비관적 락(SELECT ... FOR UPDATE) 기반 발급 전략
 */
@Component("pessimisticLockIssueStrategy")
public class PessimisticLockIssueStrategy implements CouponIssueStrategy {

    @Override
    public IssueResult issue(Long campaignId, Long userId, Long stockId, String idempotencyKey) {
        // TODO: SELECT ... FOR UPDATE로 campaign_stocks row 잠근 뒤 재고 확인 -> 차감 -> coupons INSERT
        throw new UnsupportedOperationException("구현 예정");
    }

    @Override
    public String name() {
        return "PESSIMISTIC_LOCK";
    }
}
