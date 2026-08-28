package com.uply.coupon.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.uply.coupon.campaign.dto.response.CampaignStatusResponse;
import com.uply.coupon.campaign.service.CampaignQueryService;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.service.CouponService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 회귀 테스트: DB 전용 전략(PESSIMISTIC_LOCK)에서 발급하면 {@code /status} 조회가 Redis 캐시가 아니라 DB {@code
 * campaign_stocks.remaining_stock} 를 반영해야 한다.
 *
 * <p>이전에는 {@code CampaignQueryService} 가 항상 Redis 카운터를 읽어, V0/V1 회차에서 재고 소진이 화면에 전혀 보이지 않았다({@code
 * RemainingStockReader} 도입 전).
 */
@SpringBootTest(
        properties = {
            "coupon.issue.strategy=PESSIMISTIC_LOCK",
            "coupon.save.strategy=sync-db",
            "coupon.idempotency.enabled=false",
            "coupon.kafka.consumer.enabled=false"
        })
class CampaignStatusDbStrategyIntegrationTest extends IntegrationTestContainers {

    @Autowired CampaignQueryService campaignQueryService;
    @Autowired CouponService couponService;
    @Autowired CouponIntegrationFixture fixture;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        fixture.reset();
        // Redis stock 키는 일부러 만들지 않는다. 비관적 락 경로는 Redis 를 쓰지 않아야 한다.
        fixture.createCampaign(10);
        fixture.createUsers(5, 10001L);
    }

    @AfterEach
    void tearDown() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        fixture.reset();
    }

    @Test
    void 비관적락_전략_발급이_status_조회의_DB_잔여재고에_반영된다() {
        CampaignStatusResponse before =
                campaignQueryService.getCampaignStatus(
                        CouponIntegrationFixture.CAMPAIGN_ID,
                        CouponIntegrationFixture.ROUTE,
                        CouponIntegrationFixture.FARE);
        assertThat(before.totalStock()).isEqualTo(10);
        assertThat(before.remainingStock()).isEqualTo(10);

        for (int i = 0; i < 3; i++) {
            long userId = 10001L + i;
            couponService.issue(
                    "it-status-db-" + userId,
                    new CouponIssueRequest(
                            userId,
                            CouponIntegrationFixture.CAMPAIGN_ID,
                            CouponIntegrationFixture.ROUTE,
                            CouponIntegrationFixture.FARE));
        }

        CampaignStatusResponse after =
                campaignQueryService.getCampaignStatus(
                        CouponIntegrationFixture.CAMPAIGN_ID,
                        CouponIntegrationFixture.ROUTE,
                        CouponIntegrationFixture.FARE);

        assertThat(after.remainingStock()).isEqualTo(7);
        assertThat(fixture.remaining()).isEqualTo(7L);
    }
}
