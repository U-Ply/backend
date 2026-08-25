package com.uply.coupon.campaign.controller;

import com.uply.coupon.campaign.api.CampaignApiPaths;
import com.uply.coupon.campaign.service.CampaignStatusStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping(CampaignApiPaths.CAMPAIGNS)
@RequiredArgsConstructor
public class CampaignStatusStreamController {
    private final CampaignStatusStreamService campaignStatusStreamService;

    @GetMapping(
            value = "/{campaignId}" + CampaignApiPaths.STATUS_STREAM,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable Long campaignId,
            @RequestParam String routeId,
            @RequestParam String fareClass) {
        return campaignStatusStreamService.subscribe(campaignId, routeId, fareClass);
    }
}
