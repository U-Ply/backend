package com.uply.coupon.campaign.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.strategy.IssueFailReason;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RedisStockIdLookupTest {

    @InjectMocks private RedisStockIdLookup redisStockIdLookup;

    @Mock private StringRedisTemplate redisTemplate;

    @Mock private ValueOperations<String, String> valueOperations;
    
    @Mock private CampaignStockRepository campaignStockRepository;

    @Test
    @DisplayName("Redis에 stockId 키가 존재하면 Long 타입의 stockId를 정상 반환한다.")
    void lookupStockId_Success() {
        // given
        Long campaignId = 1L;
        String routeId = "ICN-NRT";
        String fareClass = "Y";
        String expectedKey = "stockId:1:ICN-NRT:Y";

        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(expectedKey)).willReturn("100");

        // when
        Long stockId = redisStockIdLookup.lookupStockId(campaignId, routeId, fareClass);

        // then
        assertThat(stockId).isEqualTo(100L);
        verify(valueOperations).get(expectedKey);
        verifyNoInteractions(campaignStockRepository);
    }

    @Test
    @DisplayName("Redis에 stockId 키가 없으나(null) DB에 존재할 경우 CouponIssueException(CAMPAIGN_NOT_CACHED)이 발생한다.")
    void lookupStockId_NotFound_ThrowsException() {
        // given
        Long campaignId = 1L;
        String routeId = "ICN-NRT";
        String fareClass = "Y";
        String expectedKey = "stockId:1:ICN-NRT:Y";

        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(expectedKey)).willReturn(null);
        // DB에는 존재한다는 가정
        given(campaignStockRepository.existsByCampaignIdAndRouteIdAndFareClass(campaignId, routeId, fareClass))
        .willReturn(true);

        // when & then
        assertThatThrownBy(() -> redisStockIdLookup.lookupStockId(campaignId, routeId, fareClass))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.CAMPAIGN_NOT_CACHED);

        verify(valueOperations).get(expectedKey);
        verify(campaignStockRepository).existsByCampaignIdAndRouteIdAndFareClass(campaignId, routeId, fareClass);
    }
    
    @Test
    @DisplayName("Redis와 DB 모두에 존재하지 않을 경우 CampaignNotFoundException이 발생한다.")
    void lookupStockId_CacheMiss_NotInDb_ThrowsCampaignNotFoundException() {
        // given
        Long campaignId = 1L;
        String routeId = "ICN-NRT";
        String fareClass = "Y";
        String expectedKey = "stockId:1:ICN-NRT:Y";

        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(expectedKey)).willReturn(null);
        given(campaignStockRepository.existsByCampaignIdAndRouteIdAndFareClass(campaignId, routeId, fareClass))
                .willReturn(false);

        // when & then
        assertThatThrownBy(() -> redisStockIdLookup.lookupStockId(campaignId, routeId, fareClass))
                .isInstanceOf(CampaignNotFoundException.class);

        verify(valueOperations).get(expectedKey);
        verify(campaignStockRepository).existsByCampaignIdAndRouteIdAndFareClass(campaignId, routeId, fareClass);
    }
}
