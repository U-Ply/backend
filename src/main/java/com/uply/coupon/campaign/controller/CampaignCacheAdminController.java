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
 * <p>두 엔드포인트는 목적이 다르다.
 *
 * <ul>
 *   <li>{@code /cache/warmup} — 오픈 전 사전 적재, 또는 신규 발급 트래픽을 확실히 차단한 뒤의 전체 재구축. 살아있는 키도 DB 스냅샷으로 무조건
 *       덮어쓴다({@link CampaignCacheWarmupService#warmupCampaign}).
 *   <li>{@code /cache/recover} — 운영 중 Redis 키 일부가 유실됐을 때의 수동 복구. 트래픽 차단을 전제하지 않으며, 이미 있는 키는 절대 덮어쓰지
 *       않고 없는 키만 채운다({@link CampaignCacheWarmupService#recoverMissingCache}). {@code
 *       CAMPAIGN_NOT_CACHED} 감지 시 호출할 엔드포인트는 이쪽이다 — warmup을 잘못 호출하면 실시간으로 감소 중인 재고가 되살아나 초과 발급으로
 *       이어질 수 있다.
 * </ul>
 *
 * <p>두 엔드포인트 모두 Kafka 저장 전략(V3)에서는 lag·DLT가 정착하지 않으면 503으로 거부된다.
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
        log.info("캠페인 캐시 전체 웜업 완료 — campaignId: {}", campaignId);
        return ResponseEntity.ok(CampaignCacheWarmupResponse.completed(campaignId));
    }

    @PostMapping("/{campaignId}/cache/recover")
    public ResponseEntity<CampaignCacheWarmupResponse> recover(
            @PathVariable("campaignId") Long campaignId) {
        campaignCacheWarmupService.recoverMissingCache(campaignId);
        log.info("캠페인 캐시 부분 복구 완료 — campaignId: {}", campaignId);
        return ResponseEntity.ok(CampaignCacheWarmupResponse.recovered(campaignId));
    }
}
