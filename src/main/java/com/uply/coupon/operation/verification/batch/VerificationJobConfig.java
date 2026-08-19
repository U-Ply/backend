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
                .tasklet(verificationTasklet(null, null), transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet verificationTasklet(
            @Value("#{jobParameters['runId']}") String runId,
            @Value("#{jobParameters['failOnViolation']}") String failOnViolation) {

        // 기본은 실패시킨다. 명시적으로 false 를 넘긴 회차만 기록 전용이 된다.
        boolean shouldFail = !"false".equalsIgnoreCase(failOnViolation);

        return (contribution, chunkContext) -> {
            VerificationRun run = runner.runAll(runId);
            writer.write(run);
            // 리포트는 위에서 이미 커밋됐으므로 진단 정보는 남는다.
            if (!run.clockValid()) {
                throw new IllegalStateException("앱·DB 시계 이상으로 이 회차의 검증 결과를 신뢰할 수 없다.");
            }

            contribution
                    .getStepExecution()
                    .getExecutionContext()
                    .putString("verdict", run.invariantsPassed() ? "PASS" : "FAIL");

            if (run.invariantsPassed()) {
                log.info("검증 통과 — runId={}, snapshotAt={}", run.runId(), run.snapshotAt());
                return RepeatStatus.FINISHED;
            }

            String failed =
                    run.failedInvariants().stream()
                            .map(r -> r.code() + "=" + r.violationCount())
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("");

            if (!shouldFail) {
                // NoLock(V0) 회차처럼 정합성 위반이 '의도된 결과' 인 경우.
                // 테스트 계획 5.4 의 NoLock 예외 규정 — 실패로 처리하지 않고 수치로 기록한다.
                // 위반 내용은 verification_report / verification_violation 에 그대로 남는다.
                log.warn(
                        "정합성 위반 {}건의 규칙 — {} (failOnViolation=false 라 기록만 한다)",
                        run.failedInvariants().size(),
                        failed);
                return RepeatStatus.FINISHED;
            }

            // 리포트에만 남기면 스케줄러나 CI 에서 초록불이 뜬다. 알림이 울려야 한다.
            // 결과는 위에서 이미 커밋됐으므로 리포트는 남는다.
            throw new IllegalStateException("정합성 위반 발견 — " + failed);
        };
    }
}
