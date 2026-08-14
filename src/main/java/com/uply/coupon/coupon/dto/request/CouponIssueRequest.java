package com.uply.coupon.coupon.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CouponIssueRequest(
        @NotNull(message = "userId는 필수 값입니다.") Long userId,
        @NotNull(message = "campaignId는 필수 값입니다.") Long campaignId,
        @NotBlank(message = "routeId는 필수 값입니다.") String routeId,
        @NotBlank(message = "fareClass는 필수 값입니다.") String fareClass) {}
