package com.uply.coupon.campaign.service;

import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignCacheRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RemainingStockReader {

    private final CampaignCacheRepository campaignCacheRepository;
    private final CampaignStockRepository campaignStockRepository;
    private final String issueStrategy;

    public RemainingStockReader(
            CampaignCacheRepository campaignCacheRepository,
            CampaignStockRepository campaignStockRepository,
            @Value("${coupon.issue.strategy}") String issueStrategy) {
        this.campaignCacheRepository = campaignCacheRepository;
        this.campaignStockRepository = campaignStockRepository;
        this.issueStrategy = issueStrategy;
    }

    public Integer read(Long campaignId, Long stockId) {
        return switch (issueStrategy) {
            case "LUA_SCRIPT" -> campaignCacheRepository.getRemainingStock(stockId);
            case "NO_LOCK", "PESSIMISTIC_LOCK" ->
                    campaignStockRepository
                            .findById(stockId)
                            .map(CampaignStock::getRemainingStock)
                            .orElseThrow(() -> new CampaignNotFoundException(campaignId, stockId));
            default ->
                    throw new IllegalStateException("잔여 재고 조회 소스를 찾을 수 없는 발급 전략: " + issueStrategy);
        };
    }
}
