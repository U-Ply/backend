package com.uply.coupon.common.exception;

import com.uply.coupon.coupon.strategy.IssueFailReason;
import lombok.Getter;

@Getter
public class CouponIssueException extends RuntimeException {

    private final IssueFailReason reason;

    public CouponIssueException(IssueFailReason reason) {
        super("Coupon issue failed: " + reason);
        this.reason = reason;
    }
}
