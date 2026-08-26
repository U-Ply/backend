package com.uply.coupon.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.campaign.service.CampaignCacheWarmupService;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.common.idempotency.IdempotencyCache;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.service.CouponService;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import com.uply.coupon.coupon.strategy.save.CouponSaveStrategy;
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
            "coupon.idempotency.enabled=true",
            "coupon.kafka.consumer.enabled=false"
        })
class V2LuaIssueIdempotencyCompensationIntegrationTest extends IntegrationTestContainers {

    @Autowired CouponService couponService;

    @Autowired CouponIntegrationFixture fixture;

    @Autowired CampaignCacheWarmupService warmupService;

    @Autowired StringRedisTemplate redis;

    @Autowired ObjectMapper objectMapper;

    @SpyBean private CouponSaveStrategy couponSaveStrategy;

    @BeforeEach
    void setUp() {
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

    private void stubSaveToFailOnce(
            Throwable firstCallException,
            long userId,
            long campaignId,
            long stockId,
            String idempotencyKey) {
        willThrow(firstCallException)
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
    }

    private CouponIssueRequest issueRequest(long userId, long campaignId) {
        return new CouponIssueRequest(
                userId, campaignId, CouponIntegrationFixture.ROUTE, CouponIntegrationFixture.FARE);
    }

    private IdempotencyCache readIdempotencyCache(String idempotencyKey) throws Exception {
        String raw = redis.opsForValue().get("idempotency:" + idempotencyKey);
        assertThat(raw).as("idempotency:%s 키가 Redis에 있어야 합니다.", idempotencyKey).isNotNull();
        return objectMapper.readValue(raw, IdempotencyCache.class);
    }

    @Test
    void V2_DB_저장_실패시_PROCESSING_키가_해제되고_재시도_성공하면_COMPLETED로_캐시된다() throws Exception {
        long userId = 30001L;
        long campaignId = CouponIntegrationFixture.CAMPAIGN_ID;
        long stockId = CouponIntegrationFixture.STOCK_ID;
        String idempotencyKey = "idempotency-db-save-failed-" + userId;

        stubSaveToFailOnce(
                new CouponIssueException(IssueFailReason.DB_SAVE_FAILED),
                userId,
                campaignId,
                stockId,
                idempotencyKey);

        // when: 1차 요청 - 실패해야 함
        assertThatThrownBy(
                        () -> couponService.issue(idempotencyKey, issueRequest(userId, campaignId)))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.DB_SAVE_FAILED);

        // then: 확정 실패이므로 PROCESSING 선점이 해제되어 재시도를 막지 않아야 함
        assertThat(redis.hasKey("idempotency:" + idempotencyKey))
                .as("실패 후 PROCESSING 키가 해제(clearProgress)되어야 합니다.")
                .isFalse();

        // Redis/DB 보상 확인
        assertThat(redis.opsForValue().get("stock:" + stockId)).isEqualTo("10");
        assertThat(redis.opsForSet().isMember("issued:" + campaignId, String.valueOf(userId)))
                .isFalse();
        assertThat(fixture.couponCount()).isZero();
        assertThat(fixture.historyCount()).isZero();

        // when: 재시도 - 같은 idempotencyKey, 이번엔 성공해야 함
        var response = couponService.issue(idempotencyKey, issueRequest(userId, campaignId));

        // then: 성공 응답이 COMPLETED 상태로 캐시되어야 함
        assertThat(response.status()).isEqualTo(CouponStatus.ISSUED);

        IdempotencyCache cache = readIdempotencyCache(idempotencyKey);
        assertThat(cache.getStatus()).isEqualTo("COMPLETED");
        assertThat(cache.getHttpStatus()).isEqualTo(200);
        assertThat(cache.getBody()).isNotBlank();

        assertThat(fixture.couponCount()).isEqualTo(1);
        assertThat(fixture.historyCount()).isEqualTo(1);
        assertThat(redis.opsForValue().get("stock:" + stockId)).isEqualTo("9");
        assertThat(fixture.remaining()).isEqualTo(9);
        assertThat(redis.opsForSet().isMember("issued:" + campaignId, String.valueOf(userId)))
                .isTrue();
    }

    @Test
    void V2_DB_커넥션_획득_실패시_PROCESSING_키가_해제되고_재시도_성공하면_COMPLETED로_캐시된다() throws Exception {
        long userId = 30002L;
        long campaignId = CouponIntegrationFixture.CAMPAIGN_ID;
        long stockId = CouponIntegrationFixture.STOCK_ID;
        String idempotencyKey = "idempotency-connection-unavailable-" + userId;

        stubSaveToFailOnce(
                new CannotCreateTransactionException("DB 커넥션 획득 실패 테스트"),
                userId,
                campaignId,
                stockId,
                idempotencyKey);

        // when: 1차 요청 - 실패해야 함
        assertThatThrownBy(
                        () -> couponService.issue(idempotencyKey, issueRequest(userId, campaignId)))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.CONNECTION_UNAVAILABLE);

        // then: 확정 실패이므로 PROCESSING 선점이 해제되어 재시도를 막지 않아야 함
        assertThat(redis.hasKey("idempotency:" + idempotencyKey))
                .as("실패 후 PROCESSING 키가 해제(clearProgress)되어야 합니다.")
                .isFalse();

        // Redis/DB 보상 확인
        assertThat(redis.opsForValue().get("stock:" + stockId)).isEqualTo("10");
        assertThat(redis.opsForSet().isMember("issued:" + campaignId, String.valueOf(userId)))
                .isFalse();
        assertThat(fixture.couponCount()).isZero();
        assertThat(fixture.historyCount()).isZero();

        // when: 재시도 - 같은 idempotencyKey, 이번엔 성공해야 함
        var response = couponService.issue(idempotencyKey, issueRequest(userId, campaignId));

        // then: 성공 응답이 COMPLETED 상태로 캐시되어야 함
        assertThat(response.status()).isEqualTo(CouponStatus.ISSUED);

        IdempotencyCache cache = readIdempotencyCache(idempotencyKey);
        assertThat(cache.getStatus()).isEqualTo("COMPLETED");
        assertThat(cache.getHttpStatus()).isEqualTo(200);
        assertThat(cache.getBody()).isNotBlank();

        assertThat(fixture.couponCount()).isEqualTo(1);
        assertThat(fixture.historyCount()).isEqualTo(1);
        assertThat(redis.opsForValue().get("stock:" + stockId)).isEqualTo("9");
        assertThat(fixture.remaining()).isEqualTo(9);
        assertThat(redis.opsForSet().isMember("issued:" + campaignId, String.valueOf(userId)))
                .isTrue();
    }
}
