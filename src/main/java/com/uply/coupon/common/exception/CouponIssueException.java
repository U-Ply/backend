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

    // [추가] 원인 예외 체이닝 생성자: DB, Kafka 등 하위 예외를 감쌀 때 사용
    public CouponIssueException(IssueFailReason reason, Throwable cause) {
        super("Coupon issue failed: " + reason, cause);
        this.reason = reason;
    }
}
