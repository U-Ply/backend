package com.uply.coupon.operation.verification.rule.inv;

import com.uply.coupon.operation.verification.rule.InvariantRule;
import org.springframework.stereotype.Component;

@Component
public class Inv07TerminalTimestamp implements InvariantRule {
    @Override
    public String code() {
        return "INV-07";
    }

    @Override
    public String name() {
        return "종료 상태 타임스탬프";
    }

    @Override
    public String checkedRowsSql() {
        return "SELECT COUNT(*) FROM coupons";
    }

    @Override
    public String violationSql() {
        return """
            -- 양방향 검사다. 현재 상태에 대응하는 종료 시각만 존재하고 나머지는 NULL 이어야 한다.
            -- "USED 인데 used_at 이 NULL" 만 보면 "USED 인데 cancelled_at 도 있는" 행을 놓친다.
            SELECT 'coupons' AS target_table,
                   coupon_id AS target_id,
                   CONCAT('status=', status,
                          ' used=',      IF(used_at      IS NULL, 'N', 'Y'),
                          ' cancelled=', IF(cancelled_at IS NULL, 'N', 'Y'),
                          ' expired=',   IF(expired_at   IS NULL, 'N', 'Y')) AS detail
            FROM coupons
            WHERE NOT (
                 (status = 'ISSUED'
                      AND used_at IS NULL AND cancelled_at IS NULL AND expired_at IS NULL)
              OR (status = 'USED'
                      AND used_at IS NOT NULL AND cancelled_at IS NULL AND expired_at IS NULL)
              OR (status = 'CANCELLED'
                      AND used_at IS NULL AND cancelled_at IS NOT NULL AND expired_at IS NULL)
              OR (status = 'EXPIRED'
                      AND used_at IS NULL AND cancelled_at IS NULL AND expired_at IS NOT NULL)
              OR (status = 'CANCELLED'
                      AND cancelled_at IS NOT NULL AND expired_at IS NULL)
                      )
            """;
    }
}
