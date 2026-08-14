package com.uply.coupon.coupon.controller;

import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.dto.response.CouponIssueResponse;
import com.uply.coupon.coupon.service.CouponService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;

    @PostMapping("/issue")
    public ResponseEntity<CouponIssueResponse> issueCoupon(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CouponIssueRequest request) {
        CouponIssueResponse response = couponService.issue(idempotencyKey, request);
        return ResponseEntity.ok(response);
    }
}
