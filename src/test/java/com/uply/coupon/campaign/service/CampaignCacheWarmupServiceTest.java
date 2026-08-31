package com.uply.coupon.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.uply.coupon.campaign.domain.Campaign;
import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.coupon.repository.CouponRepository;
import com.uply.coupon.operation.reconciliation.domain.KafkaSettlement;
import com.uply.coupon.operation.reconciliation.service.KafkaSettlementChecker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Redis 캐시 사전 데이터 적재 테스트 */
@ExtendWith(MockitoExtension.class)
class CampaignCacheWarmupServiceTest {

    @InjectMocks private CampaignCacheWarmupService campaignCacheWarmupService;

    @Mock private CampaignRepository campaignRepository;

    @Mock private CampaignStockRepository campaignStockRepository;

    @Mock private CouponRepository couponRepository;

    @Mock private StringRedisTemplate redisTemplate;

    @Mock private ValueOperations<String, String> valueOperations;

    @Mock private SetOperations<String, String> setOperations;

    @Mock private ObjectProvider<KafkaSettlementChecker> kafkaSettlementCheckerProvider;

    @Mock private KafkaSettlementChecker kafkaSettlementChecker;

    @Mock private CampaignStock stock1;

    @Mock private CampaignStock stock2;

    @Mock private Campaign campaign;

    @Spy private MeterRegistry meterRegistry = new SimpleMeterRegistry();

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
    @DisplayName("존재하는 캠페인에 재고 풀만 없으면 Redis 상호작용 없이 조기 리턴된다.")
    void warmupCampaign_ExistingCampaignWithNoStocks_EarlyReturn() {
        // given
        Long campaignId = 1L;
        given(campaignStockRepository.findAllByCampaignId(campaignId))
                .willReturn(Collections.emptyList());
        given(campaignRepository.existsById(campaignId)).willReturn(true);

        // when
        campaignCacheWarmupService.warmupCampaign(campaignId);

        // then
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("존재하지 않는 캠페인이면 CampaignNotFoundException을 던진다.")
    void warmupCampaign_CampaignDoesNotExist_ThrowsException() {
        // given
        Long campaignId = 999L;
        given(campaignStockRepository.findAllByCampaignId(campaignId))
                .willReturn(Collections.emptyList());
        given(campaignRepository.existsById(campaignId)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> campaignCacheWarmupService.warmupCampaign(campaignId))
                .isInstanceOf(CampaignNotFoundException.class)
                .hasMessageContaining(String.valueOf(campaignId));

        verifyNoInteractions(redisTemplate);
    }

    // recoverMissingCache: 운영 중 부분 유실 복구 — warmupCampaign과 달리 살아있는 키를
    // 절대 덮어쓰면 안 된다. 이 그룹의 테스트는 그 비파괴성을 직접 검증한다.

    @Test
    @DisplayName("recoverMissingCache는 SETNX로만 채우고, 덮어쓰기용 set()은 절대 호출하지 않는다")
    void recoverMissingCache_NeverOverwritesExistingKeys() {
        // given
        Long campaignId = 1L;
        givenSingleStockCampaign(campaignId);
        given(redisTemplate.hasKey("issued:1")).willReturn(true);

        // when
        campaignCacheWarmupService.recoverMissingCache(campaignId);

        // then
        verify(valueOperations).setIfAbsent(eq("campaign:1:openAt"), anyString());
        verify(valueOperations).setIfAbsent(eq("campaign:1:expireAt"), anyString());
        verify(valueOperations).setIfAbsent(eq("stock:100"), anyString());
        verify(valueOperations).setIfAbsent(eq("stockId:1:GMP-CJU:Y"), anyString());

        // warmupCampaign이 쓰는 무조건 덮어쓰기 경로는 이 메서드에서 절대 호출되면 안 된다.
        verify(valueOperations, never()).set(anyString(), anyString());
    }

    @Test
    @DisplayName("issued Set이 이미 있으면(0명인 정상 상태 포함) 손대지 않는다")
    void recoverMissingCache_IssuedSetAlreadyExists_LeavesItUntouched() {
        // given
        Long campaignId = 1L;
        givenSingleStockCampaign(campaignId);
        given(redisTemplate.hasKey("issued:1")).willReturn(true);

        // when
        campaignCacheWarmupService.recoverMissingCache(campaignId);

        // then
        verifyNoInteractions(couponRepository);
        verify(redisTemplate, never()).opsForSet();
    }

    @Test
    @DisplayName("issued Set이 없고 DB에 발급 이력이 있으면 SADD만으로 채운다 (DELETE·RENAME 없음)")
    void recoverMissingCache_IssuedSetMissing_RebuildsAdditively() {
        // given
        Long campaignId = 1L;
        givenSingleStockCampaign(campaignId);
        given(redisTemplate.hasKey("issued:1")).willReturn(false);
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        given(couponRepository.findUserIdsByCampaignId(campaignId)).willReturn(List.of(100L, 101L));

        // when
        campaignCacheWarmupService.recoverMissingCache(campaignId);

        // then
        verify(setOperations).add("issued:1", "100", "101");
        verify(redisTemplate, never()).delete(anyString());
        verify(redisTemplate, never()).rename(anyString(), anyString());
    }

    @Test
    @DisplayName("issued Set도 DB 발급 이력도 없으면 아무 키도 새로 만들지 않는다")
    void recoverMissingCache_NoIssuedSetAndNoDbHistory_CreatesNothing() {
        // given
        Long campaignId = 1L;
        givenSingleStockCampaign(campaignId);
        given(redisTemplate.hasKey("issued:1")).willReturn(false);
        given(couponRepository.findUserIdsByCampaignId(campaignId))
                .willReturn(Collections.emptyList());

        // when
        campaignCacheWarmupService.recoverMissingCache(campaignId);

        // then
        verify(redisTemplate, never()).opsForSet();
    }

    @Test
    @DisplayName("Kafka가 정착하지 않았으면 부분 복구도 거부한다")
    void recoverMissingCache_KafkaNotSettled_Rejected() {
        // given
        given(kafkaSettlementCheckerProvider.getIfAvailable()).willReturn(kafkaSettlementChecker);
        given(kafkaSettlementChecker.check()).willReturn(new KafkaSettlement(3L, 0L));

        // when & then
        assertThatThrownBy(() -> campaignCacheWarmupService.recoverMissingCache(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Kafka 미정착");

        verifyNoInteractions(campaignStockRepository);
    }

    @Test
    @DisplayName("자체 점검: 복구한 재고풀의 Redis 값이 DB와 정확히 일치하면 빈 리스트를 반환한다")
    void recoverMissingCache_ScopedCheck_PassesWhenValuesMatch() {
        // given
        Long campaignId = 1L;
        givenSingleStockCampaign(campaignId);
        given(redisTemplate.hasKey("issued:1")).willReturn(true);
        given(valueOperations.multiGet(List.of("stock:100"))).willReturn(List.of("70")); // DB와 동일

        // when
        List<String> mismatches = campaignCacheWarmupService.recoverMissingCache(campaignId);

        // then
        assertThat(mismatches).isEmpty();
    }

    // 리뷰에서 지적된 핵심 결함: recoverMissingCache는 트래픽 차단을 전제하지 않으므로,
    // 메서드 시작 시점 DB 스냅샷과 끝난 시점 Redis 현재값 사이에는 트래픽이 있는 한
    // 정상적인 시간차가 항상 생긴다(Redis가 Lua로 계속 더 감소함). 이 테스트가 원래는
    // "redis=65, db=70"을 불일치로 잘못 보고했었다 — 실시간 트래픽을 오탐한 것이다.
    @Test
    @DisplayName("자체 점검: Redis가 DB보다 더 감소해 있는 정상적인 시간차는 보고하지 않는다")
    void recoverMissingCache_ScopedCheck_DoesNotReportNormalLag() {
        // given — Redis(65)가 DB 스냅샷(70)보다 더 진행됨: 트래픽이 계속돼 DB가 못 따라온 정상 상태
        Long campaignId = 1L;
        givenSingleStockCampaign(campaignId);
        given(redisTemplate.hasKey("issued:1")).willReturn(true);
        given(valueOperations.multiGet(List.of("stock:100"))).willReturn(List.of("65"));

        // when
        List<String> mismatches = campaignCacheWarmupService.recoverMissingCache(campaignId);

        // then
        assertThat(mismatches).isEmpty();
    }

    @Test
    @DisplayName("자체 점검: Redis 재고가 DB보다 많으면(재고가 되살아난 방향) 초과 발급 위험으로 보고한다")
    void recoverMissingCache_ScopedCheck_ReportsMismatch_WhenRedisExceedsDb() {
        // given — Redis(999)가 DB(70)보다 많음: SETNX가 손대지 않은 기존 값이 부풀려져 있는 위험한 방향
        Long campaignId = 1L;
        givenSingleStockCampaign(campaignId);
        given(redisTemplate.hasKey("issued:1")).willReturn(true);
        given(valueOperations.multiGet(List.of("stock:100"))).willReturn(List.of("999"));

        // when
        List<String> mismatches = campaignCacheWarmupService.recoverMissingCache(campaignId);

        // then
        assertThat(mismatches).hasSize(1);
        assertThat(mismatches.get(0)).contains("stockId=100", "redis=999", "db=70");
    }

    @Test
    @DisplayName("자체 점검: multiGet 결과 수가 재고풀 수와 다르면(Redis 이상) 점검만 건너뛰고 복구는 실패시키지 않는다")
    void recoverMissingCache_ScopedCheck_SkipsWhenMultiGetSizeMismatches() {
        // given
        Long campaignId = 1L;
        givenSingleStockCampaign(campaignId);
        given(redisTemplate.hasKey("issued:1")).willReturn(true);
        given(valueOperations.multiGet(List.of("stock:100"))).willReturn(List.of());

        // when
        List<String> mismatches = campaignCacheWarmupService.recoverMissingCache(campaignId);

        // then — 예외 없이 정상 반환되고, 점검할 데이터가 없었으므로 위험 신호도 없다.
        assertThat(mismatches).isEmpty();
    }

    @Test
    @DisplayName("자체 점검은 지금 복구한 캠페인의 재고풀만 보고, 시스템 전체 REC-01 배치를 트리거하지 않는다")
    void recoverMissingCache_DoesNotTriggerSystemWideReconciliation() {
        // given
        Long campaignId = 1L;
        givenSingleStockCampaign(campaignId);
        given(redisTemplate.hasKey("issued:1")).willReturn(true);
        given(valueOperations.multiGet(List.of("stock:100"))).willReturn(List.of("70"));

        // when
        campaignCacheWarmupService.recoverMissingCache(campaignId);

        // then — campaign_stocks 전체를 훑는 배치용 JdbcTemplate 조회가 이 메서드 경로에는 없다.
        // campaignStockRepository(JPA)만 쓰였는지로 간접 확인한다.
        verify(campaignStockRepository).findAllByCampaignId(campaignId);
        verifyNoMoreInteractions(campaignStockRepository);
    }

    // 전 구간 모니터링용 지표: 워밍업이 끝나면 캠페인별 준비 완료 게이지가 뜨고,
    // openAt보다 먼저 끝났으면 lead.seconds가 양수여야 한다.
    @Test
    @DisplayName("워밍업 완료 후 캐시 준비 Gauge=1, 오픈 전 완료면 lead.seconds가 양수다")
    void warmupCampaign_registersCacheReadyGauge() {
        // given — openAt을 미래로 두어 "오픈 전 준비 완료" 상황을 만든다
        Long campaignId = 1L;
        LocalDateTime openAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30);
        given(stock1.getCampaign()).willReturn(campaign);
        given(campaign.getOpenAt()).willReturn(openAt);
        given(campaign.getExpireAt()).willReturn(openAt.plusDays(7));
        given(stock1.getId()).willReturn(100L);
        given(stock1.getRemainingStock()).willReturn(500);
        given(stock1.getRouteId()).willReturn("ICN-NRT");
        given(stock1.getFareClass()).willReturn("Y");
        given(campaignStockRepository.findAllByCampaignId(campaignId)).willReturn(List.of(stock1));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        // when
        campaignCacheWarmupService.warmupCampaign(campaignId);

        // then
        assertThat(
                        meterRegistry
                                .get("coupon.campaign.cache.ready")
                                .tag("campaign", "1")
                                .gauge()
                                .value())
                .isEqualTo(1.0);
        assertThat(
                        meterRegistry
                                .get("coupon.campaign.cache.ready.lead.seconds")
                                .tag("campaign", "1")
                                .gauge()
                                .value())
                .isPositive();
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
