package com.uply.coupon.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignCacheRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class CampaignStatusStreamServiceTest {

    @InjectMocks private CampaignStatusStreamService service;

    @Mock private CampaignStockRepository campaignStockRepository;

    @Mock private CampaignCacheRepository campaignCacheRepository;

    @Mock private CampaignStock stock;

    @Mock private CampaignStock stockB;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "timeoutMs", 1_800_000L);
        ReflectionTestUtils.setField(service, "heartbeatIntervalMs", 15_000L);
        ReflectionTestUtils.setField(service, "reconnectTimeMs", 3_000L);
    }

    private void givenStockExists(
            CampaignStock stockMock,
            Long campaignId,
            String routeId,
            String fareClass,
            Long stockId,
            Integer totalStock) {
        given(
                        campaignStockRepository.findByCampaignIdAndRouteIdAndFareClass(
                                campaignId, routeId, fareClass))
                .willReturn(Optional.of(stockMock));
        given(stockMock.getId()).willReturn(stockId);
        given(stockMock.getRouteId()).willReturn(routeId);
        given(stockMock.getFareClass()).willReturn(fareClass);
        given(stockMock.getTotalStock()).willReturn(totalStock);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, StockChannel> channelsMap() {
        return (Map<Long, StockChannel>) ReflectionTestUtils.getField(service, "channels");
    }

    private StockChannel channelFor(Long stockId) {
        return channelsMap().get(stockId);
    }

    // 구독 직후 현재 재고 상태를 담은 stock-update 이벤트를 한 번 전송하는지 검증한다.
    @Test
    void subscribe_sendsCurrentStatusImmediately() throws IOException {
        givenStockExists(stock, 1L, "JEJU", "ECONOMY", 10L, 10);
        given(campaignCacheRepository.getRemainingStock(10L)).willReturn(9);

        try (MockedConstruction<SseEmitter> mocked = mockConstruction(SseEmitter.class)) {
            SseEmitter emitter = service.subscribe(1L, "JEJU", "ECONOMY");

            assertThat(mocked.constructed()).hasSize(1);
            verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        }
    }

    // 존재하지 않는 노선·좌석등급 조합 구독 시 CampaignNotFoundException을 던지는지 검증한다.
    @Test
    void subscribe_missingStock_throwsCampaignNotFoundException() {
        given(campaignStockRepository.findByCampaignIdAndRouteIdAndFareClass(1L, "JEJU", "FIRST"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.subscribe(1L, "JEJU", "FIRST"))
                .isInstanceOf(CampaignNotFoundException.class);
    }

    // Redis 재고 값이 이전과 같으면 추가 stock-update 전송이 없는지 검증한다.
    @Test
    void pollChannel_sameRemainingStock_doesNotSendUpdate() throws IOException {
        givenStockExists(stock, 1L, "JEJU", "ECONOMY", 10L, 10);
        given(campaignCacheRepository.getRemainingStock(10L)).willReturn(9);

        try (MockedConstruction<SseEmitter> mocked = mockConstruction(SseEmitter.class)) {
            service.subscribe(1L, "JEJU", "ECONOMY");
            SseEmitter emitter = mocked.constructed().get(0);
            StockChannel channel = channelFor(10L);

            service.pollChannel(channel, channel.getLastHeartbeatAt());

            verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        }
    }

    // Redis 재고 값이 바뀌면 stock-update 이벤트를 전송하는지 검증한다.
    @Test
    void pollChannel_changedRemainingStock_sendsUpdate() throws IOException {
        givenStockExists(stock, 1L, "JEJU", "ECONOMY", 10L, 10);
        given(campaignCacheRepository.getRemainingStock(10L)).willReturn(9, 8);

        try (MockedConstruction<SseEmitter> mocked = mockConstruction(SseEmitter.class)) {
            service.subscribe(1L, "JEJU", "ECONOMY");
            SseEmitter emitter = mocked.constructed().get(0);
            StockChannel channel = channelFor(10L);

            service.pollChannel(channel, channel.getLastHeartbeatAt());

            assertThat(channel.getLastRemainingStock()).isEqualTo(8);
            verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        }
    }

    // 같은 재고 풀을 구독한 구독자 두 명 모두 변경 이벤트를 수신하는지 검증한다.
    @Test
    void pollChannel_multipleSubscribers_bothReceiveUpdate() throws IOException {
        givenStockExists(stock, 1L, "JEJU", "ECONOMY", 10L, 10);
        given(campaignCacheRepository.getRemainingStock(10L)).willReturn(9, 9, 8);

        try (MockedConstruction<SseEmitter> mocked = mockConstruction(SseEmitter.class)) {
            service.subscribe(1L, "JEJU", "ECONOMY");
            service.subscribe(1L, "JEJU", "ECONOMY");
            SseEmitter emitter1 = mocked.constructed().get(0);
            SseEmitter emitter2 = mocked.constructed().get(1);
            StockChannel channel = channelFor(10L);

            service.pollChannel(channel, channel.getLastHeartbeatAt());

            verify(emitter1, times(2)).send(any(SseEmitter.SseEventBuilder.class));
            verify(emitter2, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        }
    }

    // 같은 재고 풀은 구독자 수와 무관하게 한 번의 polling에서 Redis를 한 번만 조회하는지 검증한다.
    @Test
    void pollChannel_multipleSubscribers_queriesRedisOnce() {
        givenStockExists(stock, 1L, "JEJU", "ECONOMY", 10L, 10);
        given(campaignCacheRepository.getRemainingStock(10L)).willReturn(9);

        try (MockedConstruction<SseEmitter> mocked = mockConstruction(SseEmitter.class)) {
            service.subscribe(1L, "JEJU", "ECONOMY");
            service.subscribe(1L, "JEJU", "ECONOMY");
            StockChannel channel = channelFor(10L);

            clearInvocations(campaignCacheRepository);
            service.pollChannel(channel, channel.getLastHeartbeatAt());

            verify(campaignCacheRepository, times(1)).getRemainingStock(10L);
        }
    }

    // 한 emitter로의 전송 실패가 다른 emitter로의 전송을 막지 않는지 검증한다.
    @Test
    void pollChannel_oneEmitterSendFailure_doesNotBlockOthers() throws IOException {
        givenStockExists(stock, 1L, "JEJU", "ECONOMY", 10L, 10);
        given(campaignCacheRepository.getRemainingStock(10L)).willReturn(9, 8);

        try (MockedConstruction<SseEmitter> mocked = mockConstruction(SseEmitter.class)) {
            service.subscribe(1L, "JEJU", "ECONOMY");
            service.subscribe(1L, "JEJU", "ECONOMY");
            SseEmitter brokenEmitter = mocked.constructed().get(0);
            SseEmitter healthyEmitter = mocked.constructed().get(1);
            StockChannel channel = channelFor(10L);

            doThrow(new IOException("connection reset"))
                    .when(brokenEmitter)
                    .send(any(SseEmitter.SseEventBuilder.class));

            service.pollChannel(channel, channel.getLastHeartbeatAt());

            verify(healthyEmitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
            assertThat(channel.getEmitters().values()).doesNotContain(brokenEmitter);
        }
    }

    // completion·timeout·error 콜백이 각각 emitter를 제거하고, 마지막 emitter 제거 시 채널도 제거되는지 검증한다.
    @Test
    @SuppressWarnings("unchecked")
    void emitterCleanup_removesEmitterAndChannelOnLifecycleEvents() {
        givenStockExists(stock, 1L, "JEJU", "ECONOMY", 10L, 10);
        given(campaignCacheRepository.getRemainingStock(10L)).willReturn(9);

        try (MockedConstruction<SseEmitter> mocked = mockConstruction(SseEmitter.class)) {
            service.subscribe(1L, "JEJU", "ECONOMY"); // completion
            service.subscribe(1L, "JEJU", "ECONOMY"); // timeout
            service.subscribe(1L, "JEJU", "ECONOMY"); // error

            StockChannel channel = channelFor(10L);

            SseEmitter completionEmitter = mocked.constructed().get(0);
            SseEmitter timeoutEmitter = mocked.constructed().get(1);
            SseEmitter errorEmitter = mocked.constructed().get(2);

            ArgumentCaptor<Runnable> completionCaptor = ArgumentCaptor.forClass(Runnable.class);
            verify(completionEmitter).onCompletion(completionCaptor.capture());

            ArgumentCaptor<Runnable> timeoutCaptor = ArgumentCaptor.forClass(Runnable.class);
            verify(timeoutEmitter).onTimeout(timeoutCaptor.capture());

            ArgumentCaptor<Consumer<Throwable>> errorCaptor =
                    ArgumentCaptor.forClass(Consumer.class);
            verify(errorEmitter).onError(errorCaptor.capture());

            completionCaptor.getValue().run();
            timeoutCaptor.getValue().run();
            errorCaptor.getValue().accept(new IOException("boom"));

            assertThat(channel.getEmitters()).isEmpty();
            assertThat(channelsMap()).doesNotContainKey(10L);
        }
    }

    // heartbeat 간격이 지나기 전엔 전송하지 않고, 간격이 지나면 heartbeat를 전송하는지 검증한다.
    @Test
    void pollChannel_sendsHeartbeatAfterIntervalElapses() throws IOException {
        givenStockExists(stock, 1L, "JEJU", "ECONOMY", 10L, 10);
        given(campaignCacheRepository.getRemainingStock(10L)).willReturn(9);

        try (MockedConstruction<SseEmitter> mocked = mockConstruction(SseEmitter.class)) {
            service.subscribe(1L, "JEJU", "ECONOMY");
            SseEmitter emitter = mocked.constructed().get(0);
            StockChannel channel = channelFor(10L);
            var beforeHeartbeat = channel.getLastHeartbeatAt();

            service.pollChannel(channel, beforeHeartbeat.plusMillis(14_999));
            assertThat(channel.getLastHeartbeatAt()).isEqualTo(beforeHeartbeat);
            verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));

            var heartbeatTime = beforeHeartbeat.plusMillis(15_000);
            service.pollChannel(channel, heartbeatTime);
            assertThat(channel.getLastHeartbeatAt()).isEqualTo(heartbeatTime);
            verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        }
    }

    // 한 재고 풀의 Redis 조회 실패가 다른 재고 풀의 polling을 막지 않는지 검증한다.
    @Test
    void pollStocks_redisFailureOnOneChannelDoesNotStopOthers() throws IOException {
        givenStockExists(stock, 1L, "JEJU", "ECONOMY", 10L, 10);
        givenStockExists(stockB, 1L, "BUSAN", "ECONOMY", 20L, 5);
        given(campaignCacheRepository.getRemainingStock(10L)).willReturn(9);
        given(campaignCacheRepository.getRemainingStock(20L)).willReturn(4);

        try (MockedConstruction<SseEmitter> mocked = mockConstruction(SseEmitter.class)) {
            service.subscribe(1L, "JEJU", "ECONOMY");
            service.subscribe(1L, "BUSAN", "ECONOMY");
            SseEmitter emitterA = mocked.constructed().get(0);
            SseEmitter emitterB = mocked.constructed().get(1);

            given(campaignCacheRepository.getRemainingStock(10L))
                    .willThrow(new IllegalStateException("cache not ready"));
            given(campaignCacheRepository.getRemainingStock(20L)).willReturn(3);

            service.pollStocks();

            verify(emitterA, times(1)).send(any(SseEmitter.SseEventBuilder.class));
            verify(emitterB, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        }
    }

    // 새 구독과 마지막 emitter의 완료 정리가 동시에 일어나도
    // 채널이 폴링 대상 Map에서 고아가 되지 않는지 검증한다.
    @Test
    void subscribeAndCleanup_concurrentRace_neverOrphansChannel() throws Exception {
        givenStockExists(stock, 1L, "JEJU", "ECONOMY", 10L, 10);
        given(campaignCacheRepository.getRemainingStock(10L)).willReturn(9);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (MockedConstruction<SseEmitter> mocked = mockConstruction(SseEmitter.class)) {
            for (int i = 0; i < 200; i++) {
                service.subscribe(1L, "JEJU", "ECONOMY");
                SseEmitter emitter = mocked.constructed().get(mocked.constructed().size() - 1);

                ArgumentCaptor<Runnable> completionCaptor = ArgumentCaptor.forClass(Runnable.class);
                verify(emitter).onCompletion(completionCaptor.capture());
                Runnable cleanup = completionCaptor.getValue();

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> subscribeAgain =
                        executor.submit(
                                () -> {
                                    awaitBarrier(barrier);
                                    service.subscribe(1L, "JEJU", "ECONOMY");
                                });
                Future<?> cleanupTask =
                        executor.submit(
                                () -> {
                                    awaitBarrier(barrier);
                                    cleanup.run();
                                });
                subscribeAgain.get(5, TimeUnit.SECONDS);
                cleanupTask.get(5, TimeUnit.SECONDS);

                assertThat(channelsMap()).as("iteration %d", i).containsKey(10L);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
