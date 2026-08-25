package com.uply.coupon.operation.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.uply.coupon.it.IntegrationTestContainers;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Admin verification API의 실제 SQL을 검증하는 통합 테스트.
 *
 * <p>기존 AdminBatchControllerTest와 달리 JdbcTemplate을 mock하지 않는다.
 *
 * <p>실제 Testcontainers MySQL에 verification_report 데이터를 넣고 실제 AdminBatchController -> JdbcTemplate
 * -> MySQL SQL 경로를 검증한다.
 *
 * <p>특히 runs()의 실제 SQL CASE가 DB 데이터에 따라 BASELINE / INCOMPLETE / FAILED / PASSED를 정확하게 판정하는지 검증한다.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
class AdminBatchControllerSqlIntegrationTest extends IntegrationTestContainers {

    private static final LocalDateTime SNAPSHOT_AT = LocalDateTime.of(2026, 8, 24, 12, 0);

    @Autowired MockMvc mockMvc;

    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanupVerificationData();
    }

    @AfterEach
    void tearDown() {
        cleanupVerificationData();
    }

    /**
     * verification_report / verification_violation의 테스트 데이터를 제거한다.
     *
     * <p>runs()는 verification_report를 run_id 기준으로 GROUP BY 하기 때문에 다른 테스트의 verification run이 섞이면 결과가
     * 달라질 수 있다.
     */
    private void cleanupVerificationData() {
        jdbcTemplate.update("DELETE FROM verification_violation");
        jdbcTemplate.update("DELETE FROM verification_report");
    }

    /**
     * verification_report의 실제 스키마를 기준으로 SQL 판정에 필요한 최소 데이터를 삽입한다.
     *
     * <p>passed는 직접 삽입하지 않는다. DB generated column 또는 실제 조회 SQL이 계산하도록 둔다.
     */
    private void insertReport(
            String runId,
            String round,
            String ruleCode,
            String ruleName,
            String status,
            long violationCount) {

        jdbcTemplate.update(
                """
                INSERT INTO verification_report (
                    run_id,
                    rule_code,
                    rule_name,
                    round,
                    status,
                    violation_count,
                    sampled_count,
                    checked_rows,
                    elapsed_ms,
                    snapshot_at,
                    created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                runId,
                ruleCode,
                ruleName,
                round,
                status,
                violationCount,
                violationCount == 0 ? 0 : 1,
                1,
                100,
                Timestamp.valueOf(SNAPSHOT_AT),
                Timestamp.valueOf(SNAPSHOT_AT));
    }

    @Test
    void verificationRuns_V0는_위반이_있어도_BASELINE으로_판정한다() throws Exception {

        insertReport("it-admin-v0", "V0", "REC-01", "V0 baseline rule", "CHECKED", 3);

        insertReport("it-admin-v0", "V0", "REC-02", "V0 baseline rule 2", "CHECKED", 0);

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].run_id").value("it-admin-v0"))
                .andExpect(jsonPath("$[0].round").value("V0"))
                .andExpect(jsonPath("$[0].total_violations").value(3))
                .andExpect(jsonPath("$[0].failed_rules").value(1))
                .andExpect(jsonPath("$[0].checked_rules").value(2))
                .andExpect(jsonPath("$[0].skipped_rules").value(0))
                .andExpect(jsonPath("$[0].rule_count").value(2))
                .andExpect(jsonPath("$[0].verdict").value("BASELINE"));
    }

    @Test
    void verificationRuns_SKIPPED가_있으면_INCOMPLETE으로_판정한다() throws Exception {

        insertReport("it-admin-incomplete", "V1", "REC-01", "Checked rule", "CHECKED", 0);

        insertReport("it-admin-incomplete", "V1", "REC-02", "Skipped rule", "SKIPPED", 0);

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].run_id").value("it-admin-incomplete"))
                .andExpect(jsonPath("$[0].round").value("V1"))
                .andExpect(jsonPath("$[0].total_violations").value(0))
                .andExpect(jsonPath("$[0].failed_rules").value(0))
                .andExpect(jsonPath("$[0].checked_rules").value(1))
                .andExpect(jsonPath("$[0].skipped_rules").value(1))
                .andExpect(jsonPath("$[0].rule_count").value(2))
                .andExpect(jsonPath("$[0].verdict").value("INCOMPLETE"));
    }

    @Test
    void verificationRuns_위반이_있으면_FAILED로_판정한다() throws Exception {

        insertReport("it-admin-failed", "V1", "INV-01", "Failed rule", "CHECKED", 1);

        insertReport("it-admin-failed", "V1", "INV-02", "Passed rule", "CHECKED", 0);

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].run_id").value("it-admin-failed"))
                .andExpect(jsonPath("$[0].round").value("V1"))
                .andExpect(jsonPath("$[0].total_violations").value(1))
                .andExpect(jsonPath("$[0].failed_rules").value(1))
                .andExpect(jsonPath("$[0].checked_rules").value(2))
                .andExpect(jsonPath("$[0].skipped_rules").value(0))
                .andExpect(jsonPath("$[0].rule_count").value(2))
                .andExpect(jsonPath("$[0].verdict").value("FAILED"));
    }

    @Test
    void verificationRuns_위반도_SKIPPED도_없으면_PASSED로_판정한다() throws Exception {

        insertReport("it-admin-passed", "V1", "REC-01", "Passed rule", "CHECKED", 0);

        insertReport("it-admin-passed", "V1", "REC-02", "Passed rule 2", "CHECKED", 0);

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].run_id").value("it-admin-passed"))
                .andExpect(jsonPath("$[0].round").value("V1"))
                .andExpect(jsonPath("$[0].total_violations").value(0))
                .andExpect(jsonPath("$[0].failed_rules").value(0))
                .andExpect(jsonPath("$[0].checked_rules").value(2))
                .andExpect(jsonPath("$[0].skipped_rules").value(0))
                .andExpect(jsonPath("$[0].rule_count").value(2))
                .andExpect(jsonPath("$[0].verdict").value("PASSED"));
    }

    @Test
    void verificationRuns_SKIPPED와_위반이_동시에_있으면_INCOMPLETE이_우선한다() throws Exception {

        insertReport("it-admin-priority", "V1", "REC-01", "Failed rule", "CHECKED", 1);

        insertReport("it-admin-priority", "V1", "REC-02", "Skipped rule", "SKIPPED", 0);

        /*
         * SQL CASE 우선순위는
         *
         * INVALID(시계)
         * -> INCOMPLETE(미실행)
         * -> BASELINE(V0)
         * -> FAILED
         * -> PASSED
         *
         * 이고 VerificationReportRenderer 의 판정 사슬과 같다.
         * violation과 skipped가 동시에 존재하면 INCOMPLETE이 된다.
         */
        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].run_id").value("it-admin-priority"))
                .andExpect(jsonPath("$[0].total_violations").value(1))
                .andExpect(jsonPath("$[0].failed_rules").value(1))
                .andExpect(jsonPath("$[0].skipped_rules").value(1))
                .andExpect(jsonPath("$[0].verdict").value("INCOMPLETE"));
    }

    /**
     * 미실행이 BASELINE 보다 앞선다.
     *
     * <p>V0 는 위반 수로 판정하지 않지만(test-plan 5.4), 그것은 "기록이 온전할 때" 의 이야기다. 규칙이 빠진 V0 를 BASELINE 으로 부르면
     * 기록되지 않은 회차가 기준선으로 쓰인다. 마크다운 리포트도 같은 순서로 판정한다.
     */
    @Test
    void verificationRuns_V0라도_SKIPPED가_있으면_INCOMPLETE이다() throws Exception {

        insertReport("it-admin-v0-skipped", "V0", "REC-01", "Checked rule", "CHECKED", 0);

        insertReport("it-admin-v0-skipped", "V0", "REC-02", "Skipped rule", "SKIPPED", 0);

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].run_id").value("it-admin-v0-skipped"))
                .andExpect(jsonPath("$[0].round").value("V0"))
                .andExpect(jsonPath("$[0].skipped_rules").value(1))
                .andExpect(jsonPath("$[0].verdict").value("INCOMPLETE"));
    }

    /**
     * 시계가 깨진 회차는 어느 시점을 본 것인지 알 수 없다.
     *
     * <p>이 상태에서 BASELINE 이나 PASSED 를 내면, 믿을 수 없는 스냅샷이 판정을 통과한 것으로 기록된다.
     */
    @Test
    void verificationRuns_시계_규칙이_깨지면_INVALID다() throws Exception {

        insertReport("it-admin-clock", "V2", "CLOCK-01", "App vs DB clock", "CHECKED", 1);

        insertReport("it-admin-clock", "V2", "REC-01", "Checked rule", "CHECKED", 0);

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].run_id").value("it-admin-clock"))
                .andExpect(jsonPath("$[0].verdict").value("INVALID"));
    }

    @Test
    void verificationRuns_시계가_깨지면_V0라도_INVALID다() throws Exception {

        insertReport("it-admin-clock-v0", "V0", "CLOCK-02", "Redis vs DB clock", "CHECKED", 1);

        insertReport("it-admin-clock-v0", "V0", "REC-01", "Checked rule", "CHECKED", 0);

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].run_id").value("it-admin-clock-v0"))
                .andExpect(jsonPath("$[0].round").value("V0"))
                .andExpect(jsonPath("$[0].verdict").value("INVALID"));
    }

    /** N/A 인 CLOCK 규칙은 위반이 아니다. 무효로 뒤집히면 안 된다. */
    @Test
    void verificationRuns_CLOCK_규칙이_NA면_INVALID가_아니다() throws Exception {

        insertReport("it-admin-clock-na", "V1", "CLOCK-02", "Redis clock", "NOT_APPLICABLE", 0);

        insertReport("it-admin-clock-na", "V1", "REC-01", "Checked rule", "CHECKED", 0);

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].run_id").value("it-admin-clock-na"))
                .andExpect(jsonPath("$[0].verdict").value("PASSED"));
    }

    @Test
    void verificationRuns_NOT_APPLICABLE은_통과_규칙으로_집계되지_않는다() throws Exception {

        insertReport("it-admin-na", "V1", "REC-01", "Not applicable rule", "NOT_APPLICABLE", 0);

        insertReport("it-admin-na", "V1", "REC-02", "Checked rule", "CHECKED", 0);

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].run_id").value("it-admin-na"))
                .andExpect(jsonPath("$[0].round").value("V1"))
                .andExpect(jsonPath("$[0].rule_count").value(2))
                .andExpect(jsonPath("$[0].checked_rules").value(1))
                .andExpect(jsonPath("$[0].not_applicable_rules").value(1))
                .andExpect(jsonPath("$[0].skipped_rules").value(0));
    }

    @Test
    void verificationRunDetails는_실제_DB의_규칙별_결과를_조회한다() throws Exception {

        insertReport("it-admin-detail", "V1", "REC-01", "Passed rule", "CHECKED", 0);

        insertReport("it-admin-detail", "V1", "REC-02", "Failed rule", "CHECKED", 2);

        mockMvc.perform(get("/api/admin/batch/verification/runs/{runId}", "it-admin-detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].rule_code").value("REC-01"))
                .andExpect(jsonPath("$[0].round").value("V1"))
                .andExpect(jsonPath("$[0].status").value("CHECKED"))
                .andExpect(jsonPath("$[0].passed").value(true))
                .andExpect(jsonPath("$[0].violation_count").value(0))
                .andExpect(jsonPath("$[1].rule_code").value("REC-02"))
                .andExpect(jsonPath("$[1].round").value("V1"))
                .andExpect(jsonPath("$[1].status").value("CHECKED"))
                .andExpect(jsonPath("$[1].passed").value(false))
                .andExpect(jsonPath("$[1].violation_count").value(2));
    }

    @Test
    void verificationRunDetails는_SKIPPED를_passed_0으로_판정한다() throws Exception {

        insertReport("it-admin-detail-skipped", "V1", "REC-01", "Skipped rule", "SKIPPED", 0);

        mockMvc.perform(
                        get(
                                "/api/admin/batch/verification/runs/{runId}",
                                "it-admin-detail-skipped"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rule_code").value("REC-01"))
                .andExpect(jsonPath("$[0].round").value("V1"))
                .andExpect(jsonPath("$[0].status").value("SKIPPED"))
                .andExpect(jsonPath("$[0].violation_count").value(0))
                .andExpect(jsonPath("$[0].passed").value(false));
    }

    @Test
    void verificationRunDetails는_NOT_APPLICABLE을_passed_0으로_판정한다() throws Exception {

        insertReport(
                "it-admin-detail-na", "V1", "REC-01", "Not applicable rule", "NOT_APPLICABLE", 0);

        mockMvc.perform(get("/api/admin/batch/verification/runs/{runId}", "it-admin-detail-na"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rule_code").value("REC-01"))
                .andExpect(jsonPath("$[0].round").value("V1"))
                .andExpect(jsonPath("$[0].status").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$[0].violation_count").value(0))
                .andExpect(jsonPath("$[0].passed").value(false));
    }

    @Test
    void violations는_실제_DB의_위반_샘플을_조회한다() throws Exception {

        insertReport("it-admin-violations", "V1", "REC-01", "Failed rule", "CHECKED", 2);

        jdbcTemplate.update(
                """
                INSERT INTO verification_violation (
                    run_id,
                    rule_code,
                    target_table,
                    target_id,
                    detail
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                "it-admin-violations",
                "REC-01",
                "coupons",
                100L,
                "coupon violation");

        mockMvc.perform(
                        get(
                                "/api/admin/batch/verification/runs/{runId}/violations",
                                "it-admin-violations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].rule_code").value("REC-01"))
                .andExpect(jsonPath("$[0].target_table").value("coupons"))
                .andExpect(jsonPath("$[0].target_id").value(100))
                .andExpect(jsonPath("$[0].detail").value("coupon violation"));
    }

    @Test
    void violations는_ruleCode로_필터링한다() throws Exception {

        insertReport("it-admin-violations-filter", "V1", "REC-01", "Failed rule 1", "CHECKED", 1);

        insertReport("it-admin-violations-filter", "V1", "REC-02", "Failed rule 2", "CHECKED", 1);

        jdbcTemplate.update(
                """
                INSERT INTO verification_violation (
                    run_id,
                    rule_code,
                    target_table,
                    target_id,
                    detail
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                "it-admin-violations-filter",
                "REC-01",
                "coupons",
                100L,
                "REC-01 violation");

        jdbcTemplate.update(
                """
                INSERT INTO verification_violation (
                    run_id,
                    rule_code,
                    target_table,
                    target_id,
                    detail
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                "it-admin-violations-filter",
                "REC-02",
                "coupons",
                200L,
                "REC-02 violation");

        mockMvc.perform(
                        get(
                                        "/api/admin/batch/verification/runs/{runId}/violations",
                                        "it-admin-violations-filter")
                                .param("ruleCode", "REC-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].rule_code").value("REC-02"))
                .andExpect(jsonPath("$[0].target_id").value(200))
                .andExpect(jsonPath("$[0].detail").value("REC-02 violation"));
    }

    @Test
    void verificationRuns_limit은_실제_SQL의_LIMIT에_적용된다() throws Exception {

        insertReport("it-admin-limit-01", "V1", "REC-01", "Rule 1", "CHECKED", 0);

        insertReport("it-admin-limit-02", "V1", "REC-01", "Rule 2", "CHECKED", 0);

        insertReport("it-admin-limit-03", "V1", "REC-01", "Rule 3", "CHECKED", 0);

        mockMvc.perform(get("/api/admin/batch/verification/runs").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    /**
     * REC-01 은 DB 가 깨진 것이 아니라 Redis 와 어긋난 것이다. 마크다운은 이 경우를 "불일치" 로 부른다. API 가 FAILED 로 부르면 같은 회차가 두
     * 이름을 갖는다.
     */
    @Test
    void verificationRuns_REC만_위반이면_MISMATCH로_판정한다() throws Exception {

        insertReport("it-admin-mismatch", "V2", "INV-01", "Passed invariant", "CHECKED", 0);

        insertReport("it-admin-mismatch", "V2", "REC-01", "Redis-DB 재고 일치", "CHECKED", 1);

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].run_id").value("it-admin-mismatch"))
                .andExpect(jsonPath("$[0].round").value("V2"))
                .andExpect(jsonPath("$[0].total_violations").value(1))
                .andExpect(jsonPath("$[0].failed_rules").value(1))
                .andExpect(jsonPath("$[0].checked_rules").value(2))
                .andExpect(jsonPath("$[0].skipped_rules").value(0))
                .andExpect(jsonPath("$[0].verdict").value("MISMATCH"));
    }

    /** 둘 다 깨졌으면 더 심한 쪽을 말해야 한다. DB 가 깨진 것을 재고 불일치로 부르면 안 된다. */
    @Test
    void verificationRuns_INV와_REC이_함께_위반이면_FAILED가_우선한다() throws Exception {

        insertReport("it-admin-both", "V2", "INV-04", "Broken invariant", "CHECKED", 2);

        insertReport("it-admin-both", "V2", "REC-01", "Redis-DB 재고 일치", "CHECKED", 1);

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].run_id").value("it-admin-both"))
                .andExpect(jsonPath("$[0].total_violations").value(3))
                .andExpect(jsonPath("$[0].failed_rules").value(2))
                .andExpect(jsonPath("$[0].verdict").value("FAILED"));
    }
}
