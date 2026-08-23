package com.uply.coupon.operation.verification.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.uply.coupon.operation.verification.InvariantFixture;
import com.uply.coupon.operation.verification.report.VerificationReportRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@SpringBatchTest
@ActiveProfiles("test")
@Import(InvariantFixture.class)
class VerificationJobIntegrationTest {

    @Autowired JobLauncherTestUtils utils;
    @Autowired JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    @Qualifier("verificationJob")
    Job verificationJob;

    @Autowired JdbcTemplate jdbc;
    @Autowired InvariantFixture fixture;
    @Autowired VerificationReportRenderer renderer;

    @BeforeEach
    void setUp() {
        utils.setJob(verificationJob);
        fixture.truncateAll();
        fixture.build();
    }

    @AfterEach
    void cleanUp() {
        jobRepositoryTestUtils.removeJobExecutions();
        fixture.truncateAll();
    }

    private JobParameters params(String runId, String failOnViolation) {
        return params(runId, failOnViolation, "V1");
    }

    private JobParameters params(String runId, String failOnViolation, String round) {
        var b =
                new JobParametersBuilder()
                        .addString("runId", runId)
                        .addString("round", round, false);
        if (failOnViolation != null) b.addString("failOnViolation", failOnViolation, false);
        return b.toJobParameters();
    }

    @Test
    @DisplayName("위반이 없으면 COMPLETED 이고 리포트가 규칙 수만큼 쌓인다")
    void 정상_회차() throws Exception {
        var exec = utils.launchJob(params("job-pass", null));

        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 실제로 검사한 규칙 수를 못 박는다. 이게 없으면 전부 N/A 인 회차도 통과로 읽힌다.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM verification_report WHERE run_id='job-pass' AND status='CHECKED'",
                                Integer.class))
                .isEqualTo(13); // INV 12 + CLOCK-01. CLOCK-02 는 V1 이라 NOT_APPLICABLE

        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM verification_report WHERE run_id='job-pass' AND passed=0",
                                Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("위반이 있으면 FAILED 지만 리포트는 남는다")
    void 위반_회차_기본() throws Exception {
        jdbc.update(
                "UPDATE campaign_stocks SET remaining_stock = remaining_stock + 1 WHERE stock_id = ?",
                InvariantFixture.STOCK_OPEN);
        var exec = utils.launchJob(params("job-fail", null));

        assertThat(exec.getStatus()).isEqualTo(BatchStatus.FAILED);
        // 실패해도 진단 정보는 남아야 한다. 트랜잭션 경계가 어긋나면 여기서 깨진다.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT violation_count FROM verification_report WHERE run_id='job-fail' AND rule_code='INV-03'",
                                Long.class))
                .isEqualTo(1L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM verification_violation WHERE run_id='job-fail' AND rule_code='INV-03'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("failOnViolation=false 면 위반이 있어도 COMPLETED — V0 회차용")
    void 기록_전용_회차() throws Exception {
        jdbc.update(
                "UPDATE campaign_stocks SET remaining_stock = remaining_stock + 1 WHERE stock_id = 1");

        var exec = utils.launchJob(params("job-record", "false"));

        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT violation_count FROM verification_report WHERE run_id='job-record' AND rule_code='INV-03'",
                                Long.class))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("위반 상세에 원인이 담긴다")
    void 위반_상세() throws Exception {
        jdbc.update(
                "UPDATE campaign_stocks SET remaining_stock = remaining_stock + 1 WHERE stock_id = 1");
        utils.launchJob(params("job-detail", "false"));

        String detail =
                jdbc.queryForObject(
                        "SELECT detail FROM verification_violation WHERE run_id='job-detail' AND rule_code='INV-03'",
                        String.class);
        assertThat(detail).contains("remaining=7").contains("expected=6");
    }

    @Test
    @DisplayName("round 를 빠뜨리면 실패한다 - 조용히 넘어가면 안 된다")
    void round_required() throws Exception {
        var p = new JobParametersBuilder().addString("runId", "job-noround").toJobParameters();
        assertThat(utils.launchJob(p).getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("알 수 없는 round 는 실패한다 - 오타가 N/A 로 새면 안 된다")
    void round_typo_rejected() throws Exception {
        assertThat(utils.launchJob(params("job-typo", null, "V9")).getStatus())
                .isEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("DB 경로 회차에서 CLOCK-02 는 NOT_APPLICABLE 로 기록된다")
    void clock02_na_on_db_round() throws Exception {
        utils.launchJob(params("job-v1", null, "V1"));
        String status =
                jdbc.queryForObject(
                        "SELECT status FROM verification_report WHERE run_id='job-v1' AND rule_code='CLOCK-02'",
                        String.class);
        assertThat(status).isEqualTo("NOT_APPLICABLE");
    }

    /**
     * CLOCK-02 는 Redis 시계로 기록하는 회차에서만 판정한다. 그 외 회차에서 이걸 "통과"로 찍으면 검사하지 않은 것이 검사해서 문제없는 것처럼 읽힌다.
     * 리포트까지 그 구분이 이어지는지 지킨다.
     *
     * <p>다만 지금은 V0~V3 전부 usesRedisClock=false 라, 이 테스트만으로는 "round 를 보고 N/A 로 판단했다"와 "round 가 없어서
     * N/A 가 됐다"를 구분하지 못한다. 어느 회차든 true 로 올라가는 시점에 반대 방향 테스트를 함께 추가한다.
     */
    @Test
    @DisplayName("Redis 시계를 쓰지 않는 회차의 CLOCK-02 는 리포트에 통과가 아니라 N/A 로 찍힌다")
    void clockTwoRendersAsNotApplicable() throws Exception {
        utils.launchJob(params("job-report-v1", null, "V1"));

        String md = renderer.render("job-report-v1");

        assertThat(md).contains("CLOCK-02");
        assertThat(md).containsPattern("`CLOCK-02`[^\\n]*\\| N/A \\|");
        assertThat(md).doesNotContainPattern("`CLOCK-02`[^\\n]*\\| 통과 \\|");
    }

    /**
     * V2·V3 는 Lua 가 반환한 nowMillis 를 issued_at·event_at 에 쓰므로 Redis 시계 회차다. 여기서 N/A 가 나오면
     * RoundVersion 이 false 로 되돌아갔다는 뜻이고, 그러면 검사하지 않은 것을 통과로 세게 된다.
     */
    @Test
    @DisplayName("Redis 시계 회차에서 CLOCK-02 는 실제로 검사된다 - N/A 로 새면 안 된다")
    void clock02_checked_on_redis_round() throws Exception {
        utils.launchJob(params("job-v2", null, "V2"));

        String status =
                jdbc.queryForObject(
                        "SELECT status FROM verification_report WHERE run_id='job-v2' AND rule_code='CLOCK-02'",
                        String.class);
        assertThat(status).isEqualTo("CHECKED");

        // N/A 가 하나도 없어야 한다. V1 회차의 2건과 대비된다.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM verification_report WHERE run_id='job-v2' AND status='NOT_APPLICABLE'",
                                Integer.class))
                .isZero();
    }
}
