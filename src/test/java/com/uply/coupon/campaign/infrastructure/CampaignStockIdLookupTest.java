package com.uply.coupon.campaign.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.uply.coupon.campaign.domain.Campaign;
import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.exception.CampaignNotOpenException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CampaignStockIdLookupTest {

    @InjectMocks private CampaignStockIdLookup stockIdLookup;

    @Mock private CampaignStockRepository campaignStockRepository;

    @Mock private CampaignStock stock;

    @Mock private Campaign campaign;

    @Test
    void openedCampaignReturnsStockId() {
        LocalDateTime databaseTime = LocalDateTime.of(2026, 8, 15, 18, 0);
        given(campaignStockRepository.findByCampaignIdAndRouteIdAndFareClass(1L, "JEJU", "ECONOMY"))
                .willReturn(Optional.of(stock));
        given(campaignStockRepository.currentDatabaseTime()).willReturn(databaseTime);
        given(stock.getCampaign()).willReturn(campaign);
        given(campaign.getOpenAt()).willReturn(databaseTime.minusMinutes(1));
        given(stock.getId()).willReturn(10L);

        assertThat(stockIdLookup.lookupStockId(1L, "JEJU", "ECONOMY")).isEqualTo(10L);
    }

    @Test
    void missingCampaignStockThrowsNotFound() {
        given(campaignStockRepository.findByCampaignIdAndRouteIdAndFareClass(1L, "JEJU", "ECONOMY"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> stockIdLookup.lookupStockId(1L, "JEJU", "ECONOMY"))
                .isInstanceOf(CampaignNotFoundException.class);
    }

    @Test
    void campaignBeforeOpenTimeIsRejected() {
        LocalDateTime databaseTime = LocalDateTime.of(2026, 8, 15, 18, 0);
        given(campaignStockRepository.findByCampaignIdAndRouteIdAndFareClass(1L, "JEJU", "ECONOMY"))
                .willReturn(Optional.of(stock));
        given(campaignStockRepository.currentDatabaseTime()).willReturn(databaseTime);
        given(stock.getCampaign()).willReturn(campaign);
        given(campaign.getOpenAt()).willReturn(databaseTime.plusMinutes(1));

        assertThatThrownBy(() -> stockIdLookup.lookupStockId(1L, "JEJU", "ECONOMY"))
                .isInstanceOf(CampaignNotOpenException.class);
    }
}
