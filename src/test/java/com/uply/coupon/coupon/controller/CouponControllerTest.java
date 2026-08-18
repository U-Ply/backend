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
import com.uply.coupon.common.exception.CouponNotFoundException;
import com.uply.coupon.common.exception.GlobalExceptionHandler;
import com.uply.coupon.common.exception.InvalidStateTransitionException;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.dto.response.CouponIssueResponse;
import com.uply.coupon.coupon.service.CouponService;
import com.uply.coupon.coupon.service.CouponStateTransitionService;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CouponControllerTest {

    private static final String IDEMPOTENCY_KEY = "00000000-0000-4000-8000-000000000001";
    private static final Long COUPON_ID = 1001L;
    private static final LocalDateTime USED_AT = LocalDateTime.of(2026, 8, 18, 10, 0);
    private static final LocalDateTime CANCELLED_AT = USED_AT.plusMinutes(1);
    private static final LocalDateTime EXPIRE_AT = USED_AT.plusDays(1);

    private MockMvc mockMvc;
    private CouponService couponService;
    private CouponStateTransitionService couponStateTransitionService;

    @BeforeEach
    void setUp() {
        couponService = mock(CouponService.class);
        couponStateTransitionService = mock(CouponStateTransitionService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new CouponController(couponService, couponStateTransitionService))
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

    @Test
    void useSuccessReturns200() throws Exception {
        Coupon coupon = Coupon.issue(COUPON_ID, 1L, 1L, 1L, EXPIRE_AT);
        coupon.use(USED_AT);
        given(couponStateTransitionService.use(COUPON_ID, IDEMPOTENCY_KEY)).willReturn(coupon);

        mockMvc.perform(
                        post("/api/coupons/{couponId}/use", COUPON_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couponId").value(String.valueOf(COUPON_ID)))
                .andExpect(jsonPath("$.status").value("USED"))
                .andExpect(jsonPath("$.usedAt").value("2026-08-18T10:00:00.000Z"));
    }

    @Test
    void cancelSuccessReturns200() throws Exception {
        Coupon coupon = Coupon.issue(COUPON_ID, 1L, 1L, 1L, EXPIRE_AT);
        coupon.use(USED_AT);
        coupon.cancel(CANCELLED_AT);
        given(couponStateTransitionService.cancel(COUPON_ID, IDEMPOTENCY_KEY)).willReturn(coupon);

        mockMvc.perform(
                        post("/api/coupons/{couponId}/cancel", COUPON_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couponId").value(String.valueOf(COUPON_ID)))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").value("2026-08-18T10:01:00.000Z"));
    }

    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(post("/api/coupons/{couponId}/use", COUPON_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    void missingCouponReturns404() throws Exception {
        given(couponStateTransitionService.use(COUPON_ID, IDEMPOTENCY_KEY))
                .willThrow(new CouponNotFoundException(COUPON_ID));

        mockMvc.perform(
                        post("/api/coupons/{couponId}/use", COUPON_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COUPON_NOT_FOUND"));
    }

    @Test
    void invalidCancelTransitionReturns409() throws Exception {
        given(couponStateTransitionService.cancel(COUPON_ID, IDEMPOTENCY_KEY))
                .willThrow(
                        new InvalidStateTransitionException(
                                CouponStatus.ISSUED, CouponStatus.CANCELLED));

        mockMvc.perform(
                        post("/api/coupons/{couponId}/cancel", COUPON_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
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
