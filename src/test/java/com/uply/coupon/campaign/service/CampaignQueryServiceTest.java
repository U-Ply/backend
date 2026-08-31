package com.uply.coupon.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.uply.coupon.campaign.domain.Campaign;
import com.uply.coupon.campaign.domain.CampaignStatus;
import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.dto.response.CampaignDetailResponse;
import com.uply.coupon.campaign.dto.response.CampaignListResponse;
import com.uply.coupon.campaign.dto.response.CampaignStatusResponse;
import com.uply.coupon.campaign.repository.CampaignRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.exception.CampaignStockCacheMissException;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class CampaignQueryServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 21, 10, 0);

    @InjectMocks private CampaignQueryService campaignQueryService;

    @Mock private CampaignRepository campaignRepository;

    @Mock private CampaignStockRepository campaignStockRepository;

    @Mock private RemainingStockReader remainingStockReader;

    @Mock private Campaign campaign;

    @Mock private CampaignStock stock;

    // 목록 조회가 DB 서버 시각 기준으로 캠페인 상태(OPEN)를 계산해 응답에 담는지 검증한다.
    @Test
    void getCampaigns_calculatesStatusPerCampaign() {
        given(campaign.getId()).willReturn(1L);
        given(campaign.getName()).willReturn("제주 얼리버드 특가");
        given(campaign.getOpenAt()).willReturn(NOW.minusHours(1));
        given(campaign.getExpireAt()).willReturn(NOW.plusDays(1));
        given(campaignRepository.findAll(any(Sort.class))).willReturn(List.of(campaign));
        given(campaignRepository.currentDatabaseTime()).willReturn(NOW);

        CampaignListResponse response = campaignQueryService.getCampaigns();

        assertThat(response.campaigns()).hasSize(1);
        assertThat(response.campaigns().get(0).campaignId()).isEqualTo(1L);
        assertThat(response.campaigns().get(0).status()).isEqualTo(CampaignStatus.OPEN);
    }

    // 캠페인이 없으면 빈 배열을 응답하는지 검증한다.
    @Test
    void getCampaigns_emptyReturnsEmptyList() {
        given(campaignRepository.findAll(any(Sort.class))).willReturn(List.of());
        given(campaignRepository.currentDatabaseTime()).willReturn(NOW);

        CampaignListResponse response = campaignQueryService.getCampaigns();

        assertThat(response.campaigns()).isEmpty();
    }

    // 기본 정보 조회 시 재고 풀의 totalStock은 MySQL, remainingStock은 RemainingStockReader가 준 값을 그대로 담는지 검증
    @Test
    void getCampaign_returnsDetailWithRemainingStockFromReader() {
        given(campaignRepository.findById(1L)).willReturn(Optional.of(campaign));
        given(campaignRepository.currentDatabaseTime()).willReturn(NOW);
        given(campaign.getId()).willReturn(1L);
        given(campaign.getName()).willReturn("제주 얼리버드 특가");
        given(campaign.getOpenAt()).willReturn(NOW.minusHours(1));
        given(campaign.getExpireAt()).willReturn(NOW.plusDays(1));

        given(stock.getId()).willReturn(10L);
        given(stock.getRouteId()).willReturn("JEJU");
        given(stock.getFareClass()).willReturn("ECONOMY");
        given(stock.getTotalStock()).willReturn(8000);
        given(campaignStockRepository.findAllByCampaignIdOrderByRouteIdAscFareClassAsc(1L))
                .willReturn(List.of(stock));
        given(remainingStockReader.read(1L, 10L)).willReturn(1548);

        CampaignDetailResponse response = campaignQueryService.getCampaign(1L);

        assertThat(response.campaignId()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo(CampaignStatus.OPEN);
        assertThat(response.stocks()).hasSize(1);
        assertThat(response.stocks().get(0).routeId()).isEqualTo("JEJU");
        assertThat(response.stocks().get(0).totalStock()).isEqualTo(8000);
        assertThat(response.stocks().get(0).remainingStock()).isEqualTo(1548);
    }

    // 존재하지 않는 캠페인을 조회하면 CampaignNotFoundException을 던지는지 검증한다.
    @Test
    void getCampaign_missingCampaignThrowsNotFound() {
        given(campaignRepository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> campaignQueryService.getCampaign(1L))
                .isInstanceOf(CampaignNotFoundException.class);
    }

    // 재고 키가 Redis에 없으면(캐시 미스) CouponIssueException(CAMPAIGN_NOT_CACHED)으로 변환되고,
    // campaignId가 정확히 실려 있는지 검증한다(자동 복구 트리거가 이 값으로 캠페인을 특정한다).
    @Test
    void getCampaign_stockCacheMiss_throwsCouponIssueExceptionWithCampaignId() {
        given(campaignRepository.findById(1L)).willReturn(Optional.of(campaign));
        given(campaignRepository.currentDatabaseTime()).willReturn(NOW);

        given(stock.getId()).willReturn(10L);
        given(campaignStockRepository.findAllByCampaignIdOrderByRouteIdAscFareClassAsc(1L))
                .willReturn(List.of(stock));
        given(remainingStockReader.read(1L, 10L))
                .willThrow(new CampaignStockCacheMissException(10L));

        assertThatThrownBy(() -> campaignQueryService.getCampaign(1L))
                .isInstanceOf(CouponIssueException.class)
                .satisfies(
                        exception -> {
                            CouponIssueException issueException = (CouponIssueException) exception;
                            assertThat(issueException.getReason())
                                    .isEqualTo(IssueFailReason.CAMPAIGN_NOT_CACHED);
                            assertThat(issueException.getCampaignId()).isEqualTo(1L);
                        });
    }

    // 발급 현황 조회가 totalStock은 MySQL, remainingStock은 RemainingStockReader가 준 값을 그대로 사용하는지 검증
    @Test
    void getCampaignStatus_returnsRemainingStockFromReader() {
        given(campaignStockRepository.findByCampaignIdAndRouteIdAndFareClass(1L, "JEJU", "ECONOMY"))
                .willReturn(Optional.of(stock));
        given(stock.getId()).willReturn(10L);
        given(stock.getTotalStock()).willReturn(8000);
        given(remainingStockReader.read(1L, 10L)).willReturn(1548);

        CampaignStatusResponse response =
                campaignQueryService.getCampaignStatus(1L, "JEJU", "ECONOMY");

        assertThat(response.campaignId()).isEqualTo(1L);
        assertThat(response.totalStock()).isEqualTo(8000);
        assertThat(response.remainingStock()).isEqualTo(1548);
    }

    // 재고 키가 Redis에 없으면(캐시 미스) CouponIssueException(CAMPAIGN_NOT_CACHED)으로 변환되고,
    // campaignId가 정확히 실려 있는지 검증한다.
    @Test
    void getCampaignStatus_stockCacheMiss_throwsCouponIssueExceptionWithCampaignId() {
        given(campaignStockRepository.findByCampaignIdAndRouteIdAndFareClass(1L, "JEJU", "ECONOMY"))
                .willReturn(Optional.of(stock));
        given(stock.getId()).willReturn(10L);
        given(remainingStockReader.read(1L, 10L))
                .willThrow(new CampaignStockCacheMissException(10L));

        assertThatThrownBy(() -> campaignQueryService.getCampaignStatus(1L, "JEJU", "ECONOMY"))
                .isInstanceOf(CouponIssueException.class)
                .satisfies(
                        exception -> {
                            CouponIssueException issueException = (CouponIssueException) exception;
                            assertThat(issueException.getReason())
                                    .isEqualTo(IssueFailReason.CAMPAIGN_NOT_CACHED);
                            assertThat(issueException.getCampaignId()).isEqualTo(1L);
                        });
    }

    // 존재하지 않는 노선·좌석등급 조합이면 CampaignNotFoundException을 던지는지 검증한다.
    @Test
    void getCampaignStatus_missingStockThrowsNotFound() {
        given(campaignStockRepository.findByCampaignIdAndRouteIdAndFareClass(1L, "JEJU", "FIRST"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> campaignQueryService.getCampaignStatus(1L, "JEJU", "FIRST"))
                .isInstanceOf(CampaignNotFoundException.class);
    }
}
