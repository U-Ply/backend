package com.uply.coupon.operation.reconciliation.batch;

import com.uply.coupon.operation.reconciliation.domain.ReconciliationStatus;
import com.uply.coupon.operation.reconciliation.domain.StockReconcileRun;
import com.uply.coupon.operation.reconciliation.service.RedisStockReconcileRunner;
import com.uply.coupon.operation.verification.batch.VerificationResultWriter;
import com.uply.coupon.operation.verification.domain.RoundVersion;
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

/** REC-01 전용 Job. Redis 연결 실패는 REC-01만 SKIPPED로 남기고 다음 검증 Step이 계속될 수 있게 한다. */
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
                .tasklet(stockReconcileTasklet(null, null, null), transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet stockReconcileTasklet(
            @Value("#{jobParameters['runId']}") String runId,
            @Value("#{jobParameters['round']}") String round,
            @Value("#{jobParameters['failOnViolation']}") String failOnViolation) {
        boolean shouldFail = !"false".equalsIgnoreCase(failOnViolation);

        return (StepContribution contribution, ChunkContext chunkContext) -> {
            StockReconcileRun run = runner.run();

            // N/A · SKIPPED 도 기록한다. 검사하지 않았다는 사실 자체가 회차의 정보다.
            // 여기서 빠져나가면 리포트에서 REC-01 줄이 통째로 사라지고,
            // "해당 없음"과 "아예 안 돌림"을 구분할 수 없게 된다.
            resultWriter.write(
                    new VerificationRun(
                            runId,
                            RoundVersion.parse(round),
                            run.snapshotAt(),
                            java.util.List.of(run.result())));

            if (run.status() == ReconciliationStatus.NOT_APPLICABLE
                    || run.status() == ReconciliationStatus.SKIPPED_NOT_SETTLED) {
                contribution.setExitStatus(new ExitStatus(run.status().name(), run.detail()));
                log.info("REC-01 {} — {}", run.status(), run.detail());
                return RepeatStatus.FINISHED;
            }

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
