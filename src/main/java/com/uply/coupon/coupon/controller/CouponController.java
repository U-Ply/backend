package com.uply.coupon.coupon.controller;

import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.dto.response.CouponCancelResponse;
import com.uply.coupon.coupon.dto.response.CouponIssueResponse;
import com.uply.coupon.coupon.dto.response.CouponUseResponse;
import com.uply.coupon.coupon.service.CouponService;
import com.uply.coupon.coupon.service.CouponStateTransitionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;
    private final CouponStateTransitionService couponStateTransitionService;

    @PostMapping("/issue")
    public ResponseEntity<CouponIssueResponse> issueCoupon(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CouponIssueRequest request) {
        CouponIssueResponse response = couponService.issue(idempotencyKey, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{couponId}/use")
    public ResponseEntity<CouponUseResponse> useCoupon(
            @PathVariable Long couponId, @RequestHeader("Idempotency-Key") String idempotencyKey) {
        Coupon coupon = couponStateTransitionService.use(couponId, idempotencyKey);
        CouponUseResponse response = CouponUseResponse.of(coupon.getCouponId(), coupon.getUsedAt());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{couponId}/cancel")
    public ResponseEntity<CouponCancelResponse> cancelCoupon(
            @PathVariable Long couponId, @RequestHeader("Idempotency-Key") String idempotencyKey) {
        Coupon coupon = couponStateTransitionService.cancel(couponId, idempotencyKey);
        CouponCancelResponse response =
                CouponCancelResponse.of(coupon.getCouponId(), coupon.getCancelledAt());
        return ResponseEntity.ok(response);
    }
}
