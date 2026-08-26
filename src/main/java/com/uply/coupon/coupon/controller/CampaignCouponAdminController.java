package com.uply.coupon.coupon.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.common.exception.IdempotencyKeyReusedException;
import com.uply.coupon.common.idempotency.IdempotencyChecker;
import com.uply.coupon.common.idempotency.IdempotencyClaim;
import com.uply.coupon.common.idempotency.IdempotencyKeyValidator;
import com.uply.coupon.common.idempotency.IdempotencyOwnershipMetrics;
import com.uply.coupon.common.idempotency.IdempotencyRequestHasher;
import com.uply.coupon.coupon.dto.response.CampaignCouponRevokeResponse;
import com.uply.coupon.coupon.service.CampaignCouponRevokeService;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/campaigns")
@RequiredArgsConstructor
public class CampaignCouponAdminController {

    private final CampaignCouponRevokeService campaignCouponRevokeService;
    private final IdempotencyChecker idempotencyChecker;
    private final IdempotencyOwnershipMetrics idempotencyOwnershipMetrics;
    private final ObjectMapper objectMapper;

    @PostMapping("/{campaignId}/coupons/revoke")
    public ResponseEntity<CampaignCouponRevokeResponse> revokeCoupons(
            @PathVariable("campaignId") Long campaignId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        IdempotencyKeyValidator.validateUuidV4(idempotencyKey);
        String requestHash = createRequestHash(campaignId);
        IdempotencyClaim claim = idempotencyChecker.acquire(idempotencyKey, requestHash);
        if (claim.hasCachedResponse()) {
            CampaignCouponRevokeResponse cachedResponse =
                    parseCachedResponse(claim.cachedResponse());
            if (!Objects.equals(campaignId, cachedResponse.campaignId())) {
                throw new IdempotencyKeyReusedException();
            }
            return ResponseEntity.ok(cachedResponse);
        }
        String ownerToken = claim.ownerToken();

        try {
            int revokedCount = campaignCouponRevokeService.revoke(campaignId, idempotencyKey);
            CampaignCouponRevokeResponse response =
                    CampaignCouponRevokeResponse.of(campaignId, revokedCount);
            if (!idempotencyChecker.complete(
                    idempotencyKey, ownerToken, requestHash, toJson(response), 200)) {
                idempotencyOwnershipMetrics.recordCompleteRejected(idempotencyKey);
            }
            return ResponseEntity.ok(response);
        } catch (RuntimeException exception) {
            if (!idempotencyChecker.release(idempotencyKey, ownerToken)) {
                idempotencyOwnershipMetrics.recordReleaseRejected(idempotencyKey);
            }
            throw exception;
        }
    }

    private String toJson(CampaignCouponRevokeResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("응답 데이터 직렬화에 실패했습니다.", exception);
        }
    }

    private CampaignCouponRevokeResponse parseCachedResponse(String json) {
        try {
            return objectMapper.readValue(json, CampaignCouponRevokeResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("캐시된 응답 데이터 복원에 실패했습니다.", exception);
        }
    }

    private String createRequestHash(Long campaignId) {
        return IdempotencyRequestHasher.sha256(
                "POST", "/api/admin/campaigns/" + campaignId + "/coupons/revoke", "");
    }
}
