package com.uply.coupon.operation.verification.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.uply.coupon.operation.verification.InvariantFixture;
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
        var b = new JobParametersBuilder().addString("runId", runId);
        if (failOnViolation != null) b.addString("failOnViolation", failOnViolation, false);
        return b.toJobParameters();
    }

    @Test
    @DisplayName("위반이 없으면 COMPLETED 이고 리포트가 규칙 수만큼 쌓인다")
    void 정상_회차() throws Exception {
        var exec = utils.launchJob(params("job-pass", null));

        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM verification_report WHERE run_id='job-pass'",
                                Integer.class))
                .isGreaterThanOrEqualTo(13); // INV 12 + CLOCK-01 (+ CLOCK-02)
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
}
