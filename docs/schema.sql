-- =====================================================================
--  대규모 트래픽 선착순 쿠폰 발급 시스템 — DDL
--  항공권 특가 이벤트
--
--  요구 버전 : MySQL 8.0.16 이상
--              (8.0.16 미만은 CHECK 제약을 파싱만 하고 무시한다)
--  실행      : mysql -h127.0.0.1 -uroot -proot1234 < docs/schema.sql
--  버전 확인 : SELECT VERSION();
-- =====================================================================

CREATE DATABASE IF NOT EXISTS coupon_db
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;
USE coupon_db;

-- 재실행 가능하도록 FK 역순으로 정리
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS verification_violation;
DROP TABLE IF EXISTS verification_report;
DROP TABLE IF EXISTS coupon_history;
DROP TABLE IF EXISTS coupons;
DROP TABLE IF EXISTS campaign_stocks;
DROP TABLE IF EXISTS campaigns;
DROP TABLE IF EXISTS users;
SET FOREIGN_KEY_CHECKS = 1;


-- =====================================================================
--  1. users
-- =====================================================================
CREATE TABLE users (
    user_id    BIGINT       NOT NULL AUTO_INCREMENT,
    email      VARCHAR(255) NOT NULL,
    name       VARCHAR(100) NOT NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (user_id),
    UNIQUE KEY uk_users_email (email)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '회원';


-- =====================================================================
--  2. campaigns
--     항공권 특가 이벤트 하나의 기본 정보
-- =====================================================================
CREATE TABLE campaigns (
    campaign_id BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(255) NOT NULL COMMENT '예: 제주 얼리버드 특가',
    open_at     DATETIME(3)  NOT NULL COMMENT '오픈 예약 시각. 이전 요청은 거부',
    expire_at   DATETIME(3)  NOT NULL COMMENT '쿠폰 유효기간 만료',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (campaign_id),
    KEY idx_campaigns_open_at (open_at),

    CONSTRAINT ck_campaign_period CHECK (expire_at > open_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '캠페인';


-- =====================================================================
--  3. campaign_stocks
--     재고 풀을 route_id + fare_class 로 쪼개 Redis 핫키를 분산한다.
--     CHECK 두 개가 초과 발급에 대한 DB 레벨 최종 방어선이다.
--     (coupons 의 UNIQUE(campaign_id, user_id) 로는 초과 발급을 못 막는다.
--      유저가 전부 다르면 전부 통과하기 때문.)
-- =====================================================================
CREATE TABLE campaign_stocks (
    stock_id        BIGINT      NOT NULL AUTO_INCREMENT,
    campaign_id     BIGINT      NOT NULL,
    route_id        VARCHAR(50) NOT NULL COMMENT '예: JEJU',
    fare_class      VARCHAR(20) NOT NULL COMMENT '예: ECONOMY, BUSINESS',
    total_stock     INT         NOT NULL COMMENT '불변',
    remaining_stock INT         NOT NULL COMMENT '발급 시 -1',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (stock_id),
    UNIQUE KEY uk_stock_pool (campaign_id, route_id, fare_class),

    CONSTRAINT fk_stock_campaign FOREIGN KEY (campaign_id)
        REFERENCES campaigns (campaign_id),

    CONSTRAINT ck_stock_total  CHECK (total_stock > 0),
    CONSTRAINT ck_stock_nonneg CHECK (remaining_stock >= 0),
    CONSTRAINT ck_stock_upper  CHECK (remaining_stock <= total_stock)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '노선·등급별 재고 풀';


-- =====================================================================
--  4. coupons
--     uk_campaign_user : 1인 1매 최종 방어선
--     idx_stock_status : 검증 배치 INV-01 / INV-03
--     idx_expire       : 만료 배치 스캔
-- =====================================================================
CREATE TABLE coupons (
    coupon_id    BIGINT      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL COMMENT '이 쿠폰을 받은 회원',
    campaign_id  BIGINT      NOT NULL COMMENT '어느 캠페인에서 발급됐는지',
    stock_id     BIGINT      NOT NULL COMMENT '어느 재고 풀에서 차감됐는지',

    status       VARCHAR(20) NOT NULL COMMENT 'ISSUED, USED, CANCELLED, EXPIRED',
    issued_at    DATETIME(3) NOT NULL     COMMENT '발급 시각',
    used_at      DATETIME(3)     NULL     COMMENT '사용 시각',
    cancelled_at DATETIME(3)     NULL     COMMENT '취소 시각',
    expired_at   DATETIME(3)     NULL     COMMENT '만료 배치가 실제 처리한 시각',
    expire_at    DATETIME(3) NOT NULL     COMMENT '만료 예정 시각',
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                 COMMENT 'DB 최초 저장 시각',

    PRIMARY KEY (coupon_id),
    UNIQUE KEY uk_campaign_user (campaign_id, user_id),
    KEY idx_coupon_stock_status (stock_id, status),
    KEY idx_coupon_expire       (status, expire_at),
    KEY idx_coupon_user         (user_id),

    CONSTRAINT fk_coupon_user     FOREIGN KEY (user_id)     REFERENCES users (user_id),
    CONSTRAINT fk_coupon_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns (campaign_id),
    CONSTRAINT fk_coupon_stock    FOREIGN KEY (stock_id)    REFERENCES campaign_stocks (stock_id),

    CONSTRAINT ck_coupon_status CHECK (status IN ('ISSUED','USED','CANCELLED','EXPIRED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '발급된 쿠폰';


-- =====================================================================
--  5. coupon_history
--     uq_idempotency_key : 요청 단위 멱등성 최종 방어선.
--                          배치도 키를 만들어 넣는다.
--                          예) expire-{couponId}-{jobExecutionId}
--     idx_coupon_event   : Kafka 순서 역전 대응.
--                          "이 쿠폰의 마지막 이력" 조회에 그대로 쓰인다.
--                          history_id 가 3번째인 이유는 같은 ms 동점 처리용.
-- =====================================================================
CREATE TABLE coupon_history (
    history_id      BIGINT      NOT NULL AUTO_INCREMENT,
    coupon_id       BIGINT      NOT NULL,
    from_status     VARCHAR(20)     NULL COMMENT '최초 발급은 NULL',
    to_status       VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL COMMENT '이 변경을 일으킨 요청',
    event_at        DATETIME(3) NOT NULL COMMENT '사건 발생 시각(발행 측 기준)',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    COMMENT 'DB 저장 시각(소비 측 기준). 카프카 적재 지연 측정용',

    PRIMARY KEY (history_id),
    UNIQUE KEY uq_idempotency_key (idempotency_key),
    KEY idx_coupon_event (coupon_id, event_at, history_id),

    CONSTRAINT fk_history_coupon FOREIGN KEY (coupon_id)
        REFERENCES coupons (coupon_id),

    CONSTRAINT ck_history_to   CHECK (to_status IN ('ISSUED','USED','CANCELLED','EXPIRED')),
    CONSTRAINT ck_history_from CHECK (from_status IS NULL
                                      OR from_status IN ('ISSUED','USED','CANCELLED','EXPIRED')),
    CONSTRAINT ck_history_no_self CHECK (from_status IS NULL OR from_status <> to_status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '쿠폰 상태 변경 이력';


-- =====================================================================
--  6. verification_report
--     실행 1회 × 규칙 1개 = 1행.
--     snapshot_at 이 이 설계의 핵심 — 회차 내 모든 규칙이 같은 시점을 본다.
--     안 그러면 규칙 사이에 들어온 발급 때문에 없는 위반이 잡힌다.
-- =====================================================================
CREATE TABLE verification_report (
    report_id       BIGINT       NOT NULL AUTO_INCREMENT,
    run_id          VARCHAR(40)  NOT NULL COMMENT 'Spring Batch JobParameter runId',
    rule_code       VARCHAR(20)  NOT NULL COMMENT 'INV-01 ~ INV-08, REC-01',
    rule_name       VARCHAR(100) NOT NULL COMMENT '사람이 읽는 이름',
    snapshot_at     DATETIME(3)  NOT NULL COMMENT '시점 고정. 회차 내 전 규칙 동일',
    violation_count BIGINT       NOT NULL DEFAULT 0 COMMENT '전수 COUNT. 0이면 통과',
    sampled_count   INT          NOT NULL DEFAULT 0 COMMENT 'violation 에 실제 저장한 수',
    checked_rows    BIGINT           NULL COMMENT '이 규칙이 검사한 행 수',
    elapsed_ms      INT              NULL COMMENT '규칙별 소요 시간',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    passed          BOOLEAN GENERATED ALWAYS AS (violation_count = 0) STORED,

    PRIMARY KEY (report_id),
    UNIQUE KEY uk_run_rule (run_id, rule_code) COMMENT '같은 회차에 같은 규칙 1회 = 멱등',
    KEY idx_report_rule_passed (rule_code, passed),
    -- run_id 단독 조회는 uk_run_rule(run_id, rule_code) 가 커버하므로 별도 인덱스 불필요

    CONSTRAINT ck_report_sample CHECK (sampled_count <= violation_count)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '검증 회차 × 규칙별 결과';


-- =====================================================================
--  7. verification_violation
--     위반 행 하나 = 1행. Detect → Sample → Record 의 Sample 저장소.
--     전수는 report.violation_count, 여기엔 LIMIT 1000 만 남긴다.
--
--     FK 를 걸지 않는 이유:
--       - target_table 이 3개 테이블을 가리켜서 단일 FK 로는 불가능
--       - 검증은 읽기 전용이고, 대상이 지워져도 결과는 남아야 한다
-- =====================================================================
CREATE TABLE verification_violation (
    violation_id BIGINT       NOT NULL AUTO_INCREMENT,
    run_id       VARCHAR(40)  NOT NULL COMMENT 'verification_report 와 같은 회차',
    rule_code    VARCHAR(20)  NOT NULL COMMENT '어느 규칙이 잡았는지',
    target_table VARCHAR(30)  NOT NULL COMMENT 'coupons | campaign_stocks | coupon_history',
    target_id    BIGINT       NOT NULL COMMENT '위반한 행의 PK',
    detail       VARCHAR(500)     NULL COMMENT '예: current=USED last_history=CANCELLED',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (violation_id),
    KEY idx_violation_run_rule (run_id, rule_code),
    KEY idx_violation_target   (target_table, target_id),

    CONSTRAINT ck_violation_table CHECK (target_table IN
        ('coupons','campaign_stocks','coupon_history'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '검증 위반 샘플';


-- =====================================================================
--  확인
-- =====================================================================
-- 클라이언트 문자셋이 utf8mb4 가 아니면 한글 별칭에서 깨지므로 영문으로 둔다.
SELECT TABLE_NAME, TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'coupon_db'
ORDER BY TABLE_NAME;

-- CHECK 제약이 실제로 등록됐는지 확인 (8.0.16 미만이면 여기가 비어 있다)
SELECT TABLE_NAME, CONSTRAINT_NAME
FROM information_schema.TABLE_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = 'coupon_db' AND CONSTRAINT_TYPE = 'CHECK'
ORDER BY TABLE_NAME, CONSTRAINT_NAME;
