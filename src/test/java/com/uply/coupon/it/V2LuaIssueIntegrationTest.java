package com.uply.coupon.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.uply.coupon.campaign.service.CampaignCacheWarmupService;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.service.CouponService;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(
        properties = {
            "coupon.issue.strategy=LUA_SCRIPT",
            "coupon.save.strategy=sync-db",
            "coupon.idempotency.enabled=false"
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
        fixture.createCampaign(30);
        fixture.createUsers(100, 30001L);

        warmupService.warmupCampaign(CouponIntegrationFixture.CAMPAIGN_ID);
    }

    @AfterEach
    void tearDown() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        fixture.reset();
    }

    @Test
    void Redis_Lua_경로는_재고_30개에_100건이면_정확히_30건만_성공한다() throws Exception {
        int requests = 100;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(requests);
        ConcurrentLinkedQueue<IssueFailReason> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < requests; i++) {
            long userId = 30001L + i;
            pool.submit(
                    () -> {
                        ready.countDown();
                        try {
                            start.await();
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

        ready.await();
        start.countDown();
        done.await();
        pool.shutdown();

        assertThat(unexpected).isEmpty();
        assertThat(failures).hasSize(70);
        assertThat(failures).allMatch(reason -> reason == IssueFailReason.OUT_OF_STOCK);

        // V2 는 sync-db 라 발급 응답 시점에 DB 가 이미 확정이다. 대기가 필요 없다.
        assertThat(fixture.couponCount()).isEqualTo(30);
        assertThat(fixture.historyCount()).isEqualTo(30);
        assertThat(fixture.remaining()).isZero();

        assertThat(redis.opsForValue().get("stock:" + CouponIntegrationFixture.STOCK_ID))
                .isEqualTo("0");
        assertThat(redis.opsForSet().size("issued:" + CouponIntegrationFixture.CAMPAIGN_ID))
                .isEqualTo(30L);

        // 이 회차 데이터 위에서 검증·대사 배치를 돌리고 리포트를 남긴다.
        // build/round-results/V2.md 가 이 회차의 산출물이다.
        RoundReportAssert.assertPassed(reportWriter.writeReport("V2"), "V2");
    }
}
