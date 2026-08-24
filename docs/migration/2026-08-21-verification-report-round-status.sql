-- Existing databases created before round/status were added need this migration.
-- Fresh databases must use docs/schema.sql instead.
ALTER TABLE verification_report
    ADD COLUMN round VARCHAR(10) NULL
        COMMENT '검증 대상 회차 V0~V3' AFTER run_id,
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'CHECKED'
        COMMENT 'CHECKED | NOT_APPLICABLE | SKIPPED' AFTER round;
