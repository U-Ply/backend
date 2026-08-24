package com.uply.coupon.operation.admin;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.uply.coupon.common.exception.GlobalExceptionHandler;
import com.uply.coupon.operation.verification.report.VerificationReportRenderer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminBatchControllerTest {

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
                        .setControllerAdvice(new GlobalExceptionHandler(new SimpleMeterRegistry()))
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
    void verificationRuns_V0는_위반이_있어도_BASELINE이다() throws Exception {
        given(jdbcTemplate.queryForList(anyString(), eq(20)))
                .willReturn(
                        List.of(
                                Map.of(
                                        "run_id", "it-v0",
                                        "round", "V0",
                                        "total_violations", 3L,
                                        "failed_rules", 3L,
                                        "checked_rules", 13L,
                                        "not_applicable_rules", 1L,
                                        "skipped_rules", 0L,
                                        "rule_count", 14L,
                                        "total_elapsed_ms", 100L,
                                        "verdict", "BASELINE")));

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].round").value("V0"))
                .andExpect(jsonPath("$[0].total_violations").value(3))
                .andExpect(jsonPath("$[0].failed_rules").value(3))
                .andExpect(jsonPath("$[0].verdict").value("BASELINE"));
    }

    @Test
    void verificationRuns_SKIPPED가_있으면_INCOMPLETE이다() throws Exception {
        given(jdbcTemplate.queryForList(anyString(), eq(20)))
                .willReturn(
                        List.of(
                                Map.of(
                                        "run_id", "it-v1-incomplete",
                                        "round", "V1",
                                        "total_violations", 0L,
                                        "failed_rules", 0L,
                                        "checked_rules", 13L,
                                        "not_applicable_rules", 0L,
                                        "skipped_rules", 1L,
                                        "rule_count", 14L,
                                        "total_elapsed_ms", 100L,
                                        "verdict", "INCOMPLETE")));

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].round").value("V1"))
                .andExpect(jsonPath("$[0].skipped_rules").value(1))
                .andExpect(jsonPath("$[0].verdict").value("INCOMPLETE"));
    }

    @Test
    void verificationRuns_위반이_있으면_FAILED이다() throws Exception {
        given(jdbcTemplate.queryForList(anyString(), eq(20)))
                .willReturn(
                        List.of(
                                Map.of(
                                        "run_id", "it-v1-failed",
                                        "round", "V1",
                                        "total_violations", 1L,
                                        "failed_rules", 1L,
                                        "checked_rules", 13L,
                                        "not_applicable_rules", 1L,
                                        "skipped_rules", 0L,
                                        "rule_count", 14L,
                                        "total_elapsed_ms", 100L,
                                        "verdict", "FAILED")));

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].round").value("V1"))
                .andExpect(jsonPath("$[0].failed_rules").value(1))
                .andExpect(jsonPath("$[0].verdict").value("FAILED"));
    }

    @Test
    void verificationRuns_위반도_SKIPPED도_없으면_PASSED이다() throws Exception {
        given(jdbcTemplate.queryForList(anyString(), eq(20)))
                .willReturn(
                        List.of(
                                Map.of(
                                        "run_id", "it-v1-pass",
                                        "round", "V1",
                                        "total_violations", 0L,
                                        "failed_rules", 0L,
                                        "checked_rules", 13L,
                                        "not_applicable_rules", 1L,
                                        "skipped_rules", 0L,
                                        "rule_count", 14L,
                                        "total_elapsed_ms", 100L,
                                        "verdict", "PASSED")));

        mockMvc.perform(get("/api/admin/batch/verification/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].round").value("V1"))
                .andExpect(jsonPath("$[0].failed_rules").value(0))
                .andExpect(jsonPath("$[0].skipped_rules").value(0))
                .andExpect(jsonPath("$[0].verdict").value("PASSED"));
    }
}
