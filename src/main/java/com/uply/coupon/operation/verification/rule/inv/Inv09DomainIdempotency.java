package com.uply.coupon.operation.verification.rule.inv;

import com.uply.coupon.operation.verification.rule.InvariantRule;
import org.springframework.stereotype.Component;

@Component
public class Inv09DomainIdempotency implements InvariantRule {
    @Override
    public String code() {
        return "INV-09";
    }

    @Override
    public String name() {
        return "도메인 멱등성";
    }

    @Override
    public String checkedRowsSql() {
        return "SELECT COUNT(*) FROM coupon_history";
    }

    @Override
    public String violationSql() {
        return """
            -- idempotency_key UNIQUE 는 같은 '키' 의 중복만 막는다.
            -- 다른 키로 같은 전이가 두 번 들어온 경우는 도메인 레벨에서 재확인한다.
            SELECT 'coupon_history' AS target_table,
                   MIN(history_id) AS target_id,
                   CONCAT('coupon=', coupon_id, ' to=', to_status,
                          ' count=', COUNT(*)) AS detail
            FROM coupon_history
            GROUP BY coupon_id, to_status
            HAVING COUNT(*) > 1
            """;
    }
}
