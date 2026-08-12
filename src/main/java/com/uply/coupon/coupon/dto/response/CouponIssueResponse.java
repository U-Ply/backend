package com.uply.coupon.coupon.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

public record CouponIssueResponse(
    String couponId,
    
    CouponIssueStatus status,

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    Instant issuedAt,

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    Instant expireAt
) {
}