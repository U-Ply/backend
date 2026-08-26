package com.uply.coupon.coupon.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.exception.GlobalExceptionHandler;
import com.uply.coupon.common.exception.IdempotencyRequestInProgressException;
import com.uply.coupon.common.idempotency.IdempotencyChecker;
import com.uply.coupon.common.idempotency.IdempotencyClaim;
import com.uply.coupon.common.idempotency.IdempotencyOwnershipMetrics;
import com.uply.coupon.common.idempotency.IdempotencyRequestHasher;
import com.uply.coupon.coupon.dto.response.CampaignCouponRevokeResponse;
import com.uply.coupon.coupon.service.CampaignCouponRevokeService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CampaignCouponAdminControllerTest {

    private static final Long CAMPAIGN_ID = 10L;
    private static final String IDEMPOTENCY_KEY = "00000000-0000-4000-8000-000000000070";
    private static final String OWNER_TOKEN = "owner-token-1";
    private static final String REQUEST_HASH =
            IdempotencyRequestHasher.sha256("POST", "/api/admin/campaigns/10/coupons/revoke", "");

    private MockMvc mockMvc;
    private CampaignCouponRevokeService campaignCouponRevokeService;
    private IdempotencyChecker idempotencyChecker;
    private IdempotencyOwnershipMetrics idempotencyOwnershipMetrics;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        campaignCouponRevokeService = mock(CampaignCouponRevokeService.class);
        idempotencyChecker = mock(IdempotencyChecker.class);
        idempotencyOwnershipMetrics = mock(IdempotencyOwnershipMetrics.class);
        objectMapper = mock(ObjectMapper.class);
        // 최초 요청(캐시 미스)이 기본값이다. 캐시 Hit/처리 중 시나리오는 각 테스트에서 덮어쓴다.
        given(idempotencyChecker.acquire(anyString(), anyString()))
                .willReturn(IdempotencyClaim.acquired(OWNER_TOKEN));
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new CampaignCouponAdminController(
                                        campaignCouponRevokeService,
                                        idempotencyChecker,
                                        idempotencyOwnershipMetrics,
                                        objectMapper))
                        .setControllerAdvice(
                                new GlobalExceptionHandler(
                                        new SimpleMeterRegistry(), mock(ObjectProvider.class)))
                        .build();
    }

    // 최초 요청이면 일괄 취소 결과를 반환하고 자신의 ownerToken으로 멱등성 캐시를 완료 처리하는지 확인
    @Test
    void revokeSuccessReturns200AndCachesResponse() throws Exception {
        String responseJson = "{\"campaignId\":10,\"revokedCount\":2}";
        given(campaignCouponRevokeService.revoke(CAMPAIGN_ID, IDEMPOTENCY_KEY)).willReturn(2);
        given(objectMapper.writeValueAsString(any(CampaignCouponRevokeResponse.class)))
                .willReturn(responseJson);

        mockMvc.perform(
                        post("/api/admin/campaigns/{campaignId}/coupons/revoke", CAMPAIGN_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaignId").value(CAMPAIGN_ID))
                .andExpect(jsonPath("$.revokedCount").value(2));

        verify(idempotencyChecker)
                .complete(IDEMPOTENCY_KEY, OWNER_TOKEN, REQUEST_HASH, responseJson, 200);
        verify(idempotencyChecker, never()).release(anyString(), any());
    }

    // 완료된 동일 요청이면 비즈니스 로직을 다시 실행하지 않고 캐시 응답을 반환하는지 확인
    @Test
    void cachedRequestReturnsPreviousResponseWithoutRevokingAgain() throws Exception {
        String cachedJson = "{\"campaignId\":10,\"revokedCount\":2}";
        CampaignCouponRevokeResponse cachedResponse =
                CampaignCouponRevokeResponse.of(CAMPAIGN_ID, 2);
        given(idempotencyChecker.acquire(IDEMPOTENCY_KEY, REQUEST_HASH))
                .willReturn(IdempotencyClaim.completed(cachedJson));
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

    // 같은 멱등성 키를 다른 캠페인 요청에 재사용하면 409 오류로 차단하는지 확인
    @Test
    void reusedKeyForDifferentCampaignReturns409() throws Exception {
        Long otherCampaignId = 11L;
        String cachedJson = "{\"campaignId\":11,\"revokedCount\":2}";
        CampaignCouponRevokeResponse cachedResponse =
                CampaignCouponRevokeResponse.of(otherCampaignId, 2);
        given(idempotencyChecker.acquire(IDEMPOTENCY_KEY, REQUEST_HASH))
                .willReturn(IdempotencyClaim.completed(cachedJson));
        given(objectMapper.readValue(cachedJson, CampaignCouponRevokeResponse.class))
                .willReturn(cachedResponse);

        mockMvc.perform(
                        post("/api/admin/campaigns/{campaignId}/coupons/revoke", CAMPAIGN_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));

        verify(campaignCouponRevokeService, never()).revoke(CAMPAIGN_ID, IDEMPOTENCY_KEY);
    }

    // 같은 멱등성 키의 최초 요청이 처리 중이면 409 오류를 반환하는지 확인
    @Test
    void processingRequestReturns409() throws Exception {
        given(idempotencyChecker.acquire(IDEMPOTENCY_KEY, REQUEST_HASH))
                .willThrow(new IdempotencyRequestInProgressException());

        mockMvc.perform(
                        post("/api/admin/campaigns/{campaignId}/coupons/revoke", CAMPAIGN_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_REQUEST_IN_PROGRESS"));

        verify(campaignCouponRevokeService, never()).revoke(CAMPAIGN_ID, IDEMPOTENCY_KEY);
    }

    // 존재하지 않는 캠페인이면 404 오류를 반환하고 자신의 ownerToken으로 PROCESSING 키를 해제하는지 확인
    @Test
    void unknownCampaignReturns404() throws Exception {
        given(campaignCouponRevokeService.revoke(CAMPAIGN_ID, IDEMPOTENCY_KEY))
                .willThrow(new CampaignNotFoundException(CAMPAIGN_ID));

        mockMvc.perform(
                        post("/api/admin/campaigns/{campaignId}/coupons/revoke", CAMPAIGN_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CAMPAIGN_NOT_FOUND"));

        verify(idempotencyChecker).release(IDEMPOTENCY_KEY, OWNER_TOKEN);
    }

    // complete()가 예외를 던지면 500을 반환하고, RuntimeException catch 블록에서 release로
    // PROCESSING 선점을 해제하는지 확인
    @Test
    void cacheFailureReturns500AndReleasesProcessingKey() throws Exception {
        String responseJson = "{\"campaignId\":10,\"revokedCount\":2}";
        given(campaignCouponRevokeService.revoke(CAMPAIGN_ID, IDEMPOTENCY_KEY)).willReturn(2);
        given(objectMapper.writeValueAsString(any(CampaignCouponRevokeResponse.class)))
                .willReturn(responseJson);
        org.mockito.Mockito.doThrow(new IllegalStateException("Redis unavailable"))
                .when(idempotencyChecker)
                .complete(IDEMPOTENCY_KEY, OWNER_TOKEN, REQUEST_HASH, responseJson, 200);

        mockMvc.perform(
                        post("/api/admin/campaigns/{campaignId}/coupons/revoke", CAMPAIGN_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_SERVER_ERROR"));

        // complete()가 예외를 던졌으므로 응답 자체를 만들지 못했다 - RuntimeException catch 블록으로
        // 떨어져 release가 호출된다(발급과 달리 이 컨트롤러는 RuntimeException 전체를 잡는다).
        verify(idempotencyChecker).release(IDEMPOTENCY_KEY, OWNER_TOKEN);
    }

    // complete CAS가 소유권 상실로 거부되면(예외 없이 false) 응답은 그대로 반환하되 지표를 기록하는지 확인
    @Test
    void completeRejected_stillReturns200ButRecordsMetric() throws Exception {
        String responseJson = "{\"campaignId\":10,\"revokedCount\":2}";
        given(campaignCouponRevokeService.revoke(CAMPAIGN_ID, IDEMPOTENCY_KEY)).willReturn(2);
        given(objectMapper.writeValueAsString(any(CampaignCouponRevokeResponse.class)))
                .willReturn(responseJson);
        given(
                        idempotencyChecker.complete(
                                IDEMPOTENCY_KEY, OWNER_TOKEN, REQUEST_HASH, responseJson, 200))
                .willReturn(false);

        mockMvc.perform(
                        post("/api/admin/campaigns/{campaignId}/coupons/revoke", CAMPAIGN_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedCount").value(2));

        verify(idempotencyOwnershipMetrics).recordCompleteRejected(IDEMPOTENCY_KEY);
    }

    // Idempotency-Key 헤더가 없으면 비즈니스 로직을 실행하지 않고 400 오류를 반환하는지 확인
    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(post("/api/admin/campaigns/{campaignId}/coupons/revoke", CAMPAIGN_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

        verify(campaignCouponRevokeService, never()).revoke(CAMPAIGN_ID, IDEMPOTENCY_KEY);
    }

    // Idempotency-Key가 공백이면 Redis와 비즈니스 로직을 실행하지 않고 400을 반환하는지 확인
    @Test
    void blankIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(
                        post("/api/admin/campaigns/{campaignId}/coupons/revoke", CAMPAIGN_ID)
                                .header("Idempotency-Key", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

        verify(idempotencyChecker, never()).acquire(any(), any());
        verify(campaignCouponRevokeService, never()).revoke(any(), any());
    }

    // Idempotency-Key가 UUID 형식이 아니면 Redis와 비즈니스 로직을 실행하지 않고 400을 반환하는지 확인
    @Test
    void malformedIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(
                        post("/api/admin/campaigns/{campaignId}/coupons/revoke", CAMPAIGN_ID)
                                .header("Idempotency-Key", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

        verify(idempotencyChecker, never()).acquire(any(), any());
        verify(campaignCouponRevokeService, never()).revoke(any(), any());
    }

    // UUID 형식이지만 v4가 아니면 Redis와 비즈니스 로직을 실행하지 않고 400을 반환하는지 확인
    @Test
    void nonVersionFourUuidReturns400() throws Exception {
        String uuidV1 = "550e8400-e29b-11d4-a716-446655440000";

        mockMvc.perform(
                        post("/api/admin/campaigns/{campaignId}/coupons/revoke", CAMPAIGN_ID)
                                .header("Idempotency-Key", uuidV1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

        verify(idempotencyChecker, never()).acquire(any(), any());
        verify(campaignCouponRevokeService, never()).revoke(any(), any());
    }
}
