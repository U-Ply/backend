package com.uply.coupon.coupon.repository;

/** Kafka 비동기 발급의 MySQL 반영 진행 상태를 관리한다. */
public interface CouponIssuanceProgressRepository {

    void markPending(Long couponId);

    boolean isPending(Long couponId);

    void clear(Long couponId);
}
