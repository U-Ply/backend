package com.uply.coupon.campaign.controller;

import com.uply.coupon.campaign.api.CampaignApiPaths;
import com.uply.coupon.campaign.dto.response.CampaignDetailResponse;
import com.uply.coupon.campaign.dto.response.CampaignListResponse;
import com.uply.coupon.campaign.dto.response.CampaignStatusResponse;
import com.uply.coupon.campaign.service.CampaignQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(CampaignApiPaths.CAMPAIGNS)
@RequiredArgsConstructor
public class CampaignController {

    private final CampaignQueryService campaignQueryService;

    @GetMapping
    public ResponseEntity<CampaignListResponse> getCampaigns() {
        return ResponseEntity.ok(campaignQueryService.getCampaigns());
    }

    @GetMapping("/{campaignId}")
    public ResponseEntity<CampaignDetailResponse> getCampaign(@PathVariable Long campaignId) {
        return ResponseEntity.ok(campaignQueryService.getCampaign(campaignId));
    }

    @GetMapping("/{campaignId}" + CampaignApiPaths.STATUS)
    public ResponseEntity<CampaignStatusResponse> getCampaignStatus(
            @PathVariable Long campaignId,
            @RequestParam String routeId,
            @RequestParam String fareClass) {
        return ResponseEntity.ok(
                campaignQueryService.getCampaignStatus(campaignId, routeId, fareClass));
    }
}
