package com.uply.coupon.coupon.strategy;

public record IssueResult(
        boolean success,
        Long couponId, // 성공 시에만 값 존재
        Long stockId, // 성공 시에만 값 존재
        IssueFailReason reason // 실패 시에만 값 존재
        ) {
    public static IssueResult success(Long couponId, Long stockId) {
        return new IssueResult(true, couponId, stockId, null);
    }

    public static IssueResult fail(IssueFailReason reason) {
        return new IssueResult(false, null, null, reason);
    }
}
