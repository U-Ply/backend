package com.uply.coupon.messaging.consumer;

import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponHistory;
import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import com.uply.coupon.messaging.event.CouponIssuedEvent;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CouponIssuedPersistenceService {

    private final CouponRepository couponRepository;
    private final CouponHistoryRepository couponHistoryRepository;
    private final CampaignStockRepository campaignStockRepository;

    @Transactional
    public void persist(CouponIssuedEvent event) {
        LocalDateTime issuedAt = LocalDateTime.ofInstant(event.issuedAt(), ZoneOffset.UTC);
        LocalDateTime expireAt =
                event.expireAt() != null
                        // (신버전 이벤트) expireAt 있으면
                        ? LocalDateTime.ofInstant(event.expireAt(), ZoneOffset.UTC)
                        // (구버전) expireAt 없으면 -> DB 조회(Fallback)
                        : campaignStockRepository
                                .findCouponExpireAt(event.stockId(), event.campaignId())
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "이벤트와 일치하는 캠페인 재고를 찾을 수 없습니다. stockId="
                                                                + event.stockId()
                                                                + ", campaignId="
                                                                + event.campaignId()));

        if (!expireAt.isAfter(issuedAt)) {
            throw new IllegalStateException("쿠폰 만료 시각은 발급 시각보다 이후여야 합니다.");
        }
        // stockId(id)와 campaignId 조합의 존재 여부만 빠르게 조회
        if (!campaignStockRepository.existsByIdAndCampaignId(event.stockId(), event.campaignId())) {
            throw new IllegalStateException(
                    "존재하지 않는 캠페인 재고 조합입니다. stockId="
                            + event.stockId()
                            + ", campaignId="
                            + event.campaignId());
        }

        Coupon coupon =
                Coupon.issue(
                        event.couponId(),
                        event.userId(),
                        event.campaignId(),
                        event.stockId(),
                        issuedAt,
                        expireAt);
        CouponHistory history =
                CouponHistory.issued(event.couponId(), event.idempotencyKey(), issuedAt);

        couponRepository.saveAndFlush(coupon);
        couponHistoryRepository.saveAndFlush(history);

        int updatedRows =
                campaignStockRepository.decreaseRemainingStockIfAvailable(
                        event.stockId(), event.campaignId());
        if (updatedRows != 1) {
            throw new IllegalStateException("DB 재고 차감에 실패했습니다. stockId=" + event.stockId());
        }
    }
}
