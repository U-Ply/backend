package com.uply.coupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository.RouteFareProjection;
import com.uply.coupon.common.exception.CouponNotFoundException;
import com.uply.coupon.common.exception.CouponNotReadyException;
import com.uply.coupon.common.exception.UserNotFoundException;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.dto.response.CouponDetailResponse;
import com.uply.coupon.coupon.dto.response.UserCouponListResponse;
import com.uply.coupon.coupon.repository.CouponIssuanceProgressRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import com.uply.coupon.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponQueryServiceTest {

    private static final Long COUPON_ID = 1001L;
    private static final Long USER_ID = 10L;
    private static final Long CAMPAIGN_ID = 1L;
    private static final Long STOCK_ID = 3L;
    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 8, 19, 10, 0);
    private static final LocalDateTime EXPIRE_AT = ISSUED_AT.plusDays(7);

    @Mock private CouponRepository couponRepository;
    @Mock private CampaignStockRepository campaignStockRepository;
    @Mock private RouteFareProjection routeFareProjection;
    @Mock private UserRepository userRepository;
    @Mock private CouponIssuanceProgressRepository progressRepository;

    private CouponQueryService couponQueryService;

    @BeforeEach
    void setUp() {
        couponQueryService =
                new CouponQueryService(
                        couponRepository,
                        campaignStockRepository,
                        userRepository,
                        progressRepository);
    }

    // 쿠폰 단건 조회 시 현재 상태, 재고 정보, 미발생 상태 시각이 응답에 올바르게 매핑되는지 검증한다.
    @Test
    void couponDetailContainsCurrentStateAndStockInformation() {
        Coupon coupon = coupon();
        given(couponRepository.findById(COUPON_ID)).willReturn(Optional.of(coupon));
        given(campaignStockRepository.findRouteFareByStockIdAndCampaignId(STOCK_ID, CAMPAIGN_ID))
                .willReturn(Optional.of(routeFareProjection));
        given(routeFareProjection.getRouteId()).willReturn("ICN-JEJ");
        given(routeFareProjection.getFareClass()).willReturn("ECONOMY");

        CouponDetailResponse response = couponQueryService.getCoupon(COUPON_ID);

        assertThat(response.couponId()).isEqualTo(String.valueOf(COUPON_ID));
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.campaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(response.routeId()).isEqualTo("ICN-JEJ");
        assertThat(response.fareClass()).isEqualTo("ECONOMY");
        assertThat(response.status()).isEqualTo(CouponStatus.ISSUED);
        assertThat(response.usedAt()).isNull();
        assertThat(response.cancelledAt()).isNull();
        assertThat(response.expiredAt()).isNull();
        verify(campaignStockRepository, never())
                .decreaseRemainingStockIfAvailable(STOCK_ID, CAMPAIGN_ID);
    }

    // 쿠폰의 발급·사용·취소 시각과 만료 예정 시각이 UTC 응답 값으로 변환되는지 검증한다.
    @Test
    void couponDetailContainsStateTransitionTimestamps() {
        Coupon coupon = coupon();
        LocalDateTime usedAt = ISSUED_AT.plusMinutes(1);
        LocalDateTime cancelledAt = ISSUED_AT.plusMinutes(2);
        coupon.use(usedAt);
        coupon.cancel(cancelledAt);
        given(couponRepository.findById(COUPON_ID)).willReturn(Optional.of(coupon));
        given(campaignStockRepository.findRouteFareByStockIdAndCampaignId(STOCK_ID, CAMPAIGN_ID))
                .willReturn(Optional.of(routeFareProjection));

        CouponDetailResponse response = couponQueryService.getCoupon(COUPON_ID);

        assertThat(response.status()).isEqualTo(CouponStatus.CANCELLED);
        assertThat(response.issuedAt()).isEqualTo(ISSUED_AT.toInstant(ZoneOffset.UTC));
        assertThat(response.usedAt()).isEqualTo(usedAt.toInstant(ZoneOffset.UTC));
        assertThat(response.cancelledAt()).isEqualTo(cancelledAt.toInstant(ZoneOffset.UTC));
        assertThat(response.expiredAt()).isNull();
        assertThat(response.expireAt()).isEqualTo(EXPIRE_AT.toInstant(ZoneOffset.UTC));
    }

    // 최초 조회에 실패해도 재조회 중 쿠폰을 찾으면 정상 상세 응답을 반환하는지 검증한다.
    @Test
    void couponFoundDuringRetryIsReturned() {
        Coupon coupon = coupon();
        given(couponRepository.findById(COUPON_ID))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(coupon));
        given(progressRepository.isPending(COUPON_ID)).willReturn(true);
        given(campaignStockRepository.findRouteFareByStockIdAndCampaignId(STOCK_ID, CAMPAIGN_ID))
                .willReturn(Optional.of(routeFareProjection));
        given(routeFareProjection.getRouteId()).willReturn("ICN-JEJ");
        given(routeFareProjection.getFareClass()).willReturn("ECONOMY");

        CouponDetailResponse response = couponQueryService.getCoupon(COUPON_ID);

        assertThat(response.couponId()).isEqualTo(String.valueOf(COUPON_ID));
        verify(couponRepository, times(2)).findById(COUPON_ID);
    }

    // 쿠폰을 네 번 모두 찾지 못하면 COUPON_NOT_READY 예외가 발생하는지 검증한다.
    @Test
    void couponMissingAfterFourLookupsReturnsNotReady() {
        given(couponRepository.findById(COUPON_ID)).willReturn(Optional.empty());
        given(progressRepository.isPending(COUPON_ID)).willReturn(true);

        assertThatThrownBy(() -> couponQueryService.getCoupon(COUPON_ID))
                .isInstanceOf(CouponNotReadyException.class)
                .hasMessageContaining(String.valueOf(COUPON_ID));
        verify(couponRepository, times(4)).findById(COUPON_ID);
    }

    // DB와 Redis pending 키에 모두 없는 couponId는 COUPON_NOT_FOUND로 처리하는지 검증한다.
    @Test
    void couponMissingWithoutPendingStateReturnsNotFound() {
        given(couponRepository.findById(COUPON_ID)).willReturn(Optional.empty());
        given(progressRepository.isPending(COUPON_ID)).willReturn(false);

        assertThatThrownBy(() -> couponQueryService.getCoupon(COUPON_ID))
                .isInstanceOf(CouponNotFoundException.class)
                .hasMessageContaining(String.valueOf(COUPON_ID));
        verify(couponRepository, times(2)).findById(COUPON_ID);
    }

    // 첫 조회 직후 Consumer가 커밋하고 pending 키를 삭제한 경우 DB 재확인으로 쿠폰을 반환하는지 검증한다.
    @Test
    void couponCommittedWhilePendingStateIsClearedIsReturned() {
        Coupon coupon = coupon();
        given(couponRepository.findById(COUPON_ID))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(coupon));
        given(progressRepository.isPending(COUPON_ID)).willReturn(false);
        given(campaignStockRepository.findRouteFareByStockIdAndCampaignId(STOCK_ID, CAMPAIGN_ID))
                .willReturn(Optional.of(routeFareProjection));

        CouponDetailResponse response = couponQueryService.getCoupon(COUPON_ID);

        assertThat(response.couponId()).isEqualTo(String.valueOf(COUPON_ID));
        verify(couponRepository, times(2)).findById(COUPON_ID);
    }

    // 요청한 사용자의 쿠폰만 목록 응답 DTO로 변환하고 단건 조회는 실행하지 않는지 검증한다.
    @Test
    void onlyRequestedUsersCouponsAreMapped() {
        Coupon coupon = coupon();
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(couponRepository.findAllByUserIdOrderByIssuedAtDescCouponIdDesc(USER_ID))
                .willReturn(List.of(coupon));

        UserCouponListResponse response = couponQueryService.getUserCoupons(USER_ID);

        assertThat(response.coupons()).hasSize(1);
        assertThat(response.coupons().get(0).couponId()).isEqualTo(String.valueOf(COUPON_ID));
        assertThat(response.coupons().get(0).campaignId()).isEqualTo(CAMPAIGN_ID);
        verify(couponRepository).findAllByUserIdOrderByIssuedAtDescCouponIdDesc(USER_ID);
        verify(couponRepository, never()).findById(COUPON_ID);
    }

    // 보유 쿠폰이 없는 사용자는 재조회 없이 빈 목록을 반환하는지 검증한다.
    @Test
    void userWithoutCouponsReturnsEmptyListWithoutRetry() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(couponRepository.findAllByUserIdOrderByIssuedAtDescCouponIdDesc(USER_ID))
                .willReturn(List.of());

        UserCouponListResponse response = couponQueryService.getUserCoupons(USER_ID);

        assertThat(response.coupons()).isEmpty();
        verify(couponRepository).findAllByUserIdOrderByIssuedAtDescCouponIdDesc(USER_ID);
    }

    // 존재하지 않는 사용자는 USER_NOT_FOUND 예외를 발생시키고 쿠폰 목록을 조회하지 않는지 검증한다.
    @Test
    void missingUserReturnsNotFoundWithoutCouponLookup() {
        given(userRepository.existsById(USER_ID)).willReturn(false);

        assertThatThrownBy(() -> couponQueryService.getUserCoupons(USER_ID))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining(String.valueOf(USER_ID));
        verify(couponRepository, never()).findAllByUserIdOrderByIssuedAtDescCouponIdDesc(USER_ID);
    }

    private Coupon coupon() {
        return Coupon.issue(COUPON_ID, USER_ID, CAMPAIGN_ID, STOCK_ID, ISSUED_AT, EXPIRE_AT);
    }
}
