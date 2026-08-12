package com.uply.coupon.coupon.strategy;

import org.springframework.stereotype.Component;

/**
 * Redis + Lua Script 기반 발급 전략 (최종 채택 후보).
 * 담당: 1-B (이승지)
 */
@Component("luaScriptIssueStrategy")
public class LuaScriptIssueStrategy implements CouponIssueStrategy {

    @Override
    public IssueResult issue(Long campaignId, Long userId, Long stockId, String idempotencyKey) {
        // TODO: Lua Script로 재고 확인 + 중복 체크 + 차감을 원자적으로 처리 후 Kafka 이벤트 발행
        throw new UnsupportedOperationException("구현 예정");
    }

    @Override
    public String name() {
        return "LUA_SCRIPT";
    }
}
