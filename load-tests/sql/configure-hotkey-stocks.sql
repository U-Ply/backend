USE coupon_db;

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE verification_violation;
TRUNCATE TABLE verification_report;
TRUNCATE TABLE coupon_history;
TRUNCATE TABLE coupons;
TRUNCATE TABLE campaign_stocks;
SET FOREIGN_KEY_CHECKS = 1;

UPDATE campaigns SET name = 'Hotkey route distribution test' WHERE campaign_id = 1;

INSERT INTO campaign_stocks
    (stock_id, campaign_id, route_id, fare_class, total_stock, remaining_stock, created_at)
VALUES
    (1, 1, 'JEJU',     'ECONOMY',  500,  500, '2026-08-01 00:00:00.000'),
    (2, 1, 'FUKUOKA', 'ECONOMY',  300,  300, '2026-08-01 00:00:00.000'),
    (3, 1, 'BANGKOK',  'ECONOMY', 1000, 1000, '2026-08-01 00:00:00.000');

SELECT stock_id, campaign_id, route_id, fare_class, total_stock, remaining_stock
FROM campaign_stocks
ORDER BY stock_id;
