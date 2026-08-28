package com.uply.coupon.operation.verification.batch;

import com.uply.coupon.operation.verification.domain.*;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

@Slf4j
@Configuration
public class VerificationJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final VerificationRunner runner;
    private final VerificationResultWriter writer;
    private final Step stockReconcileStep;

    public VerificationJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            VerificationRunner runner,
            VerificationResultWriter writer,
            @Qualifier("stockReconcileStep") Step stockReconcileStep) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.runner = runner;
        this.writer = writer;
        this.stockReconcileStep = stockReconcileStep;
    }

    @Bean
    public Job verificationRoundJob() {
        return new JobBuilder("verificationRoundJob", jobRepository)
                .start(stockReconcileStep)
                .next(verificationStep())
                .build();
    }

    @Bean
    public Job verificationJob() {
        return new JobBuilder("verificationJob", jobRepository).start(verificationStep()).build();
    }

    @Bean
    public Step verificationStep() {
        return new StepBuilder("verificationStep", jobRepository)
                .tasklet(verificationTasklet(null, null, null, null), transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet verificationTasklet(
            @Value("#{jobParameters['runId']}") String runId,
            @Value("#{jobParameters['failOnViolation']}") String failOnViolation,
            @Value("#{jobParameters['round']}") String roundParam,
            @Value("#{jobParameters['roundSnapshotAt']}") String roundSnapshotAtParam) {

        boolean shouldFail = !"false".equalsIgnoreCase(failOnViolation);

        return (contribution, chunkContext) -> {
            RoundVersion round = RoundVersion.parse(roundParam);

            // 내부 검증은 VerificationRunner가 확보한 실제 DB 스냅샷으로 수행한다.
            VerificationRun run = runner.runAll(runId, round);

            // 회차 검증 Job에서는 REC-01과 동일한 회차 기준 시각을 리포트에 기록한다.
            // 독립 verificationJob 실행은 기존처럼 실제 검증 시각을 기록한다.
            LocalDateTime reportSnapshotAt =
                    roundSnapshotAtParam == null
                            ? run.snapshotAt()
                            : LocalDateTime.parse(roundSnapshotAtParam);

            writer.write(
                    new VerificationRun(run.runId(), run.round(), reportSnapshotAt, run.results()));

            if (!run.clockValid()) {
                throw new IllegalStateException(
                        "시계 정합성 위반으로 이 회차의 검증 결과를 신뢰할 수 없다. round=" + round);
            }

            contribution
                    .getStepExecution()
                    .getExecutionContext()
                    .putString("verdict", run.invariantsPassed() ? "PASS" : "FAIL");

            if (run.invariantsPassed()) {
                log.info("검증 통과 — runId={}, snapshotAt={}", run.runId(), reportSnapshotAt);
                return RepeatStatus.FINISHED;
            }

            String failed =
                    run.failedInvariants().stream()
                            .map(r -> r.code() + "=" + r.violationCount())
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("");

            if (!shouldFail) {
                log.warn(
                        "정합성 위반 {}건의 규칙 — {} (failOnViolation=false 라 기록만 한다)",
                        run.failedInvariants().size(),
                        failed);
                return RepeatStatus.FINISHED;
            }

            throw new IllegalStateException("정합성 위반 발견 — " + failed);
        };
    }
}
