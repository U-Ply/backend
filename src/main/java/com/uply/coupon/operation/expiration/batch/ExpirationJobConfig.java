package com.uply.coupon.operation.expiration.batch;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 유효기간이 지났는데 아직 ISSUED 인 쿠폰을 EXPIRED 로 바꾼다.
 *
 * <p>검증 배치는 집합 연산이라 Tasklet 이었지만, 만료는 행 하나씩 처리하는 작업이라 Chunk
 *
 * <p>재고는 복구하지 않는다(발급 시점에 영구 소진)
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ExpirationJobConfig {

    private static final int CHUNK = 1000;

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Bean
    public Job expirationJob() {
        return new JobBuilder("expirationJob", jobRepository)
                .start(cutoffStep())
                .next(expirationStep())
                .build();
    }

    // ─────────────────────── 1. 기준 시각 고정 ───────────────────────

    /** 회차 기준 시각을 한 번만 정해 JobExecutionContext 에 넣는다. */
    @Bean
    public Step cutoffStep() {
        return new StepBuilder("cutoffStep", jobRepository)
                .tasklet(cutoffTasklet(), transactionManager)
                .build();
    }

    @Bean
    public Tasklet cutoffTasklet() {
        return (contribution, chunkContext) -> {
            Timestamp cutoff = jdbcTemplate.queryForObject("SELECT NOW(3)", Timestamp.class);

            String value = cutoff.toString();

            chunkContext
                    .getStepContext()
                    .getStepExecution()
                    .getJobExecution()
                    .getExecutionContext()
                    .putString("cutoff", value);

            long target =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM coupons WHERE status = 'ISSUED' AND expire_at <= ?",
                            Long.class,
                            value);

            log.info("만료 기준 시각 = {}, 대상 {}건", value, target);
            return RepeatStatus.FINISHED;
        };
    }

    // ─────────────────────── 2. 만료 처리 ───────────────────────

    @Bean
    public Step expirationStep() {
        return new StepBuilder("expirationStep", jobRepository)
                .<Long, Long>chunk(CHUNK, transactionManager)
                .reader(expirationReader(null))
                .writer(expirationWriter(null))
                .build();
    }

    /**
     * 만료 대상을 읽는다.
     *
     * <p>정렬 키를 coupon_id 처리한 쿠폰은 status 가 바뀌면서 결과 집합에서 빠져나간다.
     *
     * <p>saveState(false): 재시작 시 저장된 위치부터 이어가면, 실패한 청크에 들어 있던(그래서 롤백되어 여전히 ISSUED 인) 쿠폰들이 그 위치보다 앞에
     * 있어 스킵해서
     */
    @Bean
    @StepScope
    public JdbcPagingItemReader<Long> expirationReader(
            @Value("#{jobExecutionContext['cutoff']}") String cutoff) {

        MySqlPagingQueryProvider queryProvider = new MySqlPagingQueryProvider();
        queryProvider.setSelectClause("coupon_id");
        queryProvider.setFromClause("FROM coupons");
        queryProvider.setWhereClause("WHERE status = 'ISSUED' AND expire_at <= :cutoff");

        Map<String, Order> sortKeys = new LinkedHashMap<>();
        sortKeys.put("coupon_id", Order.ASCENDING);
        queryProvider.setSortKeys(sortKeys);

        return new JdbcPagingItemReaderBuilder<Long>()
                .name("expirationReader")
                .dataSource(dataSource)
                .queryProvider(queryProvider)
                .parameterValues(Map.of("cutoff", cutoff))
                .pageSize(CHUNK)
                .rowMapper((rs, i) -> rs.getLong("coupon_id"))
                .saveState(false)
                .build();
    }

    /**
     * 상태 변경과 이력 기록을 한 트랜잭션(청크) 안에서 처리한다. UPDATE 에 status='ISSUED' 조건을 걸어 두고, 이력은 "실제로 이번 회차가 바꾼
     * 행"(expired_at = cutoff)만 골라 넣는다
     */
    @Bean
    @StepScope
    public ItemWriter<Long> expirationWriter(
            @Value("#{jobExecutionContext['cutoff']}") String cutoff) {

        return chunk -> {
            List<? extends Long> ids = chunk.getItems();
            if (ids.isEmpty()) {
                return;
            }

            jdbcTemplate.batchUpdate(
                    """
	                    UPDATE coupons
	                       SET status = 'EXPIRED', expired_at = ?
	                     WHERE coupon_id = ? AND status = 'ISSUED'
	                    """,
                    ids,
                    ids.size(),
                    (ps, id) -> {
                        ps.setString(1, cutoff);
                        ps.setLong(2, id);
                    });

            jdbcTemplate.batchUpdate(
                    """
	                    INSERT INTO coupon_history
	                        (coupon_id, from_status, to_status, idempotency_key, event_at)
	                    SELECT c.coupon_id, 'ISSUED', 'EXPIRED',
	                           CONCAT('expire-', c.coupon_id), ?
	                      FROM coupons c
	                     WHERE c.coupon_id = ? AND c.status = 'EXPIRED' AND c.expired_at = ?
	                    """,
                    ids,
                    ids.size(),
                    (ps, id) -> {
                        ps.setString(1, cutoff);
                        ps.setLong(2, id);
                        ps.setString(3, cutoff);
                    });

            log.info("만료 처리 {}건 (마지막 coupon_id={})", ids.size(), ids.get(ids.size() - 1));
        };
    }
}
