package com.uply.coupon.campaign.service;

import com.uply.coupon.campaign.domain.Campaign;
import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.dto.response.CampaignDetailResponse;
import com.uply.coupon.campaign.dto.response.CampaignListResponse;
import com.uply.coupon.campaign.dto.response.CampaignStatusResponse;
import com.uply.coupon.campaign.dto.response.CampaignStockSummaryResponse;
import com.uply.coupon.campaign.repository.CampaignCacheRepository;
import com.uply.coupon.campaign.repository.CampaignRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.exception.CampaignStockCacheMissException;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CampaignQueryService {

    private final CampaignRepository campaignRepository;
    private final CampaignStockRepository campaignStockRepository;
    private final CampaignCacheRepository campaignCacheRepository;

    public CampaignListResponse getCampaigns() {
        List<Campaign> campaigns =
                campaignRepository.findAll(Sort.by(Sort.Direction.ASC, "openAt"));
        LocalDateTime now = campaignRepository.currentDatabaseTime();
        return CampaignListResponse.of(campaigns, now);
    }

    public CampaignDetailResponse getCampaign(Long campaignId) {
        Campaign campaign =
                campaignRepository
                        .findById(campaignId)
                        .orElseThrow(() -> new CampaignNotFoundException(campaignId));
        LocalDateTime now = campaignRepository.currentDatabaseTime();

        List<CampaignStockSummaryResponse> stocks =
                campaignStockRepository
                        .findAllByCampaignIdOrderByRouteIdAscFareClassAsc(campaignId)
                        .stream()
                        .map(stock -> toStockSummary(campaignId, stock))
                        .toList();

        return CampaignDetailResponse.of(campaign, now, stocks);
    }

    public CampaignStatusResponse getCampaignStatus(
            Long campaignId, String routeId, String fareClass) {
        CampaignStock stock =
                campaignStockRepository
                        .findByCampaignIdAndRouteIdAndFareClass(campaignId, routeId, fareClass)
                        .orElseThrow(
                                () ->
                                        new CampaignNotFoundException(
                                                campaignId, routeId, fareClass));

        Integer remainingStock = getRemainingStockOrThrow(campaignId, stock.getId());
        return new CampaignStatusResponse(
                campaignId,
                stock.getRouteId(),
                stock.getFareClass(),
                stock.getTotalStock(),
                remainingStock);
    }

    private CampaignStockSummaryResponse toStockSummary(Long campaignId, CampaignStock stock) {
        Integer remainingStock = getRemainingStockOrThrow(campaignId, stock.getId());
        return new CampaignStockSummaryResponse(
                stock.getRouteId(), stock.getFareClass(), stock.getTotalStock(), remainingStock);
    }

    // Repository는 stockId만 알고 있어 campaignId를 붙일 수 없다. 자동 복구 트리거와 HTTP
    // 정책(503 CAMPAIGN_NOT_CACHED)이 campaignId 단위로 동작하므로 여기서 붙여준다.
    private Integer getRemainingStockOrThrow(Long campaignId, Long stockId) {
        try {
            return campaignCacheRepository.getRemainingStock(stockId);
        } catch (CampaignStockCacheMissException exception) {
            throw new CouponIssueException(IssueFailReason.CAMPAIGN_NOT_CACHED, campaignId);
        }
    }
}
