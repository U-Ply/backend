package com.uply.coupon.operation.admin;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.uply.coupon.common.exception.GlobalExceptionHandler;
import com.uply.coupon.common.metrics.CouponIssueMetrics;
import com.uply.coupon.operation.verification.report.VerificationReportRenderer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminBatchControllerTest {

    private static final List<String> EXPECTED_RULES =
            List.of(
                    "CLOCK-01",
                    "CLOCK-02",
                    "INV-01",
                    "INV-02",
                    "INV-03",
                    "INV-04",
                    "INV-05",
                    "INV-06",
                    "INV-07",
                    "INV-08",
                    "INV-09",
                    "INV-10",
                    "INV-11",
                    "INV-12",
                    "REC-01");

    private MockMvc mockMvc;

    private BatchLaunchService launchService;

    private VerificationReportRenderer reportRenderer;

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {

        launchService = mock(BatchLaunchService.class);

        reportRenderer = mock(VerificationReportRenderer.class);

        jdbcTemplate = mock(JdbcTemplate.class);

        AdminBatchController controller =
                new AdminBatchController(launchService, jdbcTemplate, reportRenderer);

        mockMvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(
                                new GlobalExceptionHandler(
                                        new SimpleMeterRegistry(),
                                        new CouponIssueMetrics(new SimpleMeterRegistry()),
                                        mock(ObjectProvider.class)))
                        .build();
    }

    @Test
    void unknownBatchReturnsCommonBadRequestResponse() throws Exception {

        mockMvc.perform(post("/api/admin/batch/unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void runningBatchReturnsCommonConflictResponse() throws Exception {

        given(launchService.launch("verificationJob", null, null, null))
                .willThrow(new BatchConflictException("verificationJob 이 이미 실행 중이다."));

        mockMvc.perform(post("/api/admin/batch/verification"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BATCH_CONFLICT"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void unsupportedBatchReturnsCommonNotImplementedResponse() throws Exception {

        given(launchService.launch("stockReconcileJob", null, null, null))
                .willThrow(new BatchNotImplementedException("stockReconcileJob 은 아직 구현되지 않았다."));

        mockMvc.perform(post("/api/admin/batch/reconcile"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.errorCode").value("BATCH_NOT_IMPLEMENTED"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void missingExecutionReturnsCommonNotFoundResponse() throws Exception {

        long executionId = 999L;

        given(launchService.findExecution(executionId)).willReturn(null);

        mockMvc.perform(get("/api/admin/batch/executions/{executionId}", executionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("BATCH_EXECUTION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void invalidExecutionIdReturnsCommonBadRequestResponse() throws Exception {

        mockMvc.perform(get("/api/admin/batch/executions/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void reportEndpointReturnsMarkdown() throws Exception {

        given(reportRenderer.render("L1-V1-01")).willReturn("# 검증 리포트 — L1-V1-01\n");

        mockMvc.perform(get("/api/admin/batch/verification/runs/{runId}/report", "L1-V1-01"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("L1-V1-01")));
    }

    @Test
    void verificationRoundPassesRoundOnlyAndLetsServerGenerateRunId() throws Exception {

        JobExecution execution = mock(JobExecution.class);

        JobParameters parameters =
                new JobParametersBuilder()
                        .addString("runId", "server-generated-run-id")
                        .addString("round", "V1")
                        .toJobParameters();

        given(execution.getJobParameters()).willReturn(parameters);
        given(execution.getId()).willReturn(123L);
        given(execution.getStatus()).willReturn(BatchStatus.STARTING);

        given(launchService.launch("verificationRoundJob", null, null, "V1")).willReturn(execution);

        mockMvc.perform(post("/api/admin/batch/verification-round").param("round", "v1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.job").value("verification-round"))
                .andExpect(jsonPath("$.jobName").value("verificationRoundJob"))
                .andExpect(jsonPath("$.runId").value("server-generated-run-id"))
                .andExpect(jsonPath("$.round").value("V1"))
                .andExpect(jsonPath("$.jobExecutionId").value(123))
                .andExpect(jsonPath("$.status").value("STARTING"));

        verify(launchService).launch("verificationRoundJob", null, null, "V1");
    }

    @Test
    void verificationRuns_V0는_위반이_있어도_BASELINE이다() throws Exception {

        givenAggregate("it-v0", "V0", 3L, 3L, 15L, 0L, 0L, 15L);

        givenRules("it-v0", allCheckedRulesWithViolation("INV-01"));

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].round").value("V0"))
                .andExpect(jsonPath("$[0].total_violations").value(3))
                .andExpect(jsonPath("$[0].failed_rules").value(3))
                .andExpect(jsonPath("$[0].rule_count").value(15))
                .andExpect(jsonPath("$[0].complete").value(true))
                .andExpect(jsonPath("$[0].missing_rules").isEmpty())
                .andExpect(jsonPath("$[0].verdict").value("BASELINE"));
    }

    @Test
    void verificationRuns_위반도_SKIPPED도_없으면_PASSED이다() throws Exception {

        givenAggregate("it-v1-pass", "V1", 0L, 0L, 15L, 0L, 0L, 15L);

        givenRules("it-v1-pass", allCheckedRules());

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].round").value("V1"))
                .andExpect(jsonPath("$[0].total_violations").value(0))
                .andExpect(jsonPath("$[0].failed_rules").value(0))
                .andExpect(jsonPath("$[0].rule_count").value(15))
                .andExpect(jsonPath("$[0].complete").value(true))
                .andExpect(jsonPath("$[0].missing_rules").isEmpty())
                .andExpect(jsonPath("$[0].verdict").value("PASSED"));
    }

    @Test
    void verificationRuns_위반이_있으면_FAILED이다() throws Exception {

        givenAggregate("it-v1-failed", "V1", 1L, 1L, 15L, 0L, 0L, 15L);

        givenRules("it-v1-failed", allCheckedRulesWithViolation("INV-01"));

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].round").value("V1"))
                .andExpect(jsonPath("$[0].total_violations").value(1))
                .andExpect(jsonPath("$[0].failed_rules").value(1))
                .andExpect(jsonPath("$[0].rule_count").value(15))
                .andExpect(jsonPath("$[0].complete").value(true))
                .andExpect(jsonPath("$[0].missing_rules").isEmpty())
                .andExpect(jsonPath("$[0].verdict").value("FAILED"));
    }

    @Test
    void verificationRuns_REC01위반이면_MISMATCH이다() throws Exception {

        givenAggregate("it-v1-mismatch", "V1", 1L, 1L, 15L, 0L, 0L, 15L);

        givenRules("it-v1-mismatch", allCheckedRulesWithViolation("REC-01"));

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].round").value("V1"))
                .andExpect(jsonPath("$[0].total_violations").value(1))
                .andExpect(jsonPath("$[0].failed_rules").value(1))
                .andExpect(jsonPath("$[0].rule_count").value(15))
                .andExpect(jsonPath("$[0].complete").value(true))
                .andExpect(jsonPath("$[0].missing_rules").isEmpty())
                .andExpect(jsonPath("$[0].verdict").value("MISMATCH"));
    }

    @Test
    void verificationRuns_SKIPPED가_있으면_INCOMPLETE이다() throws Exception {

        givenAggregate("it-v1-skipped", "V1", 0L, 0L, 14L, 0L, 1L, 15L);

        givenRules("it-v1-skipped", allRulesWithSkipped("REC-01"));

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].round").value("V1"))
                .andExpect(jsonPath("$[0].rule_count").value(15))
                .andExpect(jsonPath("$[0].skipped_rules").value(1))
                .andExpect(jsonPath("$[0].complete").value(false))
                .andExpect(jsonPath("$[0].missing_rules").isEmpty())
                .andExpect(jsonPath("$[0].verdict").value("INCOMPLETE"));
    }

    @Test
    void verificationRuns_규칙이_15개_미만이면_INCOMPLETE이다() throws Exception {

        givenAggregate("it-v1-missing", "V1", 0L, 0L, 14L, 0L, 0L, 14L);

        List<Map<String, Object>> rules = allCheckedRules();

        rules.removeIf(rule -> "REC-01".equals(rule.get("rule_code")));

        givenRules("it-v1-missing", rules);

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].round").value("V1"))
                .andExpect(jsonPath("$[0].rule_count").value(14))
                .andExpect(jsonPath("$[0].complete").value(false))
                .andExpect(jsonPath("$[0].missing_rules[0]").value("REC-01"))
                .andExpect(jsonPath("$[0].verdict").value("INCOMPLETE"));
    }

    @Test
    void verificationRuns_예상하지_않은_규칙이_있으면_INCOMPLETE이다() throws Exception {

        givenAggregate("it-v1-unexpected", "V1", 0L, 0L, 15L, 0L, 0L, 15L);

        List<Map<String, Object>> rules = allCheckedRules();

        rules.removeIf(rule -> "REC-01".equals(rule.get("rule_code")));

        rules.add(rule("OTHER-01", "CHECKED", 0L));

        givenRules("it-v1-unexpected", rules);

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rule_count").value(15))
                .andExpect(jsonPath("$[0].complete").value(false))
                .andExpect(jsonPath("$[0].missing_rules[0]").value("REC-01"))
                .andExpect(jsonPath("$[0].verdict").value("INCOMPLETE"));
    }

    @Test
    void verificationRuns_CLOCK위반이면_INVALID가_우선한다() throws Exception {

        givenAggregate("it-v1-clock-invalid", "V1", 1L, 1L, 15L, 0L, 0L, 15L);

        givenRules("it-v1-clock-invalid", allCheckedRulesWithViolation("CLOCK-01"));

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].round").value("V1"))
                .andExpect(jsonPath("$[0].rule_count").value(15))
                .andExpect(jsonPath("$[0].complete").value(true))
                .andExpect(jsonPath("$[0].verdict").value("INVALID"));
    }

    private void givenAggregate(
            String runId,
            String round,
            long totalViolations,
            long failedRules,
            long checkedRules,
            long notApplicableRules,
            long skippedRules,
            long ruleCount) {

        given(jdbcTemplate.queryForList(anyString(), eq(20)))
                .willReturn(
                        List.of(
                                Map.of(
                                        "run_id",
                                        runId,
                                        "round",
                                        round,
                                        "total_violations",
                                        totalViolations,
                                        "failed_rules",
                                        failedRules,
                                        "checked_rules",
                                        checkedRules,
                                        "not_applicable_rules",
                                        notApplicableRules,
                                        "skipped_rules",
                                        skippedRules,
                                        "rule_count",
                                        ruleCount,
                                        "total_elapsed_ms",
                                        100L)));
    }

    private void givenRules(String runId, List<Map<String, Object>> rules) {

        given(jdbcTemplate.queryForList(anyString(), eq(runId))).willReturn(rules);
    }

    private List<Map<String, Object>> allCheckedRules() {

        return new ArrayList<>(
                EXPECTED_RULES.stream().map(ruleCode -> rule(ruleCode, "CHECKED", 0L)).toList());
    }

    private List<Map<String, Object>> allCheckedRulesWithViolation(String failedRule) {

        return new ArrayList<>(
                EXPECTED_RULES.stream()
                        .map(
                                ruleCode ->
                                        rule(
                                                ruleCode,
                                                "CHECKED",
                                                ruleCode.equals(failedRule) ? 1L : 0L))
                        .toList());
    }

    private List<Map<String, Object>> allRulesWithSkipped(String skippedRule) {

        return new ArrayList<>(
                EXPECTED_RULES.stream()
                        .map(
                                ruleCode ->
                                        rule(
                                                ruleCode,
                                                ruleCode.equals(skippedRule)
                                                        ? "SKIPPED"
                                                        : "CHECKED",
                                                0L))
                        .toList());
    }

    private Map<String, Object> rule(String ruleCode, String status, long violationCount) {

        Map<String, Object> row = new HashMap<>();

        row.put("rule_code", ruleCode);
        row.put("status", status);
        row.put("violation_count", violationCount);

        return row;
    }
}
