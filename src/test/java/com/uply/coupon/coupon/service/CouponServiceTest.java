package com.uply.coupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.campaign.service.StockIdLookup;
import com.uply.coupon.campaign.service.StockIdLookupSelector;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.common.exception.IdempotencyKeyReusedException;
import com.uply.coupon.common.exception.IdempotencyRequestInProgressException;
import com.uply.coupon.common.idempotency.IdempotencyChecker;
import com.uply.coupon.common.idempotency.IdempotencyRequestHasher;
import com.uply.coupon.common.metrics.CouponIssueMetrics;
import com.uply.coupon.coupon.api.CouponApiPaths;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.dto.response.CouponIssueResponse;
import com.uply.coupon.coupon.strategy.CouponIssueStrategy;
import com.uply.coupon.coupon.strategy.CouponIssueStrategySelector;
import com.uply.coupon.coupon.strategy.IssueResult;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

    // 발급 성공 카운터. 목이라 아무 일도 하지 않지만, 주입되지 않으면 발급 성공 경로에서
    // NPE가 난다.
    @Mock private CouponIssueMetrics couponIssueMetrics;

    @InjectMocks private CouponServiceImpl couponService;

    @Mock private CouponIssueStrategy couponIssueStrategy;

    @Mock private CouponIssueStrategySelector strategySelector;

    @Mock private StockIdLookup stockIdLookup;
    @Mock private StockIdLookupSelector stockIdLookupSelector;

    @Mock private IdempotencyChecker idempotencyChecker;

    @Mock private ObjectMapper objectMapper;

    /**
     * 전략이 실제로 저장했다고 가정하는 시각.
     *
     * <p>Instant.now()와 명백히 다른 고정값이어야 응답이 이 값을 그대로 쓰는지(D-2) 검증할 수 있다.
     */
    private static final Instant STORED_ISSUED_AT = Instant.parse("2026-01-02T03:04:05.678Z");

    private static final Instant STORED_EXPIRE_AT = Instant.parse("2026-01-09T03:04:05.678Z");

    private static final String IDEMPOTENCY_KEY = "key-123";
    private static final Long CAMPAIGN_ID = 1L;
    private static final String ROUTE_ID = "JEJU";
    private static final String FARE_CLASS = "BUSINESS";
    private static final Long USER_ID = 500L;
    private static final Long STOCK_ID = 999L;
    private static final Long COUPON_ID = 7777L;
    private static final String ISSUE_REQUEST_JSON =
            "{\"userId\":500,\"campaignId\":1,\"routeId\":\"JEJU\",\"fareClass\":\"BUSINESS\"}";

    private CouponIssueRequest createRequest() {
        return new CouponIssueRequest(USER_ID, CAMPAIGN_ID, ROUTE_ID, FARE_CLASS);
    }

    private String issueRequestHash() {
        return IdempotencyRequestHasher.sha256(
                "POST", CouponApiPaths.ISSUE_URI, ISSUE_REQUEST_JSON);
    }

    @Nested
    @DisplayName("issue 메서드 - 멱등성 캐시 Hit 시나리오")
    class CacheHitTest {

        @Test
        @DisplayName("COMPLETED 캐시가 존재하면 비즈니스 로직을 실행하지 않고 즉시 역직렬화된 응답을 반환한다")
        void issue_cacheHit_returnsCachedResponse() throws Exception {
            // given
            CouponIssueRequest request = createRequest();
            String cachedBodyJson = "{\"couponId\":\"7777\",\"status\":\"ISSUED\"}";
            CouponIssueResponse expectedResponse =
                    CouponIssueResponse.builder()
                            .couponId(String.valueOf(COUPON_ID))
                            .status(CouponStatus.ISSUED)
                            .build();

            given(idempotencyChecker.getCachedResponse(eq(IDEMPOTENCY_KEY), anyString()))
                    .willReturn(Optional.of(cachedBodyJson));
            given(objectMapper.readValue(cachedBodyJson, CouponIssueResponse.class))
                    .willReturn(expectedResponse);

            // when
            CouponIssueResponse result = couponService.issue(IDEMPOTENCY_KEY, request);

            // then
            assertThat(result.couponId()).isEqualTo(String.valueOf(COUPON_ID));
            assertThat(result.status()).isEqualTo(CouponStatus.ISSUED);

            // 중요: 캐시 Hit 시 주식 조회 및 발급 전략이 호출되지 않음을 검증
            verifyNoInteractions(stockIdLookup);
            verifyNoInteractions(stockIdLookupSelector);
            verifyNoInteractions(couponIssueStrategy);
            verifyNoInteractions(strategySelector);
        }

        @Test
        @DisplayName("PROCESSING 상태로 인해 getCachedResponse에서 예외 발생 시 그대로 예외가 전파된다")
        void issue_processingState_throwsException() {
            // given
            CouponIssueRequest request = createRequest();
            given(idempotencyChecker.getCachedResponse(eq(IDEMPOTENCY_KEY), anyString()))
                    .willThrow(new IdempotencyRequestInProgressException());

            // when & then
            assertThatThrownBy(() -> couponService.issue(IDEMPOTENCY_KEY, request))
                    .isInstanceOf(IdempotencyRequestInProgressException.class);

            verifyNoInteractions(stockIdLookup);
            verifyNoInteractions(stockIdLookupSelector);
            verifyNoInteractions(couponIssueStrategy);
            verifyNoInteractions(strategySelector);
        }

        // 같은 키가 다른 발급 요청에 재사용되면 요청 해시 충돌로 거부하는지 검증한다.
        @Test
        @DisplayName("같은 멱등성 키를 다른 발급 요청에 재사용하면 IDEMPOTENCY_KEY_REUSED를 반환한다")
        void issue_reusedKeyWithDifferentRequest_throwsException() throws Exception {
            CouponIssueRequest request = createRequest();

            given(objectMapper.writeValueAsString(request)).willReturn(ISSUE_REQUEST_JSON);
            given(idempotencyChecker.getCachedResponse(IDEMPOTENCY_KEY, issueRequestHash()))
                    .willThrow(new IdempotencyKeyReusedException());

            assertThatThrownBy(() -> couponService.issue(IDEMPOTENCY_KEY, request))
                    .isInstanceOf(IdempotencyKeyReusedException.class);

            verifyNoInteractions(stockIdLookupSelector, couponIssueStrategy, strategySelector);
        }
    }

    @Nested
    @DisplayName("issue 메서드 - 최초 요청 시나리오")
    class FirstRequestTest {

        @Test
        @DisplayName("최초 요청 성공 시 쿠폰을 발급하고 응답을 Redis에 캐싱한다")
        void issue_firstRequest_success() throws Exception {
            // given
            CouponIssueRequest request = createRequest();
            IssueResult successResult =
                    IssueResult.success(COUPON_ID, STORED_ISSUED_AT, STORED_EXPIRE_AT);
            String responseJson = "{\"couponId\":\"7777\"}";

            given(idempotencyChecker.getCachedResponse(IDEMPOTENCY_KEY, issueRequestHash()))
                    .willReturn(Optional.empty());
            given(strategySelector.current()).willReturn(couponIssueStrategy);
            given(couponIssueStrategy.name()).willReturn("PESSIMISTIC_LOCK");
            given(stockIdLookupSelector.forStrategy("PESSIMISTIC_LOCK")).willReturn(stockIdLookup);
            given(stockIdLookup.lookupStockId(CAMPAIGN_ID, ROUTE_ID, FARE_CLASS))
                    .willReturn(STOCK_ID);
            given(couponIssueStrategy.issue(CAMPAIGN_ID, USER_ID, STOCK_ID, IDEMPOTENCY_KEY))
                    .willReturn(successResult);
            given(objectMapper.writeValueAsString(request)).willReturn(ISSUE_REQUEST_JSON);
            given(objectMapper.writeValueAsString(any(CouponIssueResponse.class)))
                    .willReturn(responseJson);

            // when
            CouponIssueResponse response = couponService.issue(IDEMPOTENCY_KEY, request);

            // then
            assertThat(response.couponId()).isEqualTo(String.valueOf(COUPON_ID));
            assertThat(response.status()).isEqualTo(CouponStatus.ISSUED);

            // 응답 시각은 전략이 저장한 값 그대로여야 한다 (Instant.now()로 새로 만들면 깨진다)
            assertThat(response.issuedAt()).isEqualTo(STORED_ISSUED_AT);
            assertThat(response.expireAt()).isEqualTo(STORED_EXPIRE_AT);

            // 멱등성 응답 캐싱 호출 검증
            verify(idempotencyChecker, times(1))
                    .cacheResponse(IDEMPOTENCY_KEY, issueRequestHash(), responseJson, 200);
            verify(idempotencyChecker, never()).clearProgress(anyString());
        }

        @Test
        @DisplayName("비즈니스 로직 수행 중 예외 발생 시 clearProgress를 호출하여 PROCESSING 선점을 해제하고 예외를 전파한다")
        void issue_firstRequest_exception_clearsProgress() {
            // given
            CouponIssueRequest request = createRequest();

            given(idempotencyChecker.getCachedResponse(eq(IDEMPOTENCY_KEY), anyString()))
                    .willReturn(Optional.empty());
            given(strategySelector.current()).willReturn(couponIssueStrategy);
            given(couponIssueStrategy.name()).willReturn("NO_LOCK");
            given(stockIdLookupSelector.forStrategy("NO_LOCK")).willReturn(stockIdLookup);
            given(stockIdLookup.lookupStockId(CAMPAIGN_ID, ROUTE_ID, FARE_CLASS))
                    .willThrow(new RuntimeException("DB 조회 실패"));

            // when & then
            assertThatThrownBy(() -> couponService.issue(IDEMPOTENCY_KEY, request))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("DB 조회 실패");

            // PROCESSING 키 삭제 검증
            verify(idempotencyChecker, times(1)).clearProgress(IDEMPOTENCY_KEY);
        }

        @Test
        @DisplayName("발급 성공 후 응답 캐싱이 실패하면 PROCESSING 키를 지우지 않는다")
        void issue_afterIssuance_cacheFailure_keepsProgress() throws Exception {
            // given
            CouponIssueRequest request = createRequest();
            IssueResult successResult =
                    IssueResult.success(COUPON_ID, STORED_ISSUED_AT, STORED_EXPIRE_AT);

            given(idempotencyChecker.getCachedResponse(eq(IDEMPOTENCY_KEY), anyString()))
                    .willReturn(Optional.empty());
            given(strategySelector.current()).willReturn(couponIssueStrategy);
            given(couponIssueStrategy.name()).willReturn("PESSIMISTIC_LOCK");
            given(stockIdLookupSelector.forStrategy("PESSIMISTIC_LOCK")).willReturn(stockIdLookup);
            given(stockIdLookup.lookupStockId(CAMPAIGN_ID, ROUTE_ID, FARE_CLASS))
                    .willReturn(STOCK_ID);
            given(couponIssueStrategy.issue(CAMPAIGN_ID, USER_ID, STOCK_ID, IDEMPOTENCY_KEY))
                    .willReturn(successResult);
            given(objectMapper.writeValueAsString(request)).willReturn(ISSUE_REQUEST_JSON);

            // 발급은 끝났고 응답 직렬화 단계에서만 실패하는 상황
            given(objectMapper.writeValueAsString(any(CouponIssueResponse.class)))
                    .willThrow(new JsonProcessingException("직렬화 실패") {});

            // when & then
            assertThatThrownBy(() -> couponService.issue(IDEMPOTENCY_KEY, request))
                    .isInstanceOf(IllegalStateException.class);

            // 쿠폰은 이미 발급됐다. 여기서 키를 지우면 같은 요청이 발급 로직을 다시 실행해
            // 중복 발급이 난다.
            verify(idempotencyChecker, never()).clearProgress(anyString());
        }

        @Test
        @DisplayName("Redis 보상 실패로 결과가 불명확하면 PROCESSING 키를 유지한다")
        void issue_compensationFailure_keepsProgress() {
            // given
            CouponIssueRequest request = createRequest();

            given(idempotencyChecker.getCachedResponse(eq(IDEMPOTENCY_KEY), anyString()))
                    .willReturn(Optional.empty());
            given(strategySelector.current()).willReturn(couponIssueStrategy);
            given(couponIssueStrategy.name()).willReturn("LUA_SCRIPT");
            given(stockIdLookupSelector.forStrategy("LUA_SCRIPT")).willReturn(stockIdLookup);
            given(stockIdLookup.lookupStockId(CAMPAIGN_ID, ROUTE_ID, FARE_CLASS))
                    .willReturn(STOCK_ID);
            given(couponIssueStrategy.issue(CAMPAIGN_ID, USER_ID, STOCK_ID, IDEMPOTENCY_KEY))
                    .willThrow(
                            new CouponIssueException(
                                    com.uply.coupon.coupon.strategy.IssueFailReason
                                            .SAVE_RESULT_UNKNOWN));

            // when & then
            assertThatThrownBy(() -> couponService.issue(IDEMPOTENCY_KEY, request))
                    .isInstanceOf(CouponIssueException.class);

            // 재고가 덜 복구된 상태이므로 재시도를 허용하면 재시도할수록 재고만 줄어든다.
            verify(idempotencyChecker, never()).clearProgress(anyString());
        }

        @Test
        @DisplayName("발급 전략이 실패 결과를 반환하면 전용 예외를 던지고 PROCESSING 선점을 해제한다")
        void issue_strategyFailure_throwsCouponIssueException() {
            CouponIssueRequest request = createRequest();

            given(idempotencyChecker.getCachedResponse(eq(IDEMPOTENCY_KEY), anyString()))
                    .willReturn(Optional.empty());
            given(strategySelector.current()).willReturn(couponIssueStrategy);
            given(couponIssueStrategy.name()).willReturn("PESSIMISTIC_LOCK");
            given(stockIdLookupSelector.forStrategy("PESSIMISTIC_LOCK")).willReturn(stockIdLookup);
            given(stockIdLookup.lookupStockId(CAMPAIGN_ID, ROUTE_ID, FARE_CLASS))
                    .willReturn(STOCK_ID);
            given(couponIssueStrategy.issue(CAMPAIGN_ID, USER_ID, STOCK_ID, IDEMPOTENCY_KEY))
                    .willReturn(
                            IssueResult.fail(
                                    com.uply.coupon.coupon.strategy.IssueFailReason.OUT_OF_STOCK));

            assertThatThrownBy(() -> couponService.issue(IDEMPOTENCY_KEY, request))
                    .isInstanceOf(CouponIssueException.class)
                    .extracting("reason")
                    .isEqualTo(com.uply.coupon.coupon.strategy.IssueFailReason.OUT_OF_STOCK);

            verify(idempotencyChecker).clearProgress(IDEMPOTENCY_KEY);
            verify(idempotencyChecker, never())
                    .cacheResponse(anyString(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("idempotencyKey가 null이나 공백이면 멱등성 검사 및 캐싱을 건너뛰고 정상 발급한다")
        void issue_noIdempotencyKey_success() {
            // given
            CouponIssueRequest request = createRequest();
            IssueResult successResult =
                    IssueResult.success(COUPON_ID, STORED_ISSUED_AT, STORED_EXPIRE_AT);

            given(strategySelector.current()).willReturn(couponIssueStrategy);
            given(couponIssueStrategy.name()).willReturn("NO_LOCK");
            given(stockIdLookupSelector.forStrategy("NO_LOCK")).willReturn(stockIdLookup);
            given(stockIdLookup.lookupStockId(CAMPAIGN_ID, ROUTE_ID, FARE_CLASS))
                    .willReturn(STOCK_ID);
            given(couponIssueStrategy.issue(CAMPAIGN_ID, USER_ID, STOCK_ID, null))
                    .willReturn(successResult);

            // when
            CouponIssueResponse response = couponService.issue(null, request);

            // then
            assertThat(response.couponId()).isEqualTo(String.valueOf(COUPON_ID));
            assertThat(response.issuedAt()).isEqualTo(STORED_ISSUED_AT);
            verifyNoInteractions(idempotencyChecker);
        }
    }
}
