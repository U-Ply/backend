package com.uply.coupon.messaging.event;

import java.time.Instant;
import java.time.LocalDateTime;

public record CouponIssuedEvent(
        Long couponId,
        Long userId,
        Long campaignId,
        Long stockId,
        String idempotencyKey,
        Instant issuedAt,
        LocalDateTime expireAt) {}
