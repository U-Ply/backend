-- Level 2 반복 실행용 DB 초기화
-- 사용자·캠페인·재고 풀은 유지하고 실행 결과만 제거한다.

USE coupon_db;

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE verification_violation;
TRUNCATE TABLE verification_report;
TRUNCATE TABLE coupon_history;
TRUNCATE TABLE coupons;
SET FOREIGN_KEY_CHECKS = 1;

UPDATE campaign_stocks
SET remaining_stock = total_stock
WHERE campaign_id = 1;

SELECT COUNT(*) AS user_count FROM users;
SELECT COUNT(*) AS coupon_count FROM coupons;
SELECT COUNT(*) AS history_count FROM coupon_history;
SELECT stock_id, route_id, fare_class, total_stock, remaining_stock
FROM campaign_stocks
WHERE campaign_id = 1
ORDER BY stock_id;
