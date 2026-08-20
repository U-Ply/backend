package com.uply.coupon.coupon.dto.response;

import com.uply.coupon.coupon.domain.Coupon;
import java.util.List;

/** 사용자 보유 쿠폰 목록 조회 응답 DTO */
public record UserCouponListResponse(List<UserCouponSummaryResponse> coupons) {

    // 혹시 모를 목록 수정을 방지하기 위해 List.copyOf() 사용
    public UserCouponListResponse {
        coupons = List.copyOf(coupons);
    }

    public static UserCouponListResponse from(List<Coupon> coupons) {
        List<UserCouponSummaryResponse> items =
                coupons.stream().map(UserCouponSummaryResponse::from).toList();
        return new UserCouponListResponse(items);
    }
}
