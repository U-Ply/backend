-- Level 2 실행 후 DB 판정 쿼리

USE coupon_db;

SELECT COUNT(*) AS coupon_count
FROM coupons
WHERE stock_id = 1;

SELECT remaining_stock
FROM campaign_stocks
WHERE stock_id = 1;

SELECT COUNT(*) AS over_issued_pool_count
FROM (
    SELECT cs.stock_id
    FROM campaign_stocks cs
    LEFT JOIN coupons c ON c.stock_id = cs.stock_id
    WHERE cs.stock_id = 1
    GROUP BY cs.stock_id, cs.total_stock
    HAVING COUNT(c.coupon_id) > cs.total_stock
) violations;

SELECT COUNT(*) AS duplicate_user_count
FROM (
    SELECT campaign_id, user_id
    FROM coupons
    WHERE campaign_id = 1
    GROUP BY campaign_id, user_id
    HAVING COUNT(*) > 1
) duplicates;

SELECT
    cs.stock_id,
    cs.total_stock,
    cs.remaining_stock,
    COUNT(c.coupon_id) AS issued_count,
    cs.total_stock - COUNT(c.coupon_id) AS expected_remaining,
    cs.remaining_stock - (cs.total_stock - COUNT(c.coupon_id)) AS stock_diff
FROM campaign_stocks cs
LEFT JOIN coupons c ON c.stock_id = cs.stock_id
WHERE cs.stock_id = 1
GROUP BY cs.stock_id, cs.total_stock, cs.remaining_stock;
