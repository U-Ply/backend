package com.uply.coupon.operation.verification.rule.inv;

import com.uply.coupon.operation.verification.rule.InvariantRule;
import org.springframework.stereotype.Component;

@Component
public class Inv08ReferencePair implements InvariantRule {
    @Override
    public String code() {
        return "INV-08";
    }

    @Override
    public String name() {
        return "참조 조합 일치";
    }

    @Override
    public String checkedRowsSql() {
        return "SELECT COUNT(*) FROM coupons";
    }

    @Override
    public String violationSql() {
        return """
            -- FK 두 개가 각각은 유효한데 조합이 틀린 경우. FK 로는 못 잡는다.
            SELECT 'coupons' AS target_table,
                   c.coupon_id AS target_id,
                   CONCAT('coupon.campaign=', c.campaign_id,
                          ' stock(', c.stock_id, ').campaign=', s.campaign_id) AS detail
            FROM coupons c
            JOIN campaign_stocks s ON s.stock_id = c.stock_id
            WHERE c.campaign_id <> s.campaign_id
            """;
    }
}
