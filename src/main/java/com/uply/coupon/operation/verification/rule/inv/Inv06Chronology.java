package com.uply.coupon.operation.verification.rule.inv;

import com.uply.coupon.operation.verification.rule.InvariantRule;
import org.springframework.stereotype.Component;

@Component
public class Inv06Chronology implements InvariantRule {
    @Override
    public String code() {
        return "INV-06";
    }

    @Override
    public String name() {
        return "시각 순서";
    }

    @Override
    public String checkedRowsSql() {
        return "SELECT COUNT(*) FROM coupons";
    }

    @Override
    public String violationSql() {
        return """
            SELECT 'coupons' AS target_table,
                   coupon_id AS target_id,
                   CONCAT('issued=', issued_at,
                          ' used=',      COALESCE(CAST(used_at      AS CHAR), '-'),
                          ' cancelled=', COALESCE(CAST(cancelled_at AS CHAR), '-'),
                          ' expired=',   COALESCE(CAST(expired_at   AS CHAR), '-'),
                          ' expire=',    expire_at) AS detail
            FROM coupons
            WHERE (used_at      IS NOT NULL AND used_at      < issued_at)
               OR (cancelled_at IS NOT NULL AND cancelled_at < issued_at)
               OR (expired_at   IS NOT NULL AND expired_at   < issued_at)
               -- 만료 처리는 유효기간이 지난 뒤에 일어나야 한다
               OR (expired_at   IS NOT NULL AND expired_at   < expire_at)
            """;
    }
}
