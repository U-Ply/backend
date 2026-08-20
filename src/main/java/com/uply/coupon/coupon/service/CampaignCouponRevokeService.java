package com.uply.coupon.coupon.service;

import com.uply.coupon.campaign.repository.CampaignRepository;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CampaignCouponRevokeService {

    private static final String HISTORY_KEY_PREFIX = "revoke-";
    static final int CHUNK_SIZE = 500;

    private final CampaignRepository campaignRepository;
    private final CouponRepository couponRepository;
    private final CouponHistoryRepository couponHistoryRepository;
    private final CampaignCouponRevokeChunkProcessor chunkProcessor;

    public int revoke(Long campaignId, String idempotencyKey) {
        if (!campaignRepository.existsById(campaignId)) {
            throw new CampaignNotFoundException(campaignId);
        }

        String historyKeyPrefix = createHistoryKeyPrefix(idempotencyKey);
        int totalRevokedCount =
                Math.toIntExact(
                        couponHistoryRepository.countCampaignRevocationsByHistoryKeyPrefix(
                                campaignId, historyKeyPrefix));
        long lastCouponId = 0L;
        Pageable chunkPage = PageRequest.of(0, CHUNK_SIZE);

        while (true) {
            List<Long> couponIds =
                    couponRepository.findIssuedCouponIdsByCampaignIdAfter(
                            campaignId, lastCouponId, chunkPage);
            if (couponIds.isEmpty()) {
                break;
            }

            totalRevokedCount += chunkProcessor.revokeChunk(couponIds, historyKeyPrefix);
            lastCouponId = couponIds.get(couponIds.size() - 1);
        }

        return totalRevokedCount;
    }

    private String createHistoryKeyPrefix(String idempotencyKey) {
        return HISTORY_KEY_PREFIX + idempotencyKey + "-";
    }
}
