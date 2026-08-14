-- =====================================================================
--  검증 쿼리 (수동 실행용) — INV-01 / 02 / 03 / 04
--
--  Spring Batch 로 감싸기 전에 SQL 이 맞는지 먼저 확인하는 용도다.
--  배치부터 만들면 "쿼리가 틀린 건지 배치가 틀린 건지" 를 구분할 수 없다.
--
--  실행:
--    docker compose exec -T mysql mysql --default-character-set=utf8mb4 \
--      -uroot -proot1234 coupon_db < tools/verify_inv.sql
--
--  기대:
--    정상 데이터        → 전부 0
--    --corrupt=5 주입   → INV-04 만 5, 나머지는 0 (오탐 없음)
-- =====================================================================

-- 시점 고정. 회차 안의 모든 규칙이 같은 순간을 봐야 한다.
-- 안 하면 규칙 사이에 들어온 발급 때문에 없는 위반이 잡힌다.
SET @snapshot_at = NOW(3);

SELECT @snapshot_at AS snapshot_at;

SELECT 'INV-01' AS rule_code, '발급 건수 <= 총 재고' AS rule_name, COUNT(*) AS violations
FROM (
    SELECT s.stock_id
    FROM campaign_stocks s
    -- 시점 조건은 반드시 ON 절에. WHERE 로 가면 LEFT JOIN 이 INNER 로 퇴화해서
    -- "발급이 0건인 재고 풀" 이 결과에서 통째로 빠진다.
    LEFT JOIN coupons c ON c.stock_id = s.stock_id AND c.created_at <= @snapshot_at
    WHERE s.created_at <= @snapshot_at
    GROUP BY s.stock_id, s.total_stock
    HAVING COUNT(c.coupon_id) > s.total_stock
) t

UNION ALL

SELECT 'INV-02', '1인 1매', COUNT(*)
FROM (
    SELECT campaign_id, user_id
    FROM coupons
    WHERE created_at <= @snapshot_at
    GROUP BY campaign_id, user_id
    HAVING COUNT(*) > 1
) t

UNION ALL

SELECT 'INV-03', '재고 카운터 정합성', COUNT(*)
FROM (
    -- 취소는 재고 소멸 정책이므로 취소분을 더하지 않는다 (공통 협업 기준 3번)
    SELECT s.stock_id
    FROM campaign_stocks s
    LEFT JOIN coupons c ON c.stock_id = s.stock_id AND c.created_at <= @snapshot_at
    WHERE s.created_at <= @snapshot_at
    GROUP BY s.stock_id, s.remaining_stock, s.total_stock
    HAVING s.remaining_stock <> s.total_stock - COUNT(c.coupon_id)
) t

UNION ALL

SELECT 'INV-04', '현재 상태 = 이력의 최종 상태', COUNT(*)
FROM (
    WITH last_h AS (
        SELECT coupon_id, to_status,
               -- history_id 가 세 번째인 이유는 같은 밀리초 동점을 깨기 위해서다.
               -- idx_coupon_event(coupon_id, event_at, history_id) 를 그대로 탄다.
               ROW_NUMBER() OVER (
                   PARTITION BY coupon_id ORDER BY event_at DESC, history_id DESC
               ) AS rn
        FROM coupon_history
        WHERE created_at <= @snapshot_at
    )
    SELECT c.coupon_id
    FROM coupons c
    JOIN last_h l ON l.coupon_id = c.coupon_id AND l.rn = 1
    WHERE c.created_at <= @snapshot_at AND c.status <> l.to_status
) t;


-- =====================================================================
--  INV-04 위반 샘플 (검출된 게 있으면 어떤 쿠폰인지 확인)
-- =====================================================================
WITH last_h AS (
    SELECT coupon_id, to_status,
           ROW_NUMBER() OVER (
               PARTITION BY coupon_id ORDER BY event_at DESC, history_id DESC
           ) AS rn
    FROM coupon_history
    WHERE created_at <= @snapshot_at
)
SELECT c.coupon_id, c.status AS now_status, l.to_status AS last_history
FROM coupons c
JOIN last_h l ON l.coupon_id = c.coupon_id AND l.rn = 1
WHERE c.created_at <= @snapshot_at AND c.status <> l.to_status
ORDER BY c.coupon_id
LIMIT 20;


-- =====================================================================
--  참고 — 생성된 데이터가 의도대로인지 확인
-- =====================================================================
SELECT status, COUNT(*) AS cnt,
       ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 1) AS pct
FROM coupons
GROUP BY status
ORDER BY cnt DESC;

SELECT COUNT(*) AS coupons, (SELECT COUNT(*) FROM coupon_history) AS history,
       ROUND((SELECT COUNT(*) FROM coupon_history) / COUNT(*), 2) AS history_per_coupon
FROM coupons;
