package com.uply.coupon.coupon.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.common.exception.GlobalExceptionHandler;
import com.uply.coupon.common.exception.IdempotencyRequestInProgressException;
import com.uply.coupon.common.idempotency.IdempotencyChecker;
import com.uply.coupon.coupon.dto.response.CampaignCouponRevokeResponse;
import com.uply.coupon.coupon.service.CampaignCouponRevokeService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CampaignCouponAdminControllerTest {

    private static final Long CAMPAIGN_ID = 10L;
    private static final String IDEMPOTENCY_KEY = "00000000-0000-4000-8000-000000000070";

    private MockMvc mockMvc;
    private CampaignCouponRevokeService campaignCouponRevokeService;
    private IdempotencyChecker idempotencyChecker;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        campaignCouponRevokeService = mock(CampaignCouponRevokeService.class);
        idempotencyChecker = mock(IdempotencyChecker.class);
        objectMapper = mock(ObjectMapper.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new CampaignCouponAdminController(
                                        campaignCouponRevokeService,
                                        idempotencyChecker,
                                        objectMapper))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    // 최초 요청이면 일괄 취소 결과를 반환하고 같은 응답을 멱등성 캐시에 저장하는지 확인
    @Test
    void revokeSuccessReturns200AndCachesResponse() throws Exception {
        String responseJson = "{\"campaignId\":10,\"revokedCount\":2}";
        given(idempotencyChecker.getCachedResponse(IDEMPOTENCY_KEY)).willReturn(Optional.empty());
        given(campaignCouponRevokeService.revoke(CAMPAIGN_ID, IDEMPOTENCY_KEY)).willReturn(2);
        given(objectMapper.writeValueAsString(any(CampaignCouponRevokeResponse.class)))
                .willReturn(responseJson);

        mockMvc.perform(
                        post("/api/admin/campaigns/{campaignId}/coupons/revoke", CAMPAIGN_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaignId").value(CAMPAIGN_ID))
                .andExpect(jsonPath("$.revokedCount").value(2));

        verify(idempotencyChecker).cacheResponse(IDEMPOTENCY_KEY, responseJson, 200);
        verify(idempotencyChecker, never()).clearProgress(IDEMPOTENCY_KEY);
    }

    // 완료된 동일 요청이면 비즈니스 로직을 다시 실행하지 않고 캐시 응답을 반환하는지 확인
    @Test
    void cachedRequestReturnsPreviousResponseWithoutRevokingAgain() throws Exception {
        String cachedJson = "{\"campaignId\":10,\"revokedCount\":2}";
        CampaignCouponRevokeResponse cachedResponse =
                CampaignCouponRevokeResponse.of(CAMPAIGN_ID, 2);
        given(idempotencyChecker.getCachedResponse(IDEMPOTENCY_KEY))
                .willReturn(Optional.of(cachedJson));
        given(objectMapper.readValue(cachedJson, CampaignCouponRevokeResponse.class))
                .willReturn(cachedResponse);

        mockMvc.perform(
                        post("/api/admin/campaigns/{campaignId}/coupons/revoke", CAMPAIGN_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaignId").value(CAMPAIGN_ID))
                .andExpect(jsonPath("$.revokedCount").value(2));

        verify(campaignCouponRevokeService, never()).revoke(CAMPAIGN_ID, IDEMPOTENCY_KEY);
    }

    // 같은 멱등성 키의 최초 요청이 처리 중이면 409 오류를 반환하는지 확인
    @Test
    void processingRequestReturns409() throws Exception {
        given(idempotencyChecker.getCachedResponse(IDEMPOTENCY_KEY))
                .willThrow(new IdempotencyRequestInProgressException());

        mockMvc.perform(
                        post("/api/admin/campaigns/{campaignId}/coupons/revoke", CAMPAIGN_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_REQUEST_IN_PROGRESS"));

        verify(campaignCouponRevokeService, never()).revoke(CAMPAIGN_ID, IDEMPOTENCY_KEY);
    }

    // Idempotency-Key 헤더가 없으면 비즈니스 로직을 실행하지 않고 400 오류를 반환하는지 확인
    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(post("/api/admin/campaigns/{campaignId}/coupons/revoke", CAMPAIGN_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

        verify(campaignCouponRevokeService, never()).revoke(CAMPAIGN_ID, IDEMPOTENCY_KEY);
    }
}
