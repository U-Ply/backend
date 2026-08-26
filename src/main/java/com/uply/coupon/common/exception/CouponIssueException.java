package com.uply.coupon.common.exception;

import com.uply.coupon.coupon.strategy.IssueFailReason;
import lombok.Getter;

@Getter
public class CouponIssueException extends RuntimeException {

    private final IssueFailReason reason;

    // CAMPAIGN_NOT_CACHED 자동 복구 트리거가 캠페인별로 실패를 집계하는 데 쓴다. 이 필드를
    // 채우지 않는 생성자로 던지면 null로 남으며, 그 경우 자동 복구는 그냥 건너뛴다.
    private final Long campaignId;

    public CouponIssueException(IssueFailReason reason) {
        super("Coupon issue failed: " + reason);
        this.reason = reason;
        this.campaignId = null;
    }

    // [추가] 원인 예외 체이닝 생성자: DB, Kafka 등 하위 예외를 감쌀 때 사용
    public CouponIssueException(IssueFailReason reason, Throwable cause) {
        super("Coupon issue failed: " + reason, cause);
        this.reason = reason;
        this.campaignId = null;
    }

    public CouponIssueException(IssueFailReason reason, Long campaignId) {
        super("Coupon issue failed: " + reason);
        this.reason = reason;
        this.campaignId = campaignId;
    }
}
