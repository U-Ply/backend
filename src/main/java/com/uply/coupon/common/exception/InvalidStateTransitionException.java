package com.uply.coupon.common.exception;

import com.uply.coupon.coupon.domain.CouponStatus;

public class InvalidStateTransitionException extends RuntimeException {

    public static final String ERROR_CODE = "INVALID_STATE_TRANSITION";

    private final CouponStatus currentStatus;
    private final CouponStatus targetStatus;

    // 쿠폰 상태 전이 실패는 문법 오류가 아니라 실행 중 비즈니스 규칙 위반이므로 RuntimeException을 사용
    public InvalidStateTransitionException(CouponStatus currentStatus, CouponStatus targetStatus) {
        super("Coupon status cannot transition from " + currentStatus + " to " + targetStatus);
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }

    public CouponStatus getCurrentStatus() {
        return currentStatus;
    }

    public CouponStatus getTargetStatus() {
        return targetStatus;
    }
}
