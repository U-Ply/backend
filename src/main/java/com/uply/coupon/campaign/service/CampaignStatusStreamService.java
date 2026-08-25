package com.uply.coupon.campaign.service;

import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.dto.response.CampaignStatusResponse;
import com.uply.coupon.campaign.repository.CampaignCacheRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignStatusStreamService {
    private final CampaignStockRepository campaignStockRepository;
    private final CampaignCacheRepository campaignCacheRepository;

    private final Map<Long, StockChannel> channels = new ConcurrentHashMap<>();

    @Value("${coupon.status-stream.timeout-ms:1800000}")
    private long timeoutMs;

    @Value("${coupon.status-stream.heartbeat-interval-ms:15000}")
    private long heartbeatIntervalMs;

    @Value("${coupon.status-stream.reconnect-time-ms:3000}")
    private long reconnectTimeMs;

    public SseEmitter subscribe(Long campaignId, String routeId, String fareClass) {
        CampaignStock stock =
                campaignStockRepository
                        .findByCampaignIdAndRouteIdAndFareClass(campaignId, routeId, fareClass)
                        .orElseThrow(
                                () ->
                                        new CampaignNotFoundException(
                                                campaignId, routeId, fareClass));

        Integer remainingStock = campaignCacheRepository.getRemainingStock(stock.getId());

        StockChannel channel =
                channels.computeIfAbsent(
                        stock.getId(),
                        id ->
                                new StockChannel(
                                        stock.getId(),
                                        campaignId,
                                        stock.getRouteId(),
                                        stock.getFareClass(),
                                        stock.getTotalStock(),
                                        remainingStock));

        SseEmitter emitter = new SseEmitter(timeoutMs);
        String emitterId = UUID.randomUUID().toString();

        Runnable cleanup = () -> removeEmitter(channel, emitterId);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());

        channel.addEmitter(emitterId, emitter);
        sendEvent(emitter, "stock-update", toResponse(channel));

        return emitter;
    }

    @Scheduled(fixedDelayString = "${coupon.status-stream.poll-interval-ms:1000}")
    public void pollStocks() {
        Instant now = Instant.now();
        for (StockChannel channel : channels.values()) {
            try {
                pollChannel(channel, now);
            } catch (Exception e) {
                // 한 재고 풀 처리 중 실패해도 나머지 채널 polling은 계속돼야 한다.
                log.warn("재고 현황 polling 실패: stockId={}", channel.getStockId(), e);
            }
        }
    }

    void pollChannel(StockChannel channel, Instant now) {
        Integer currentRemainingStock;
        try {
            currentRemainingStock = campaignCacheRepository.getRemainingStock(channel.getStockId());
        } catch (IllegalStateException e) {
            // Redis 캐시가 일시적으로 비어있는 경우: 이번 tick만 건너뛰고 다음 tick에서 재시도
            log.warn("재고 캐시 조회 실패, 다음 polling에서 재시도: stockId={}", channel.getStockId(), e);
            return;
        }

        if (!Objects.equals(currentRemainingStock, channel.getLastRemainingStock())) {
            channel.setLastRemainingStock(currentRemainingStock);
            broadcast(channel, "stock-update", toResponse(channel));
        }

        if (Duration.between(channel.getLastHeartbeatAt(), now).toMillis() >= heartbeatIntervalMs) {
            channel.setLastHeartbeatAt(now);
            broadcast(channel, "heartbeat", new HeartbeatPayload(now.toString()));
        }
    }

    private void broadcast(StockChannel channel, String eventName, Object payload) {
        channel.getEmitters()
                .forEach((emitterId, emitter) -> sendEvent(emitter, eventName, payload));
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name(eventName)
                            .id(String.valueOf(Instant.now().toEpochMilli()))
                            .reconnectTime(reconnectTimeMs)
                            .data(payload, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("SSE 이벤트 전송 실패, emitter는 onError 콜백에서 정리됨: eventName={}", eventName, e);
        }
    }

    private void removeEmitter(StockChannel channel, String emitterId) {
        channel.removeEmitter(emitterId);
        if (channel.isEmpty()) {
            channels.remove(channel.getStockId(), channel);
        }
    }

    private CampaignStatusResponse toResponse(StockChannel channel) {
        return new CampaignStatusResponse(
                channel.getCampaignId(),
                channel.getRouteId(),
                channel.getFareClass(),
                channel.getTotalStock(),
                channel.getLastRemainingStock());
    }

    private record HeartbeatPayload(String timestamp) {}
}
