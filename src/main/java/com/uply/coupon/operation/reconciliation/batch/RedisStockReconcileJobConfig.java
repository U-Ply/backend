package com.uply.coupon.operation.reconciliation.batch;

import com.uply.coupon.operation.reconciliation.domain.ReconciliationStatus;
import com.uply.coupon.operation.reconciliation.domain.StockReconcileRun;
import com.uply.coupon.operation.reconciliation.service.RedisStockReconcileRunner;
import com.uply.coupon.operation.verification.batch.VerificationResultWriter;
import com.uply.coupon.operation.verification.domain.VerificationRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** REC-01 전용 Job. Redis 연결 실패는 이 Job만 실패시키며 INV 검증에는 영향을 주지 않는다. */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisStockReconcileJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final RedisStockReconcileRunner runner;
    private final VerificationResultWriter resultWriter;

    @Bean
    public Job stockReconcileJob() {
        return new JobBuilder("stockReconcileJob", jobRepository)
                .start(stockReconcileStep())
                .build();
    }

    @Bean
    public Step stockReconcileStep() {
        return new StepBuilder("stockReconcileStep", jobRepository)
                .tasklet(stockReconcileTasklet(null, null), transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet stockReconcileTasklet(
            @Value("#{jobParameters['runId']}") String runId,
            @Value("#{jobParameters['failOnViolation']}") String failOnViolation) {
        boolean shouldFail = !"false".equalsIgnoreCase(failOnViolation);

        return (StepContribution contribution, ChunkContext chunkContext) -> {
            StockReconcileRun run = runner.run();
            if (run.status() == ReconciliationStatus.NOT_APPLICABLE
                    || run.status() == ReconciliationStatus.SKIPPED_NOT_SETTLED) {
                contribution.setExitStatus(new ExitStatus(run.status().name(), run.detail()));
                log.info("REC-01 {} — {}", run.status(), run.detail());
                return RepeatStatus.FINISHED;
            }

            resultWriter.write(
                    new VerificationRun(runId, run.snapshotAt(), java.util.List.of(run.result())));

            if (run.status() == ReconciliationStatus.PASSED) {
                log.info("REC-01 통과 — {}", run.detail());
                return RepeatStatus.FINISHED;
            }

            contribution.setExitStatus(new ExitStatus("MISMATCH", run.detail()));
            if (!shouldFail) {
                log.warn("REC-01 불일치 탐지 — {} (failOnViolation=false)", run.detail());
                return RepeatStatus.FINISHED;
            }

            throw new IllegalStateException("REC-01 Redis-DB 재고 불일치 — " + run.detail());
        };
    }
}
