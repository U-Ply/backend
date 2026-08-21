package com.uply.coupon.messaging.event;

import java.time.Instant;

/**
 * 쿠폰 발급 이벤트.
 *
 * <p>시각 필드가 둘인 이유는 용도가 다르기 때문이다. 하나로 합치면 한쪽 의미가 조용히 사라진다.
 *
 * @param issuedAt 발급이 성립한 시각. Lua 경로이므로 Redis TIME 기준이며 coupons.issued_at으로 저장된다.
 * @param publishedAt Kafka 발행 시각. 프로듀서 JVM 기준이며 E2E 레이턴시 측정에만 쓰고 저장하지 않는다.
 */
public record CouponIssuedEvent(
        Long couponId,
        Long userId,
        Long campaignId,
        Long stockId,
        String idempotencyKey,
        Instant issuedAt,
        Instant publishedAt) {}
