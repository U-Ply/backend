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
            clearPendingSafely(event.couponId());
            log.info("중복 쿠폰 발급 이벤트를 건너뜁니다. couponId={}", event.couponId());
            return false;
        }

        try {
            persistenceService.persist(event);
            clearPendingSafely(event.couponId());
            return true;
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicate(event)) {
                clearPendingSafely(event.couponId());
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

    private void clearPendingSafely(Long couponId) {
        try {
            progressRepository.clear(couponId);
        } catch (RuntimeException exception) {
            // DB 반영은 이미 커밋됐다. Redis 정리 실패를 Kafka 처리 실패로 전파하면
            // 정상 저장된 이벤트가 불필요하게 재시도되거나 DLT로 이동하므로 TTL 정리에 맡긴다.
            log.warn("pending 키 삭제에 실패했습니다. TTL 만료로 정리됩니다. couponId={}", couponId, exception);
        }
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
        if (event.expireAt() == null) {
            throw new IllegalArgumentException("expireAt은 필수입니다.");
        }
        // publishedAt은 검증하지 않는다.
        // 저장에 쓰이지 않는 측정 전용 필드이고, 필드 추가 이전에 발행된 메시지에는 값이 없다.
    }

    private void validatePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + "는 양수여야 합니다.");
        }
    }
}
