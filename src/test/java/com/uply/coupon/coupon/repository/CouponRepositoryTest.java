package com.uply.coupon.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.uply.coupon.campaign.domain.Campaign;
import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponStatus;
import jakarta.persistence.EntityManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CouponRepositoryTest {

    private static final AtomicLong COUPON_ID_SEQUENCE = new AtomicLong(8_000_000_000_000L);

    @Autowired private CouponRepository couponRepository;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private CampaignStockRepository campaignStockRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    private Campaign campaign;
    private CampaignStock stock;

    @BeforeEach
    void setUp() {
        campaign = createCampaign("항공사 일괄 취소 테스트 캠페인");
        stock = createStock(campaign);
    }

    @Test
    void findsOnlyIssuedCouponsOfCampaign() {
        Coupon issuedCoupon = createCoupon(campaign, stock, CouponStatus.ISSUED);
        createCoupon(campaign, stock, CouponStatus.USED);

        Campaign otherCampaign = createCampaign("다른 캠페인");
        CampaignStock otherStock = createStock(otherCampaign);
        createCoupon(otherCampaign, otherStock, CouponStatus.ISSUED);

        entityManager.clear();

        List<Long> couponIds = couponRepository.findIssuedCouponIdsByCampaignId(campaign.getId());

        assertThat(couponIds).containsExactly(issuedCoupon.getCouponId());
    }

    @Test
    void revokesIssuedCouponAndRecordsCancelledAtWithoutChangingStock() {
        Coupon coupon = createCoupon(campaign, stock, CouponStatus.ISSUED);
        int stockBefore = findRemainingStock(stock.getId());

        int updatedRows = couponRepository.revokeIfIssued(coupon.getCouponId());

        Coupon revokedCoupon = couponRepository.findById(coupon.getCouponId()).orElseThrow();
        assertThat(updatedRows).isEqualTo(1);
        assertThat(revokedCoupon.getStatus()).isEqualTo(CouponStatus.CANCELLED);
        assertThat(revokedCoupon.getCancelledAt()).isNotNull();
        assertThat(findRemainingStock(stock.getId())).isEqualTo(stockBefore);
    }

    @ParameterizedTest
    @EnumSource(
            value = CouponStatus.class,
            names = {"USED", "CANCELLED", "EXPIRED"})
    void doesNotRevokeCouponThatIsNotIssued(CouponStatus currentStatus) {
        Coupon coupon = createCoupon(campaign, stock, currentStatus);

        int updatedRows = couponRepository.revokeIfIssued(coupon.getCouponId());

        Coupon unchangedCoupon = couponRepository.findById(coupon.getCouponId()).orElseThrow();
        assertThat(updatedRows).isZero();
        assertThat(unchangedCoupon.getStatus()).isEqualTo(currentStatus);
    }

    private Campaign createCampaign(String name) {
        LocalDateTime now = LocalDateTime.now();
        return campaignRepository.saveAndFlush(
                Campaign.builder()
                        .name(name)
                        .openAt(now.minusHours(1))
                        .expireAt(now.plusDays(7))
                        .build());
    }

    private CampaignStock createStock(Campaign targetCampaign) {
        return campaignStockRepository.saveAndFlush(
                CampaignStock.builder()
                        .campaign(targetCampaign)
                        .routeId("ICN-JEJ")
                        .fareClass("ECONOMY")
                        .totalStock(10)
                        .build());
    }

    private Coupon createCoupon(
            Campaign targetCampaign, CampaignStock targetStock, CouponStatus status) {
        LocalDateTime now = LocalDateTime.now();
        Coupon coupon =
                Coupon.issue(
                        COUPON_ID_SEQUENCE.incrementAndGet(),
                        createUser(),
                        targetCampaign.getId(),
                        targetStock.getId(),
                        now,
                        now.plusDays(7));

        switch (status) {
            case ISSUED -> {
                // 발급 상태를 그대로 유지한다.
            }
            case USED -> coupon.use(now.plusMinutes(1));
            case CANCELLED -> coupon.cancel(now.plusMinutes(1));
            case EXPIRED -> coupon.expire(now.plusMinutes(1));
        }

        return couponRepository.saveAndFlush(coupon);
    }

    private Long createUser() {
        String email = "revoke-test-" + COUPON_ID_SEQUENCE.incrementAndGet() + "@example.com";
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(
                connection -> {
                    var statement =
                            connection.prepareStatement(
                                    "INSERT INTO users (email, name) VALUES (?, ?)",
                                    Statement.RETURN_GENERATED_KEYS);
                    statement.setString(1, email);
                    statement.setString(2, "항공사 취소 테스트 사용자");
                    return statement;
                },
                keyHolder);

        Number generatedKey = keyHolder.getKey();
        if (generatedKey == null) {
            throw new IllegalStateException("테스트 사용자 ID 생성에 실패했습니다.");
        }
        return generatedKey.longValue();
    }

    private int findRemainingStock(Long stockId) {
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
