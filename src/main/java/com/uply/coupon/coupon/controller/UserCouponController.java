package com.uply.coupon.coupon.controller;

import com.uply.coupon.coupon.dto.response.UserCouponListResponse;
import com.uply.coupon.coupon.service.CouponQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserCouponController {

    private final CouponQueryService couponQueryService;

    @GetMapping("/{userId}/coupons")
    public ResponseEntity<UserCouponListResponse> getUserCoupons(@PathVariable Long userId) {
        return ResponseEntity.ok(couponQueryService.getUserCoupons(userId));
    }
}
