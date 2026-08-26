package com.uply.coupon.campaign.service;

import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.dto.response.CampaignStatusResponse;
import com.uply.coupon.campaign.repository.CampaignCacheRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.exception.CampaignStockCacheMissException;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final CampaignCacheRepository campaignCacheRepository;

    // 자동 트리거는 coupon.cache-recovery.auto-trigger-enabled=true일 때만 빈이 존재한다
    // (기본 비활성화). GlobalExceptionHandler와 같은 패턴으로 ObjectProvider로 받아
    // 없으면 조용히 건너뛴다.
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

        // 구독 시점(SSE 응답 시작 전)의 캐시 미스는 CouponIssueException으로 변환해
        // GlobalExceptionHandler의 기존 503 CAMPAIGN_NOT_CACHED 처리(카운터 증가 +
        // 자동 복구 트리거)를 그대로 재사용한다.
        Integer remainingStock;
        try {
            remainingStock = campaignCacheRepository.getRemainingStock(stock.getId());
        } catch (CampaignStockCacheMissException exception) {
            throw new CouponIssueException(IssueFailReason.CAMPAIGN_NOT_CACHED, campaignId);
        }

        SseEmitter emitter = new SseEmitter(timeoutMs);
        String emitterId = UUID.randomUUID().toString();

        // 채널 조회/생성과 emitter 등록을 같은 compute() 안에서 원자적으로 처리한다.
        // 분리돼 있으면 이 사이에 다른 스레드가 채널을 비우고 지워서, 새 emitter가
        // 폴링 Map에서 빠진 "고아 채널"에 붙는 경쟁 조건이 생긴다.
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
            currentRemainingStock = campaignCacheRepository.getRemainingStock(channel.getStockId());
        } catch (CampaignStockCacheMissException e) {
            // 이미 SSE 응답이 시작된 뒤라 GlobalExceptionHandler가 503 JSON을 돌려줄 수 없다.
            // 여기서 직접 자동 복구를 트리거하고, 같은 오류를 매 tick 반복하지 않도록
            // 채널을 정리한다(에러 이벤트 전송 + emitter 종료 + polling 대상에서 제거).
            log.warn(
                    "재고 캐시 미스로 SSE 채널을 종료한다: stockId={}, campaignId={}",
                    channel.getStockId(),
                    channel.getCampaignId(),
                    e);
            terminateChannelDueToCacheMiss(channel);
            return;
        } catch (IllegalStateException e) {
            // Redis 값이 잘못된 경우(파싱 실패): 캐시 미스가 아니라 시스템 오류이므로 자동 복구를
            // 트리거하지 않고, 이번 tick만 건너뛰고 다음 tick에서 재시도한다.
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
                .forEach(
                        (emitterId, emitter) ->
                                sendEvent(channel, emitterId, emitter, eventName, payload));
    }

    /**
     * 폴링 도중 캐시 미스가 나면 자동 복구를 트리거하고, 구독자에게 에러 이벤트를 보낸 뒤 연결을 종료하고 채널을 polling 대상에서 제거한다. 채널을 지우지 않으면
     * 다음 tick에서 같은 캐시 미스가 무한 반복된다.
     *
     * <p>emitter 정리와 채널 제거를 {@link #channels}의 같은 {@code compute()} 호출 안에서 원자적으로 묶는다. {@code
     * subscribe()}/{@code removeEmitter()}와 마찬가지로, 분리해서 처리하면 정리 도중 새로 들어온 {@code subscribe()}가 이 채널
     * 객체에 emitter를 추가했는데 그 직후 채널이 통째로 지워져, 새 구독자가 에러 이벤트도 못 받고 이후 polling 대상에서도 빠지는 고아 상태가 된다.
     */
    private void terminateChannelDueToCacheMiss(StockChannel channel) {
        notifyCacheMiss(channel.getCampaignId());

        // compute() 안에서는 맵에서 떼어내는 작업만 하고(빠르게), 실제 전송·종료 I/O는 이미
        // 떼어낸 뒤 락 밖에서 수행한다. compute()가 반환하는 값은 "새로 매핑할 값"이라 지워지기
        // 전의 채널 객체 자체는 별도로 캡처해야 한다.
        AtomicReference<StockChannel> removedChannel = new AtomicReference<>();
        channels.compute(
                channel.getStockId(),
                (id, existing) -> {
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
                                // send()와 별개로 방어한다 - complete()가 실패해도(예: 클라이언트가
                                // 이미 컨테이너 쪽에서 연결을 끊어 정리된 경우) 나머지 emitter
                                // 정리를 계속해야 한다. 채널은 이미 맵에서 제거됐으므로 이 예외가
                                // "다음 tick에서 같은 캐시 미스가 무한 반복"되는 결과로 이어지지도
                                // 않는다.
                                log.debug("캐시 미스 emitter 종료 실패: emitterId={}", emitterId, e);
                            }
                        });
    }

    private void notifyCacheMiss(Long campaignId) {
        // GlobalExceptionHandler와 이름·태그가 동일한 카운터를 조회(없으면 생성)한다.
        // Micrometer는 같은 이름+태그의 Counter.builder().register()를 여러 곳에서 호출해도
        // 동일한 등록된 인스턴스를 반환하므로, 발급 API 경로(GlobalExceptionHandler)와 SSE
        // 폴링 경로가 하나의 카운터를 공유하게 된다 - 그러지 않으면 SSE에서 발견한 캐시 미스가
        // 이 지표에서 누락된다.
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
            // onError 콜백 발생 여부에 의존하지 않고, 전송 실패 시점에 바로 제거한다.
            log.debug("SSE 이벤트 전송 실패, emitter를 명시적으로 제거함: eventName={}", eventName, e);
            removeEmitter(channel, emitterId);
        }
    }

    private void removeEmitter(StockChannel channel, String emitterId) {
        // emitter 제거와 "비어있으면 채널도 제거"를 같은 compute() 안에서 처리해
        // subscribe()의 compute()와 상호 배타적으로 만든다(같은 키에 대해 동시에 안 겹침).
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
