package com.uply.coupon.campaign.service;

import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Redis 캐시 사전 데이터 적재 테스트
 */
@ExtendWith(MockitoExtension.class)
class CampaignCacheWarmupServiceTest {

    @InjectMocks
    private CampaignCacheWarmupService campaignCacheWarmupService;

    @Mock
    private CampaignStockRepository campaignStockRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private CampaignStock stock1;

    @Mock
    private CampaignStock stock2;

    @Test
    @DisplayName("캠페인 재고 목록이 정상 조회되면 Redis에 재고, stockId, 발급 키 설정이 실행된다.")
    void warmupCampaign_Success() {
        // given
        Long campaignId = 1L;

        // Stock 1 데이터 설정
        given(stock1.getId()).willReturn(100L);
        given(stock1.getTotalStock()).willReturn(500);
        given(stock1.getRouteId()).willReturn("ICN-NRT");
        given(stock1.getFareClass()).willReturn("Y");

        // Stock 2 데이터 설정
        given(stock2.getId()).willReturn(101L);
        given(stock2.getTotalStock()).willReturn(300);
        given(stock2.getRouteId()).willReturn("ICN-HND");
        given(stock2.getFareClass()).willReturn("C");

        given(campaignStockRepository.findAllByCampaignId(campaignId))
                .willReturn(List.of(stock1, stock2));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        // when
        campaignCacheWarmupService.warmupCampaign(campaignId);

        // then
        // 1. Stock 1 저장 검증
        verify(valueOperations).set("stock:100", "500", 24L, TimeUnit.HOURS);
        verify(valueOperations).set("stockId:1:ICN-NRT:Y", "100", 24L, TimeUnit.HOURS);

        // 2. Stock 2 저장 검증
        verify(valueOperations).set("stock:101", "300", 24L, TimeUnit.HOURS);
        verify(valueOperations).set("stockId:1:ICN-HND:C", "101", 24L, TimeUnit.HOURS);

        // 3. 발급내역(issued) Key TTL 설정 검증
        verify(redisTemplate).expire("issued:1", 24L, TimeUnit.HOURS);
    }

    @Test
    @DisplayName("캠페인 재고 목록이 비어있으면 Redis 상호작용 없이 조기 리턴된다.")
    void warmupCampaign_EmptyStocks_EarlyReturn() {
        // given
        Long campaignId = 1L;
        given(campaignStockRepository.findAllByCampaignId(campaignId))
                .willReturn(Collections.emptyList());

        // when
        campaignCacheWarmupService.warmupCampaign(campaignId);

        // then
        verifyNoInteractions(redisTemplate);
    }
}