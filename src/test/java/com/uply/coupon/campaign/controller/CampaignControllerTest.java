package com.uply.coupon.campaign.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.uply.coupon.campaign.domain.CampaignStatus;
import com.uply.coupon.campaign.dto.response.CampaignDetailResponse;
import com.uply.coupon.campaign.dto.response.CampaignListResponse;
import com.uply.coupon.campaign.dto.response.CampaignStatusResponse;
import com.uply.coupon.campaign.dto.response.CampaignStockSummaryResponse;
import com.uply.coupon.campaign.dto.response.CampaignSummaryResponse;
import com.uply.coupon.campaign.service.CampaignQueryService;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.common.exception.GlobalExceptionHandler;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CampaignControllerTest {

    private MockMvc mockMvc;
    private CampaignQueryService campaignQueryService;

    @BeforeEach
    void setUp() {
        campaignQueryService = mock(CampaignQueryService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new CampaignController(campaignQueryService))
                        .setControllerAdvice(
                                new GlobalExceptionHandler(
                                        new SimpleMeterRegistry(), mock(ObjectProvider.class)))
                        .build();
    }

    // 캠페인 목록 조회가 200과 함께 캠페인 배열을 반환하는지 검증한다.
    @Test
    void getCampaigns_returns200WithCampaignList() throws Exception {
        CampaignSummaryResponse summary =
                new CampaignSummaryResponse(
                        1L,
                        "제주 얼리버드 특가",
                        Instant.parse("2026-08-21T10:00:00.000Z"),
                        Instant.parse("2026-08-31T23:59:59.000Z"),
                        CampaignStatus.OPEN);
        given(campaignQueryService.getCampaigns())
                .willReturn(new CampaignListResponse(List.of(summary)));

        mockMvc.perform(get("/api/campaigns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaigns.length()").value(1))
                .andExpect(jsonPath("$.campaigns[0].campaignId").value(1))
                .andExpect(jsonPath("$.campaigns[0].status").value("OPEN"));
    }

    // 캠페인이 없으면 200과 함께 빈 배열을 반환하는지 검증한다.
    @Test
    void getCampaigns_emptyReturnsEmptyArray() throws Exception {
        given(campaignQueryService.getCampaigns()).willReturn(new CampaignListResponse(List.of()));

        mockMvc.perform(get("/api/campaigns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaigns").isEmpty());
    }

    // 캠페인 기본 정보 조회가 재고 풀 상세를 포함해 200을 반환하는지 검증한다.
    @Test
    void getCampaign_returns200WithStocks() throws Exception {
        CampaignStockSummaryResponse stock =
                new CampaignStockSummaryResponse("JEJU", "ECONOMY", 8000, 1548);
        CampaignDetailResponse response =
                new CampaignDetailResponse(
                        1L,
                        "제주 얼리버드 특가",
                        Instant.parse("2026-08-21T10:00:00.000Z"),
                        Instant.parse("2026-08-31T23:59:59.000Z"),
                        CampaignStatus.OPEN,
                        List.of(stock));
        given(campaignQueryService.getCampaign(1L)).willReturn(response);

        mockMvc.perform(get("/api/campaigns/{campaignId}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaignId").value(1))
                .andExpect(jsonPath("$.stocks[0].routeId").value("JEJU"))
                .andExpect(jsonPath("$.stocks[0].totalStock").value(8000))
                .andExpect(jsonPath("$.stocks[0].remainingStock").value(1548));
    }

    // 존재하지 않는 캠페인 조회가 404 CAMPAIGN_NOT_FOUND를 반환하는지 검증한다.
    @Test
    void getCampaign_missingCampaignReturns404() throws Exception {
        given(campaignQueryService.getCampaign(999L))
                .willThrow(new CampaignNotFoundException(999L));

        mockMvc.perform(get("/api/campaigns/{campaignId}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CAMPAIGN_NOT_FOUND"));
    }

    // 발급 현황 조회가 잔여 재고를 포함해 200을 반환하는지 검증한다.
    @Test
    void getCampaignStatus_returns200WithRemainingStock() throws Exception {
        CampaignStatusResponse response =
                new CampaignStatusResponse(1L, "JEJU", "ECONOMY", 8000, 1548);
        given(campaignQueryService.getCampaignStatus(1L, "JEJU", "ECONOMY")).willReturn(response);

        mockMvc.perform(
                        get("/api/campaigns/{campaignId}/status", 1L)
                                .param("routeId", "JEJU")
                                .param("fareClass", "ECONOMY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingStock").value(1548));
    }

    // 존재하지 않는 노선·좌석등급 조합 조회가 404 CAMPAIGN_NOT_FOUND를 반환하는지 검증한다.
    @Test
    void getCampaignStatus_missingRouteFareCombinationReturns404() throws Exception {
        given(campaignQueryService.getCampaignStatus(1L, "JEJU", "FIRST"))
                .willThrow(new CampaignNotFoundException(1L, "JEJU", "FIRST"));

        mockMvc.perform(
                        get("/api/campaigns/{campaignId}/status", 1L)
                                .param("routeId", "JEJU")
                                .param("fareClass", "FIRST"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CAMPAIGN_NOT_FOUND"));
    }

    // Redis 재고 캐시가 준비되지 않은 경우(캐시 미스) 503 CAMPAIGN_NOT_CACHED로 처리되는지 검증한다.
    @Test
    void getCampaignStatus_cacheNotReadyReturns503() throws Exception {
        given(campaignQueryService.getCampaignStatus(1L, "JEJU", "ECONOMY"))
                .willThrow(new CouponIssueException(IssueFailReason.CAMPAIGN_NOT_CACHED, 1L));

        mockMvc.perform(
                        get("/api/campaigns/{campaignId}/status", 1L)
                                .param("routeId", "JEJU")
                                .param("fareClass", "ECONOMY"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CAMPAIGN_NOT_CACHED"));
    }

    // 캠페인 기본 정보 조회 중 재고 캐시 미스가 나면 503 CAMPAIGN_NOT_CACHED로 처리되는지 검증한다.
    @Test
    void getCampaign_cacheNotReadyReturns503() throws Exception {
        given(campaignQueryService.getCampaign(1L))
                .willThrow(new CouponIssueException(IssueFailReason.CAMPAIGN_NOT_CACHED, 1L));

        mockMvc.perform(get("/api/campaigns/{campaignId}", 1L))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CAMPAIGN_NOT_CACHED"));
    }
}
