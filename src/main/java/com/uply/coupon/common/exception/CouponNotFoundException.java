package com.uply.coupon.common.exception;

public class CouponNotFoundException extends RuntimeException {

    public static final String ERROR_CODE = "COUPON_NOT_FOUND";

    private final Long couponId;

    public CouponNotFoundException(Long couponId) {
        super("Coupon not found: " + couponId);
        this.couponId = couponId;
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }

    public Long getCouponId() {
        return couponId;
    }
}
