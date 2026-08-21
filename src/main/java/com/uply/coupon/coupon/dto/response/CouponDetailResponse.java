package com.uply.coupon.coupon.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 쿠폰 단건 조회 응답 DTO */
public record CouponDetailResponse(
        String couponId,
        Long userId,
        Long campaignId,
        String routeId,
        String fareClass,
        CouponStatus status,
        @JsonFormat(
                        shape = JsonFormat.Shape.STRING,
                        pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        timezone = "UTC")
                Instant issuedAt,
        @JsonFormat(
                        shape = JsonFormat.Shape.STRING,
                        pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        timezone = "UTC")
                Instant usedAt,
        @JsonFormat(
                        shape = JsonFormat.Shape.STRING,
                        pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        timezone = "UTC")
                Instant cancelledAt,
        @JsonFormat(
                        shape = JsonFormat.Shape.STRING,
                        pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        timezone = "UTC")
                Instant expiredAt,
        @JsonFormat(
                        shape = JsonFormat.Shape.STRING,
                        pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        timezone = "UTC")
                Instant expireAt) {

    public static CouponDetailResponse of(Coupon coupon, String routeId, String fareClass) {
        return new CouponDetailResponse(
                String.valueOf(coupon.getCouponId()),
                coupon.getUserId(),
                coupon.getCampaignId(),
                routeId,
                fareClass,
                coupon.getStatus(),
                toInstant(coupon.getIssuedAt()),
                toInstant(coupon.getUsedAt()),
                toInstant(coupon.getCancelledAt()),
                toInstant(coupon.getExpiredAt()),
                toInstant(coupon.getExpireAt()));
    }

    private static Instant toInstant(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant(ZoneOffset.UTC);
    }
}
