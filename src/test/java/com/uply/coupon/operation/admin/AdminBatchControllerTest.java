package com.uply.coupon.operation.admin;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.uply.coupon.common.exception.GlobalExceptionHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminBatchControllerTest {

    private MockMvc mockMvc;
    private BatchLaunchService launchService;

    @BeforeEach
    void setUp() {
        launchService = mock(BatchLaunchService.class);
        AdminBatchController controller =
                new AdminBatchController(launchService, mock(JdbcTemplate.class));
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
}
