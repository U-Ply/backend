package com.uply.coupon.campaign.controller;

import com.uply.coupon.campaign.dto.response.CampaignCacheWarmupResponse;
import com.uply.coupon.campaign.service.CampaignCacheWarmupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 캠페인 Redis 캐시를 DB 기준으로 웜업/복구하는 운영자 전용 API.
 *
 * <p>오픈 전 사전 웜업뿐 아니라, 운영 중 Redis 데이터가 유실된 경우(재시작, 키 evict 등)의 수동 복구 진입점이기도 하다. 호출 전 신규 발급 트래픽을 차단해야
 * 하며, Kafka 저장 전략(V3)에서는 lag·DLT가 정착하지 않으면 503으로 거부된다 — 자세한 전제는 {@link CampaignCacheWarmupService}
 * 참고.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/campaigns")
@RequiredArgsConstructor
public class CampaignCacheAdminController {

    private final CampaignCacheWarmupService campaignCacheWarmupService;

    @PostMapping("/{campaignId}/cache/warmup")
    public ResponseEntity<CampaignCacheWarmupResponse> warmup(
            @PathVariable("campaignId") Long campaignId) {
        campaignCacheWarmupService.warmupCampaign(campaignId);
        log.info("캠페인 캐시 웜업/복구 완료 — campaignId: {}", campaignId);
        return ResponseEntity.ok(CampaignCacheWarmupResponse.completed(campaignId));
    }
}
