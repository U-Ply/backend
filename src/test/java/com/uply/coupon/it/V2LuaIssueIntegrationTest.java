package com.uply.coupon.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;

import com.uply.coupon.campaign.service.CampaignCacheWarmupService;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.service.CouponService;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import com.uply.coupon.coupon.strategy.save.CouponSaveStrategy;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.CannotCreateTransactionException;

@SpringBootTest(
        properties = {
            "coupon.issue.strategy=LUA_SCRIPT",
            "coupon.save.strategy=sync-db",
            "coupon.idempotency.enabled=false",
            // 이 회차는 Kafka 를 쓰지 않는다. 기본값이 true 라 그대로 두면 이 컨텍스트의
            // 컨슈머가 coupon-service 그룹에 붙어 V3 가 발행한 메시지를 가져가 버린다.
            // Spring 은 테스트 컨텍스트를 캐시하고 닫지 않으므로 회차가 끝나도 계속 살아 있다.
            "coupon.kafka.consumer.enabled=false"
        })
class V2LuaIssueIntegrationTest extends IntegrationTestContainers {

    @Autowired CouponService couponService;
    @Autowired CouponIntegrationFixture fixture;
    @Autowired CampaignCacheWarmupService warmupService;
    @Autowired StringRedisTemplate redis;
    @Autowired RoundReportWriter reportWriter;

    @BeforeEach
    void setUp() {
        // 청소는 앞에서 한다. 뒤에서만 하면 앞선 클래스가 남긴 키 때문에 이 테스트가
        // 자기 잘못이 아닌 이유로 깨진다. V3 도 같은 캠페인 ID 를 쓰므로 issued: 셋이
        // 실제로 겹친다. warmupCampaign 보다 반드시 먼저다 — 뒤에 두면 방금 채운
        // 캐시를 지운다.
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();

        fixture.reset();
        fixture.createCampaign(10);
        fixture.createUsers(30, 30001L);

        warmupService.warmupCampaign(CouponIntegrationFixture.CAMPAIGN_ID);
    }

    @AfterEach
    void tearDown() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        fixture.reset();
    }

    @Test
    void Redis_Lua_경로는_재고_10개에_30건이면_정확히_10건만_성공한다() throws Exception {
        int requests = 30;

        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(requests);

        ConcurrentLinkedQueue<IssueFailReason> failures = new ConcurrentLinkedQueue<>();

        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        try {
            for (int i = 0; i < requests; i++) {
                long userId = 30001L + i;

                pool.submit(
                        () -> {
                            ready.countDown();

                            try {
                                assertThat(start.await(10, TimeUnit.SECONDS))
                                        .as("동시 발급 시작 신호를 10초 안에 받지 못했습니다.")
                                        .isTrue();

                                couponService.issue(
                                        "integration-v2-" + userId,
                                        new CouponIssueRequest(
                                                userId,
                                                CouponIntegrationFixture.CAMPAIGN_ID,
                                                CouponIntegrationFixture.ROUTE,
                                                CouponIntegrationFixture.FARE));
                            } catch (CouponIssueException e) {
                                failures.add(e.getReason());
                            } catch (Throwable t) {
                                unexpected.add(t);
                            } finally {
                                done.countDown();
                            }
                        });
            }

            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("모든 발급 작업이 10초 안에 준비되지 않았습니다.")
                    .isTrue();

            start.countDown();

            assertThat(done.await(60, TimeUnit.SECONDS))
                    .as("모든 발급 작업이 60초 안에 종료되지 않았습니다.")
                    .isTrue();

            assertThat(unexpected).isEmpty();

            assertThat(failures).hasSize(20);

            assertThat(failures).allMatch(reason -> reason == IssueFailReason.OUT_OF_STOCK);

            // V2 는 sync-db 라 발급 응답 시점에 DB 가 이미 확정이다. 대기가 필요 없다.
            assertThat(fixture.couponCount()).isEqualTo(10);
            assertThat(fixture.historyCount()).isEqualTo(10);
            assertThat(fixture.remaining()).isZero();

            assertThat(redis.opsForValue().get("stock:" + CouponIntegrationFixture.STOCK_ID))
                    .isEqualTo("0");

            assertThat(redis.opsForSet().size("issued:" + CouponIntegrationFixture.CAMPAIGN_ID))
                    .isEqualTo(10L);

            // 이 회차 데이터 위에서 검증·대사 배치를 돌리고 리포트를 남긴다.
            // build/round-results/V2.md 가 이 회차의 산출물이다.
            RoundReportAssert.assertPassed(reportWriter.writeReport("V2"), "V2");

        } finally {
            pool.shutdownNow();
        }
    }

    @SpyBean private CouponSaveStrategy couponSaveStrategy;

    @Test
    void V2_DB_저장_실패시_Redis_보상_후_재시도하면_성공한다() {
        long userId = 30001L;
        long campaignId = CouponIntegrationFixture.CAMPAIGN_ID;
        long stockId = CouponIntegrationFixture.STOCK_ID;
        String idempotencyKey = "compensation-test-" + userId;

        // 1차 호출만 실패하도록 스텁, 2차부터는 실제 메서드 실행
        willThrow(new CouponIssueException(IssueFailReason.DB_SAVE_FAILED))
                .willCallRealMethod()
                .given(couponSaveStrategy)
                .save(
                        any(),
                        eq(userId),
                        eq(campaignId),
                        eq(stockId),
                        eq(idempotencyKey),
                        any(),
                        any());

        // when: 1차 요청 - 실패해야 함
        assertThatThrownBy(
                        () ->
                                couponService.issue(
                                        idempotencyKey,
                                        new CouponIssueRequest(
                                                userId,
                                                campaignId,
                                                CouponIntegrationFixture.ROUTE,
                                                CouponIntegrationFixture.FARE)))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.DB_SAVE_FAILED);

        // then: Redis 보상 확인
        assertThat(redis.opsForValue().get("stock:" + stockId)).isEqualTo("10"); // 원복
        assertThat(redis.opsForSet().isMember("issued:" + campaignId, String.valueOf(userId)))
                .isFalse();

        // DB 미저장 확인
        assertThat(fixture.couponCount()).isZero();
        assertThat(fixture.historyCount()).isZero();

        // when: 재시도 - 이번엔 성공해야 함
        var response =
                couponService.issue(
                        idempotencyKey,
                        new CouponIssueRequest(
                                userId,
                                campaignId,
                                CouponIntegrationFixture.ROUTE,
                                CouponIntegrationFixture.FARE));

        // then: 최종 일치 확인
        assertThat(response.status()).isEqualTo(CouponStatus.ISSUED);
        assertThat(fixture.couponCount()).isEqualTo(1);
        assertThat(redis.opsForValue().get("stock:" + stockId)).isEqualTo("9");
        assertThat(fixture.remaining()).isEqualTo(9);
    }

    @Test
    void V2_DB_커넥션_획득_실패시_Redis_보상_후_재시도하면_성공한다() {
        long userId = 30002L; // 1차 테스트와 겹치지 않는 userId 사용 권장
        long campaignId = CouponIntegrationFixture.CAMPAIGN_ID;
        long stockId = CouponIntegrationFixture.STOCK_ID;
        String idempotencyKey = "connection-fail-test-" + userId;

        willThrow(new CannotCreateTransactionException("DB 커넥션 획득 실패 테스트"))
                .willCallRealMethod()
                .given(couponSaveStrategy)
                .save(
                        any(),
                        eq(userId),
                        eq(campaignId),
                        eq(stockId),
                        eq(idempotencyKey),
                        any(),
                        any());

        assertThatThrownBy(
                        () ->
                                couponService.issue(
                                        idempotencyKey,
                                        new CouponIssueRequest(
                                                userId,
                                                campaignId,
                                                CouponIntegrationFixture.ROUTE,
                                                CouponIntegrationFixture.FARE)))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.CONNECTION_UNAVAILABLE); // 1차와 다른 부분

        assertThat(redis.opsForValue().get("stock:" + stockId)).isEqualTo("10");
        assertThat(redis.opsForSet().isMember("issued:" + campaignId, String.valueOf(userId)))
                .isFalse();
        assertThat(fixture.couponCount()).isZero();

        var response =
                couponService.issue(
                        idempotencyKey,
                        new CouponIssueRequest(
                                userId,
                                campaignId,
                                CouponIntegrationFixture.ROUTE,
                                CouponIntegrationFixture.FARE));

        assertThat(response.status()).isEqualTo(CouponStatus.ISSUED);
    }
}
