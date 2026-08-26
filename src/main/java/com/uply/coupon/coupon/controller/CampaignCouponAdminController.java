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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
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

        // 회수 자체가 성립하기 전의 확정 실패에서만 PROCESSING 선점을 해제한다. revoke()가
        // DB에 커밋된 뒤에는 응답 직렬화·Redis 캐싱이 실패해도 키를 지우면 안 된다 - 지우면
        // 같은 idempotencyKey의 재시도가 이미 끝난 회수 로직을 다시 실행해 중복 회수로
        // 이어진다. CouponServiceImpl.issue()의 issuanceCompleted 플래그와 같은 원칙이다.
        int revokedCount;
        try {
            revokedCount = campaignCouponRevokeService.revoke(campaignId, idempotencyKey);
        } catch (RuntimeException exception) {
            if (!idempotencyChecker.release(idempotencyKey, ownerToken)) {
                idempotencyOwnershipMetrics.recordReleaseRejected(idempotencyKey);
            }
            throw exception;
        }

        CampaignCouponRevokeResponse response =
                CampaignCouponRevokeResponse.of(campaignId, revokedCount);
        try {
            if (!idempotencyChecker.complete(
                    idempotencyKey, ownerToken, requestHash, toJson(response), 200)) {
                idempotencyOwnershipMetrics.recordCompleteRejected(idempotencyKey);
            }
        } catch (RuntimeException exception) {
            // 회수는 이미 성립했다. 캐시 저장(직렬화 포함)이 실패해도 최초 성공 응답은
            // 그대로 반환하고, 실패는 관측만 한다 - release()도, 500 전환도 하지 않는다.
            log.error(
                    "[일괄 회수 응답 캐싱 실패] campaignId: {}, key: {}",
                    campaignId,
                    idempotencyKey,
                    exception);
            idempotencyOwnershipMetrics.recordCompleteRejected(idempotencyKey);
        }
        return ResponseEntity.ok(response);
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
