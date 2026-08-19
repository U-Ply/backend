package com.uply.coupon.operation.verification.rule.inv;

import com.uply.coupon.operation.verification.rule.InvariantRule;
import org.springframework.stereotype.Component;

@Component
public class Inv10NoOrphan implements InvariantRule {
    @Override
    public String code() {
        return "INV-10";
    }

    @Override
    public String name() {
        return "고아 행 없음";
    }

    @Override
    public String violationSql() {
        return """
            -- 초기화 스크립트가 FOREIGN_KEY_CHECKS=0 을 쓰기 때문에
            -- FK 가 걸려 있어도 고아 행이 생길 수 있다.
            -- 한 규칙이 두 테이블의 위반을 내므로 target_table 을 행마다 다르게 낸다.
            SELECT 'campaign_stocks' AS target_table, s.stock_id AS target_id,
                   CONCAT('missing campaign=', s.campaign_id) AS detail
            FROM campaign_stocks s
            LEFT JOIN campaigns g ON g.campaign_id = s.campaign_id
            WHERE g.campaign_id IS NULL

            UNION ALL
            SELECT 'coupons', c.coupon_id, CONCAT('missing user=', c.user_id)
            FROM coupons c
            LEFT JOIN users u ON u.user_id = c.user_id
            WHERE u.user_id IS NULL

            UNION ALL
            SELECT 'coupons', c.coupon_id, CONCAT('missing campaign=', c.campaign_id)
            FROM coupons c
            LEFT JOIN campaigns g ON g.campaign_id = c.campaign_id
            WHERE g.campaign_id IS NULL

            UNION ALL
            SELECT 'coupons', c.coupon_id, CONCAT('missing stock=', c.stock_id)
            FROM coupons c
            LEFT JOIN campaign_stocks s ON s.stock_id = c.stock_id
            WHERE s.stock_id IS NULL

            UNION ALL
            SELECT 'coupon_history', h.history_id, CONCAT('missing coupon=', h.coupon_id)
            FROM coupon_history h
            LEFT JOIN coupons c ON c.coupon_id = h.coupon_id
            WHERE c.coupon_id IS NULL
            """;
    }
}
