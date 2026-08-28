package com.uply.coupon.campaign.service;

import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.dto.response.CampaignStatusResponse;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.exception.CampaignStockCacheMissException;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
    private final RemainingStockReader remainingStockReader;

    private final ObjectProvider<CacheAutoRecoveryTrigger> cacheAutoRecoveryTriggerProvider;

    private final MeterRegistry meterRegistry;

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

        Integer remainingStock;
        try {
            remainingStock = remainingStockReader.read(campaignId, stock.getId());
        } catch (CampaignStockCacheMissException exception) {
            throw new CouponIssueException(IssueFailReason.CAMPAIGN_NOT_CACHED, campaignId);
        }

        SseEmitter emitter = new SseEmitter(timeoutMs);
        String emitterId = UUID.randomUUID().toString();

        StockChannel channel =
                channels.compute(
                        stock.getId(),
                        (id, existing) -> {
                            StockChannel target =
                                    existing != null
                                            ? existing
                                            : new StockChannel(
                                                    stock.getId(),
                                                    campaignId,
                                                    stock.getRouteId(),
                                                    stock.getFareClass(),
                                                    stock.getTotalStock(),
                                                    remainingStock);
                            target.addEmitter(emitterId, emitter);
                            return target;
                        });

        Runnable cleanup = () -> removeEmitter(channel, emitterId);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());

        sendEvent(channel, emitterId, emitter, "stock-update", toResponse(channel));

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
            currentRemainingStock =
                    remainingStockReader.read(channel.getCampaignId(), channel.getStockId());
        } catch (CampaignStockCacheMissException e) {
            log.warn(
                    "재고 캐시 미스로 SSE 채널을 종료한다: stockId={}, campaignId={}",
                    channel.getStockId(),
                    channel.getCampaignId(),
                    e);
            terminateChannelDueToCacheMiss(channel);
            return;
        } catch (IllegalStateException e) {
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

    @PostConstruct
    void registerMetrics() {
        Gauge.builder(
                        "coupon.sse.active_connections",
                        channels,
                        map -> map.values().stream().mapToInt(StockChannel::emitterCount).sum())
                .description("현재 열려 있는 재고 현황 SSE 구독 수")
                .register(meterRegistry);
    }

    private void broadcast(StockChannel channel, String eventName, Object payload) {
        channel.getEmitters()
                .forEach(
                        (emitterId, emitter) ->
                                sendEvent(channel, emitterId, emitter, eventName, payload));
    }

    private void terminateChannelDueToCacheMiss(StockChannel channel) {
        notifyCacheMiss(channel.getCampaignId());

        AtomicReference<StockChannel> removedChannel = new AtomicReference<>();
        channels.compute(
                channel.getStockId(),
                (id, existing) -> {
                    // 현재 맵에 있는 게 이 메서드가 넘겨받은 channel이 아니면(이미 교체됨) 손대지 않음
                    if (existing != channel) {
                        return existing;
                    }
                    removedChannel.set(existing);
                    return null;
                });

        StockChannel finalChannel = removedChannel.get();
        if (finalChannel == null) {
            return;
        }

        finalChannel
                .getEmitters()
                .forEach(
                        (emitterId, emitter) -> {
                            try {
                                emitter.send(
                                        SseEmitter.event()
                                                .name("error")
                                                .data(
                                                        new CacheMissErrorPayload(
                                                                "CAMPAIGN_NOT_CACHED",
                                                                "캠페인 발급 준비가 완료되지 않았습니다. 잠시 후"
                                                                        + " 다시 시도해 주세요."),
                                                        MediaType.APPLICATION_JSON));
                            } catch (Exception e) {
                                log.debug("캐시 미스 에러 이벤트 전송 실패: emitterId={}", emitterId, e);
                            }
                            try {
                                emitter.complete();
                            } catch (Exception e) {
                                log.debug("캐시 미스 emitter 종료 실패: emitterId={}", emitterId, e);
                            }
                        });
    }

    private void notifyCacheMiss(Long campaignId) {
        Counter.builder("coupon.issue.failure")
                .tag("reason", "campaign_not_cached")
                .description("발급 요청이 Redis 캠페인 캐시 미스(웜업 누락/유실)로 실패한 횟수")
                .register(meterRegistry)
                .increment();

        CacheAutoRecoveryTrigger trigger = cacheAutoRecoveryTriggerProvider.getIfAvailable();
        if (trigger != null) {
            trigger.onCacheMiss(campaignId);
        }
    }

    private void sendEvent(
            StockChannel channel,
            String emitterId,
            SseEmitter emitter,
            String eventName,
            Object payload) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name(eventName)
                            .id(String.valueOf(Instant.now().toEpochMilli()))
                            .reconnectTime(reconnectTimeMs)
                            .data(payload, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            // onError 콜백 발생 여부에 의존하지 않고, 전송 실패 시점에 바로 제거
            log.debug("SSE 이벤트 전송 실패, emitter를 명시적으로 제거함: eventName={}", eventName, e);
            removeEmitter(channel, emitterId);
        }
    }

    private void removeEmitter(StockChannel channel, String emitterId) {
        channels.compute(
                channel.getStockId(),
                (id, existing) -> {
                    if (existing == null) {
                        return null;
                    }
                    existing.removeEmitter(emitterId);
                    return existing.isEmpty() ? null : existing;
                });
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

    private record CacheMissErrorPayload(String errorCode, String message) {}
}
