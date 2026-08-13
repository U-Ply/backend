package com.uply.coupon.coupon.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CouponIssueRequest(
        @NotBlank(message = "userId는 필수 값입니다.") String userId,
        @NotBlank(message = "campaignId는 필수 값입니다.") String campaignId,
        @NotBlank(message = "routeId는 필수 값입니다.") String routeId,
        @NotBlank(message = "fareClass는 필수 값입니다.") String fareClass) {}
