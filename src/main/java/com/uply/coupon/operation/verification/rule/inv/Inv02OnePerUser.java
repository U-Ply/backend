package com.uply.coupon.operation.verification.rule.inv;

import com.uply.coupon.operation.verification.rule.InvariantRule;
import org.springframework.stereotype.Component;

@Component
public class Inv02OnePerUser implements InvariantRule {
    @Override
    public String code() {
        return "INV-02";
    }

    @Override
    public String name() {
        return "1인 1매";
    }

    @Override
    public String checkedRowsSql() {
        return "SELECT COUNT(*) FROM coupons";
    }

    @Override
    public String violationSql() {
        return """
            -- 위반 단위가 (캠페인, 유저) 조합이라 대표 행을 하나 골라야 한다.
            -- MIN(coupon_id) 로 고정하면 회차마다 같은 값이 나와 비교가 가능하다.
            SELECT 'coupons' AS target_table,
                   MIN(coupon_id) AS target_id,
                   CONCAT('campaign=', campaign_id, ' user=', user_id,
                          ' count=', COUNT(*)) AS detail
            FROM coupons
            GROUP BY campaign_id, user_id
            HAVING COUNT(*) > 1
            """;
    }
}
