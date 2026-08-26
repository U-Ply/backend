package com.uply.coupon.coupon.repository;

import java.time.Duration;

public interface CouponIssuanceProgressRepository {

    void markPending(Long couponId);

    boolean isPending(Long couponId);

    void clear(Long couponId);

    long countStale(Duration staleThreshold);
}
