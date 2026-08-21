package com.uply.coupon.coupon.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponHistory;
import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import com.uply.coupon.coupon.strategy.save.SyncMysqlSaveStrategy;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class SyncMysqlSaveStrategyTest {

    @Mock private CouponRepository couponRepository;

    @Mock private CouponHistoryRepository couponHistoryRepository;

    @Mock private CampaignStockRepository campaignStockRepository;

    @InjectMocks private SyncMysqlSaveStrategy syncMysqlSaveStrategy;

    private long expireAtEpochMillis = 1780000000000L;
    private LocalDateTime expireAt =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(expireAtEpochMillis), ZoneOffset.UTC);

    // 상위 전략이 전달하는 발급 시각 (Lua 경로는 Redis TIME, DB 경로는 NOW(3) 기준)
    private long issuedAtEpochMillis = 1770000000000L;
    private LocalDateTime issuedAt =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(issuedAtEpochMillis), ZoneOffset.UTC);

    @Test
    @DisplayName("SyncMysqlSaveStrategy 호출 시 Coupon과 CouponHistory가 정상 저장된다")
    void save_Success() {
        // given
        Long couponId = 1000L;
        Long userId = 100L;
        Long campaignId = 1L;
        Long stockId = 10L;
        String idempotencyKey = "idempotency-key-123";

        given(campaignStockRepository.decreaseRemainingStockIfAvailable(stockId, campaignId))
                .willReturn(1);

        // when
        syncMysqlSaveStrategy.save(
                couponId, userId, campaignId, stockId, idempotencyKey, issuedAt, expireAt);

        // then
        verify(campaignStockRepository).decreaseRemainingStockIfAvailable(stockId, campaignId);

        // 전달받은 issuedAt이 그대로 저장되어야 한다 (JVM now()로 새로 만들면 여기서 깨진다)
        ArgumentCaptor<Coupon> couponCaptor = ArgumentCaptor.forClass(Coupon.class);
        verify(couponRepository).saveAndFlush(couponCaptor.capture());
        assertThat(couponCaptor.getValue().getIssuedAt()).isEqualTo(issuedAt);
        assertThat(couponCaptor.getValue().getExpireAt()).isEqualTo(expireAt);

        // INV-04 대비 - 발급 이력의 event_at은 같은 행의 issued_at과 같아야 한다
        ArgumentCaptor<CouponHistory> historyCaptor = ArgumentCaptor.forClass(CouponHistory.class);
        verify(couponHistoryRepository).saveAndFlush(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getEventAt()).isEqualTo(issuedAt);
    }

    @Test
    @DisplayName("DB 재고 부족으로 차감 실패 시 OUT_OF_STOCK 예외가 발생한다")
    void save_OutOfStock_ThrowsException() {
        // given
        Long couponId = 1000L;
        Long userId = 100L;
        Long campaignId = 1L;
        Long stockId = 10L;
        String idempotencyKey = "idempotency-key-123";

        given(campaignStockRepository.decreaseRemainingStockIfAvailable(stockId, campaignId))
                .willReturn(0);

        // when & then
        assertThatThrownBy(
                        () ->
                                syncMysqlSaveStrategy.save(
                                        couponId,
                                        userId,
                                        campaignId,
                                        stockId,
                                        idempotencyKey,
                                        issuedAt,
                                        expireAt))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.OUT_OF_STOCK);
    }

    @Test
    @DisplayName("uk_campaign_user 위반은 1인 1매 거부이므로 ALREADY_ISSUED로 변환된다")
    void save_CampaignUserUniqueViolation_ThrowsAlreadyIssued() {
        // given
        Long couponId = 1000L;
        Long userId = 100L;
        Long campaignId = 1L;
        Long stockId = 10L;
        String idempotencyKey = "idempotency-key-123";

        given(campaignStockRepository.decreaseRemainingStockIfAvailable(stockId, campaignId))
                .willReturn(1);
        given(couponRepository.saveAndFlush(any(Coupon.class)))
                .willThrow(integrityViolation("uk_campaign_user"));

        // when & then
        assertThatThrownBy(
                        () ->
                                syncMysqlSaveStrategy.save(
                                        couponId,
                                        userId,
                                        campaignId,
                                        stockId,
                                        idempotencyKey,
                                        issuedAt,
                                        expireAt))
                .isInstanceOf(CouponIssueException.class)
                .hasCauseInstanceOf(DataIntegrityViolationException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.ALREADY_ISSUED);
    }

    @Test
    @DisplayName("uq_idempotency_key 위반은 중복 발급이 아니라 멱등성 계층이 뚫린 것이므로 DB_SAVE_FAILED로 변환된다")
    void save_IdempotencyKeyUniqueViolation_ThrowsDbSaveFailed() {
        // given
        Long couponId = 1000L;
        Long userId = 100L;
        Long campaignId = 1L;
        Long stockId = 10L;
        String idempotencyKey = "idempotency-key-123";

        given(campaignStockRepository.decreaseRemainingStockIfAvailable(stockId, campaignId))
                .willReturn(1);
        given(couponRepository.saveAndFlush(any(Coupon.class)))
                .willThrow(integrityViolation("uq_idempotency_key"));

        // when & then
        // 같은 UNIQUE 위반이라도 제약이 다르면 의미가 다르다.
        // 이것까지 ALREADY_ISSUED(409)로 응답하면 설계 위반이 정상 거부로 숨는다.
        assertThatThrownBy(
                        () ->
                                syncMysqlSaveStrategy.save(
                                        couponId,
                                        userId,
                                        campaignId,
                                        stockId,
                                        idempotencyKey,
                                        issuedAt,
                                        expireAt))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.DB_SAVE_FAILED);
    }

    @Test
    @DisplayName("DB 락 대기 한계 초과는 DB 저장 실패가 아니라 LOCK_TIMEOUT으로 구분된다")
    void save_LockWaitTimeout_ThrowsLockTimeout() {
        // given
        Long couponId = 1000L;
        Long userId = 100L;
        Long campaignId = 1L;
        Long stockId = 10L;
        String idempotencyKey = "idempotency-key-123";

        given(campaignStockRepository.decreaseRemainingStockIfAvailable(stockId, campaignId))
                .willThrow(new CannotAcquireLockException("Lock wait timeout exceeded"));

        // when & then
        // k6가 coupon_lock_timeout으로 따로 집계하고 503 재시도 가능으로 응답해야 한다.
        assertThatThrownBy(
                        () ->
                                syncMysqlSaveStrategy.save(
                                        couponId,
                                        userId,
                                        campaignId,
                                        stockId,
                                        idempotencyKey,
                                        issuedAt,
                                        expireAt))
                .isInstanceOf(CouponIssueException.class)
                .hasCauseInstanceOf(CannotAcquireLockException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.LOCK_TIMEOUT);
    }

    /** Hibernate가 제약 이름을 담아 올려보내는 형태를 그대로 흉내낸다. */
    private DataIntegrityViolationException integrityViolation(String constraintName) {
        return new DataIntegrityViolationException(
                "could not execute statement",
                new ConstraintViolationException(
                        "Duplicate entry", new SQLException("Duplicate entry"), constraintName));
    }

    @Test
    @DisplayName("중복이 아닌 정합성 위반(FK·CHECK)은 ALREADY_ISSUED가 아니라 DB_SAVE_FAILED로 변환된다")
    void save_NonDuplicateIntegrityViolation_ThrowsDbSaveFailed() {
        // given
        Long couponId = 1000L;
        Long userId = 100L;
        Long campaignId = 1L;
        Long stockId = 10L;
        String idempotencyKey = "idempotency-key-123";

        given(campaignStockRepository.decreaseRemainingStockIfAvailable(stockId, campaignId))
                .willReturn(1);
        given(couponRepository.saveAndFlush(any(Coupon.class)))
                .willThrow(
                        new DataIntegrityViolationException(
                                "Cannot add or update a child row: a foreign key constraint fails"));

        // when & then
        // FK 위반을 ALREADY_ISSUED로 응답하면 클라이언트가 "이미 발급받았다"는 틀린 사실을 통보받는다.
        assertThatThrownBy(
                        () ->
                                syncMysqlSaveStrategy.save(
                                        couponId,
                                        userId,
                                        campaignId,
                                        stockId,
                                        idempotencyKey,
                                        issuedAt,
                                        expireAt))
                .isInstanceOf(CouponIssueException.class)
                .hasCauseInstanceOf(DataIntegrityViolationException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.DB_SAVE_FAILED);
    }

    @Test
    @DisplayName("커넥션 고갈 등 기타 인프라 예외는 DB_SAVE_FAILED로 변환되며 원인 예외가 유지된다")
    void save_GeneralException_ThrowsDbSaveFailed() {
        // given
        Long couponId = 1000L;
        Long userId = 100L;
        Long campaignId = 1L;
        Long stockId = 10L;
        String idempotencyKey = "idempotency-key-123";

        given(campaignStockRepository.decreaseRemainingStockIfAvailable(stockId, campaignId))
                .willThrow(new DataAccessResourceFailureException("Connection pool exhausted"));

        // when & then
        assertThatThrownBy(
                        () ->
                                syncMysqlSaveStrategy.save(
                                        couponId,
                                        userId,
                                        campaignId,
                                        stockId,
                                        idempotencyKey,
                                        issuedAt,
                                        expireAt))
                .isInstanceOf(CouponIssueException.class)
                .hasCauseInstanceOf(DataAccessResourceFailureException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.DB_SAVE_FAILED);
    }
}
