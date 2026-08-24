package com.uply.coupon.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.uply.coupon.operation.verification.InvariantFixture;
import com.uply.coupon.operation.verification.report.VerificationReportRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@SpringBatchTest
@Import(InvariantFixture.class)
class VerificationBatchIntegrationTest extends IntegrationTestContainers {

    /**
     * 검증 Job 이 기록하는 규칙 총수. INV-01~12, CLOCK-01, CLOCK-02.
     *
     * <p>REC-01 은 이 Job 이 아니라 대사 배치가 남기므로 여기 포함되지 않는다.
     *
     * <p>규칙을 추가하면 이 상수도 같이 올려야 한다. 그게 목적이다. 총수를 안 세면 규칙이 통째로 빠져도 아무도 모른다.
     */
    private static final int TOTAL_RULES = 14;

    /** DB 회차(V0·V1)에서 실제로 검사되는 규칙 수. CLOCK-02 는 Redis 회차 전용이다. */
    private static final int CHECKED_ON_DB_ROUND = 13;

    @Autowired JobLauncherTestUtils utils;
    @Autowired JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    @Qualifier("verificationJob")
    Job verificationJob;

    @Autowired InvariantFixture fixture;
    @Autowired VerificationReportRenderer renderer;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        utils.setJob(verificationJob);
        fixture.truncateAll();
        fixture.build();
    }

    @AfterEach
    void tearDown() {
        jobRepositoryTestUtils.removeJobExecutions();
        fixture.truncateAll();
    }

    @Test
    void 정상_회차는_COMPLETED이고_모든_규칙이_실행된다() throws Exception {
        String runId = "it-verification-pass";

        var execution = utils.launchJob(params(runId, "V1", null));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 규칙이 통째로 빠지지 않았는가.
        assertThat(countRules(runId)).as("기록된 규칙 총수").isEqualTo(TOTAL_RULES);

        // 검사하지 않은 것을 통과로 세지 않는다.
        // acceptance.sh 9단계의 "미실행 [1-9]" 게이트를 옮겨온 것이다.
        assertThat(countByStatus(runId, "SKIPPED")).as("실행되지 않은 규칙").isZero();

        assertThat(countByStatus(runId, "CHECKED")).as("실제로 검사된 규칙").isEqualTo(CHECKED_ON_DB_ROUND);

        // 위반 판정은 status 로 좁힌 뒤에 본다.
        //
        // verification_report.passed 는 violation_count = 0 으로 계산되는 생성 컬럼이라,
        // 아무것도 검사하지 않은 SKIPPED 규칙도 passed = 1 로 잡힌다.
        // passed 를 게이트로 쓰면 "검사하지 않았으므로 통과" 가 되는데, 그건 이 프로젝트가
        // 리포트 렌더러에서 걷어낸 바로 그 결함이다.
        assertThat(countFailed(runId)).as("검사했는데 위반이 있는 규칙").isZero();
    }

    @Test
    void INV03_위반이면_실패해도_report와_violation이_남는다() throws Exception {
        String runId = "it-verification-fail";

        jdbc.update(
                "UPDATE campaign_stocks SET remaining_stock = remaining_stock + 1 WHERE stock_id = ?",
                InvariantFixture.STOCK_OPEN);

        var execution = utils.launchJob(params(runId, "V1", null));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);

        // 위반이 CHECKED 상태로 기록됐는지까지 본다.
        // violation_count 만 보면 SKIPPED 인데 값이 실린 모순 상태를 놓친다.
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT status
                                FROM verification_report
                                WHERE run_id = ? AND rule_code = 'INV-03'
                                """,
                                String.class,
                                runId))
                .isEqualTo("CHECKED");

        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT violation_count
                                FROM verification_report
                                WHERE run_id = ? AND rule_code = 'INV-03'
                                """,
                                Long.class,
                                runId))
                .isEqualTo(1L);

        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT COUNT(*)
                                FROM verification_violation
                                WHERE run_id = ? AND rule_code = 'INV-03'
                                """,
                                Integer.class,
                                runId))
                .isEqualTo(1);

        // 한 규칙이 실패해도 나머지 규칙 기록은 남아야 한다.
        // 여기서 규칙이 줄어들면 리포트가 부분만 담고 있다는 뜻이다.
        assertThat(countRules(runId)).as("실패 회차에도 규칙 기록은 온전해야 한다").isEqualTo(TOTAL_RULES);
    }

    @Test
    void CLOCK02는_DB_회차에서_NOT_APPLICABLE이다() throws Exception {
        String runId = "it-verification-clock";

        utils.launchJob(params(runId, "V1", null));

        String status =
                jdbc.queryForObject(
                        """
                        SELECT status
                        FROM verification_report
                        WHERE run_id = ? AND rule_code = 'CLOCK-02'
                        """,
                        String.class,
                        runId);

        assertThat(status).isEqualTo("NOT_APPLICABLE");

        String rendered = renderer.render(runId);
        assertThat(rendered).contains("CLOCK-02");
        assertThat(rendered).containsPattern("`CLOCK-02`[^\\n]*\\| N/A \\|");
        assertThat(rendered).doesNotContainPattern("`CLOCK-02`[^\\n]*\\| 통과 \\|");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private int countRules(String runId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM verification_report WHERE run_id = ?", Integer.class, runId);
    }

    private int countByStatus(String runId, String status) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM verification_report WHERE run_id = ? AND status = ?",
                Integer.class,
                runId,
                status);
    }

    private int countFailed(String runId) {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM verification_report
                WHERE run_id = ?
                  AND status = 'CHECKED'
                  AND violation_count > 0
                """,
                Integer.class,
                runId);
    }

    private JobParameters params(String runId, String round, String failOnViolation) {
        var builder =
                new JobParametersBuilder()
                        .addString("runId", runId)
                        .addString("round", round, false);

        if (failOnViolation != null) {
            builder.addString("failOnViolation", failOnViolation, false);
        }

        return builder.toJobParameters();
    }
}
