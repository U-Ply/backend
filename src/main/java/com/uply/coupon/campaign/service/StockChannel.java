package com.uply.coupon.campaign.service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class StockChannel {
    private final Long stockId;
    private final Long campaignId;
    private final String routeId;
    private final String fareClass;
    private final Integer totalStock;
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    private volatile Integer lastRemainingStock;
    private volatile Instant lastHeartbeatAt;

    public StockChannel(
            Long stockId,
            Long campaignId,
            String routeId,
            String fareClass,
            Integer totalStock,
            Integer initialRemainingStock) {
        this.stockId = stockId;
        this.campaignId = campaignId;
        this.routeId = routeId;
        this.fareClass = fareClass;
        this.totalStock = totalStock;
        this.lastRemainingStock = initialRemainingStock;
        this.lastHeartbeatAt = Instant.now();
    }

    public void addEmitter(String emitterId, SseEmitter emitter) {
        emitters.put(emitterId, emitter);
    }

    public void removeEmitter(String emitterId) {
        emitters.remove(emitterId);
    }

    public boolean isEmpty() {
        return emitters.isEmpty();
    }

    public Integer getLastRemainingStock() {
        return lastRemainingStock;
    }

    public void setLastRemainingStock(Integer lastRemainingStock) {
        this.lastRemainingStock = lastRemainingStock;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(Instant lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public Long getStockId() {
        return stockId;
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public String getRouteId() {
        return routeId;
    }

    public String getFareClass() {
        return fareClass;
    }

    public Integer getTotalStock() {
        return totalStock;
    }

    public Map<String, SseEmitter> getEmitters() {
        return emitters;
    }
}
