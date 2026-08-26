package com.uply.coupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uply.coupon.campaign.domain.Campaign;
import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponHistory;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import com.uply.coupon.it.IntegrationTestContainers;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CouponStateTransitionIntegrationTest extends IntegrationTestContainers {

    private static final AtomicLong COUPON_ID_SEQUENCE = new AtomicLong(8_300_000_000_000L);
    private static final String USE_IDEMPOTENCY_KEY = "00000000-0000-4000-8000-000000000081";
    private static final String CANCEL_IDEMPOTENCY_KEY = "00000000-0000-4000-8000-000000000082";

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

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();
        userId = createUser();

        Campaign campaign =
                campaignRepository.saveAndFlush(
                        Campaign.builder()
                                .name("쿠폰 상태 전이 통합 테스트")
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

        couponId = COUPON_ID_SEQUENCE.incrementAndGet();
        couponRepository.saveAndFlush(
                Coupon.issue(
                        couponId, userId, campaignId, stockId, now.minusDays(1), now.plusDays(7)));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM coupon_history WHERE coupon_id = ?", couponId);
        jdbcTemplate.update("DELETE FROM coupons WHERE coupon_id = ?", couponId);
        jdbcTemplate.update("DELETE FROM campaign_stocks WHERE stock_id = ?", stockId);
        jdbcTemplate.update("DELETE FROM campaigns WHERE campaign_id = ?", campaignId);
        jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", userId);
    }

    // 쿠폰 사용 후 예약 취소가 이력과 함께 저장되고 재고는 변경되지 않는지 검증한다.
    @Test
    void useAndCancelSaveMatchingHistoriesWithoutChangingStock() {
        int stockBefore = findRemainingStock();

        transitionService.use(couponId, USE_IDEMPOTENCY_KEY);
        transitionService.cancel(couponId, CANCEL_IDEMPOTENCY_KEY);

        Coupon coupon = couponRepository.findById(couponId).orElseThrow();
        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.CANCELLED);
        assertThat(coupon.getUsedAt()).isNotNull();
        assertThat(coupon.getCancelledAt()).isNotNull();
        assertThat(coupon.getExpiredAt()).isNull();
        assertThat(findHistoryTransitions()).containsExactly("ISSUED->USED", "USED->CANCELLED");
        assertThat(findRemainingStock()).isEqualTo(stockBefore);
    }

    // 이력 저장이 실패하면 쿠폰 취소 상태 변경도 함께 롤백되는지 검증한다.
    @Test
    void cancellationUpdateRollsBackWhenHistoryInsertFails() {
        int stockBefore = findRemainingStock();
        transitionService.use(couponId, USE_IDEMPOTENCY_KEY);
        couponHistoryRepository.saveAndFlush(
                CouponHistory.issued(couponId, CANCEL_IDEMPOTENCY_KEY, LocalDateTime.now()));

        assertThatThrownBy(() -> transitionService.cancel(couponId, CANCEL_IDEMPOTENCY_KEY))
                .isInstanceOf(DataIntegrityViolationException.class);

        Coupon coupon = couponRepository.findById(couponId).orElseThrow();
        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.USED);
        assertThat(coupon.getUsedAt()).isNotNull();
        assertThat(coupon.getCancelledAt()).isNull();
        assertThat(findRemainingStock()).isEqualTo(stockBefore);
    }

    private Long createUser() {
        String email = "state-transition-" + COUPON_ID_SEQUENCE.incrementAndGet() + "@example.com";
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(
                connection -> {
                    var statement =
                            connection.prepareStatement(
                                    "INSERT INTO users (email, name) VALUES (?, ?)",
                                    Statement.RETURN_GENERATED_KEYS);
                    statement.setString(1, email);
                    statement.setString(2, "쿠폰 상태 전이 테스트 사용자");
                    return statement;
                },
                keyHolder);

        Number generatedKey = keyHolder.getKey();
        if (generatedKey == null) {
            throw new IllegalStateException("테스트 사용자 ID 생성에 실패했습니다.");
        }
        return generatedKey.longValue();
    }

    private List<String> findHistoryTransitions() {
        return jdbcTemplate.queryForList(
                """
                SELECT CONCAT(from_status, '->', to_status)
                  FROM coupon_history
                 WHERE coupon_id = ?
                   AND from_status IS NOT NULL
                 ORDER BY event_at, history_id
                """,
                String.class,
                couponId);
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
