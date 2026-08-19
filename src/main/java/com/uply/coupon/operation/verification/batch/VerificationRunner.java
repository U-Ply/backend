package com.uply.coupon.operation.verification.batch;

import com.uply.coupon.operation.verification.domain.*;
import com.uply.coupon.operation.verification.rule.InvariantRule;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationRunner {

    private static final int SAMPLE_LIMIT = 1000;
    private static final double CLOCK_SKEW_TOLERANCE_SEC = 5.0;

    private final JdbcTemplate jdbcTemplate;
    private final List<InvariantRule> rules;

    /** 모든 규칙을 하나의 일관 스냅샷 위에서 실행한다. */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ,
            readOnly = true)
    public VerificationRun runAll(String runId) {

        // read view 는 '첫 consistent read' 에 열린다. SELECT NOW() 는 InnoDB 테이블을
        // 읽지 않아 뷰를 고정하지 못한다. 그래서 작은 테이블을 한 번 읽어 시점을 못박는다.
        // 이 줄을 지우면 규칙별로 다른 시점을 보게 된다.
        jdbcTemplate.queryForList("SELECT 1 FROM campaign_stocks LIMIT 1");

        LocalDateTime snapshotAt =
                jdbcTemplate.queryForObject("SELECT NOW(3)", Timestamp.class).toLocalDateTime();

        List<RuleResult> results = new ArrayList<>();
        results.add(checkClock());

        List<InvariantRule> ordered =
                rules.stream().sorted(Comparator.comparing(InvariantRule::code)).toList();

        for (InvariantRule rule : ordered) {
            results.add(evaluate(rule));
        }

        return new VerificationRun(runId, snapshotAt, results);
    }

    private RuleResult evaluate(InvariantRule rule) {
        long started = System.nanoTime();
        try {
            String body = rule.violationSql();

            long violationCount =
                    Optional.ofNullable(
                                    jdbcTemplate.queryForObject(
                                            "SELECT COUNT(*) FROM (" + body + ") v", Long.class))
                            .orElse(0L);

            List<Violation> samples = List.of();
            if (violationCount > 0) {
                samples =
                        jdbcTemplate.query(
                                "SELECT * FROM (" + body + ") v ORDER BY target_id LIMIT ?",
                                (rs, i) ->
                                        new Violation(
                                                rs.getString("target_table"),
                                                rs.getLong("target_id"),
                                                rs.getString("detail")),
                                SAMPLE_LIMIT);
            }

            Long checkedRows =
                    rule.checkedRowsSql() == null
                            ? null
                            : jdbcTemplate.queryForObject(rule.checkedRowsSql(), Long.class);

            int elapsedMs = (int) ((System.nanoTime() - started) / 1_000_000);
            log.info("[{}] {} — 위반 {}건, {}ms", rule.code(), rule.name(), violationCount, elapsedMs);

            return new RuleResult(
                    rule.code(),
                    rule.name(),
                    violationCount,
                    samples.size(),
                    checkedRows,
                    elapsedMs,
                    samples);

        } catch (DataAccessException e) {
            throw new IllegalStateException(rule.code() + " 실행 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 앱과 DB 의 시계·타임존이 어긋나면 이 회차의 판정 전체를 믿을 수 없다.
     *
     * <p>오차는 UNIX_TIMESTAMP(epoch)로 잰다. 타임존과 무관한 절대시각이라 순수한 시계 오차만 나온다. 타임존 설정 자체는 별도로 확인한다. 둘을 한
     * 값으로 섞으면 원인을 못 가른다.
     */
    private RuleResult checkClock() {
        String tz = jdbcTemplate.queryForObject("SELECT @@session.time_zone", String.class);
        double dbEpoch = jdbcTemplate.queryForObject("SELECT UNIX_TIMESTAMP(NOW(3))", Double.class);
        double driftSec = System.currentTimeMillis() / 1000.0 - dbEpoch;

        boolean tzBad = !("+00:00".equals(tz) || "UTC".equalsIgnoreCase(tz));
        boolean driftBad = Math.abs(driftSec) > CLOCK_SKEW_TOLERANCE_SEC;
        long violations = (tzBad || driftBad) ? 1L : 0L;

        String detail = String.format("session_tz=%s drift=%.3fs", tz, driftSec);
        if (violations > 0) {
            log.error("[CLOCK-01] 시계/타임존 이상 — {}. 이 회차의 검증 결과는 신뢰할 수 없다.", detail);
        } else {
            log.info("[CLOCK-01] {}", detail);
        }

        return new RuleResult(
                "CLOCK-01", "앱·DB 시계 정합성 — " + detail, violations, 0, null, 0, List.of());
    }
}
