package com.uply.coupon.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignCacheRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.exception.CampaignStockCacheMissException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RemainingStockReaderTest {

    private final CampaignCacheRepository campaignCacheRepository =
            mock(CampaignCacheRepository.class);
    private final CampaignStockRepository campaignStockRepository =
            mock(CampaignStockRepository.class);

    private RemainingStockReader reader(String strategy) {
        return new RemainingStockReader(campaignCacheRepository, campaignStockRepository, strategy);
    }

    // LUA_SCRIPT(V2/V3): Redis 카운터를 읽고 DB 는 건드리지 않는다.
    @Test
    void luaScript_readsFromRedisAndNotDatabase() {
        given(campaignCacheRepository.getRemainingStock(10L)).willReturn(42);

        assertThat(reader("LUA_SCRIPT").read(1L, 10L)).isEqualTo(42);
        verifyNoInteractions(campaignStockRepository);
    }

    // PESSIMISTIC_LOCK(V1): DB campaign_stocks.remaining_stock 를 읽고 Redis 는 건드리지 않는다.
    @Test
    void pessimisticLock_readsFromDatabaseAndNotRedis() {
        CampaignStock stock = mock(CampaignStock.class);
        given(stock.getRemainingStock()).willReturn(7);
        given(campaignStockRepository.findById(10L)).willReturn(Optional.of(stock));

        assertThat(reader("PESSIMISTIC_LOCK").read(1L, 10L)).isEqualTo(7);
        verifyNoInteractions(campaignCacheRepository);
    }

    // NO_LOCK(V0): PESSIMISTIC_LOCK 과 동일하게 DB 를 읽는다.
    @Test
    void noLock_readsFromDatabase() {
        CampaignStock stock = mock(CampaignStock.class);
        given(stock.getRemainingStock()).willReturn(3);
        given(campaignStockRepository.findById(10L)).willReturn(Optional.of(stock));

        assertThat(reader("NO_LOCK").read(1L, 10L)).isEqualTo(3);
    }

    // DB 전략에서 stockId 행이 없으면 CampaignNotFoundException 을 던진다(캐시 미스가 아니다).
    @Test
    void database_missingRow_throwsCampaignNotFound() {
        given(campaignStockRepository.findById(10L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> reader("PESSIMISTIC_LOCK").read(1L, 10L))
                .isInstanceOf(CampaignNotFoundException.class);
    }

    // LUA_SCRIPT 전략의 Redis 캐시 미스는 변환하지 않고 그대로 던진다(호출부가 503 으로 변환).
    @Test
    void luaScript_cacheMiss_propagatesRawException() {
        given(campaignCacheRepository.getRemainingStock(10L))
                .willThrow(new CampaignStockCacheMissException(10L));

        assertThatThrownBy(() -> reader("LUA_SCRIPT").read(1L, 10L))
                .isInstanceOf(CampaignStockCacheMissException.class);
    }

    // 알 수 없는 발급 전략이면 IllegalStateException 을 던진다.
    @Test
    void unknownStrategy_throwsIllegalState() {
        assertThatThrownBy(() -> reader("SOMETHING_ELSE").read(1L, 10L))
                .isInstanceOf(IllegalStateException.class);
    }
}
