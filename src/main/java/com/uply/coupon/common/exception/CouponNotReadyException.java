package com.uply.coupon.common.exception;

public class CouponNotReadyException extends RuntimeException {

    public static final String ERROR_CODE = "COUPON_NOT_READY";

    private final Long couponId;

    public CouponNotReadyException(Long couponId) {
        super("Coupon is not ready: " + couponId);
        this.couponId = couponId;
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }

    public Long getCouponId() {
        return couponId;
    }
}
