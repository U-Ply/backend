package com.uply.coupon.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.uply.coupon.campaign.domain.Campaign;
import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.user.repository.UserRepository;
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
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UserRepository.class)
class CouponRepositoryTest {

    private static final AtomicLong COUPON_ID_SEQUENCE = new AtomicLong(8_000_000_000_000L);

    @Autowired private CouponRepository couponRepository;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private CampaignStockRepository campaignStockRepository;
    @Autowired private UserRepository userRepository;
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
    void findsIssuedCouponsOfCampaignInCouponIdOrderWithKeysetPagination() {
        Coupon firstIssuedCoupon = createCoupon(campaign, stock, CouponStatus.ISSUED);
        Coupon secondIssuedCoupon = createCoupon(campaign, stock, CouponStatus.ISSUED);
        Coupon thirdIssuedCoupon = createCoupon(campaign, stock, CouponStatus.ISSUED);
        createCoupon(campaign, stock, CouponStatus.USED);

        Campaign otherCampaign = createCampaign("다른 캠페인");
        CampaignStock otherStock = createStock(otherCampaign);
        createCoupon(otherCampaign, otherStock, CouponStatus.ISSUED);

        entityManager.clear();

        List<Long> firstPage =
                couponRepository.findIssuedCouponIdsByCampaignIdAfter(
                        campaign.getId(), 0L, PageRequest.of(0, 2));
        List<Long> secondPage =
                couponRepository.findIssuedCouponIdsByCampaignIdAfter(
                        campaign.getId(), secondIssuedCoupon.getCouponId(), PageRequest.of(0, 2));

        assertThat(firstPage)
                .containsExactly(firstIssuedCoupon.getCouponId(), secondIssuedCoupon.getCouponId());
        assertThat(secondPage).containsExactly(thirdIssuedCoupon.getCouponId());
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

    // 특정 사용자의 쿠폰만 발급 시각과 쿠폰 ID 기준 최신순으로 조회되는지 검증한다.
    @Test
    void findsOnlyRequestedUsersCouponsInLatestOrder() {
        Long targetUserId = createUser();
        LocalDateTime now = LocalDateTime.now();
        Coupon olderCoupon =
                createCoupon(
                        campaign, stock, targetUserId, CouponStatus.ISSUED, now.minusMinutes(1));

        Campaign otherCampaign = createCampaign("사용자 쿠폰 목록 테스트 캠페인");
        CampaignStock otherStock = createStock(otherCampaign);
        Coupon newerCoupon =
                createCoupon(otherCampaign, otherStock, targetUserId, CouponStatus.ISSUED, now);
        createCoupon(campaign, stock, CouponStatus.ISSUED);

        entityManager.clear();

        List<Coupon> coupons =
                couponRepository.findAllByUserIdOrderByIssuedAtDescCouponIdDesc(targetUserId);

        assertThat(coupons)
                .extracting(Coupon::getCouponId)
                .containsExactly(newerCoupon.getCouponId(), olderCoupon.getCouponId());
    }

    // 재고 ID와 캠페인 ID로 노선과 좌석 등급 Projection을 조회하는지 검증한다.
    @Test
    void findsRouteAndFareByStockAndCampaign() {
        CampaignStockRepository.RouteFareProjection routeFare =
                campaignStockRepository
                        .findRouteFareByStockIdAndCampaignId(stock.getId(), campaign.getId())
                        .orElseThrow();

        assertThat(routeFare.getRouteId()).isEqualTo("ICN-JEJ");
        assertThat(routeFare.getFareClass()).isEqualTo("ECONOMY");
    }

    // users 테이블의 기본키를 기준으로 사용자 존재 여부를 정확히 확인하는지 검증한다.
    @Test
    void checksWhetherUserExists() {
        Long existingUserId = createUser();

        assertThat(userRepository.existsById(existingUserId)).isTrue();
        assertThat(userRepository.existsById(Long.MAX_VALUE)).isFalse();
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
        return createCoupon(targetCampaign, targetStock, createUser(), status, now);
    }

    private Coupon createCoupon(
            Campaign targetCampaign,
            CampaignStock targetStock,
            Long userId,
            CouponStatus status,
            LocalDateTime issuedAt) {
        Coupon coupon =
                Coupon.issue(
                        COUPON_ID_SEQUENCE.incrementAndGet(),
                        userId,
                        targetCampaign.getId(),
                        targetStock.getId(),
                        issuedAt,
                        issuedAt.plusDays(7));

        switch (status) {
            case ISSUED -> {
                // 발급 상태를 그대로 유지한다.
            }
            case USED -> coupon.use(issuedAt.plusMinutes(1));
            case CANCELLED -> coupon.cancel(issuedAt.plusMinutes(1));
            case EXPIRED -> coupon.expire(issuedAt.plusMinutes(1));
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
