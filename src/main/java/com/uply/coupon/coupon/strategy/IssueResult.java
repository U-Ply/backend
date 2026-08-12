package com.uply.coupon.coupon.strategy;

public record IssueResult(
        boolean success,
        Long couponId,          // 성공 시에만 값 존재
        IssueFailReason reason  // 실패 시에만 값 존재
) {
    public static IssueResult success(Long couponId) {
        return new IssueResult(true, couponId, null);
    }

    public static IssueResult fail(IssueFailReason reason) {
        return new IssueResult(false, null, reason);
    }
}
