package com.uply.coupon.coupon.strategy;

public enum IssueFailReason {
    OUT_OF_STOCK,      // 재고 소진
    ALREADY_ISSUED,    // 중복 발급 시도
    LOCK_TIMEOUT,      // 락 대기 타임아웃 (비관적 락 전략에서 주로 발생)
    CAMPAIGN_NOT_OPEN  // 오픈 시각 이전 요청
}
