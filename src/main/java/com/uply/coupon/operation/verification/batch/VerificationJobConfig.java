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
    private final Step stockReconcileStep; // RedisStockReconcileJobConfig 의 빈을 주입받는다

    /**
     * 회차 검증 = REC-01 + INV 12 + CLOCK 2. 한 runId 로 15개 규칙을 한 리포트에 남긴다.
     *
     * <p><b>왜 별도 Job 인가.</b> {@code verificationJob} 과 {@code stockReconcileJob} 을 따로 두면 호출자가 하나만
     * 돌릴 수 있고, 그러면 리포트에 REC-01 줄이 통째로 빠진다. 그 상태의 "규칙 14개 전부 통과" 는 재고 대사를 <b>한 번도 보지 않았다</b>는 뜻인데,
     * 판정은 그걸 PASSED 로 읽는다. 판정 사슬의 MISMATCH 분기도 REC-01 이 같은 run_id 에 있어야만 도달한다. 실제로 관리자 화면에서 "데이터
     * 검증" 만 눌러 14개짜리 리포트가 나온 적이 있다.
     *
     * <p><b>왜 대사가 먼저인가.</b> 1초면 끝나므로 여기서 막히면 76초짜리 검증을 시작하기 전에 안다. 그리고 REC-01 은 Redis·DB 를 교차 비교하므로
     * "트래픽이 멈춘 직후" 에 가까울수록 정확하다. 검증이 먼저면 300만 건 기준 76초 뒤의 상태를 재게 되는데, 리포트의 snapshot_at 은 하나라서 그 간격이
     * 화면에 드러나지 않는다.
     *
     * <p><b>failOnViolation 은 false 로 넘긴다.</b> Job 을 FAILED 로 끝내면 리포트를 렌더링하기 전에 회차가 죽어 무엇이 왜 깨졌는지
     * 남지 않는다. 위반 여부는 Job 종료 상태가 아니라 리포트의 판정 줄로 읽는다 — RoundReportWriter 가 같은 이유로 같은 선택을 했다.
     */
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
                .tasklet(verificationTasklet(null, null, null), transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet verificationTasklet(
            @Value("#{jobParameters['runId']}") String runId,
            @Value("#{jobParameters['failOnViolation']}") String failOnViolation,
            @Value("#{jobParameters['round']}") String roundParam) {

        // 기본은 실패시킨다. 명시적으로 false 를 넘긴 회차만 기록 전용이 된다.
        boolean shouldFail = !"false".equalsIgnoreCase(failOnViolation);

        return (contribution, chunkContext) -> {
            // 진입 경로가 API/커맨드라인 둘이라 여기서도 막는다.
            // 빠뜨리면 CLOCK-02 가 N/A 로 조용히 넘어가 검사가 안 된 채 통과한다.
            RoundVersion round = RoundVersion.parse(roundParam);

            VerificationRun run = runner.runAll(runId, round);
            writer.write(run);

            if (!run.clockValid()) {
                throw new IllegalStateException(
                        "시계 정합성 위반으로 이 회차의 검증 결과를 신뢰할 수 없다. round=" + round);
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
