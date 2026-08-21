package com.uply.coupon.coupon.strategy.save;

import java.time.LocalDateTime;

/**
 * 쿠폰 저장 전략 인터페이스
 *
 * <p>1. MySql 동기 저장 2. Kafka 이벤트 발행
 */
public interface CouponSaveStrategy {
    void save(
            Long couponId,
            Long userId,
            Long campaignId,
            Long stockId,
            String idempotencyKey,
            LocalDateTime issuedAt,
            LocalDateTime expireAt);
}
