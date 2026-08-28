-- 핫키·다중 재고 풀 시나리오 종료 후 campaign 1의 전체 재고 풀을 검증한다.

USE coupon_db;

SELECT
    cs.stock_id,
    cs.route_id,
    cs.fare_class,
    cs.total_stock,
    cs.remaining_stock,
    COUNT(c.coupon_id) AS issued_count,
    cs.total_stock - COUNT(c.coupon_id) AS expected_remaining,
    cs.remaining_stock - (cs.total_stock - COUNT(c.coupon_id)) AS stock_diff
FROM campaign_stocks cs
LEFT JOIN coupons c ON c.stock_id = cs.stock_id
WHERE cs.campaign_id = 1
GROUP BY
    cs.stock_id,
    cs.route_id,
    cs.fare_class,
    cs.total_stock,
    cs.remaining_stock
ORDER BY cs.stock_id;

SELECT COUNT(*) AS over_issued_pool_count
FROM (
    SELECT cs.stock_id
    FROM campaign_stocks cs
    LEFT JOIN coupons c ON c.stock_id = cs.stock_id
    WHERE cs.campaign_id = 1
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

SELECT COUNT(*) AS stock_mismatch_pool_count
FROM (
    SELECT cs.stock_id
    FROM campaign_stocks cs
    LEFT JOIN coupons c ON c.stock_id = cs.stock_id
    WHERE cs.campaign_id = 1
    GROUP BY cs.stock_id, cs.total_stock, cs.remaining_stock
    HAVING cs.remaining_stock <> cs.total_stock - COUNT(c.coupon_id)
) mismatches;
