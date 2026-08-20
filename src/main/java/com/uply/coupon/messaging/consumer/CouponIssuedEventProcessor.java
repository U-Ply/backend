package com.uply.coupon.messaging.consumer;

import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import com.uply.coupon.coupon.repository.CouponIssuanceProgressRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import com.uply.coupon.messaging.event.CouponIssuedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssuedEventProcessor {

    private final CouponRepository couponRepository;
    private final CouponHistoryRepository couponHistoryRepository;
    private final CouponIssuedPersistenceService persistenceService;
    private final CouponIssuanceProgressRepository progressRepository;

    public boolean process(CouponIssuedEvent event) {
        validate(event);

        if (isDuplicate(event)) {
            progressRepository.clear(event.couponId());
            log.info("중복 쿠폰 발급 이벤트를 건너뜁니다. couponId={}", event.couponId());
            return false;
        }

        try {
            persistenceService.persist(event);
            progressRepository.clear(event.couponId());
            return true;
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicate(event)) {
                progressRepository.clear(event.couponId());
                log.info("동시 처리된 중복 쿠폰 발급 이벤트를 건너뜁니다. couponId={}", event.couponId());
                return false;
            }
            throw exception;
        }
    }

    private boolean isDuplicate(CouponIssuedEvent event) {
        return couponRepository.existsById(event.couponId())
                || couponRepository.existsByCampaignIdAndUserId(event.campaignId(), event.userId())
                || couponHistoryRepository.existsByIdempotencyKey(event.idempotencyKey());
    }

    private void validate(CouponIssuedEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("쿠폰 발급 이벤트는 필수입니다.");
        }
        validatePositive(event.couponId(), "couponId");
        validatePositive(event.userId(), "userId");
        validatePositive(event.campaignId(), "campaignId");
        validatePositive(event.stockId(), "stockId");
        if (event.idempotencyKey() == null || event.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey는 필수입니다.");
        }
        if (event.issuedAt() == null) {
            throw new IllegalArgumentException("issuedAt은 필수입니다.");
        }
    }

    private void validatePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + "는 양수여야 합니다.");
        }
    }
}
