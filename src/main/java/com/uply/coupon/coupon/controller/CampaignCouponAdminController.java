package com.uply.coupon.coupon.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.common.idempotency.IdempotencyChecker;
import com.uply.coupon.coupon.dto.response.CampaignCouponRevokeResponse;
import com.uply.coupon.coupon.service.CampaignCouponRevokeService;
import java.util.Optional;
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
    private final ObjectMapper objectMapper;

    @PostMapping("/{campaignId}/coupons/revoke")
    public ResponseEntity<CampaignCouponRevokeResponse> revokeCoupons(
            @PathVariable("campaignId") Long campaignId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        Optional<String> cachedBody = idempotencyChecker.getCachedResponse(idempotencyKey);
        if (cachedBody.isPresent()) {
            return ResponseEntity.ok(parseCachedResponse(cachedBody.get()));
        }

        try {
            int revokedCount = campaignCouponRevokeService.revoke(campaignId, idempotencyKey);
            CampaignCouponRevokeResponse response =
                    CampaignCouponRevokeResponse.of(campaignId, revokedCount);
            idempotencyChecker.cacheResponse(idempotencyKey, toJson(response), 200);
            return ResponseEntity.ok(response);
        } catch (RuntimeException exception) {
            idempotencyChecker.clearProgress(idempotencyKey);
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
}
