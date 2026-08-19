package com.uply.coupon.operation.verification.rule.inv;

import com.uply.coupon.operation.verification.rule.InvariantRule;
import org.springframework.stereotype.Component;

@Component
public class Inv11IssuedWithinCampaignWindow implements InvariantRule {

    @Override
    public String code() {
        return "INV-11";
    }

    @Override
    public String name() {
        return "캠페인 기간 내 발급";
    }

    @Override
    public String checkedRowsSql() {
        return "SELECT COUNT(*) FROM coupons";
    }

    @Override
    public String violationSql() {
        return """
            -- 발급 API 가 캠페인 기간(정책 C-2)을 검사하지만, 그 검사를 우회하거나
            -- 빠뜨린 경로가 있었는지를 데이터 쪽에서 다시 확인한다.
            -- 쿠폰과 캠페인에 걸친 조건이라 FK 로도 CHECK 로도 표현할 수 없다.
            SELECT 'coupons' AS target_table,
                   c.coupon_id AS target_id,
                   CONCAT('issued=', c.issued_at,
                          ' window=', g.open_at, ' ~ ', g.expire_at) AS detail
            FROM coupons c
            JOIN campaigns g ON g.campaign_id = c.campaign_id
            WHERE c.issued_at < g.open_at OR c.issued_at > g.expire_at
            """;
    }
}
