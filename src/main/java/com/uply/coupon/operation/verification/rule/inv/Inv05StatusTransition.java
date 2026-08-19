package com.uply.coupon.operation.verification.rule.inv;

import com.uply.coupon.operation.verification.rule.InvariantRule;
import org.springframework.stereotype.Component;

@Component
public class Inv05StatusTransition implements InvariantRule {
    @Override
    public String code() {
        return "INV-05";
    }

    @Override
    public String name() {
        return "상태 전이 유효성";
    }

    @Override
    public String checkedRowsSql() {
        return "SELECT COUNT(*) FROM coupon_history";
    }

    @Override
    public String violationSql() {
        return """
            -- from_status 가 NULL 이면 비교 연산이 UNKNOWN 이 되어 NOT 으로 감싸도 행이
            -- 걸러지지 않는다. COALESCE 로 문자열을 만들어 NULL 안전하게 비교한다.
            SELECT 'coupon_history' AS target_table,
                   history_id AS target_id,
                   CONCAT(COALESCE(from_status, 'NULL'), '->', to_status) AS detail
            FROM coupon_history
            WHERE CONCAT(COALESCE(from_status, 'NULL'), '->', to_status) NOT IN (
                      'NULL->ISSUED',
                      'ISSUED->USED',
                      'ISSUED->CANCELLED',
                      'ISSUED->EXPIRED')
            """;
    }
}
