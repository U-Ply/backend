package com.uply.coupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.uply.coupon.campaign.domain.Campaign;
import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.repository.CouponRepository;
import jakarta.persistence.EntityManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CampaignCouponRevokeService.class)
class CampaignCouponRevokeIntegrationTest {

    private static final AtomicLong COUPON_ID_SEQUENCE = new AtomicLong(8_100_000_000_000L);
    private static final String IDEMPOTENCY_KEY = "00000000-0000-4000-8000-000000000070";

    @Autowired private CampaignCouponRevokeService service;
    @Autowired private CouponRepository couponRepository;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private CampaignStockRepository campaignStockRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    private Campaign campaign;
    private CampaignStock stock;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();
        campaign =
                campaignRepository.saveAndFlush(
                        Campaign.builder()
                                .name("항공사 일괄 취소 통합 테스트")
                                .openAt(now.minusDays(1))
                                .expireAt(now.plusDays(7))
                                .build());

        stock =
                CampaignStock.builder()
                        .campaign(campaign)
                        .routeId("ICN-JEJ")
                        .fareClass("ECONOMY")
                        .totalStock(10)
                        .build();
        stock.decreaseStock(5);
        stock = campaignStockRepository.saveAndFlush(stock);
    }

    // 실제 MySQL에서 ISSUED만 취소되고 이력은 성공 건수만 저장되며 재고는 유지되는지 확인
    @Test
    void revokesOnlyIssuedCouponsAndKeepsStockUnchanged() {
        Coupon firstIssued = createCoupon(CouponStatus.ISSUED);
        Coupon secondIssued = createCoupon(CouponStatus.ISSUED);
        Coupon used = createCoupon(CouponStatus.USED);
        Coupon cancelled = createCoupon(CouponStatus.CANCELLED);
        Coupon expired = createCoupon(CouponStatus.EXPIRED);
        int stockBefore = findRemainingStock();

        int revokedCount = service.revoke(campaign.getId(), IDEMPOTENCY_KEY);
        entityManager.flush();
        entityManager.clear();

        assertThat(revokedCount).isEqualTo(2);
        assertCouponStatus(firstIssued.getCouponId(), CouponStatus.CANCELLED);
        assertCouponStatus(secondIssued.getCouponId(), CouponStatus.CANCELLED);
        assertCouponStatus(used.getCouponId(), CouponStatus.USED);
        assertCouponStatus(cancelled.getCouponId(), CouponStatus.CANCELLED);
        assertCouponStatus(expired.getCouponId(), CouponStatus.EXPIRED);

        assertThat(findCoupon(firstIssued.getCouponId()).getCancelledAt()).isNotNull();
        assertThat(findCoupon(secondIssued.getCouponId()).getCancelledAt()).isNotNull();
        assertThat(findRemainingStock()).isEqualTo(stockBefore);

        List<HistoryRow> revokeHistories = findRevokeHistories();
        assertThat(revokeHistories).hasSize(2);
        assertThat(revokeHistories)
                .extracting(HistoryRow::couponId)
                .containsExactly(firstIssued.getCouponId(), secondIssued.getCouponId());
        assertThat(revokeHistories)
                .extracting(HistoryRow::fromStatus)
                .containsOnly(CouponStatus.ISSUED.name());
        assertThat(revokeHistories)
                .extracting(HistoryRow::toStatus)
                .containsOnly(CouponStatus.CANCELLED.name());
    }

    private Coupon createCoupon(CouponStatus status) {
        LocalDateTime now = LocalDateTime.now();
        Coupon coupon =
                Coupon.issue(
                        COUPON_ID_SEQUENCE.incrementAndGet(),
                        createUser(),
                        campaign.getId(),
                        stock.getId(),
                        now.minusDays(1),
                        now.plusDays(7));

        switch (status) {
            case ISSUED -> {
                // 발급 상태를 그대로 유지한다.
            }
            case USED -> coupon.use(now.minusHours(2));
            case CANCELLED -> coupon.cancel(now.minusHours(1));
            case EXPIRED -> coupon.expire(now.minusHours(1));
        }

        return couponRepository.saveAndFlush(coupon);
    }

    private Long createUser() {
        String email =
                "revoke-integration-" + COUPON_ID_SEQUENCE.incrementAndGet() + "@example.com";
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(
                connection -> {
                    var statement =
                            connection.prepareStatement(
                                    "INSERT INTO users (email, name) VALUES (?, ?)",
                                    Statement.RETURN_GENERATED_KEYS);
                    statement.setString(1, email);
                    statement.setString(2, "항공사 취소 통합 테스트 사용자");
                    return statement;
                },
                keyHolder);

        Number generatedKey = keyHolder.getKey();
        if (generatedKey == null) {
            throw new IllegalStateException("테스트 사용자 ID 생성에 실패했습니다.");
        }
        return generatedKey.longValue();
    }

    private Coupon findCoupon(Long couponId) {
        return couponRepository.findById(couponId).orElseThrow();
    }

    private void assertCouponStatus(Long couponId, CouponStatus expectedStatus) {
        assertThat(findCoupon(couponId).getStatus()).isEqualTo(expectedStatus);
    }

    private int findRemainingStock() {
        Integer remainingStock =
                jdbcTemplate.queryForObject(
                        "SELECT remaining_stock FROM campaign_stocks WHERE stock_id = ?",
                        Integer.class,
                        stock.getId());
        if (remainingStock == null) {
            throw new IllegalStateException("테스트 재고를 찾을 수 없습니다.");
        }
        return remainingStock;
    }

    private List<HistoryRow> findRevokeHistories() {
        return jdbcTemplate.query(
                """
                SELECT coupon_id, from_status, to_status
                  FROM coupon_history
                 WHERE idempotency_key LIKE ?
                 ORDER BY coupon_id
                """,
                (resultSet, rowNumber) ->
                        new HistoryRow(
                                resultSet.getLong("coupon_id"),
                                resultSet.getString("from_status"),
                                resultSet.getString("to_status")),
                "revoke-%-" + IDEMPOTENCY_KEY);
    }

    private record HistoryRow(Long couponId, String fromStatus, String toStatus) {}
}
