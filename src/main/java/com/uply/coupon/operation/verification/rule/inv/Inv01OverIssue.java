package com.uply.coupon.operation.verification.rule.inv;

import com.uply.coupon.operation.verification.rule.InvariantRule;
import org.springframework.stereotype.Component;

@Component
public class Inv01OverIssue implements InvariantRule {
    @Override
    public String code() {
        return "INV-01";
    }

    @Override
    public String name() {
        return "초과 발급 금지";
    }

    @Override
    public String checkedRowsSql() {
        return "SELECT COUNT(*) FROM campaign_stocks";
    }

    @Override
    public String violationSql() {
        return """
            SELECT 'campaign_stocks' AS target_table,
                   s.stock_id AS target_id,
                   CONCAT('issued=', COUNT(c.coupon_id), ' total=', s.total_stock) AS detail
            FROM campaign_stocks s
            -- LEFT JOIN 이라야 발급이 0건인 재고 풀도 집계에 남는다
            LEFT JOIN coupons c ON c.stock_id = s.stock_id
            GROUP BY s.stock_id, s.total_stock
            HAVING COUNT(c.coupon_id) > s.total_stock
            """;
    }
}
