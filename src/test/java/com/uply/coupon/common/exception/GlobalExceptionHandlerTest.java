package com.uply.coupon.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.campaign.service.CacheAutoRecoveryTrigger;
import com.uply.coupon.common.idempotency.IdempotencyChecker;
import com.uply.coupon.coupon.controller.CouponController;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.service.CouponQueryService;
import com.uply.coupon.coupon.service.CouponService;
import com.uply.coupon.coupon.service.CouponStateTransitionService;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;

class GlobalExceptionHandlerTest {

    private static final String IDEMPOTENCY_KEY = "00000000-0000-4000-8000-000000000001";

    private MockMvc mockMvc;
    private CouponService couponService;
    private SimpleMeterRegistry meterRegistry;
    private CacheAutoRecoveryTrigger cacheAutoRecoveryTrigger;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        couponService = mock(CouponService.class);
        meterRegistry = new SimpleMeterRegistry();
        cacheAutoRecoveryTrigger = mock(CacheAutoRecoveryTrigger.class);
        ObjectProvider<CacheAutoRecoveryTrigger> cacheAutoRecoveryTriggerProvider =
                mock(ObjectProvider.class);
        given(cacheAutoRecoveryTriggerProvider.getIfAvailable())
                .willReturn(cacheAutoRecoveryTrigger);
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new CouponController(
                                        couponService,
                                        mock(CouponStateTransitionService.class),
                                        mock(CouponQueryService.class),
                                        mock(IdempotencyChecker.class),
                                        new ObjectMapper().findAndRegisterModules()))
                        .setControllerAdvice(
                                new GlobalExceptionHandler(
                                        meterRegistry, cacheAutoRecoveryTriggerProvider))
                        .build();
    }

    // DB 교착으로 인한 동시성 충돌을 503 CONCURRENCY_CONFLICT로 변환하는지 검증한다.
    @Test
    @DisplayName("커밋 시점 교착은 503 CONCURRENCY_CONFLICT로 응답한다")
    void concurrencyConflictReturns503() throws Exception {
        given(couponService.issue(eq(IDEMPOTENCY_KEY), any(CouponIssueRequest.class)))
                .willThrow(new PessimisticLockingFailureException("deadlock detected"));

        mockMvc.perform(
                        post("/api/coupons/issue")
                                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CONCURRENCY_CONFLICT"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // DB 커넥션 획득 실패를 503 CONNECTION_UNAVAILABLE로 변환하는지 검증한다.
    // 이 경로는 @Transactional 프록시 단계에서 터진 CannotCreateTransactionException을
    // handleConnectionUnavailable()이 직접 받는다 — handleCouponIssue()는 아예 호출되지
    // 않으므로(예외 타입이 다르다) connection_unavailable 카운터는 정확히 1만 늘어야 한다.
    @Test
    @DisplayName("커넥션 획득 실패는 503 CONNECTION_UNAVAILABLE로 응답하고 카운터를 정확히 1 증가시킨다")
    void connectionUnavailableReturns503() throws Exception {
        given(couponService.issue(eq(IDEMPOTENCY_KEY), any(CouponIssueRequest.class)))
                .willThrow(
                        new CannotCreateTransactionException(
                                "Could not open JPA EntityManager for transaction"));

        mockMvc.perform(
                        post("/api/coupons/issue")
                                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CONNECTION_UNAVAILABLE"))
                .andExpect(jsonPath("$.timestamp").exists());

        assertThat(failureCount("connection_unavailable")).isEqualTo(1.0);
    }

    // 이 경로는 V2/V3 전략이 CannotCreateTransactionException을 이미 CouponIssueException으로
    // 감싸 던진 경우다 — handleCouponIssue()만 호출되고 handleConnectionUnavailable()은 호출되지
    // 않는다(예외 타입이 CouponIssueException이라 매칭되지 않는다). 마찬가지로 정확히 1이어야 한다.
    @Test
    @DisplayName(
            "V2·V3의 CONNECTION_UNAVAILABLE 사유는 503 CONNECTION_UNAVAILABLE로 응답하고 카운터를 정확히 1 증가시킨다")
    void connectionUnavailableReasonReturns503() throws Exception {
        given(couponService.issue(eq(IDEMPOTENCY_KEY), any(CouponIssueRequest.class)))
                .willThrow(
                        new CouponIssueException(
                                IssueFailReason.CONNECTION_UNAVAILABLE,
                                new CannotCreateTransactionException(
                                        "Could not open JPA EntityManager for transaction")));

        mockMvc.perform(
                        post("/api/coupons/issue")
                                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CONNECTION_UNAVAILABLE"))
                .andExpect(jsonPath("$.timestamp").exists());

        assertThat(failureCount("connection_unavailable")).isEqualTo(1.0);
    }

    // CAMPAIGN_NOT_CACHED는 openAt/expireAt/stock 키 중 하나가 Redis에 없을 때 발생한다.
    // 다른 503 사유(LOCK_TIMEOUT, SAVE_RESULT_UNKNOWN 등)와 카운터가 섞이지 않아야
    // 감지 지표로 쓸 수 있으므로, reason 태그로 정확히 분리되는지 함께 검증한다.
    @Test
    @DisplayName("캠페인 캐시 미스는 503 CAMPAIGN_NOT_CACHED로 응답하고 전용 카운터를 증가시킨다")
    void campaignNotCachedReturns503AndIncrementsCounter() throws Exception {
        given(couponService.issue(eq(IDEMPOTENCY_KEY), any(CouponIssueRequest.class)))
                .willThrow(new CouponIssueException(IssueFailReason.CAMPAIGN_NOT_CACHED, 1L));

        mockMvc.perform(
                        post("/api/coupons/issue")
                                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CAMPAIGN_NOT_CACHED"))
                .andExpect(jsonPath("$.timestamp").exists());

        double count =
                meterRegistry
                        .get("coupon.issue.failure")
                        .tag("reason", "campaign_not_cached")
                        .counter()
                        .count();
        assertThat(count).isEqualTo(1.0);
    }

    // 3단계(자동 트리거): CAMPAIGN_NOT_CACHED 발생 시 GlobalExceptionHandler가
    // CacheAutoRecoveryTrigger로 campaignId를 그대로 넘겨야 임계치 판정이 캠페인 단위로 된다.
    @Test
    @DisplayName("캠페인 캐시 미스는 예외에 담긴 campaignId로 자동 복구 트리거를 호출한다")
    void campaignNotCachedNotifiesAutoRecoveryTriggerWithCampaignId() throws Exception {
        given(couponService.issue(eq(IDEMPOTENCY_KEY), any(CouponIssueRequest.class)))
                .willThrow(new CouponIssueException(IssueFailReason.CAMPAIGN_NOT_CACHED, 1L));

        mockMvc.perform(
                post("/api/coupons/issue")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()));

        verify(cacheAutoRecoveryTrigger).onCacheMiss(1L);
    }

    // 다른 503 사유는 이 카운터를 건드리면 안 된다 — 섞이면 감지 신호로서 무의미해진다.
    // 자동 복구 트리거도 마찬가지로, CAMPAIGN_NOT_CACHED가 아닌 사유로는 절대 호출되면 안 된다.
    @Test
    @DisplayName("CONNECTION_UNAVAILABLE 발생은 campaign_not_cached 카운터도, 자동 복구 트리거도 건드리지 않는다")
    void connectionUnavailableDoesNotIncrementCampaignNotCachedCounter() throws Exception {
        given(couponService.issue(eq(IDEMPOTENCY_KEY), any(CouponIssueRequest.class)))
                .willThrow(
                        new CannotCreateTransactionException(
                                "Could not open JPA EntityManager for transaction"));

        mockMvc.perform(
                        post("/api/coupons/issue")
                                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest()))
                .andExpect(status().isServiceUnavailable());

        double count =
                meterRegistry
                        .get("coupon.issue.failure")
                        .tag("reason", "campaign_not_cached")
                        .counter()
                        .count();
        assertThat(count).isEqualTo(0.0);
        verifyNoInteractions(cacheAutoRecoveryTrigger);
    }

    // 15.3절이 요구하는 "재고 소진 / 중복 발급" 집계. 두 사유 모두 409로 나가므로 HTTP
    // 상태 코드만으로는 구분되지 않는다 — reason 태그가 유일한 구분 수단이다.
    @Test
    @DisplayName("재고 소진과 중복 발급은 각각의 reason 태그로 따로 집계된다")
    void businessConflictsAreCountedByReasonTag() throws Exception {
        given(couponService.issue(eq(IDEMPOTENCY_KEY), any(CouponIssueRequest.class)))
                .willThrow(new CouponIssueException(IssueFailReason.OUT_OF_STOCK));

        mockMvc.perform(
                        post("/api/coupons/issue")
                                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_STOCK"));

        assertThat(failureCount("out_of_stock")).isEqualTo(1.0);
        assertThat(failureCount("already_issued")).isZero();
    }

    private double failureCount(String reasonTag) {
        return meterRegistry.get("coupon.issue.failure").tag("reason", reasonTag).counter().count();
    }

    private String validRequest() {
        return """
                {
                  "userId": 1,
                  "campaignId": 1,
                  "routeId": "JEJU",
                  "fareClass": "ECONOMY"
                }
                """;
    }
}
