package com.uply.coupon.operation.verification.batch;

import com.uply.coupon.operation.verification.domain.*;
import com.uply.coupon.operation.verification.rule.InvariantRule;
import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
    /**
     * Redis-DB 시계 허용 오차.
     *
     * <p>INV-04(이력 순서) · INV-06(시각 순서) · INV-11(캠페인 기간)의 경계가 밀리초라 1.0초는 너무 느슨했다. 같은 호스트의 컨테이너면 실제
     * drift 는 거의 0 이다.
     *
     * <p>측정값은 순수 시계 차이가 아니다. Redis TIME 과 NOW(3) 를 순차로 읽으므로 왕복 2회가 섞인다. 허용치는 그 잡음보다 크게 잡는다.
     */
    @Value("${coupon.verification.redis-clock-tolerance-sec:0.1}")
    private double redisClockToleranceSec;

    /** 설정 경로가 틀려도 @Value 기본값으로 조용히 돌아간다. 실제 적용값을 기동 시 남긴다. */
    @PostConstruct
    void logConfig() {
        log.info("[검증 설정] redis-clock-tolerance-sec={}", redisClockToleranceSec);
    }

    private static final int SAMPLE_LIMIT = 1000;
    private static final double CLOCK_SKEW_TOLERANCE_SEC = 5.0;
    private final ObjectProvider<RedisConnectionFactory> redisConnectionFactory;

    private final JdbcTemplate jdbcTemplate;
    private final List<InvariantRule> rules;

    /** 모든 규칙을 하나의 일관 스냅샷 위에서 실행한다. */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ,
            readOnly = true)
    public VerificationRun runAll(String runId, RoundVersion round) {

        // read view 는 '첫 consistent read' 에 열린다. SELECT NOW() 는 InnoDB 테이블을
        // 읽지 않아 뷰를 고정하지 못한다. 그래서 작은 테이블을 한 번 읽어 시점을 못박는다.
        // 이 줄을 지우면 규칙별로 다른 시점을 보게 된다.
        jdbcTemplate.queryForList("SELECT 1 FROM campaign_stocks LIMIT 1");

        LocalDateTime snapshotAt =
                jdbcTemplate.queryForObject("SELECT NOW(3)", Timestamp.class).toLocalDateTime();

        List<RuleResult> results = new ArrayList<>();
        results.add(checkClock());
        results.add(checkRedisClock(round != null && round.usesRedisClock()));

        List<InvariantRule> ordered =
                rules.stream().sorted(Comparator.comparing(InvariantRule::code)).toList();

        for (InvariantRule rule : ordered) {
            results.add(evaluate(rule));
        }

        return new VerificationRun(runId, round, snapshotAt, results);
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
     * <p>Redis 경로 회차(V3)는 coupons.issued_at 과 coupon_history.event_at 을 Redis 시계로 기록한다. Redis-DB
     * drift 가 곧 INV-04 · INV-06 · INV-11 의 오차가 되므로 그 회차에서만 판정 대상으로 올린다.
     *
     * <p>Redis 컨테이너는 V0/V1 회차에도 떠 있다. 연결 가능 여부로 판단하면 Redis 를 쓰지 않는 회차에서 없는 문제를 만든다. 그래서 회차 버전으로
     * 가른다.
     *
     * <p>측정값은 순수 시계 차이가 아니다. Redis TIME 과 NOW(3) 를 순차로 읽으므로 왕복 2회가 섞인다.
     */
    private RuleResult checkRedisClock(boolean applicable) {

        if (!applicable) {
            return clockResult("CLOCK-02", "Redis 경로 회차 아님 (N/A)", false);
        }

        RedisConnectionFactory factory = redisConnectionFactory.getIfAvailable();
        if (factory == null) {
            // Redis 경로 회차라고 선언했는데 Redis 가 없다 = 설정 오류. 통과가 아니라 위반이다.
            return clockResult("CLOCK-02", "Redis 필요한 회차인데 미구성", true);
        }

        try (RedisConnection connection = factory.getConnection()) {
            Long redisMillis = connection.serverCommands().time(TimeUnit.MILLISECONDS);
            double dbEpoch =
                    jdbcTemplate.queryForObject("SELECT UNIX_TIMESTAMP(NOW(3))", Double.class);
            double driftSec = redisMillis / 1000.0 - dbEpoch;

            boolean violated = Math.abs(driftSec) > redisClockToleranceSec;
            String detail =
                    String.format("redis_drift=%.3fs tol=%.3fs", driftSec, redisClockToleranceSec);
            if (violated) {
                log.error("[CLOCK-02] Redis·DB 시계가 {} 어긋났다. 이 회차의 시각 기록을 믿을 수 없다.", detail);
            } else {
                log.info("[CLOCK-02] {}", detail);
            }
            return clockResult("CLOCK-02", detail, violated);

        } catch (Exception e) {
            log.error("[CLOCK-02] Redis 경로 회차인데 Redis 에 닿지 못했다 — {}", e.getMessage());
            return clockResult("CLOCK-02", "Redis 연결 실패", true);
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
