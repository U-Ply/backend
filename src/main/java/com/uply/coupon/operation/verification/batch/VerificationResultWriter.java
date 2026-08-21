package com.uply.coupon.operation.verification.batch;

import com.uply.coupon.operation.verification.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@RequiredArgsConstructor
public class VerificationResultWriter {

    private final JdbcTemplate jdbcTemplate;

    /** 결과 적재는 읽기 스냅샷 트랜잭션 밖에서 한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(VerificationRun run) {
        for (RuleResult r : run.results()) {

            jdbcTemplate.update(
                    """
                    INSERT INTO verification_report
                        (run_id, round, status, rule_code, rule_name, snapshot_at,
                         violation_count, sampled_count, checked_rows, elapsed_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        round           = VALUES(round),
                        status          = VALUES(status),
                        rule_name       = VALUES(rule_name),
                        snapshot_at     = VALUES(snapshot_at),
                        violation_count = VALUES(violation_count),
                        sampled_count   = VALUES(sampled_count),
                        checked_rows    = VALUES(checked_rows),
                        elapsed_ms      = VALUES(elapsed_ms)
                    """,
                    run.runId(),
                    run.round() == null ? null : run.round().name(),
                    r.status().name(),
                    r.code(),
                    r.name(),
                    run.snapshotAt(),
                    r.violationCount(),
                    r.sampledCount(),
                    r.checkedRows(),
                    r.elapsedMs());

            // 재실행 시 이전 샘플이 남아 중복되지 않도록 회차×규칙 단위로 먼저 비운다.
            jdbcTemplate.update(
                    "DELETE FROM verification_violation WHERE run_id = ? AND rule_code = ?",
                    run.runId(),
                    r.code());

            if (r.samples().isEmpty()) {
                continue;
            }

            jdbcTemplate.batchUpdate(
                    """
                    INSERT INTO verification_violation
                        (run_id, rule_code, target_table, target_id, detail)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    r.samples(),
                    500,
                    (ps, v) -> {
                        ps.setString(1, run.runId());
                        ps.setString(2, r.code());
                        ps.setString(3, v.targetTable());
                        ps.setLong(4, v.targetId());
                        ps.setString(5, v.detail());
                    });
        }
    }
}
