package com.uply.coupon.operation.verification.rule.inv;

import com.uply.coupon.operation.verification.rule.InvariantRule;
import org.springframework.stereotype.Component;

@Component
public class Inv04CurrentStatusMatchesLastHistory implements InvariantRule {

    @Override
    public String code() {
        return "INV-04";
    }

    @Override
    public String name() {
        return "현재 상태 = 최종 이력";
    }

    @Override
    public String checkedRowsSql() {
        return "SELECT COUNT(*) FROM coupons";
    }

    @Override
    public String violationSql() {
        return """
            WITH last_h AS (
                SELECT coupon_id, to_status,
                       -- history_id 가 세 번째인 이유는 같은 밀리초 동점을 깨기 위해서다.
                       -- idx_coupon_event(coupon_id, event_at, history_id) 를 그대로 탄다.
                       ROW_NUMBER() OVER (
                           PARTITION BY coupon_id ORDER BY event_at DESC, history_id DESC
                       ) AS rn
                FROM coupon_history
            )
            SELECT 'coupons' AS target_table,
                   c.coupon_id AS target_id,
                   CONCAT('current=', c.status,
                          ' last_history=', COALESCE(l.to_status, '(이력 없음)')) AS detail
            FROM coupons c
            -- LEFT JOIN 이라야 '이력이 아예 없는 쿠폰' 도 위반으로 잡힌다 (요구 분석서 11절)
            LEFT JOIN last_h l ON l.coupon_id = c.coupon_id AND l.rn = 1
            WHERE l.coupon_id IS NULL OR c.status <> l.to_status
            """;
    }
}
