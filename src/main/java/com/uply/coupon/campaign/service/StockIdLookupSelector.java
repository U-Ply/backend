package com.uply.coupon.campaign.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** 발급 전략별 stockId 조회 저장소를 선택한다. */
@Component
public class StockIdLookupSelector {

    private final StockIdLookup databaseStockIdLookup;
    private final StockIdLookup redisStockIdLookup;

    public StockIdLookupSelector(
            @Qualifier("campaignStockIdLookup") StockIdLookup databaseStockIdLookup,
            @Qualifier("redisStockIdLookup") StockIdLookup redisStockIdLookup) {
        this.databaseStockIdLookup = databaseStockIdLookup;
        this.redisStockIdLookup = redisStockIdLookup;
    }

    public StockIdLookup forStrategy(String strategyName) {
        return switch (strategyName) {
            case "NO_LOCK", "PESSIMISTIC_LOCK" -> databaseStockIdLookup;
            case "LUA_SCRIPT" -> redisStockIdLookup;
            default ->
                    throw new IllegalArgumentException(
                            "stockId 조회 방식을 찾을 수 없는 발급 전략: " + strategyName);
        };
    }
}
