package com.uply.coupon.coupon.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.common.exception.CouponNotFoundException;
import com.uply.coupon.common.exception.CouponNotReadyException;
import com.uply.coupon.common.exception.GlobalExceptionHandler;
import com.uply.coupon.common.exception.IdempotencyKeyReusedException;
import com.uply.coupon.common.exception.IdempotencyRequestInProgressException;
import com.uply.coupon.common.exception.InvalidStateTransitionException;
import com.uply.coupon.common.idempotency.IdempotencyChecker;
import com.uply.coupon.common.idempotency.IdempotencyRequestHasher;
import com.uply.coupon.common.metrics.CouponIssueMetrics;
import com.uply.coupon.coupon.api.CouponApiPaths;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.dto.response.CouponDetailResponse;
import com.uply.coupon.coupon.dto.response.CouponIssueResponse;
import com.uply.coupon.coupon.dto.response.CouponUseResponse;
import com.uply.coupon.coupon.service.CouponQueryService;
import com.uply.coupon.coupon.service.CouponService;
import com.uply.coupon.coupon.service.CouponStateTransitionService;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;

class CouponControllerTest {

    private static final String IDEMPOTENCY_KEY = "00000000-0000-4000-8000-000000000001";
    private static final Long COUPON_ID = 1001L;
    private static final LocalDateTime USED_AT = LocalDateTime.of(2026, 8, 18, 10, 0);
    private static final LocalDateTime ISSUED_AT = USED_AT.minusDays(1);
    private static final LocalDateTime CANCELLED_AT = USED_AT.plusMinutes(1);
    private static final LocalDateTime EXPIRE_AT = USED_AT.plusDays(1);

    private MockMvc mockMvc;
    private CouponService couponService;
    private CouponStateTransitionService couponStateTransitionService;
    private CouponQueryService couponQueryService;
    private IdempotencyChecker idempotencyChecker;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        couponService = mock(CouponService.class);
        couponStateTransitionService = mock(CouponStateTransitionService.class);
        couponQueryService = mock(CouponQueryService.class);
        idempotencyChecker = mock(IdempotencyChecker.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new CouponController(
                                        couponService,
                                        couponStateTransitionService,
                                        couponQueryService,
                                        idempotencyChecker,
                                        objectMapper))
                        .setControllerAdvice(
                                new GlobalExceptionHandler(
                                        new SimpleMeterRegistry(),
                                        new CouponIssueMetrics(new SimpleMeterRegistry()),
                                        mock(ObjectProvider.class)))
                        .build();
    }

    // 정상적인 쿠폰 발급 요청이 200 응답과 발급 정보를 반환하는지 검증한다.
    @Test
    void issueSuccessReturns200() throws Exception {
        CouponIssueResponse response =
                CouponIssueResponse.builder()
                        .couponId("100")
                        .status(CouponStatus.ISSUED)
                        .issuedAt(Instant.parse("2026-08-15T09:00:00Z"))
                        .expireAt(Instant.parse("2026-08-22T09:00:00Z"))
                        .build();
        given(couponService.issue(eq(IDEMPOTENCY_KEY), any(CouponIssueRequest.class)))
                .willReturn(response);

        mockMvc.perform(
                        post("/api/coupons/issue")
                                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couponId").value("100"))
                .andExpect(jsonPath("$.status").value("ISSUED"));
    }

    // UUID v4 형식이 아닌 발급 멱등성 키를 비즈니스 로직 실행 전에 거부하는지 검증한다.
    @Test
    void nonUuidV4IssueIdempotencyKeyReturns400BeforeIssuance() throws Exception {
        mockMvc.perform(
                        post("/api/coupons/issue")
                                .header("Idempotency-Key", "test")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

        verify(couponService, never()).issue(any(String.class), any(CouponIssueRequest.class));
    }

    // 발급 가능한 재고가 없을 때 409 OUT_OF_STOCK을 반환하는지 검증한다.
    @Test
    void outOfStockReturns409() throws Exception {
        given(couponService.issue(eq(IDEMPOTENCY_KEY), any(CouponIssueRequest.class)))
                .willThrow(new CouponIssueException(IssueFailReason.OUT_OF_STOCK));

        mockMvc.perform(
                        post("/api/coupons/issue")
                                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // 이미 쿠폰을 발급받은 사용자의 재요청에 409 ALREADY_ISSUED를 반환하는지 검증한다.
    @Test
    void alreadyIssuedReturns409() throws Exception {
        given(couponService.issue(eq(IDEMPOTENCY_KEY), any(CouponIssueRequest.class)))
                .willThrow(new CouponIssueException(IssueFailReason.ALREADY_ISSUED));

        mockMvc.perform(
                        post("/api/coupons/issue")
                                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ALREADY_ISSUED"));
    }

    // 존재하지 않는 캠페인에 대한 발급 요청이 404 CAMPAIGN_NOT_FOUND를 반환하는지 검증한다.
    @Test
    void missingCampaignReturns404() throws Exception {
        given(couponService.issue(eq(IDEMPOTENCY_KEY), any(CouponIssueRequest.class)))
                .willThrow(new CampaignNotFoundException(1L, "JEJU", "ECONOMY"));

        mockMvc.perform(
                        post("/api/coupons/issue")
                                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CAMPAIGN_NOT_FOUND"));
    }

    // 발급된 쿠폰의 정상 사용 요청이 200 응답과 사용 시각을 반환하는지 검증한다.
    @Test
    void useSuccessReturns200() throws Exception {
        Coupon coupon = Coupon.issue(COUPON_ID, 1L, 1L, 1L, ISSUED_AT, EXPIRE_AT);
        coupon.use(USED_AT);
        given(couponStateTransitionService.use(COUPON_ID, IDEMPOTENCY_KEY)).willReturn(coupon);

        mockMvc.perform(
                        post("/api/coupons/{couponId}/use", COUPON_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couponId").value(String.valueOf(COUPON_ID)))
                .andExpect(jsonPath("$.status").value("USED"))
                .andExpect(jsonPath("$.usedAt").value("2026-08-18T10:00:00.000Z"));

        verify(couponQueryService).awaitCouponPersistence(COUPON_ID);
        verify(idempotencyChecker)
                .cacheResponse(
                        eq(IDEMPOTENCY_KEY), eq(requestHash("/use")), any(String.class), eq(200));
    }

    // 사용된 쿠폰의 정상 예약 취소 요청이 200 응답과 취소 시각을 반환하는지 검증한다.
    @Test
    void cancelSuccessReturns200() throws Exception {
        Coupon coupon = Coupon.issue(COUPON_ID, 1L, 1L, 1L, ISSUED_AT, EXPIRE_AT);
        coupon.use(USED_AT);
        coupon.cancel(CANCELLED_AT);
        given(couponStateTransitionService.cancel(COUPON_ID, IDEMPOTENCY_KEY)).willReturn(coupon);

        mockMvc.perform(
                        post("/api/coupons/{couponId}/cancel", COUPON_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couponId").value(String.valueOf(COUPON_ID)))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").value("2026-08-18T10:01:00.000Z"));

        verify(couponQueryService).awaitCouponPersistence(COUPON_ID);
        verify(idempotencyChecker)
                .cacheResponse(
                        eq(IDEMPOTENCY_KEY),
                        eq(requestHash("/cancel")),
                        any(String.class),
                        eq(200));
    }

    // 사용·취소 요청에 멱등성 키가 없으면 400 INVALID_REQUEST를 반환하는지 검증한다.
    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(post("/api/coupons/{couponId}/use", COUPON_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    // UUID v4 형식이 아닌 멱등성 키를 상태 변경 전에 거부하는지 검증한다.
    @Test
    void nonUuidV4IdempotencyKeyReturns400BeforeStateChange() throws Exception {
        mockMvc.perform(
                        post("/api/coupons/{couponId}/use", COUPON_ID)
                                .header("Idempotency-Key", "not-a-uuid-v4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

        verify(couponQueryService, never()).awaitCouponPersistence(any(Long.class));
        verify(couponStateTransitionService, never()).use(any(Long.class), any(String.class));
    }

    // 완료된 동일 사용 요청은 상태를 다시 변경하지 않고 최초 응답을 반환하는지 검증한다.
    @Test
    void completedUseRequestReturnsCachedResponseWithoutStateChange() throws Exception {
        String cachedResponse =
                objectMapper.writeValueAsString(CouponUseResponse.of(COUPON_ID, USED_AT));
        given(idempotencyChecker.getCachedResponse(IDEMPOTENCY_KEY, requestHash("/use")))
                .willReturn(Optional.of(cachedResponse));

        mockMvc.perform(
                        post("/api/coupons/{couponId}/use", COUPON_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("USED"))
                .andExpect(jsonPath("$.usedAt").value("2026-08-18T10:00:00.000Z"));

        verify(couponQueryService, never()).awaitCouponPersistence(any(Long.class));
        verify(couponStateTransitionService, never()).use(any(Long.class), any(String.class));
    }

    // 같은 멱등성 키를 다른 요청에 재사용하면 409 IDEMPOTENCY_KEY_REUSED를 반환하는지 검증한다.
    @Test
    void reusedIdempotencyKeyReturns409() throws Exception {
        given(idempotencyChecker.getCachedResponse(IDEMPOTENCY_KEY, requestHash("/use")))
                .willThrow(new IdempotencyKeyReusedException());

        mockMvc.perform(
                        post("/api/coupons/{couponId}/use", COUPON_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));

        verify(couponStateTransitionService, never()).use(any(Long.class), any(String.class));
    }

    // 동일한 요청이 처리 중이면 409 IDEMPOTENCY_REQUEST_IN_PROGRESS를 반환하는지 검증한다.
    @Test
    void processingIdempotencyRequestReturns409() throws Exception {
        given(idempotencyChecker.getCachedResponse(IDEMPOTENCY_KEY, requestHash("/use")))
                .willThrow(new IdempotencyRequestInProgressException());

        mockMvc.perform(
                        post("/api/coupons/{couponId}/use", COUPON_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_REQUEST_IN_PROGRESS"));

        verify(couponStateTransitionService, never()).use(any(Long.class), any(String.class));
    }

    // 쿠폰의 DB 반영이 지연되면 상태를 변경하지 않고 멱등성 진행 상태를 정리하는지 검증한다.
    @Test
    void couponNotReadyClearsProgressWithoutStateChange() throws Exception {
        doThrow(new CouponNotReadyException(COUPON_ID))
                .when(couponQueryService)
                .awaitCouponPersistence(COUPON_ID);

        mockMvc.perform(
                        post("/api/coupons/{couponId}/use", COUPON_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COUPON_NOT_READY"));

        verify(couponStateTransitionService, never()).use(any(Long.class), any(String.class));
        verify(idempotencyChecker).clearProgress(IDEMPOTENCY_KEY);
    }

    // 상태 변경 완료 후 응답 캐싱이 실패해도 멱등성 진행 상태를 삭제하지 않는지 검증한다.
    @Test
    void completedStateChangeDoesNotClearProgressWhenResponseCacheFails() throws Exception {
        Coupon coupon = Coupon.issue(COUPON_ID, 1L, 1L, 1L, ISSUED_AT, EXPIRE_AT);
        coupon.use(USED_AT);
        given(couponStateTransitionService.use(COUPON_ID, IDEMPOTENCY_KEY)).willReturn(coupon);
        doThrow(new IllegalStateException("Redis response cache failure"))
                .when(idempotencyChecker)
                .cacheResponse(
                        eq(IDEMPOTENCY_KEY), eq(requestHash("/use")), any(String.class), eq(200));

        mockMvc.perform(
                        post("/api/coupons/{couponId}/use", COUPON_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isInternalServerError());

        verify(couponStateTransitionService).use(COUPON_ID, IDEMPOTENCY_KEY);
        verify(idempotencyChecker, never()).clearProgress(IDEMPOTENCY_KEY);
    }

    // 존재하지 않는 쿠폰의 상태 변경 요청이 404 COUPON_NOT_FOUND를 반환하는지 검증한다.
    @Test
    void missingCouponReturns404() throws Exception {
        doThrow(new CouponNotFoundException(COUPON_ID))
                .when(couponQueryService)
                .awaitCouponPersistence(COUPON_ID);

        mockMvc.perform(
                        post("/api/coupons/{couponId}/use", COUPON_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COUPON_NOT_FOUND"));

        verify(couponStateTransitionService, never()).use(any(Long.class), any(String.class));
        verify(idempotencyChecker).clearProgress(IDEMPOTENCY_KEY);
    }

    // 허용되지 않은 쿠폰 취소 전이가 409 INVALID_STATE_TRANSITION을 반환하는지 검증한다.
    @Test
    void invalidCancelTransitionReturns409() throws Exception {
        given(couponStateTransitionService.cancel(COUPON_ID, IDEMPOTENCY_KEY))
                .willThrow(
                        new InvalidStateTransitionException(
                                CouponStatus.ISSUED, CouponStatus.CANCELLED));

        mockMvc.perform(
                        post("/api/coupons/{couponId}/cancel", COUPON_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));

        verify(idempotencyChecker).clearProgress(IDEMPOTENCY_KEY);
    }

    // DB 커밋 결과를 확정할 수 없는 예외가 발생하면 멱등성 진행 상태를 유지하는지 검증한다.
    @Test
    void uncertainCommitFailureDoesNotClearProgress() throws Exception {
        given(couponStateTransitionService.use(COUPON_ID, IDEMPOTENCY_KEY))
                .willThrow(new TransactionSystemException("DB commit outcome is unknown"));

        mockMvc.perform(
                        post("/api/coupons/{couponId}/use", COUPON_ID)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_SERVER_ERROR"));

        verify(idempotencyChecker, never()).clearProgress(IDEMPOTENCY_KEY);
    }

    // 멱등성 키 없이 쿠폰 단건 조회가 가능하고 개인정보를 포함하지 않는지 검증한다.
    @Test
    void couponDetailCanBeRetrievedWithoutIdempotencyKey() throws Exception {
        CouponDetailResponse response =
                new CouponDetailResponse(
                        String.valueOf(COUPON_ID),
                        1L,
                        2L,
                        "ICN-JEJ",
                        "ECONOMY",
                        CouponStatus.ISSUED,
                        Instant.parse("2026-08-18T10:00:00Z"),
                        null,
                        null,
                        null,
                        Instant.parse("2026-08-19T10:00:00Z"));
        given(couponQueryService.getCoupon(COUPON_ID)).willReturn(response);

        mockMvc.perform(get("/api/coupons/{couponId}", COUPON_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couponId").value(String.valueOf(COUPON_ID)))
                .andExpect(jsonPath("$.routeId").value("ICN-JEJ"))
                .andExpect(jsonPath("$.fareClass").value("ECONOMY"))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.name").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    // 쿠폰 DB 반영이 준비되지 않은 경우 409 COUPON_NOT_READY 응답을 반환하는지 검증한다.
    @Test
    void couponNotReadyReturns409() throws Exception {
        given(couponQueryService.getCoupon(COUPON_ID))
                .willThrow(new CouponNotReadyException(COUPON_ID));

        mockMvc.perform(get("/api/coupons/{couponId}", COUPON_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COUPON_NOT_READY"));
    }

    // DB와 Redis 발급 진행 기록에 모두 없는 쿠폰은 404 COUPON_NOT_FOUND를 반환하는지 검증한다.
    @Test
    void couponNotFoundReturns404() throws Exception {
        given(couponQueryService.getCoupon(COUPON_ID))
                .willThrow(new CouponNotFoundException(COUPON_ID));

        mockMvc.perform(get("/api/coupons/{couponId}", COUPON_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COUPON_NOT_FOUND"));
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

    private String requestHash(String actionPath) {
        return IdempotencyRequestHasher.sha256(
                "POST", CouponApiPaths.couponActionUri(COUPON_ID, actionPath), "");
    }
}
