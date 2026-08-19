package com.uply.coupon.operation.verification.rule.inv;

import com.uply.coupon.operation.verification.rule.InvariantRule;
import org.springframework.stereotype.Component;

/**
 * 쿠폰 만료 시각은 캠페인에서 상속된다
 *
 * <p>이 규칙은 캠페인이 발급 이후 수정되지 않는다는 전제 위에서만 성립한다.
 */
@Component
public class Inv12ExpireInheritedFromCampaign implements InvariantRule {

    @Override
    public String code() {
        return "INV-12";
    }

    @Override
    public String name() {
        return "만료 시각 캠페인 상속";
    }

    @Override
    public String checkedRowsSql() {
        return "SELECT COUNT(*) FROM coupons";
    }

    @Override
    public String violationSql() {
        return """
            -- 전략마다 만료 시각을 다르게 계산하면 여기서 잡힌다.
            -- 실제로 SyncMysqlSaveStrategy 가 LocalDateTime.now().plusDays(7) 로
            -- 저장하고 있어 인수 기준 E-4 가 깨진 것으로 문서화돼 있다.
            SELECT 'coupons' AS target_table,
                   c.coupon_id AS target_id,
                   CONCAT('coupon=', c.expire_at, ' campaign=', g.expire_at) AS detail
            FROM coupons c
            JOIN campaigns g ON g.campaign_id = c.campaign_id
            WHERE c.expire_at <> g.expire_at
            """;
    }
}
