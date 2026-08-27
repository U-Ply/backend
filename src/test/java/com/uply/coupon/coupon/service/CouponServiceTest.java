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
import com.uply.coupon.common.idempotency.IdempotencyClaim;
import com.uply.coupon.common.idempotency.IdempotencyOwnershipMetrics;
import com.uply.coupon.common.idempotency.IdempotencyRequestHasher;
import com.uply.coupon.coupon.api.CouponApiPaths;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.dto.response.CouponIssueResponse;
import com.uply.coupon.coupon.strategy.CouponIssueStrategy;
import com.uply.coupon.coupon.strategy.CouponIssueStrategySelector;
import com.uply.coupon.coupon.strategy.IssueResult;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

    @InjectMocks private CouponServiceImpl couponService;

    @Mock private CouponIssueStrategy couponIssueStrategy;

    @Mock private CouponIssueStrategySelector strategySelector;

    @Mock private StockIdLookup stockIdLookup;
    @Mock private StockIdLookupSelector stockIdLookupSelector;

    @Mock private IdempotencyChecker idempotencyChecker;

    @Mock private IdempotencyOwnershipMetrics idempotencyOwnershipMetrics;

    @Mock private ObjectMapper objectMapper;

    /**
     * 전략이 실제로 저장했다고 가정하는 시각.
     *
     * <p>Instant.now()와 명백히 다른 고정값이어야 응답이 이 값을 그대로 쓰는지(D-2) 검증할 수 있다.
     */
    private static final Instant STORED_ISSUED_AT = Instant.parse("2026-01-02T03:04:05.678Z");

    private static final Instant STORED_EXPIRE_AT = Instant.parse("2026-01-09T03:04:05.678Z");

    private static final String IDEMPOTENCY_KEY = "key-123";
    private static final String OWNER_TOKEN = "owner-token-1";
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

    private void givenAcquired() {
        given(idempotencyChecker.acquire(eq(IDEMPOTENCY_KEY), anyString()))
                .willReturn(IdempotencyClaim.acquired(OWNER_TOKEN));
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

            given(idempotencyChecker.acquire(eq(IDEMPOTENCY_KEY), anyString()))
                    .willReturn(IdempotencyClaim.completed(cachedBodyJson));
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
        @DisplayName("PROCESSING 상태로 인해 acquire에서 예외 발생 시 그대로 예외가 전파된다")
        void issue_processingState_throwsException() {
            // given
            CouponIssueRequest request = createRequest();
            given(idempotencyChecker.acquire(eq(IDEMPOTENCY_KEY), anyString()))
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
            given(idempotencyChecker.acquire(IDEMPOTENCY_KEY, issueRequestHash()))
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
        @DisplayName("최초 요청 성공 시 쿠폰을 발급하고 자신의 ownerToken으로 응답을 완료 처리한다")
        void issue_firstRequest_success() throws Exception {
            // given
            CouponIssueRequest request = createRequest();
            IssueResult successResult =
                    IssueResult.success(COUPON_ID, STORED_ISSUED_AT, STORED_EXPIRE_AT);
            String responseJson = "{\"couponId\":\"7777\"}";

            givenAcquired();
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
            given(
                            idempotencyChecker.complete(
                                    IDEMPOTENCY_KEY,
                                    OWNER_TOKEN,
                                    issueRequestHash(),
                                    responseJson,
                                    200))
                    .willReturn(true);

            // when
            CouponIssueResponse response = couponService.issue(IDEMPOTENCY_KEY, request);

            // then
            assertThat(response.couponId()).isEqualTo(String.valueOf(COUPON_ID));
            assertThat(response.status()).isEqualTo(CouponStatus.ISSUED);

            // 응답 시각은 전략이 저장한 값 그대로여야 한다 (Instant.now()로 새로 만들면 깨진다)
            assertThat(response.issuedAt()).isEqualTo(STORED_ISSUED_AT);
            assertThat(response.expireAt()).isEqualTo(STORED_EXPIRE_AT);

            // 자신의 ownerToken으로 complete를 호출했는지, 소유권 상실 지표는 기록하지 않았는지 검증
            verify(idempotencyChecker, times(1))
                    .complete(IDEMPOTENCY_KEY, OWNER_TOKEN, issueRequestHash(), responseJson, 200);
            verify(idempotencyChecker, never()).release(anyString(), any());
            verifyNoInteractions(idempotencyOwnershipMetrics);
        }

        @Test
        @DisplayName("complete가 소유권 상실로 거부되면 응답은 그대로 반환하되 지표를 기록한다")
        void issue_completeRejected_recordsMetricButStillReturnsResponse() throws Exception {
            CouponIssueRequest request = createRequest();
            IssueResult successResult =
                    IssueResult.success(COUPON_ID, STORED_ISSUED_AT, STORED_EXPIRE_AT);
            String responseJson = "{\"couponId\":\"7777\"}";

            givenAcquired();
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
            given(
                            idempotencyChecker.complete(
                                    IDEMPOTENCY_KEY,
                                    OWNER_TOKEN,
                                    issueRequestHash(),
                                    responseJson,
                                    200))
                    .willReturn(false);

            CouponIssueResponse response = couponService.issue(IDEMPOTENCY_KEY, request);

            assertThat(response.couponId()).isEqualTo(String.valueOf(COUPON_ID));
            verify(idempotencyOwnershipMetrics).recordCompleteRejected(IDEMPOTENCY_KEY);
        }

        @Test
        @DisplayName("비즈니스 로직 수행 중 예외 발생 시 자신의 ownerToken으로 release하여 PROCESSING 선점을 해제한다")
        void issue_firstRequest_exception_releasesProgress() {
            // given
            CouponIssueRequest request = createRequest();

            givenAcquired();
            given(strategySelector.current()).willReturn(couponIssueStrategy);
            given(couponIssueStrategy.name()).willReturn("NO_LOCK");
            given(stockIdLookupSelector.forStrategy("NO_LOCK")).willReturn(stockIdLookup);
            given(stockIdLookup.lookupStockId(CAMPAIGN_ID, ROUTE_ID, FARE_CLASS))
                    .willThrow(new RuntimeException("DB 조회 실패"));

            // when & then
            assertThatThrownBy(() -> couponService.issue(IDEMPOTENCY_KEY, request))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("DB 조회 실패");

            // PROCESSING 키 해제 검증(자신의 ownerToken으로)
            verify(idempotencyChecker, times(1)).release(IDEMPOTENCY_KEY, OWNER_TOKEN);
        }

        @Test
        @DisplayName("release가 소유권 상실로 거부되면 지표를 기록한다")
        void issue_releaseRejected_recordsMetric() {
            CouponIssueRequest request = createRequest();

            givenAcquired();
            given(strategySelector.current()).willReturn(couponIssueStrategy);
            given(couponIssueStrategy.name()).willReturn("NO_LOCK");
            given(stockIdLookupSelector.forStrategy("NO_LOCK")).willReturn(stockIdLookup);
            given(stockIdLookup.lookupStockId(CAMPAIGN_ID, ROUTE_ID, FARE_CLASS))
                    .willThrow(new RuntimeException("DB 조회 실패"));
            given(idempotencyChecker.release(IDEMPOTENCY_KEY, OWNER_TOKEN)).willReturn(false);

            assertThatThrownBy(() -> couponService.issue(IDEMPOTENCY_KEY, request))
                    .isInstanceOf(RuntimeException.class);

            verify(idempotencyOwnershipMetrics).recordReleaseRejected(IDEMPOTENCY_KEY);
        }

        @Test
        @DisplayName("발급 성공 후 응답 캐싱이 실패해도 발급 성공 응답을 그대로 반환하고 PROCESSING 키를 지우지 않는다")
        void issue_afterIssuance_cacheFailure_returnsSuccessAndKeepsProgress() throws Exception {
            // given
            CouponIssueRequest request = createRequest();
            IssueResult successResult =
                    IssueResult.success(COUPON_ID, STORED_ISSUED_AT, STORED_EXPIRE_AT);

            givenAcquired();
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

            // when
            CouponIssueResponse response = couponService.issue(IDEMPOTENCY_KEY, request);

            // then: 발급은 이미 성립했으므로 캐싱 실패와 무관하게 성공 응답을 그대로 반환한다
            assertThat(response.couponId()).isEqualTo(String.valueOf(COUPON_ID));
            assertThat(response.status()).isEqualTo(CouponStatus.ISSUED);

            // 쿠폰은 이미 발급됐다. 여기서 키를 지우면 같은 요청이 발급 로직을 다시 실행해
            // 중복 발급이 난다.
            verify(idempotencyChecker, never()).release(anyString(), any());
            verify(idempotencyChecker, never()).complete(any(), any(), any(), any(), anyInt());
            verify(idempotencyOwnershipMetrics).recordCompleteRejected(IDEMPOTENCY_KEY);
        }

        @Test
        @DisplayName("Redis 보상 실패로 결과가 불명확하면 PROCESSING 키를 유지한다")
        void issue_compensationFailure_keepsProgress() {
            // given
            CouponIssueRequest request = createRequest();

            givenAcquired();
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
            verify(idempotencyChecker, never()).release(anyString(), any());
        }

        @Test
        @DisplayName("발급 전략이 실패 결과를 반환하면 전용 예외를 던지고 자신의 ownerToken으로 PROCESSING 선점을 해제한다")
        void issue_strategyFailure_throwsCouponIssueException() {
            CouponIssueRequest request = createRequest();

            givenAcquired();
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

            verify(idempotencyChecker).release(IDEMPOTENCY_KEY, OWNER_TOKEN);
            verify(idempotencyChecker, never())
                    .complete(anyString(), anyString(), anyString(), anyString(), anyInt());
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
