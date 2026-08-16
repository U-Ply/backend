package com.uply.coupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.campaign.service.StockIdLookup;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.common.exception.IdempotencyRequestInProgressException;
import com.uply.coupon.common.idempotency.IdempotencyChecker;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.dto.response.CouponIssueResponse;
import com.uply.coupon.coupon.strategy.CouponIssueStrategy;
import com.uply.coupon.coupon.strategy.CouponIssueStrategySelector;
import com.uply.coupon.coupon.strategy.IssueResult;
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

    @InjectMocks private CouponServiceImpl couponService;

    @Mock private CouponIssueStrategy couponIssueStrategy;

    @Mock private CouponIssueStrategySelector strategySelector;

    @Mock private StockIdLookup stockIdLookup;

    @Mock private IdempotencyChecker idempotencyChecker;

    @Mock private ObjectMapper objectMapper;

    private static final String IDEMPOTENCY_KEY = "key-123";
    private static final Long CAMPAIGN_ID = 1L;
    private static final String ROUTE_ID = "JEJU";
    private static final String FARE_CLASS = "BUSINESS";
    private static final Long USER_ID = 500L;
    private static final Long STOCK_ID = 999L;
    private static final Long COUPON_ID = 7777L;

    private CouponIssueRequest createRequest() {
        return new CouponIssueRequest(USER_ID, CAMPAIGN_ID, ROUTE_ID, FARE_CLASS);
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

            given(idempotencyChecker.getCachedResponse(IDEMPOTENCY_KEY))
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
            verifyNoInteractions(couponIssueStrategy);
            verifyNoInteractions(strategySelector);
        }

        @Test
        @DisplayName("PROCESSING 상태로 인해 getCachedResponse에서 예외 발생 시 그대로 예외가 전파된다")
        void issue_processingState_throwsException() {
            // given
            CouponIssueRequest request = createRequest();
            given(idempotencyChecker.getCachedResponse(IDEMPOTENCY_KEY))
                    .willThrow(new IdempotencyRequestInProgressException());

            // when & then
            assertThatThrownBy(() -> couponService.issue(IDEMPOTENCY_KEY, request))
                    .isInstanceOf(IdempotencyRequestInProgressException.class);

            verifyNoInteractions(stockIdLookup);
            verifyNoInteractions(couponIssueStrategy);
            verifyNoInteractions(strategySelector);
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
            IssueResult successResult = IssueResult.success(COUPON_ID);
            String responseJson = "{\"couponId\":\"7777\"}";

            given(idempotencyChecker.getCachedResponse(IDEMPOTENCY_KEY))
                    .willReturn(Optional.empty());
            given(stockIdLookup.lookupStockId(CAMPAIGN_ID, ROUTE_ID, FARE_CLASS))
                    .willReturn(STOCK_ID);
            given(strategySelector.current()).willReturn(couponIssueStrategy);
            given(couponIssueStrategy.issue(CAMPAIGN_ID, USER_ID, STOCK_ID, IDEMPOTENCY_KEY))
                    .willReturn(successResult);
            given(objectMapper.writeValueAsString(any(CouponIssueResponse.class)))
                    .willReturn(responseJson);

            // when
            CouponIssueResponse response = couponService.issue(IDEMPOTENCY_KEY, request);

            // then
            assertThat(response.couponId()).isEqualTo(String.valueOf(COUPON_ID));
            assertThat(response.status()).isEqualTo(CouponStatus.ISSUED);

            // 멱등성 응답 캐싱 호출 검증
            verify(idempotencyChecker, times(1))
                    .cacheResponse(eq(IDEMPOTENCY_KEY), eq(responseJson), eq(200));
            verify(idempotencyChecker, never()).clearProgress(anyString());
        }

        @Test
        @DisplayName("비즈니스 로직 수행 중 예외 발생 시 clearProgress를 호출하여 PROCESSING 선점을 해제하고 예외를 전파한다")
        void issue_firstRequest_exception_clearsProgress() {
            // given
            CouponIssueRequest request = createRequest();

            given(idempotencyChecker.getCachedResponse(IDEMPOTENCY_KEY))
                    .willReturn(Optional.empty());
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
        @DisplayName("발급 전략이 실패 결과를 반환하면 전용 예외를 던지고 PROCESSING 선점을 해제한다")
        void issue_strategyFailure_throwsCouponIssueException() {
            CouponIssueRequest request = createRequest();

            given(idempotencyChecker.getCachedResponse(IDEMPOTENCY_KEY))
                    .willReturn(Optional.empty());
            given(stockIdLookup.lookupStockId(CAMPAIGN_ID, ROUTE_ID, FARE_CLASS))
                    .willReturn(STOCK_ID);
            given(strategySelector.current()).willReturn(couponIssueStrategy);
            given(couponIssueStrategy.issue(CAMPAIGN_ID, USER_ID, STOCK_ID, IDEMPOTENCY_KEY))
                    .willReturn(
                            IssueResult.fail(
                                    com.uply.coupon.coupon.strategy.IssueFailReason.OUT_OF_STOCK));

            assertThatThrownBy(() -> couponService.issue(IDEMPOTENCY_KEY, request))
                    .isInstanceOf(CouponIssueException.class)
                    .extracting("reason")
                    .isEqualTo(com.uply.coupon.coupon.strategy.IssueFailReason.OUT_OF_STOCK);

            verify(idempotencyChecker).clearProgress(IDEMPOTENCY_KEY);
            verify(idempotencyChecker, never()).cacheResponse(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("idempotencyKey가 null이나 공백이면 멱등성 검사 및 캐싱을 건너뛰고 정상 발급한다")
        void issue_noIdempotencyKey_success() {
            // given
            CouponIssueRequest request = createRequest();
            IssueResult successResult = IssueResult.success(COUPON_ID);

            given(stockIdLookup.lookupStockId(CAMPAIGN_ID, ROUTE_ID, FARE_CLASS))
                    .willReturn(STOCK_ID);
            given(strategySelector.current()).willReturn(couponIssueStrategy);
            given(couponIssueStrategy.issue(CAMPAIGN_ID, USER_ID, STOCK_ID, null))
                    .willReturn(successResult);

            // when
            CouponIssueResponse response = couponService.issue(null, request);

            // then
            assertThat(response.couponId()).isEqualTo(String.valueOf(COUPON_ID));
            verifyNoInteractions(idempotencyChecker);
        }
    }
}
