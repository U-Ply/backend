package com.uply.coupon.coupon.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.common.exception.GlobalExceptionHandler;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.dto.response.CouponIssueResponse;
import com.uply.coupon.coupon.service.CouponService;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CouponControllerTest {

    private static final String IDEMPOTENCY_KEY = "00000000-0000-4000-8000-000000000001";

    private MockMvc mockMvc;
    private CouponService couponService;

    @BeforeEach
    void setUp() {
        couponService = mock(CouponService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new CouponController(couponService))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void issueSuccessReturns200() throws Exception {
        CouponIssueResponse response =
                CouponIssueResponse.builder()
                        .couponId("100")
                        .status(CouponStatus.ISSUED)
                        .issuedAt(Instant.parse("2026-08-15T09:00:00Z"))
                        .expireAt(Instant.parse("2026-08-22T09:00:00Z"))
                        .build();
        given(couponService.issue(eq(IDEMPOTENCY_KEY), any(CouponIssueRequest.class)))
                .willReturn(response);

        mockMvc.perform(
                        post("/api/coupons/issue")
                                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couponId").value("100"))
                .andExpect(jsonPath("$.status").value("ISSUED"));
    }

    @Test
    void outOfStockReturns409() throws Exception {
        given(couponService.issue(eq(IDEMPOTENCY_KEY), any(CouponIssueRequest.class)))
                .willThrow(new CouponIssueException(IssueFailReason.OUT_OF_STOCK));

        mockMvc.perform(
                        post("/api/coupons/issue")
                                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void alreadyIssuedReturns409() throws Exception {
        given(couponService.issue(eq(IDEMPOTENCY_KEY), any(CouponIssueRequest.class)))
                .willThrow(new CouponIssueException(IssueFailReason.ALREADY_ISSUED));

        mockMvc.perform(
                        post("/api/coupons/issue")
                                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ALREADY_ISSUED"));
    }

    @Test
    void missingCampaignReturns404() throws Exception {
        given(couponService.issue(eq(IDEMPOTENCY_KEY), any(CouponIssueRequest.class)))
                .willThrow(new CampaignNotFoundException(1L, "JEJU", "ECONOMY"));

        mockMvc.perform(
                        post("/api/coupons/issue")
                                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CAMPAIGN_NOT_FOUND"));
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
