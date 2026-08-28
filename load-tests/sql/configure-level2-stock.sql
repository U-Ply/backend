-- 핫키·다중 재고 풀 시나리오 실행 후 Level 2/3 기본 재고 구조로 복구한다.
-- 사용자와 캠페인은 유지하고 발급 결과·검증 결과·재고 풀만 초기화한다.

USE coupon_db;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE verification_violation;
TRUNCATE TABLE verification_report;
TRUNCATE TABLE coupon_history;
TRUNCATE TABLE coupons;
TRUNCATE TABLE campaign_stocks;
SET FOREIGN_KEY_CHECKS = 1;

UPDATE campaigns
SET name = 'Level 2 제주 얼리버드 특가'
WHERE campaign_id = 1;

INSERT INTO campaign_stocks (
    stock_id,
    campaign_id,
    route_id,
    fare_class,
    total_stock,
    remaining_stock,
    created_at
) VALUES (
    1,
    1,
    'JEJU',
    'ECONOMY',
    10000,
    10000,
    '2026-08-01 00:00:00.000'
);

SELECT stock_id, campaign_id, route_id, fare_class, total_stock, remaining_stock
FROM campaign_stocks
WHERE campaign_id = 1
ORDER BY stock_id;
