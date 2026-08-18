package com.uply.coupon.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

class StockIdLookupSelectorTest {

    private final StockIdLookup databaseStockIdLookup = mock(StockIdLookup.class);
    private final StockIdLookup redisStockIdLookup = mock(StockIdLookup.class);
    private final StockIdLookupSelector selector =
            new StockIdLookupSelector(databaseStockIdLookup, redisStockIdLookup);

    @Test
    void noLockUsesDatabaseLookup() {
        assertThat(selector.forStrategy("NO_LOCK")).isSameAs(databaseStockIdLookup);
    }

    @Test
    void pessimisticLockUsesDatabaseLookup() {
        assertThat(selector.forStrategy("PESSIMISTIC_LOCK")).isSameAs(databaseStockIdLookup);
    }

    @Test
    void luaScriptUsesRedisLookup() {
        assertThat(selector.forStrategy("LUA_SCRIPT")).isSameAs(redisStockIdLookup);
    }
}
