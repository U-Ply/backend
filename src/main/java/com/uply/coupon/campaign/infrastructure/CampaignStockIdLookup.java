package com.uply.coupon.campaign.infrastructure;

import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.campaign.service.StockIdLookup;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.exception.CampaignNotOpenException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class CampaignStockIdLookup implements StockIdLookup {

    private final CampaignStockRepository campaignStockRepository;

    @Override
    @Transactional(readOnly = true)
    public Long lookupStockId(Long campaignId, String routeId, String fareClass) {
        CampaignStock stock =
                campaignStockRepository
                        .findByCampaignIdAndRouteIdAndFareClass(campaignId, routeId, fareClass)
                        .orElseThrow(
                                () ->
                                        new CampaignNotFoundException(
                                                campaignId, routeId, fareClass));

        LocalDateTime databaseTime = campaignStockRepository.currentDatabaseTime();
        if (stock.getCampaign().getOpenAt().isAfter(databaseTime)) {
        	System.out.println("캠페인 시작 전이다.");
            throw new CampaignNotOpenException(campaignId, stock.getCampaign().getOpenAt());
        }

        return stock.getId();
    }
}
