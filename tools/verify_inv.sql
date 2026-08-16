-- =====================================================================
--  정합성 검증 쿼리 (수동 실행용) — INV-01 ~ INV-10
--
--  요구 분석서 11절의 판정 기준을 그대로 옮긴 것이다.
--  Spring Batch 로 감싸기 전에 SQL 이 맞는지 먼저 확인하는 용도.
--  배치부터 만들면 "쿼리가 틀린 건지 배치가 틀린 건지" 를 구분할 수 없다.
--
--  실행:
--    docker compose exec -T mysql mysql --default-character-set=utf8mb4 \
--      -uroot -proot1234 coupon_db < tools/verify_inv.sql
--
--  기대:
--    정상 데이터        → 전부 0
--    --corrupt=5 주입   → INV-04 만 5, 나머지는 0 (오탐 없음)
--
--  ※ REC-01(Redis-DB 재고 대사)은 Redis 커넥션이 필요해 여기 없다.
--     별도 Job(stockReconcileJob)으로 분리한다.
-- =====================================================================

-- 시점 고정. 회차 안의 모든 규칙이 같은 순간을 봐야 한다.
-- 안 하면 규칙 사이에 들어온 발급 때문에 없는 위반이 잡힌다.
SET @snapshot_at = NOW(3);

SELECT @snapshot_at AS snapshot_at;


-- ─────────────────────────── 요약 ───────────────────────────

SELECT 'INV-01' AS rule_code, '초과 발급 금지' AS rule_name, COUNT(*) AS violations
FROM (
    -- 시점 조건은 반드시 ON 절에. WHERE 로 가면 LEFT JOIN 이 INNER 로 퇴화해서
    -- "발급이 0건인 재고 풀" 이 결과에서 통째로 빠진다.
    SELECT s.stock_id
    FROM campaign_stocks s
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

SELECT 'INV-03', 'DB 재고 카운터', COUNT(*)
FROM (
    -- 발급 이후 재고는 복구되지 않으므로 상태를 보지 않고 전체 쿠폰 수를 뺀다.
    SELECT s.stock_id
    FROM campaign_stocks s
    LEFT JOIN coupons c ON c.stock_id = s.stock_id AND c.created_at <= @snapshot_at
    WHERE s.created_at <= @snapshot_at
    GROUP BY s.stock_id, s.remaining_stock, s.total_stock
    HAVING s.remaining_stock <> s.total_stock - COUNT(c.coupon_id)
) t

UNION ALL

SELECT 'INV-04', '현재 상태 = 최종 이력', COUNT(*)
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
    -- LEFT JOIN 이라야 "이력이 아예 없는 쿠폰" 도 위반으로 잡힌다 (요구 분석서 11절)
    SELECT c.coupon_id
    FROM coupons c
    LEFT JOIN last_h l ON l.coupon_id = c.coupon_id AND l.rn = 1
    WHERE c.created_at <= @snapshot_at
      AND (l.coupon_id IS NULL OR c.status <> l.to_status)
) t

UNION ALL

SELECT 'INV-05', '상태 전이 유효성', COUNT(*)
FROM (
    -- from_status 가 NULL 이라 비교 연산이 UNKNOWN 이 되면 NOT 으로 감싸도 행이
    -- 걸러지지 않는다. COALESCE 로 문자열을 만들어 NULL 안전하게 비교한다.
    SELECT history_id
    FROM coupon_history
    WHERE created_at <= @snapshot_at
      AND CONCAT(COALESCE(from_status, 'NULL'), '->', to_status) NOT IN (
              'NULL->ISSUED',
              'ISSUED->USED',
              'ISSUED->CANCELLED',
              'ISSUED->EXPIRED'
          )
) t

UNION ALL

SELECT 'INV-06', '시각 순서', COUNT(*)
FROM (
    SELECT coupon_id
    FROM coupons
    WHERE created_at <= @snapshot_at
      AND (
             (used_at      IS NOT NULL AND used_at      < issued_at)
          OR (cancelled_at IS NOT NULL AND cancelled_at < issued_at)
          OR (expired_at   IS NOT NULL AND expired_at   < issued_at)
          -- 만료 처리는 유효기간이 지난 뒤에 일어나야 한다
          OR (expired_at   IS NOT NULL AND expired_at   < expire_at)
      )
) t

UNION ALL

SELECT 'INV-07', '종료 상태 타임스탬프', COUNT(*)
FROM (
    -- 현재 상태에 대응하는 종료 시각만 존재하고 나머지는 NULL 이어야 한다.
    SELECT coupon_id
    FROM coupons
    WHERE created_at <= @snapshot_at
      AND NOT (
             (status = 'ISSUED'
                  AND used_at IS NULL AND cancelled_at IS NULL AND expired_at IS NULL)
          OR (status = 'USED'
                  AND used_at IS NOT NULL AND cancelled_at IS NULL AND expired_at IS NULL)
          OR (status = 'CANCELLED'
                  AND used_at IS NULL AND cancelled_at IS NOT NULL AND expired_at IS NULL)
          OR (status = 'EXPIRED'
                  AND used_at IS NULL AND cancelled_at IS NULL AND expired_at IS NOT NULL)
      )
) t

UNION ALL

SELECT 'INV-08', '참조 조합 일치', COUNT(*)
FROM (
    -- FK 두 개가 각각은 유효한데 조합이 틀린 경우. FK 로는 못 잡는다.
    SELECT c.coupon_id
    FROM coupons c
    JOIN campaign_stocks s ON s.stock_id = c.stock_id
    WHERE c.created_at <= @snapshot_at
      AND c.campaign_id <> s.campaign_id
) t

UNION ALL

SELECT 'INV-09', '도메인 멱등성', COUNT(*)
FROM (
    -- idempotency_key UNIQUE 는 같은 '키' 의 중복만 막는다.
    -- 다른 키로 같은 전이가 두 번 들어온 경우는 도메인 레벨에서 재확인한다.
    SELECT coupon_id, to_status
    FROM coupon_history
    WHERE created_at <= @snapshot_at
    GROUP BY coupon_id, to_status
    HAVING COUNT(*) > 1
) t

UNION ALL

SELECT 'INV-10', '고아 행 없음', COUNT(*)
FROM (
    -- 초기화 스크립트가 FOREIGN_KEY_CHECKS=0 을 쓰기 때문에
    -- FK 가 걸려 있어도 고아 행이 생길 수 있다.
    -- 부모에는 시점 조건을 걸지 않는다. 걸면 "나중에 만들어진 부모" 가
    -- 없는 것으로 보여 없는 고아가 잡힌다.
    SELECT s.stock_id AS id
    FROM campaign_stocks s
    LEFT JOIN campaigns g ON g.campaign_id = s.campaign_id
    WHERE s.created_at <= @snapshot_at AND g.campaign_id IS NULL

    UNION ALL
    SELECT c.coupon_id
    FROM coupons c
    LEFT JOIN users u ON u.user_id = c.user_id
    WHERE c.created_at <= @snapshot_at AND u.user_id IS NULL

    UNION ALL
    SELECT c.coupon_id
    FROM coupons c
    LEFT JOIN campaigns g ON g.campaign_id = c.campaign_id
    WHERE c.created_at <= @snapshot_at AND g.campaign_id IS NULL

    UNION ALL
    SELECT c.coupon_id
    FROM coupons c
    LEFT JOIN campaign_stocks s ON s.stock_id = c.stock_id
    WHERE c.created_at <= @snapshot_at AND s.stock_id IS NULL

    UNION ALL
    SELECT h.history_id
    FROM coupon_history h
    LEFT JOIN coupons c ON c.coupon_id = h.coupon_id
    WHERE h.created_at <= @snapshot_at AND c.coupon_id IS NULL
) t;


-- ─────────────────────── INV-04 위반 샘플 ───────────────────────

WITH last_h AS (
    SELECT coupon_id, to_status,
           ROW_NUMBER() OVER (
               PARTITION BY coupon_id ORDER BY event_at DESC, history_id DESC
           ) AS rn
    FROM coupon_history
    WHERE created_at <= @snapshot_at
)
SELECT c.coupon_id, c.status AS now_status,
       COALESCE(l.to_status, '(이력 없음)') AS last_history
FROM coupons c
LEFT JOIN last_h l ON l.coupon_id = c.coupon_id AND l.rn = 1
WHERE c.created_at <= @snapshot_at
  AND (l.coupon_id IS NULL OR c.status <> l.to_status)
ORDER BY c.coupon_id
LIMIT 20;


-- ─────────────────────── 데이터 요약 ───────────────────────

SELECT status, COUNT(*) AS cnt,
       ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 1) AS pct
FROM coupons
GROUP BY status
ORDER BY cnt DESC;

SELECT COUNT(*) AS coupons,
       (SELECT COUNT(*) FROM coupon_history) AS history,
       ROUND((SELECT COUNT(*) FROM coupon_history) / COUNT(*), 2) AS history_per_coupon
FROM coupons;
