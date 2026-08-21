package com.uply.coupon.operation.reconciliation.service;

import com.uply.coupon.operation.reconciliation.domain.KafkaSettlement;
import com.uply.coupon.operation.reconciliation.domain.ReconciliationStatus;
import com.uply.coupon.operation.reconciliation.domain.StockReconcileRun;
import com.uply.coupon.operation.verification.domain.RuleResult;
import com.uply.coupon.operation.verification.domain.Violation;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** REC-01 전수 대사를 실행한다. 대사 결과는 탐지·기록만 하며 Redis나 MySQL을 수정하지 않는다. */
@Slf4j
@Component
public class RedisStockReconcileRunner {

    private static final String RULE_CODE = "REC-01";
    private static final String RULE_NAME = "Redis-DB 재고 일치";
    private static final int SAMPLE_LIMIT = 1_000;

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectProvider<KafkaSettlementChecker> kafkaSettlementCheckerProvider;
    private final MeterRegistry meterRegistry;
    private final String issueStrategy;
    private final String saveStrategy;
    private final AtomicLong lastMismatchCount = new AtomicLong();

    public RedisStockReconcileRunner(
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate redisTemplate,
            ObjectProvider<KafkaSettlementChecker> kafkaSettlementCheckerProvider,
            MeterRegistry meterRegistry,
            @Value("${coupon.issue.strategy:LUA_SCRIPT}") String issueStrategy,
            @Value("${coupon.save.strategy:sync-db}") String saveStrategy) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.kafkaSettlementCheckerProvider = kafkaSettlementCheckerProvider;
        this.meterRegistry = meterRegistry;
        this.issueStrategy = issueStrategy;
        this.saveStrategy = saveStrategy;

        Gauge.builder("coupon.reconciliation.mismatch.count", lastMismatchCount, AtomicLong::get)
                .description("가장 최근 REC-01 실행에서 탐지한 Redis-DB 재고 불일치 수")
                .register(meterRegistry);
    }

    public StockReconcileRun run() {
        // 조기 반환 경로도 결과 행을 남겨야 하므로 시점을 먼저 확보한다.
        // 한 판단 안에서 시계를 섞지 않기 위해 DB 시계를 쓴다.
        LocalDateTime snapshotAt =
                jdbcTemplate.queryForObject("SELECT NOW(3)", Timestamp.class).toLocalDateTime();

        if (!"LUA_SCRIPT".equalsIgnoreCase(issueStrategy)) {
            return complete(
                    StockReconcileRun.notApplicable(
                            "issueStrategy=" + issueStrategy + " 은 Redis 재고를 사용하지 않습니다.",
                            snapshotAt));
        }

        try {
            if ("kafka".equalsIgnoreCase(saveStrategy)) {
                KafkaSettlementChecker checker =
                        kafkaSettlementCheckerProvider.getIfAvailable(
                                () ->
                                        () -> {
                                            throw new IllegalStateException(
                                                    "Kafka 저장 전략인데 KafkaSettlementChecker가 없습니다.");
                                        });
                KafkaSettlement settlement = checker.check();
                if (!settlement.settled()) {
                    return complete(
                            StockReconcileRun.notSettled(
                                    "kafkaLag="
                                            + settlement.lag()
                                            + ", dltCount="
                                            + settlement.dltCount(),
                                    snapshotAt));
                }
            }

            long started = System.nanoTime();
            List<StockRow> dbStocks =
                    jdbcTemplate.query(
                            "SELECT stock_id, remaining_stock FROM campaign_stocks ORDER BY stock_id",
                            (resultSet, rowNum) ->
                                    new StockRow(
                                            resultSet.getLong("stock_id"),
                                            resultSet.getInt("remaining_stock")));

            List<String> keys = dbStocks.stream().map(row -> "stock:" + row.stockId()).toList();
            List<String> redisValues =
                    keys.isEmpty() ? List.of() : redisTemplate.opsForValue().multiGet(keys);
            if (redisValues == null || redisValues.size() != dbStocks.size()) {
                throw new IllegalStateException("Redis 재고 일괄 조회 결과의 행 수가 DB 재고 행 수와 다릅니다.");
            }

            long mismatchCount = 0;
            List<Violation> samples = new ArrayList<>();
            for (int index = 0; index < dbStocks.size(); index++) {
                StockRow stock = dbStocks.get(index);
                String redisValue = redisValues.get(index);
                String detail = differenceDetail(redisValue, stock.remainingStock());
                if (detail == null) {
                    continue;
                }

                mismatchCount++;
                if (samples.size() < SAMPLE_LIMIT) {
                    samples.add(new Violation("campaign_stocks", stock.stockId(), detail));
                }
            }

            int elapsedMs = (int) ((System.nanoTime() - started) / 1_000_000);
            RuleResult result =
                    RuleResult.checked(
                            RULE_CODE,
                            RULE_NAME,
                            mismatchCount,
                            samples.size(),
                            (long) dbStocks.size(),
                            elapsedMs,
                            samples);

            ReconciliationStatus status =
                    mismatchCount == 0
                            ? ReconciliationStatus.PASSED
                            : ReconciliationStatus.MISMATCH;
            return complete(
                    new StockReconcileRun(
                            status,
                            "checkedStocks=" + dbStocks.size() + ", mismatches=" + mismatchCount,
                            snapshotAt,
                            result));

        } catch (RuntimeException exception) {
            meterRegistry
                    .counter("coupon.reconciliation.run.total", "result", "failed")
                    .increment();
            throw exception;
        }
    }

    private StockReconcileRun complete(StockReconcileRun run) {
        switch (run.status()) {
            case PASSED -> {
                lastMismatchCount.set(0);
                meterRegistry
                        .counter("coupon.reconciliation.run.total", "result", "pass")
                        .increment();
            }
            case MISMATCH -> {
                lastMismatchCount.set(run.result().violationCount());
                meterRegistry
                        .counter("coupon.reconciliation.run.total", "result", "mismatch")
                        .increment();
            }
            case NOT_APPLICABLE, SKIPPED_NOT_SETTLED ->
                    meterRegistry
                            .counter("coupon.reconciliation.run.total", "result", "skipped")
                            .increment();
        }
        return run;
    }

    private String differenceDetail(String redisValue, int dbRemaining) {
        if (redisValue == null) {
            return "redis=MISSING, db=" + dbRemaining + ", diff=N/A";
        }

        try {
            int redisRemaining = Integer.parseInt(redisValue);
            int diff = redisRemaining - dbRemaining;
            return diff == 0
                    ? null
                    : "redis=" + redisRemaining + ", db=" + dbRemaining + ", diff=" + diff;
        } catch (NumberFormatException exception) {
            return "redis=INVALID(" + redisValue + "), db=" + dbRemaining + ", diff=N/A";
        }
    }

    private record StockRow(long stockId, int remainingStock) {}
}
