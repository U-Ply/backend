package com.uply.coupon.operation.verification.rule.inv;

import com.uply.coupon.operation.verification.rule.InvariantRule;
import org.springframework.stereotype.Component;

@Component
public class Inv03StockCounter implements InvariantRule {
    @Override
    public String code() {
        return "INV-03";
    }

    @Override
    public String name() {
        return "DB 재고 카운터";
    }

    @Override
    public String checkedRowsSql() {
        return "SELECT COUNT(*) FROM campaign_stocks";
    }

    @Override
    public String violationSql() {
        return """
            -- 발급 이후 재고는 복구되지 않는다(영구 소진 정책). 상태를 보지 않고 전체 쿠폰 수를 뺀다.
            -- remaining_stock 은 가변 카운터라 created_at 필터로는 시점을 맞출 수 없다.
            -- MVCC 스냅샷이 쿠폰 행과 이 값을 같은 순간으로 묶어준다.
            SELECT 'campaign_stocks' AS target_table,
                   s.stock_id AS target_id,
                   CONCAT('remaining=', s.remaining_stock,
                          ' expected=', s.total_stock - COUNT(c.coupon_id)) AS detail
            FROM campaign_stocks s
            LEFT JOIN coupons c ON c.stock_id = s.stock_id
            GROUP BY s.stock_id, s.remaining_stock, s.total_stock
            HAVING s.remaining_stock <> s.total_stock - COUNT(c.coupon_id)
            """;
    }
}
