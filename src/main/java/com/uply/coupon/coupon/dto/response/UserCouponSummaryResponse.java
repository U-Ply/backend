package com.uply.coupon.coupon.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponStatus;
import java.time.Instant;
import java.time.ZoneOffset;

/** 사용자 보유 쿠폰 목록의 쿠폰 항목 응답 DTO */
public record UserCouponSummaryResponse(
        String couponId,
        Long campaignId,
        CouponStatus status,
        @JsonFormat(
                        shape = JsonFormat.Shape.STRING,
                        pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        timezone = "UTC")
                Instant issuedAt) {

    public static UserCouponSummaryResponse from(Coupon coupon) {
        return new UserCouponSummaryResponse(
                String.valueOf(coupon.getCouponId()),
                coupon.getCampaignId(),
                coupon.getStatus(),
                coupon.getIssuedAt().toInstant(ZoneOffset.UTC));
    }
}
