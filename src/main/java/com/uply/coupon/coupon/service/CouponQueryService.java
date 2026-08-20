package com.uply.coupon.coupon.service;

import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository.RouteFareProjection;
import com.uply.coupon.common.exception.CouponNotReadyException;
import com.uply.coupon.common.exception.UserNotFoundException;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.dto.response.CouponDetailResponse;
import com.uply.coupon.coupon.dto.response.UserCouponListResponse;
import com.uply.coupon.coupon.repository.CouponRepository;
import com.uply.coupon.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CouponQueryService {

    private static final int MAX_LOOKUP_ATTEMPTS = 4;
    private static final long LOOKUP_INTERVAL_MILLIS = 100L;

    private final CouponRepository couponRepository;
    private final CampaignStockRepository campaignStockRepository;
    private final UserRepository userRepository;

    public CouponDetailResponse getCoupon(Long couponId) {
        Coupon coupon = findCouponWithRetry(couponId);
        RouteFareProjection routeFare =
                campaignStockRepository
                        .findRouteFareByStockIdAndCampaignId(
                                coupon.getStockId(), coupon.getCampaignId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Coupon stock information not found: stockId="
                                                        + coupon.getStockId()
                                                        + ", campaignId="
                                                        + coupon.getCampaignId()));

        return CouponDetailResponse.of(coupon, routeFare.getRouteId(), routeFare.getFareClass());
    }

    @Transactional(readOnly = true)
    public UserCouponListResponse getUserCoupons(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException(userId);
        }

        List<Coupon> coupons =
                couponRepository.findAllByUserIdOrderByIssuedAtDescCouponIdDesc(userId);
        return UserCouponListResponse.from(coupons);
    }

    private Coupon findCouponWithRetry(Long couponId) {
        for (int attempt = 0; attempt < MAX_LOOKUP_ATTEMPTS; attempt++) {
            Optional<Coupon> coupon = couponRepository.findById(couponId);
            if (coupon.isPresent()) {
                return coupon.get();
            }

            if (attempt < MAX_LOOKUP_ATTEMPTS - 1) {
                waitForNextLookup();
            }
        }

        throw new CouponNotReadyException(couponId);
    }

    private void waitForNextLookup() {
        try {
            Thread.sleep(LOOKUP_INTERVAL_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Coupon lookup retry was interrupted.", exception);
        }
    }
}
