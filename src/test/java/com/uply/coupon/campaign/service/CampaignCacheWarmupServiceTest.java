package com.uply.coupon.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.uply.coupon.campaign.domain.Campaign;
import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Redis 캐시 사전 데이터 적재 테스트 */
@ExtendWith(MockitoExtension.class)
class CampaignCacheWarmupServiceTest {

    @InjectMocks private CampaignCacheWarmupService campaignCacheWarmupService;

    @Mock private CampaignStockRepository campaignStockRepository;

    // 기발급 유저 Set 재구축에 쓰인다. 주입되지 않으면 warmupCampaign이 NPE로 죽는다.
    @Mock private CouponRepository couponRepository;

    @Mock private StringRedisTemplate redisTemplate;

    @Mock private ValueOperations<String, String> valueOperations;

    @Mock private SetOperations<String, String> setOperations;

    @Mock private CampaignStock stock1;

    @Mock private CampaignStock stock2;

    @Mock private Campaign campaign;

    @Test
    @DisplayName("캠페인 재고 목록이 정상 조회되면 Redis에 재고, stockId, 발급 키 설정이 실행된다.")
    void warmupCampaign_Success() {
        // given
        Long campaignId = 1L;
        LocalDateTime openAt = LocalDateTime.of(2026, 8, 17, 10, 0);
        LocalDateTime expireAt = LocalDateTime.of(2026, 8, 24, 10, 0);
        long openAtEpochMillis = openAt.toInstant(ZoneOffset.UTC).toEpochMilli();
        long expireAtEpochMillis = expireAt.toInstant(ZoneOffset.UTC).toEpochMilli();

        // Stock 1 데이터 설정
        given(stock1.getCampaign()).willReturn(campaign);
        given(campaign.getOpenAt()).willReturn(openAt);
        given(campaign.getExpireAt()).willReturn(expireAt);
        given(stock1.getId()).willReturn(100L);
        // 캐싱 기준은 총재고가 아니라 잔여 재고다 (재시작 후에도 소진분이 유지되어야 한다)
        given(stock1.getRemainingStock()).willReturn(500);
        given(stock1.getRouteId()).willReturn("ICN-NRT");
        given(stock1.getFareClass()).willReturn("Y");

        // Stock 2 데이터 설정
        given(stock2.getId()).willReturn(101L);
        given(stock2.getRemainingStock()).willReturn(300);
        given(stock2.getRouteId()).willReturn("ICN-HND");
        given(stock2.getFareClass()).willReturn("C");

        given(campaignStockRepository.findAllByCampaignId(campaignId))
                .willReturn(List.of(stock1, stock2));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        // when
        campaignCacheWarmupService.warmupCampaign(campaignId);

        // then
        verify(valueOperations)
                .set("campaign:1:openAt", String.valueOf(openAtEpochMillis), 24L, TimeUnit.HOURS);
        verify(valueOperations)
                .set(
                        "campaign:1:expireAt",
                        String.valueOf(expireAtEpochMillis),
                        24L,
                        TimeUnit.HOURS);

        // 1. Stock 1 저장 검증
        verify(valueOperations).set("stock:100", "500", 24L, TimeUnit.HOURS);
        verify(valueOperations).set("stockId:1:ICN-NRT:Y", "100", 24L, TimeUnit.HOURS);

        // 2. Stock 2 저장 검증
        verify(valueOperations).set("stock:101", "300", 24L, TimeUnit.HOURS);
        verify(valueOperations).set("stockId:1:ICN-HND:C", "101", 24L, TimeUnit.HOURS);

        // 3. DB에 발급 이력이 없으므로 issued Set은 남겨두지 않고 삭제되어야 한다.
        //    (SADD만 하던 예전 방식은 여기서 오염된 Set을 그대로 살려뒀다)
        verify(redisTemplate).delete("issued:1");
    }

    @Test
    @DisplayName("DB에 기발급 유저가 있으면 임시 키에 적재 후 RENAME으로 issued Set을 원자 교체한다.")
    void warmupCampaign_RebuildsIssuedSetAtomically() {
        // given
        Long campaignId = 1L;
        LocalDateTime openAt = LocalDateTime.of(2026, 8, 17, 10, 0);
        LocalDateTime expireAt = LocalDateTime.of(2026, 8, 24, 10, 0);

        given(stock1.getCampaign()).willReturn(campaign);
        given(campaign.getOpenAt()).willReturn(openAt);
        given(campaign.getExpireAt()).willReturn(expireAt);
        given(stock1.getId()).willReturn(100L);
        given(stock1.getRemainingStock()).willReturn(70);
        given(stock1.getRouteId()).willReturn("GMP-CJU");
        given(stock1.getFareClass()).willReturn("Y");

        given(campaignStockRepository.findAllByCampaignId(campaignId)).willReturn(List.of(stock1));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        given(couponRepository.findUserIdsByCampaignId(campaignId))
                .willReturn(List.of(100L, 101L, 102L));

        // when
        campaignCacheWarmupService.warmupCampaign(campaignId);

        // then
        // 기존 키에 덧칠하지 않고 임시 키에 쌓아야 한다 (SADD 대상이 issued:1이면 오염이 남는다)
        ArgumentCaptor<String> rebuildKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(setOperations).add(rebuildKeyCaptor.capture(), eq("100"), eq("101"), eq("102"));

        String rebuildKey = rebuildKeyCaptor.getValue();
        assertThat(rebuildKey).startsWith("issued:1:rebuild:");

        // TTL은 임시 키에 걸고, RENAME이 그 TTL을 그대로 옮겨간다
        verify(redisTemplate).expire(rebuildKey, 24L, TimeUnit.HOURS);
        verify(redisTemplate).rename(rebuildKey, "issued:1");
        verify(redisTemplate, never()).delete("issued:1");
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
