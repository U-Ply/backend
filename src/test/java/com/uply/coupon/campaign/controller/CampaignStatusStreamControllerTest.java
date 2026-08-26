package com.uply.coupon.campaign.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.uply.coupon.campaign.service.CampaignStatusStreamService;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.exception.GlobalExceptionHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class CampaignStatusStreamControllerTest {

    private MockMvc mockMvc;
    private CampaignStatusStreamService campaignStatusStreamService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        campaignStatusStreamService = mock(CampaignStatusStreamService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new CampaignStatusStreamController(campaignStatusStreamService))
                        .setControllerAdvice(
                                new GlobalExceptionHandler(
                                        new SimpleMeterRegistry(), mock(ObjectProvider.class)))
                        .build();
    }

    // 정상 요청이 200과 text/event-stream Content-Type을 반환하는지 검증
    @Test
    void stream_returns200WithEventStreamContentType() throws Exception {
        SseEmitter emitter = new SseEmitter();
        emitter.complete();
        given(campaignStatusStreamService.subscribe(1L, "JEJU", "ECONOMY")).willReturn(emitter);

        MvcResult mvcResult =
                mockMvc.perform(
                                get("/api/campaigns/{campaignId}/status/stream", 1L)
                                        .param("routeId", "JEJU")
                                        .param("fareClass", "ECONOMY"))
                        .andExpect(request().asyncStarted())
                        .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    // campaignId, routeId, fareClass가 서비스에 정확히 전달되는지
    @Test
    void stream_passesPathAndQueryParamsToService() throws Exception {
        given(campaignStatusStreamService.subscribe(1L, "JEJU", "ECONOMY"))
                .willReturn(new SseEmitter());

        mockMvc.perform(
                get("/api/campaigns/{campaignId}/status/stream", 1L)
                        .param("routeId", "JEJU")
                        .param("fareClass", "ECONOMY"));

        verify(campaignStatusStreamService).subscribe(1L, "JEJU", "ECONOMY");
    }

    // 존재하지 않는 재고 풀 구독 요청이 404 CAMPAIGN_NOT_FOUND를 반환하는지
    @Test
    void stream_missingStock_returns404() throws Exception {
        given(campaignStatusStreamService.subscribe(1L, "JEJU", "FIRST"))
                .willThrow(new CampaignNotFoundException(1L, "JEJU", "FIRST"));

        mockMvc.perform(
                        get("/api/campaigns/{campaignId}/status/stream", 1L)
                                .param("routeId", "JEJU")
                                .param("fareClass", "FIRST"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CAMPAIGN_NOT_FOUND"));
    }

    // 필수 쿼리 파라미터가 없으면 400 INVALID_REQUEST를 반환하는지 확인
    @Test
    void stream_missingQueryParam_returns400() throws Exception {
        mockMvc.perform(
                        get("/api/campaigns/{campaignId}/status/stream", 1L)
                                .param("routeId", "JEJU"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }
}
