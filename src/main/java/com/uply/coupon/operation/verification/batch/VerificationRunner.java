package com.uply.coupon.operation.verification.batch;

import com.uply.coupon.operation.verification.domain.*;
import com.uply.coupon.operation.verification.rule.InvariantRule;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationRunner {

    private static final int SAMPLE_LIMIT = 1000;
    private static final double CLOCK_SKEW_TOLERANCE_SEC = 5.0;
    private static final double REDIS_CLOCK_TOLERANCE_SEC = 1.0;
    private final ObjectProvider<RedisConnectionFactory> redisConnectionFactory;

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
        results.add(checkRedisClock());

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

    /**
     * Redis 와 DB 의 시계 오차를 잰다.
     *
     * <p>Lua 경로는 오픈/만료를 Redis TIME 으로, DB 경로는 NOW(3) 로 판정한다. 두 시계가 어긋나면 같은 요청에 전략마다 다른 답이 나온다. 인수
     * 기준 E-2(만료 정각) / E-3(만료 1초 후)이 1초 경계를 재므로, 허용 오차를 그보다 크게 잡으면 그 테스트가 의미를 잃는다.
     *
     * <p>Redis 가 없거나 닿지 않으면 위반이 아니라 N/A 로 기록한다. INV 규칙은 MySQL 만으로 성립하므로, Redis 를 쓰지 않는 V0/V1 회차에서
     * 없는 문제를 만들면 안 된다.
     */
    private RuleResult checkRedisClock() {
        RedisConnectionFactory factory = redisConnectionFactory.getIfAvailable();
        if (factory == null) {
            return clockResult("CLOCK-02", "Redis 미구성 (N/A)", false);
        }

        try (RedisConnection connection = factory.getConnection()) {
            Long redisMillis = connection.serverCommands().time(TimeUnit.MILLISECONDS);
            double dbEpoch =
                    jdbcTemplate.queryForObject("SELECT UNIX_TIMESTAMP(NOW(3))", Double.class);
            double driftSec = redisMillis / 1000.0 - dbEpoch;

            boolean violated = Math.abs(driftSec) > REDIS_CLOCK_TOLERANCE_SEC;
            String detail = String.format("redis_drift=%.3fs", driftSec);

            if (violated) {
                log.error(
                        "[CLOCK-02] Redis·DB 시계가 {} 어긋났다. " + "Lua 경로와 DB 경로의 만료 판정이 갈릴 수 있다.",
                        detail);
            } else {
                log.info("[CLOCK-02] {}", detail);
            }
            return clockResult("CLOCK-02", detail, violated);

        } catch (Exception e) {
            log.warn("[CLOCK-02] Redis 에 닿지 못했다 — {}", e.getMessage());
            return clockResult("CLOCK-02", "Redis 연결 실패 (N/A)", false);
        }
    }

    /** rule_name 이 VARCHAR(100) 이라 잘라 넣는다. 행 단위 위반이 아니므로 샘플은 비운다. */
    private RuleResult clockResult(String code, String detail, boolean violated) {
        String name = code.startsWith("CLOCK-02") ? "Redis·DB 시계 — " : "앱·DB 시계 — ";
        name = name + detail;
        if (name.length() > 100) {
            name = name.substring(0, 100);
        }
        return new RuleResult(code, name, violated ? 1L : 0L, 0, null, 0, List.of());
    }
}
