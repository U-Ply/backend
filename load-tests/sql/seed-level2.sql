-- Level 2 공통 시드
-- 주의: coupon_db의 기존 데이터를 모두 삭제하고 테스트 기준 데이터로 교체한다.

USE coupon_db;

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE verification_violation;
TRUNCATE TABLE verification_report;
TRUNCATE TABLE coupon_history;
TRUNCATE TABLE coupons;
TRUNCATE TABLE campaign_stocks;
TRUNCATE TABLE campaigns;
TRUNCATE TABLE users;
SET FOREIGN_KEY_CHECKS = 1;

SET SESSION cte_max_recursion_depth = 20001;

INSERT INTO users (user_id, email, name, created_at)
WITH RECURSIVE sequence AS (
    SELECT 1 AS user_id
    UNION ALL
    SELECT user_id + 1
    FROM sequence
    WHERE user_id < 20000
)
SELECT
    user_id,
    CONCAT('loadtest', user_id, '@example.com'),
    CONCAT('loadtest-user-', user_id),
    '2026-08-01 00:00:00.000'
FROM sequence;

INSERT INTO campaigns (
    campaign_id,
    name,
    open_at,
    expire_at,
    created_at
) VALUES (
    1,
    'Level 2 제주 얼리버드 특가',
    '2026-08-01 00:00:00.000',
    '2099-12-31 23:59:59.999',
    '2026-08-01 00:00:00.000'
);

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

SELECT COUNT(*) AS user_count FROM users;
SELECT campaign_id, name, open_at, expire_at FROM campaigns WHERE campaign_id = 1;
SELECT stock_id, campaign_id, route_id, fare_class, total_stock, remaining_stock
FROM campaign_stocks
WHERE stock_id = 1;
