package com.uply.coupon.coupon.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.uply.coupon.coupon.domain.CouponStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 쿠폰 사용 응답 DTO */
public record CouponUseResponse(
        String couponId,
        CouponStatus status,
        @JsonFormat(
                        shape = JsonFormat.Shape.STRING,
                        pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        timezone = "UTC")
                Instant usedAt) {

    public static CouponUseResponse of(Long couponId, LocalDateTime usedAt) {
        return new CouponUseResponse(
                String.valueOf(couponId), CouponStatus.USED, usedAt.toInstant(ZoneOffset.UTC));
    }
}
