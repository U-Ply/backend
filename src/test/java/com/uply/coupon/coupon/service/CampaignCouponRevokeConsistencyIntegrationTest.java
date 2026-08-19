package com.uply.coupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uply.coupon.campaign.domain.Campaign;
import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.common.exception.InvalidStateTransitionException;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponHistory;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CampaignCouponRevokeService.class, CouponStateTransitionService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignCouponRevokeConsistencyIntegrationTest {

    private static final AtomicLong COUPON_ID_SEQUENCE = new AtomicLong(8_200_000_000_000L);
    private static final String REVOKE_IDEMPOTENCY_KEY = "00000000-0000-4000-8000-000000000070";
    private static final String USE_IDEMPOTENCY_KEY = "00000000-0000-4000-8000-000000000071";

    @Autowired private CampaignCouponRevokeService revokeService;
    @Autowired private CouponStateTransitionService transitionService;
    @Autowired private CouponRepository couponRepository;
    @Autowired private CouponHistoryRepository couponHistoryRepository;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private CampaignStockRepository campaignStockRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long campaignId;
    private Long stockId;
    private Long couponId;
    private LocalDateTime expireAt;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();
        userId = createUser();

        Campaign campaign =
                campaignRepository.saveAndFlush(
                        Campaign.builder()
                                .name("항공사 취소 정합성 통합 테스트")
                                .openAt(now.minusDays(1))
                                .expireAt(now.plusDays(7))
                                .build());
        campaignId = campaign.getId();

        CampaignStock stock =
                CampaignStock.builder()
                        .campaign(campaign)
                        .routeId("ICN-JEJ")
                        .fareClass("ECONOMY")
                        .totalStock(10)
                        .build();
        stock.decreaseStock(1);
        stock = campaignStockRepository.saveAndFlush(stock);
        stockId = stock.getId();

        expireAt = now.plusDays(7);
        couponId = COUPON_ID_SEQUENCE.incrementAndGet();
        couponRepository.saveAndFlush(
                Coupon.issue(couponId, userId, campaignId, stockId, now.minusDays(1), expireAt));
    }

    @AfterEach
    void tearDown() {
        if (couponId != null) {
            jdbcTemplate.update("DELETE FROM coupon_history WHERE coupon_id = ?", couponId);
            jdbcTemplate.update("DELETE FROM coupons WHERE coupon_id = ?", couponId);
        }
        if (stockId != null) {
            jdbcTemplate.update("DELETE FROM campaign_stocks WHERE stock_id = ?", stockId);
        }
        if (campaignId != null) {
            jdbcTemplate.update("DELETE FROM campaigns WHERE campaign_id = ?", campaignId);
        }
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", userId);
        }
    }

    // 사용·항공사 취소·만료가 동시에 요청돼도 정확히 하나의 상태 전이와 이력만 성공하는지 확인
    @Test
    void onlyOneTransitionSucceedsWhenUseRevokeAndExpireCompete() throws Exception {
        int stockBefore = findRemainingStock();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch readyLatch = new CountDownLatch(3);
        CountDownLatch startLatch = new CountDownLatch(1);

        try {
            Future<Boolean> useResult =
                    executor.submit(
                            concurrentTask(
                                    readyLatch,
                                    startLatch,
                                    () -> {
                                        try {
                                            transitionService.use(couponId, USE_IDEMPOTENCY_KEY);
                                            return true;
                                        } catch (InvalidStateTransitionException exception) {
                                            return false;
                                        }
                                    }));
            Future<Boolean> revokeResult =
                    executor.submit(
                            concurrentTask(
                                    readyLatch,
                                    startLatch,
                                    () ->
                                            revokeService.revoke(campaignId, REVOKE_IDEMPOTENCY_KEY)
                                                    == 1));
            Future<Boolean> expireResult =
                    executor.submit(
                            concurrentTask(
                                    readyLatch,
                                    startLatch,
                                    () ->
                                            transitionService.expireCoupon(
                                                    couponId,
                                                    "expire-" + couponId + "-concurrency-test",
                                                    expireAt)));

            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            long successCount =
                    Stream.of(useResult, revokeResult, expireResult)
                            .filter(this::getResult)
                            .count();

            assertThat(successCount).isEqualTo(1);
        } finally {
            startLatch.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        Coupon finalCoupon = couponRepository.findById(couponId).orElseThrow();
        assertThat(finalCoupon.getStatus())
                .isIn(CouponStatus.USED, CouponStatus.CANCELLED, CouponStatus.EXPIRED);

        long terminalTimestampCount =
                Stream.of(
                                finalCoupon.getUsedAt(),
                                finalCoupon.getCancelledAt(),
                                finalCoupon.getExpiredAt())
                        .filter(Objects::nonNull)
                        .count();
        assertThat(terminalTimestampCount).isEqualTo(1);
        assertThat(countHistories()).isEqualTo(1);
        assertThat(findLastHistoryStatus()).isEqualTo(finalCoupon.getStatus().name());
        assertThat(findRemainingStock()).isEqualTo(stockBefore);
    }

    // 이력의 UNIQUE 제약 위반으로 저장이 실패하면 쿠폰 취소 UPDATE도 함께 롤백되는지 확인
    @Test
    void rollsBackCouponUpdateWhenHistoryInsertFails() {
        int stockBefore = findRemainingStock();
        String duplicatedHistoryKey = "revoke-" + couponId + "-" + REVOKE_IDEMPOTENCY_KEY;
        couponHistoryRepository.saveAndFlush(
                CouponHistory.issued(couponId, duplicatedHistoryKey, LocalDateTime.now()));

        assertThatThrownBy(() -> revokeService.revoke(campaignId, REVOKE_IDEMPOTENCY_KEY))
                .isInstanceOf(DataIntegrityViolationException.class);

        Coupon unchangedCoupon = couponRepository.findById(couponId).orElseThrow();
        assertThat(unchangedCoupon.getStatus()).isEqualTo(CouponStatus.ISSUED);
        assertThat(unchangedCoupon.getCancelledAt()).isNull();
        assertThat(countHistories()).isEqualTo(1);
        assertThat(findRemainingStock()).isEqualTo(stockBefore);
    }

    private Callable<Boolean> concurrentTask(
            CountDownLatch readyLatch, CountDownLatch startLatch, Callable<Boolean> action) {
        return () -> {
            readyLatch.countDown();
            if (!startLatch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 실행 시작 신호를 기다리는 시간이 초과됐습니다.");
            }
            return action.call();
        };
    }

    private boolean getResult(Future<Boolean> result) {
        try {
            return result.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("동시 상태 전이 결과 확인에 실패했습니다.", exception);
        }
    }

    private Long createUser() {
        String email =
                "revoke-consistency-" + COUPON_ID_SEQUENCE.incrementAndGet() + "@example.com";
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(
                connection -> {
                    var statement =
                            connection.prepareStatement(
                                    "INSERT INTO users (email, name) VALUES (?, ?)",
                                    Statement.RETURN_GENERATED_KEYS);
                    statement.setString(1, email);
                    statement.setString(2, "항공사 취소 정합성 테스트 사용자");
                    return statement;
                },
                keyHolder);

        Number generatedKey = keyHolder.getKey();
        if (generatedKey == null) {
            throw new IllegalStateException("테스트 사용자 ID 생성에 실패했습니다.");
        }
        return generatedKey.longValue();
    }

    private long countHistories() {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM coupon_history WHERE coupon_id = ?",
                        Long.class,
                        couponId);
        return count == null ? 0L : count;
    }

    private String findLastHistoryStatus() {
        List<String> statuses =
                jdbcTemplate.queryForList(
                        """
                        SELECT to_status
                          FROM coupon_history
                         WHERE coupon_id = ?
                         ORDER BY event_at DESC, history_id DESC
                         LIMIT 1
                        """,
                        String.class,
                        couponId);
        if (statuses.isEmpty()) {
            throw new IllegalStateException("상태 전이 이력을 찾을 수 없습니다.");
        }
        return statuses.get(0);
    }

    private int findRemainingStock() {
        Integer remainingStock =
                jdbcTemplate.queryForObject(
                        "SELECT remaining_stock FROM campaign_stocks WHERE stock_id = ?",
                        Integer.class,
                        stockId);
        if (remainingStock == null) {
            throw new IllegalStateException("테스트 재고를 찾을 수 없습니다.");
        }
        return remainingStock;
    }
}
