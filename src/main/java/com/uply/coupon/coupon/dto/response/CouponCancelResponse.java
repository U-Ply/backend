package com.uply.coupon.coupon.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.uply.coupon.coupon.domain.CouponStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 쿠폰 취소 응답 DTO */
public record CouponCancelResponse(
        String couponId,
        CouponStatus status,
        @JsonFormat(
                        shape = JsonFormat.Shape.STRING,
                        pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        timezone = "UTC")
                Instant cancelledAt) {

    public static CouponCancelResponse of(Long couponId, LocalDateTime cancelledAt) {
        return new CouponCancelResponse(
                String.valueOf(couponId),
                CouponStatus.CANCELLED,
                cancelledAt.toInstant(ZoneOffset.UTC));
    }
}
