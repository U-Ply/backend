package com.uply.coupon.it;

import com.uply.coupon.operation.verification.report.VerificationReportRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 회차 데이터 위에서 검증·대사 배치를 돌리고 마크다운 리포트를 파일로 남긴다.
 *
 * <p>acceptance.sh 7~9 단계를 옮겨온 것이다. 셸을 버리면서 같이 사라졌던 부분인데, 회차 리포트는 이 역할의 실제 산출물이라 없으면 안 된다.
 *
 * <p><b>왜 두 Job 을 같은 runId 로 돌리는가</b>
 *
 * <p>불변식(INV-*)과 시계(CLOCK-*)는 검증 Job 이, Redis·DB 재고 대사(REC-01)는 대사 Job 이 남긴다. 리포트는 run_id 로 행을
 * 모으므로, 두 Job 에 같은 runId 를 주어야 한 장에 합쳐진다. 검증 Job 만 돌리면 REC-01 이 리포트에 아예 나타나지 않는데, 그러면 "규칙 14개 전부
 * 통과" 가 재고 대사를 **한 번도 보지 않은** 상태를 뜻하게 된다.
 *
 * <p>실제로 이 프로젝트에서 회차 중 잡힌 결함 하나가 REC-01 이 잡은 재고 누수였다(redis=0, db=1, diff=-1). 그 규칙이 리포트에서 빠지면 같은
 * 결함이 다시 조용히 지나간다.
 *
 * <p><b>왜 failOnViolation=false 인가</b>
 *
 * <p>Job 을 FAILED 로 끝내면 리포트를 렌더링하기 전에 회차가 죽는다. 그러면 무엇이 왜 깨졌는지 남지 않는다. 위반 여부는 Job 의 종료 상태가 아니라 리포트의
 * 판정 줄로 읽고, 그 판정은 호출한 테스트가 단언한다. V0 는 애초에 위반이 예상된 산출물이므로 이 방식이어야만 한다 (test-plan 5.4).
 *
 * <p><b>왜 build/ 에 쓰는가</b>
 *
 * <p>테스트가 저장소 파일을 직접 고치면 실행할 때마다 작업 트리가 더러워지고, 실패한 회차의 리포트가 성공한 회차의 것을 덮어쓴다. build/ 에 떨어뜨린 뒤 내용을
 * 확인하고 docs/round-results/ 로 옮긴다.
 */
@Component
public class RoundReportWriter {

    private static final Path OUT_DIR = Path.of("build", "round-results");

    private final JobLauncher jobLauncher;
    private final Job verificationJob;
    private final Job stockReconcileJob;
    private final VerificationReportRenderer renderer;

    public RoundReportWriter(
            JobLauncher jobLauncher,
            @Qualifier("verificationJob") Job verificationJob,
            @Qualifier("stockReconcileJob") Job stockReconcileJob,
            VerificationReportRenderer renderer) {
        this.jobLauncher = jobLauncher;
        this.verificationJob = verificationJob;
        this.stockReconcileJob = stockReconcileJob;
        this.renderer = renderer;
    }

    /**
     * 검증·대사 배치를 돌리고 리포트를 build/round-results/{round}.md 로 쓴 뒤, 마크다운 전문을 돌려준다.
     *
     * @param round V0 | V1 | V2 | V3
     */
    public String writeReport(String round) throws Exception {
        String runId = "IT-" + round + "-" + System.nanoTime();

        run(
                verificationJob,
                "verification",
                round,
                new JobParametersBuilder()
                        .addString("runId", runId)
                        .addString("round", round, false)
                        .addString("failOnViolation", "false", false)
                        .toJobParameters());

        run(
                stockReconcileJob,
                "reconciliation",
                round,
                new JobParametersBuilder()
                        .addString("runId", runId)
                        .addString("round", round, false)
                        .addString("failOnViolation", "false", false)
                        .toJobParameters());

        String markdown = renderer.render(runId);

        Files.createDirectories(OUT_DIR);
        Files.writeString(OUT_DIR.resolve(round + ".md"), markdown);

        return markdown;
    }

    /** COMPLETED 가 아니면 규칙 판정이 아니라 배치 자체가 죽은 것이다. 리포트를 읽을 수 없다. */
    private void run(Job job, String label, String round, JobParameters parameters)
            throws Exception {
        JobExecution execution = jobLauncher.run(job, parameters);

        if (execution.getStatus() != BatchStatus.COMPLETED) {
            throw new IllegalStateException(
                    label
                            + " job for round "
                            + round
                            + " ended as "
                            + execution.getStatus()
                            + " ("
                            + execution.getAllFailureExceptions()
                            + ")");
        }
    }
}
