package com.uply.coupon.operation.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.uply.coupon.operation.reconciliation.domain.KafkaSettlement;
import com.uply.coupon.operation.reconciliation.domain.ReconciliationStatus;
import com.uply.coupon.operation.verification.domain.RuleStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class RedisStockReconcileRunnerTest {

    private static final String STOCK_QUERY =
            "SELECT stock_id, remaining_stock FROM campaign_stocks ORDER BY stock_id";

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private ObjectProvider<KafkaSettlementChecker> kafkaSettlementCheckerProvider;
    @Mock private KafkaSettlementChecker kafkaSettlementChecker;

    private RedisStockReconcileRunner runner;

    @BeforeEach
    void setUp() {
        runner =
                new RedisStockReconcileRunner(
                        jdbcTemplate,
                        redisTemplate,
                        kafkaSettlementCheckerProvider,
                        new SimpleMeterRegistry(),
                        "LUA_SCRIPT",
                        "sync-db");
    }

    @Test
    void returnsPassedWhenRedisAndDbStocksMatch() throws Exception {
        givenDatabaseStocks();
        given(valueOperations.multiGet(List.of("stock:101", "stock:102")))
                .willReturn(List.of("10", "5"));

        var result = runner.run();

        assertThat(result.status()).isEqualTo(ReconciliationStatus.PASSED);
        assertThat(result.result().violationCount()).isZero();
        assertThat(result.result().checkedRows()).isEqualTo(2L);
    }

    @Test
    void recordsDifferentAndInvalidRedisValuesAsMismatches() throws Exception {
        givenDatabaseStocks();
        given(valueOperations.multiGet(List.of("stock:101", "stock:102")))
                .willReturn(List.of("9", "INVALID"));

        var result = runner.run();

        assertThat(result.status()).isEqualTo(ReconciliationStatus.MISMATCH);
        assertThat(result.result().violationCount()).isEqualTo(2);
        assertThat(result.result().samples())
                .extracting(sample -> sample.detail())
                .containsExactly(
                        "redis=9, db=10, diff=-1", "redis=INVALID(INVALID), db=5, diff=N/A");
    }

    @Test
    void recordsMissingRedisKeyAsMismatch() throws Exception {
        givenDatabaseStocks();
        given(valueOperations.multiGet(List.of("stock:101", "stock:102")))
                .willReturn(java.util.Arrays.asList(null, "5"));

        var result = runner.run();

        assertThat(result.status()).isEqualTo(ReconciliationStatus.MISMATCH);
        assertThat(result.result().samples().get(0).detail())
                .isEqualTo("redis=MISSING, db=10, diff=N/A");
    }

    @Test
    void returnsPassedWithoutCallingRedisWhenThereAreNoDbStocks() {
        given(jdbcTemplate.queryForObject(eq("SELECT NOW(3)"), eq(Timestamp.class)))
                .willReturn(Timestamp.valueOf(LocalDateTime.of(2026, 8, 20, 12, 0)));
        given(jdbcTemplate.query(eq(STOCK_QUERY), any(RowMapper.class))).willReturn(List.of());

        var result = runner.run();

        assertThat(result.status()).isEqualTo(ReconciliationStatus.PASSED);
        assertThat(result.result().checkedRows()).isZero();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void doesNotReadRedisForNoLockOrPessimisticLockStrategies() {
        givenSnapshotTime();
        RedisStockReconcileRunner noLockRunner =
                new RedisStockReconcileRunner(
                        jdbcTemplate,
                        redisTemplate,
                        kafkaSettlementCheckerProvider,
                        new SimpleMeterRegistry(),
                        "NO_LOCK",
                        "sync-db");

        var result = noLockRunner.run();

        assertThat(result.status()).isEqualTo(ReconciliationStatus.NOT_APPLICABLE);
        assertThat(result.result().status()).isEqualTo(RuleStatus.NOT_APPLICABLE);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void skipsReconciliationWhenRedisCannotBeRead() throws Exception {
        givenDatabaseStocks();
        given(valueOperations.multiGet(List.of("stock:101", "stock:102")))
                .willThrow(new IllegalStateException("Redis unavailable"));

        var result = runner.run();

        assertThat(result.status()).isEqualTo(ReconciliationStatus.SKIPPED_NOT_SETTLED);
        assertThat(result.result().status()).isEqualTo(RuleStatus.SKIPPED);
        assertThat(result.result().code()).isEqualTo("REC-01");
        assertThat(result.detail()).contains("Redis 재고 조회 실패");
    }

    @Test
    void skipsV3ReconciliationUntilKafkaLagAndDltAreBothZero() {
        givenSnapshotTime();
        given(kafkaSettlementCheckerProvider.getIfAvailable(any(Supplier.class)))
                .willReturn(kafkaSettlementChecker);
        given(kafkaSettlementChecker.check()).willReturn(new KafkaSettlement(1L, 0L));

        RedisStockReconcileRunner kafkaRunner =
                new RedisStockReconcileRunner(
                        jdbcTemplate,
                        redisTemplate,
                        kafkaSettlementCheckerProvider,
                        new SimpleMeterRegistry(),
                        "LUA_SCRIPT",
                        "kafka");

        var result = kafkaRunner.run();

        assertThat(result.status()).isEqualTo(ReconciliationStatus.SKIPPED_NOT_SETTLED);
        assertThat(result.result().status()).isEqualTo(RuleStatus.SKIPPED);
        assertThat(result.detail()).contains("kafkaLag=1");
    }

    private void givenSnapshotTime() {
        given(jdbcTemplate.queryForObject(eq("SELECT NOW(3)"), eq(Timestamp.class)))
                .willReturn(Timestamp.valueOf(LocalDateTime.of(2026, 8, 20, 12, 0)));
    }

    private void givenDatabaseStocks() throws Exception {
        givenSnapshotTime();
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        givenStocks(101L, 10, 102L, 5);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void givenStocks(long firstId, int firstRemaining, long secondId, int secondRemaining)
            throws Exception {
        ResultSet first = resultSet(firstId, firstRemaining);
        ResultSet second = resultSet(secondId, secondRemaining);
        given(jdbcTemplate.query(eq(STOCK_QUERY), any(RowMapper.class)))
                .willAnswer(
                        invocation -> {
                            RowMapper mapper = invocation.getArgument(1);
                            return List.of(mapper.mapRow(first, 0), mapper.mapRow(second, 1));
                        });
    }

    private ResultSet resultSet(long stockId, int remainingStock) throws Exception {
        ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
        given(resultSet.getLong("stock_id")).willReturn(stockId);
        given(resultSet.getInt("remaining_stock")).willReturn(remainingStock);
        return resultSet;
    }
}
