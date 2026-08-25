package com.uply.coupon.campaign.controller;

import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.uply.coupon.campaign.service.CampaignCacheWarmupService;
import com.uply.coupon.common.exception.CacheRecoveryNotSettledException;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.exception.GlobalExceptionHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CampaignCacheAdminControllerTest {

    private static final Long CAMPAIGN_ID = 10L;

    private MockMvc mockMvc;
    private CampaignCacheWarmupService campaignCacheWarmupService;

    @BeforeEach
    void setUp() {
        campaignCacheWarmupService = mock(CampaignCacheWarmupService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new CampaignCacheAdminController(campaignCacheWarmupService))
                        .setControllerAdvice(new GlobalExceptionHandler(new SimpleMeterRegistry()))
                        .build();
    }

    @Test
    @DisplayName("웜업 성공 시 200과 함께 campaignId·상태를 반환한다")
    void warmupSuccessReturns200() throws Exception {
        mockMvc.perform(post("/api/admin/campaigns/{campaignId}/cache/warmup", CAMPAIGN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaignId").value(CAMPAIGN_ID))
                .andExpect(jsonPath("$.status").value("WARMED_UP"));

        verify(campaignCacheWarmupService).warmupCampaign(CAMPAIGN_ID);
    }

    // 존재하지 않는 campaignId를 "성공"으로 오인하면 안 된다 — 404로 명확히 구분한다.
    @Test
    @DisplayName("존재하지 않는 캠페인이면 404 CAMPAIGN_NOT_FOUND를 반환한다")
    void warmupCampaignNotFoundReturns404() throws Exception {
        willThrow(new CampaignNotFoundException(CAMPAIGN_ID))
                .given(campaignCacheWarmupService)
                .warmupCampaign(CAMPAIGN_ID);

        mockMvc.perform(post("/api/admin/campaigns/{campaignId}/cache/warmup", CAMPAIGN_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CAMPAIGN_NOT_FOUND"));
    }

    // Kafka lag/DLT가 정착하지 않은 상태에서의 복구 시도는 재시도 가능한 일시적 실패로 취급한다.
    @Test
    @DisplayName("Kafka가 정착하지 않았으면 503 CACHE_RECOVERY_NOT_SETTLED를 반환한다")
    void warmupKafkaNotSettledReturns503() throws Exception {
        willThrow(
                        new CacheRecoveryNotSettledException(
                                "Kafka 미정착 상태에서는 캐시 복구를 실행할 수 없습니다. lag=5, dlt=0"))
                .given(campaignCacheWarmupService)
                .warmupCampaign(CAMPAIGN_ID);

        mockMvc.perform(post("/api/admin/campaigns/{campaignId}/cache/warmup", CAMPAIGN_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CACHE_RECOVERY_NOT_SETTLED"));
    }

    // /cache/recover는 warmup과 다른 서비스 메서드(recoverMissingCache)를 호출해야 한다 —
    // 잘못 연결되면 실시간 재고가 DB 스냅샷으로 덮어써지는 사고로 이어진다.
    @Test
    @DisplayName("부분 복구 성공 시 200과 함께 RECOVERED 상태를 반환한다")
    void recoverSuccessReturns200() throws Exception {
        mockMvc.perform(post("/api/admin/campaigns/{campaignId}/cache/recover", CAMPAIGN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaignId").value(CAMPAIGN_ID))
                .andExpect(jsonPath("$.status").value("RECOVERED"));

        verify(campaignCacheWarmupService).recoverMissingCache(CAMPAIGN_ID);
    }

    @Test
    @DisplayName("존재하지 않는 캠페인의 부분 복구는 404 CAMPAIGN_NOT_FOUND를 반환한다")
    void recoverCampaignNotFoundReturns404() throws Exception {
        willThrow(new CampaignNotFoundException(CAMPAIGN_ID))
                .given(campaignCacheWarmupService)
                .recoverMissingCache(CAMPAIGN_ID);

        mockMvc.perform(post("/api/admin/campaigns/{campaignId}/cache/recover", CAMPAIGN_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CAMPAIGN_NOT_FOUND"));
    }

    @Test
    @DisplayName("Kafka가 정착하지 않았으면 부분 복구도 503 CACHE_RECOVERY_NOT_SETTLED를 반환한다")
    void recoverKafkaNotSettledReturns503() throws Exception {
        willThrow(
                        new CacheRecoveryNotSettledException(
                                "Kafka 미정착 상태에서는 캐시 복구를 실행할 수 없습니다. lag=3, dlt=0"))
                .given(campaignCacheWarmupService)
                .recoverMissingCache(CAMPAIGN_ID);

        mockMvc.perform(post("/api/admin/campaigns/{campaignId}/cache/recover", CAMPAIGN_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CACHE_RECOVERY_NOT_SETTLED"));
    }
}
