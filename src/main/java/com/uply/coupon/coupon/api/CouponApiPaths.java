package com.uply.coupon.coupon.api;

public final class CouponApiPaths {

    public static final String COUPONS = "/api/coupons";
    public static final String ISSUE = "/issue";
    public static final String USE = "/use";
    public static final String CANCEL = "/cancel";
    public static final String ISSUE_URI = COUPONS + ISSUE;

    private CouponApiPaths() {}

    public static String couponActionUri(Long couponId, String actionPath) {
        return COUPONS + "/" + couponId + actionPath;
    }
}
