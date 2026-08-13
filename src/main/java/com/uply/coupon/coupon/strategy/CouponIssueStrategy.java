package com.uply.coupon.coupon.strategy;

public interface CouponIssueStrategy {

    IssueResult issue(Long campaignId, Long routeId, Long fareClassId, Long userId, String idempotencyKey);

    String name(); // 벤치마크 리포트에 찍을 이름 (예: "PESSIMISTIC_LOCK", "LUA_SCRIPT", "NO_LOCK")
}
