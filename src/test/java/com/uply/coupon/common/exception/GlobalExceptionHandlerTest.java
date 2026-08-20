package com.uply.coupon.common.exception;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.uply.coupon.coupon.controller.CouponController;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.service.CouponService;
import com.uply.coupon.coupon.service.CouponStateTransitionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;

class GlobalExceptionHandlerTest {

    private static final String IDEMPOTENCY_KEY = "00000000-0000-4000-8000-000000000001";

    private MockMvc mockMvc;
    private CouponService couponService;

    @BeforeEach
    void setUp() {
        couponService = mock(CouponService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new CouponController(
                                        couponService, mock(CouponStateTransitionService.class)))
                        .setControllerAdvice(new GlobalExceptionHandler(new SimpleMeterRegistry()))
                        .build();
    }

    @Test
    @DisplayName("커밋 시점 교착은 503 CONCURRENCY_CONFLICT로 응답한다")
    void concurrencyConflictReturns503() throws Exception {
        given(couponService.issue(eq(IDEMPOTENCY_KEY), any(CouponIssueRequest.class)))
                .willThrow(new PessimisticLockingFailureException("deadlock detected"));

        mockMvc.perform(
                        post("/api/coupons/issue")
                                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CONCURRENCY_CONFLICT"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("커넥션 획득 실패는 503 CONNECTION_UNAVAILABLE로 응답한다")
    void connectionUnavailableReturns503() throws Exception {
        given(couponService.issue(eq(IDEMPOTENCY_KEY), any(CouponIssueRequest.class)))
                .willThrow(
                        new CannotCreateTransactionException(
                                "Could not open JPA EntityManager for transaction"));

        mockMvc.perform(
                        post("/api/coupons/issue")
                                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CONNECTION_UNAVAILABLE"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    private String validRequest() {
        return """
                {
                  "userId": 1,
                  "campaignId": 1,
                  "routeId": "JEJU",
                  "fareClass": "ECONOMY"
                }
                """;
    }
}
