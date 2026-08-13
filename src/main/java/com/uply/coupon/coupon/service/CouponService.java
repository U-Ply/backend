package com.uply.coupon.coupon.service;

import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.dto.response.CouponIssueResponse;

public interface CouponService {

    CouponIssueResponse issue(String idempotencyKey, CouponIssueRequest request);
}
