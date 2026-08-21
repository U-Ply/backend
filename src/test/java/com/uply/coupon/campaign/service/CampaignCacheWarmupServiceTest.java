package com.uply.coupon.campaign.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.uply.coupon.campaign.domain.Campaign;
import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import com.uply.coupon.operation.reconciliation.domain.KafkaSettlement;
import com.uply.coupon.operation.reconciliation.service.KafkaSettlementChecker;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
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

    // V3에서만 빈이 존재하므로 ObjectProvider로 주입된다.
    @Mock private ObjectProvider<KafkaSettlementChecker> kafkaSettlementCheckerProvider;

    @Mock private KafkaSettlementChecker kafkaSettlementChecker;

    @Mock private CampaignStock stock1;

    @Mock private CampaignStock stock2;

    @Mock private Campaign campaign;

    @Test
    @DisplayName("캠페인 재고 목록이 정상 조회되면 재고·stockId·캠페인 시각이 TTL 없이 적재된다.")
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
        // 고정 TTL을 걸면 캠페인이 그보다 길 때 발급 도중 키가 사라진다.
        // 2인자 set()으로 호출되어야 하며, TTL 인자가 붙은 호출이 있으면 안 된다.
        verify(valueOperations).set("campaign:1:openAt", String.valueOf(openAtEpochMillis));
        verify(valueOperations).set("campaign:1:expireAt", String.valueOf(expireAtEpochMillis));

        verify(valueOperations).set("stock:100", "500");
        verify(valueOperations).set("stockId:1:ICN-NRT:Y", "100");
        verify(valueOperations).set("stock:101", "300");
        verify(valueOperations).set("stockId:1:ICN-HND:C", "101");

        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any());
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any());

        // DB에 발급 이력이 없으므로 issued Set은 남겨두지 않고 삭제되어야 한다.
        // (SADD만 하던 예전 방식은 여기서 오염된 Set을 그대로 살려뒀다)
        verify(redisTemplate).delete("issued:1");
    }

    @Test
    @DisplayName("DB에 기발급 유저가 있으면 임시 키에 적재 후 RENAME으로 issued Set을 원자 교체한다.")
    void warmupCampaign_RebuildsIssuedSetAtomically() {
        // given
        Long campaignId = 1L;
        givenSingleStockCampaign(campaignId);
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        given(couponRepository.findUserIdsByCampaignId(campaignId))
                .willReturn(List.of(100L, 101L, 102L));

        // when
        campaignCacheWarmupService.warmupCampaign(campaignId);

        // then
        // 이전 회차가 남긴 임시 키를 먼저 지운 뒤 임시 키에 쌓아야 한다.
        // SADD 대상이 issued:1이면 DB에 없는 유저가 그대로 살아남는다.
        verify(redisTemplate).delete("temp:issued:1");
        verify(setOperations).add("temp:issued:1", "100", "101", "102");
        verify(redisTemplate).rename("temp:issued:1", "issued:1");
        verify(redisTemplate, never()).delete("issued:1");
    }

    @Test
    @DisplayName("Kafka가 정착하지 않았으면(lag 또는 DLT 잔존) 웜업을 거부한다.")
    void warmupCampaign_KafkaNotSettled_Rejected() {
        // given
        given(kafkaSettlementCheckerProvider.getIfAvailable()).willReturn(kafkaSettlementChecker);
        given(kafkaSettlementChecker.check()).willReturn(new KafkaSettlement(7L, 0L));

        // when & then
        // lag이 남은 상태에서 DB 기준으로 Redis를 덮어쓰면 아직 DB에 없는 발급분이
        // issued Set에서 사라져 같은 유저가 다시 발급받을 수 있다.
        assertThatThrownBy(() -> campaignCacheWarmupService.warmupCampaign(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Kafka 미정착");

        verifyNoInteractions(campaignStockRepository);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("Kafka lag와 DLT가 모두 0이면 웜업을 진행한다.")
    void warmupCampaign_KafkaSettled_Proceeds() {
        // given
        Long campaignId = 1L;
        given(kafkaSettlementCheckerProvider.getIfAvailable()).willReturn(kafkaSettlementChecker);
        given(kafkaSettlementChecker.check()).willReturn(new KafkaSettlement(0L, 0L));
        givenSingleStockCampaign(campaignId);

        // when
        campaignCacheWarmupService.warmupCampaign(campaignId);

        // then
        verify(campaignStockRepository).findAllByCampaignId(campaignId);
        verify(redisTemplate).delete("issued:1");
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

    /** 재고 풀 하나짜리 정상 캠페인 스텁 (오픈·만료 시각 포함) */
    private void givenSingleStockCampaign(Long campaignId) {
        given(stock1.getCampaign()).willReturn(campaign);
        given(campaign.getOpenAt()).willReturn(LocalDateTime.of(2026, 8, 17, 10, 0));
        given(campaign.getExpireAt()).willReturn(LocalDateTime.of(2026, 8, 24, 10, 0));
        given(stock1.getId()).willReturn(100L);
        given(stock1.getRemainingStock()).willReturn(70);
        given(stock1.getRouteId()).willReturn("GMP-CJU");
        given(stock1.getFareClass()).willReturn("Y");

        given(campaignStockRepository.findAllByCampaignId(campaignId)).willReturn(List.of(stock1));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
    }
}
