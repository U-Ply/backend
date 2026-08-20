package com.uply.coupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uply.coupon.campaign.repository.CampaignRepository;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class CampaignCouponRevokeServiceTest {

    private static final Long CAMPAIGN_ID = 10L;
    private static final String IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440000";
    private static final String HISTORY_KEY_PREFIX = "revoke-" + IDEMPOTENCY_KEY + "-";

    @Mock private CampaignRepository campaignRepository;
    @Mock private CouponRepository couponRepository;
    @Mock private CouponHistoryRepository couponHistoryRepository;
    @Mock private CampaignCouponRevokeChunkProcessor chunkProcessor;

    @InjectMocks private CampaignCouponRevokeService service;

    @BeforeEach
    void setUp() {
        when(campaignRepository.existsById(CAMPAIGN_ID)).thenReturn(true);
    }

    // 존재하지 않는 캠페인은 예외로 차단하고 쿠폰 조회/취소를 실행하지 않는지 확인
    @Test
    void rejectsUnknownCampaign() {
        when(campaignRepository.existsById(CAMPAIGN_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.revoke(CAMPAIGN_ID, IDEMPOTENCY_KEY))
                .isInstanceOf(CampaignNotFoundException.class);

        verify(couponRepository, never()).findIssuedCouponIdsByCampaignIdAfter(any(), any(), any());
        verify(chunkProcessor, never()).revokeChunk(any(), any());
    }

    // 캠페인은 존재하지만 취소할 ISSUED 쿠폰이 없으면 성공 건수 0을 반환하는지 확인
    @Test
    void returnsZeroWhenCampaignExistsWithoutIssuedCoupons() {
        when(couponHistoryRepository.countCampaignRevocationsByHistoryKeyPrefix(
                        CAMPAIGN_ID, HISTORY_KEY_PREFIX))
                .thenReturn(0L);
        when(couponRepository.findIssuedCouponIdsByCampaignIdAfter(
                        eq(CAMPAIGN_ID), eq(0L), any(Pageable.class)))
                .thenReturn(List.of());

        int revokedCount = service.revoke(CAMPAIGN_ID, IDEMPOTENCY_KEY);

        assertThat(revokedCount).isZero();
        verify(chunkProcessor, never()).revokeChunk(any(), any());
    }

    // 쿠폰을 couponId 기준 500건씩 keyset 방식으로 나누어 처리하는지 확인
    @Test
    void processesCouponsInFiveHundredItemKeysetChunks() {
        List<Long> firstChunk = LongStream.rangeClosed(1, 500).boxed().toList();
        List<Long> secondChunk = List.of(501L, 502L);
        when(couponHistoryRepository.countCampaignRevocationsByHistoryKeyPrefix(
                        CAMPAIGN_ID, HISTORY_KEY_PREFIX))
                .thenReturn(3L);
        when(couponRepository.findIssuedCouponIdsByCampaignIdAfter(
                        eq(CAMPAIGN_ID), eq(0L), any(Pageable.class)))
                .thenReturn(firstChunk);
        when(couponRepository.findIssuedCouponIdsByCampaignIdAfter(
                        eq(CAMPAIGN_ID), eq(500L), any(Pageable.class)))
                .thenReturn(secondChunk);
        when(couponRepository.findIssuedCouponIdsByCampaignIdAfter(
                        eq(CAMPAIGN_ID), eq(502L), any(Pageable.class)))
                .thenReturn(List.of());
        when(chunkProcessor.revokeChunk(firstChunk, HISTORY_KEY_PREFIX)).thenReturn(498);
        when(chunkProcessor.revokeChunk(secondChunk, HISTORY_KEY_PREFIX)).thenReturn(1);

        int revokedCount = service.revoke(CAMPAIGN_ID, IDEMPOTENCY_KEY);

        assertThat(revokedCount).isEqualTo(502);
        verify(chunkProcessor).revokeChunk(firstChunk, HISTORY_KEY_PREFIX);
        verify(chunkProcessor).revokeChunk(secondChunk, HISTORY_KEY_PREFIX);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(couponRepository, times(3))
                .findIssuedCouponIdsByCampaignIdAfter(
                        eq(CAMPAIGN_ID), any(), pageableCaptor.capture());
        assertThat(pageableCaptor.getAllValues())
                .allSatisfy(
                        pageable ->
                                assertThat(pageable.getPageSize())
                                        .isEqualTo(CampaignCouponRevokeService.CHUNK_SIZE));
    }

    // Redis 응답 캐시 실패 후 재요청하면 기존 이력에서 최초 취소 건수를 복원하는지 확인
    @Test
    void restoresPreviousRevokedCountFromHistoryOnRetry() {
        when(couponHistoryRepository.countCampaignRevocationsByHistoryKeyPrefix(
                        CAMPAIGN_ID, HISTORY_KEY_PREFIX))
                .thenReturn(100L);
        when(couponRepository.findIssuedCouponIdsByCampaignIdAfter(
                        eq(CAMPAIGN_ID), eq(0L), any(Pageable.class)))
                .thenReturn(List.of());

        int revokedCount = service.revoke(CAMPAIGN_ID, IDEMPOTENCY_KEY);

        assertThat(revokedCount).isEqualTo(100);
        verify(chunkProcessor, never()).revokeChunk(any(), any());
    }
}
