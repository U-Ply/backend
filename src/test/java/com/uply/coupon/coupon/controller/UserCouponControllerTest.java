package com.uply.coupon.coupon.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.uply.coupon.common.exception.GlobalExceptionHandler;
import com.uply.coupon.common.exception.UserNotFoundException;
import com.uply.coupon.common.metrics.CouponIssueMetrics;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.dto.response.UserCouponListResponse;
import com.uply.coupon.coupon.dto.response.UserCouponSummaryResponse;
import com.uply.coupon.coupon.service.CouponQueryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UserCouponControllerTest {

    private static final Long USER_ID = 10L;

    private MockMvc mockMvc;
    private CouponQueryService couponQueryService;

    @BeforeEach
    void setUp() {
        couponQueryService = mock(CouponQueryService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new UserCouponController(couponQueryService))
                        .setControllerAdvice(
                                new GlobalExceptionHandler(
                                        new SimpleMeterRegistry(),
                                        new CouponIssueMetrics(new SimpleMeterRegistry()),
                                        mock(ObjectProvider.class)))
                        .build();
    }

    // 멱등성 키 없이 사용자 보유 쿠폰 목록을 조회하고 개인정보를 포함하지 않는지 검증한다.
    @Test
    void userCouponsCanBeRetrievedWithoutIdempotencyKey() throws Exception {
        UserCouponSummaryResponse coupon =
                new UserCouponSummaryResponse(
                        "1001", 1L, CouponStatus.ISSUED, Instant.parse("2026-08-19T10:00:00Z"));
        given(couponQueryService.getUserCoupons(USER_ID))
                .willReturn(new UserCouponListResponse(List.of(coupon)));

        mockMvc.perform(get("/api/users/{userId}/coupons", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coupons.length()").value(1))
                .andExpect(jsonPath("$.coupons[0].couponId").value("1001"))
                .andExpect(jsonPath("$.coupons[0].campaignId").value(1))
                .andExpect(jsonPath("$.coupons[0].status").value("ISSUED"))
                .andExpect(jsonPath("$.coupons[0].name").doesNotExist())
                .andExpect(jsonPath("$.coupons[0].email").doesNotExist());
    }

    // 사용자가 보유한 쿠폰이 없으면 coupons를 빈 배열로 응답하는지 검증한다.
    @Test
    void userWithoutCouponsReturnsEmptyList() throws Exception {
        given(couponQueryService.getUserCoupons(USER_ID))
                .willReturn(new UserCouponListResponse(List.of()));

        mockMvc.perform(get("/api/users/{userId}/coupons", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coupons").isEmpty());
    }

    // 존재하지 않는 사용자를 조회하면 404 USER_NOT_FOUND 응답을 반환하는지 검증한다.
    @Test
    void missingUserReturns404() throws Exception {
        given(couponQueryService.getUserCoupons(USER_ID))
                .willThrow(new UserNotFoundException(USER_ID));

        mockMvc.perform(get("/api/users/{userId}/coupons", USER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"));
    }
}
