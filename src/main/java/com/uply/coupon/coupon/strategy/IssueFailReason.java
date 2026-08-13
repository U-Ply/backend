package com.uply.coupon.coupon.strategy;

public enum IssueFailReason {
    OUT_OF_STOCK, // 재고 소진
    ALREADY_ISSUED, // 중복 발급 시도
    LOCK_TIMEOUT, // 락 대기 타임아웃 (비관적 락 전략에서 주로 발생)
    CAMPAIGN_NOT_OPEN, // 오픈 시각 이전 요청

    // 추가
    STOCK_NOT_FOUND, // stock-map:{campaignId}:{routeId}:{fareClassId} 정보가 ㅇ벗다
    DUPLICATE_REQUEST, // 동일 HTTP Request 중복 진입 (idempotencyKey 기준)
    SYSTEM_ERROR // 나머지 모든 에러 상황
}
