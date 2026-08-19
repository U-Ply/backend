package com.uply.coupon.operation.verification.batch;

import com.uply.coupon.operation.verification.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class VerificationJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final VerificationRunner runner;
    private final VerificationResultWriter writer;

    @Bean
    public Job verificationJob() {
        return new JobBuilder("verificationJob", jobRepository).start(verificationStep()).build();
    }

    @Bean
    public Step verificationStep() {
        return new StepBuilder("verificationStep", jobRepository)
                .tasklet(verificationTasklet(null), transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet verificationTasklet(@Value("#{jobParameters['runId']}") String runId) {
        return (contribution, chunkContext) -> {
            VerificationRun run = runner.runAll(runId);
            writer.write(run);

            // ExecutionContext 에는 작은 값만 넣는다. 위반 목록 같은 큰 데이터를 넣으면
            // BATCH_STEP_EXECUTION_CONTEXT 직렬화 크기 제한에 걸린다.
            contribution
                    .getStepExecution()
                    .getExecutionContext()
                    .putString("verdict", run.invariantsPassed() ? "PASS" : "FAIL");

            if (!run.invariantsPassed()) {
                String failed =
                        run.failedInvariants().stream()
                                .map(r -> r.code() + "=" + r.violationCount())
                                .reduce((a, b) -> a + ", " + b)
                                .orElse("");
                throw new IllegalStateException("정합성 위반 발견 — " + failed);
            }

            log.info("검증 통과 — runId={}, snapshotAt={}", run.runId(), run.snapshotAt());
            return RepeatStatus.FINISHED;
        };
    }
}
