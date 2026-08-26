package com.uply.coupon.coupon.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.campaign.service.StockIdLookupSelector;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.common.idempotency.IdempotencyChecker;
import com.uply.coupon.common.idempotency.IdempotencyRequestHasher;
import com.uply.coupon.common.metrics.CouponIssueMetrics;
import com.uply.coupon.coupon.api.CouponApiPaths;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.dto.response.CouponIssueResponse;
import com.uply.coupon.coupon.strategy.CouponIssueStrategy;
import com.uply.coupon.coupon.strategy.CouponIssueStrategySelector;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import com.uply.coupon.coupon.strategy.IssueResult;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponServiceImpl implements CouponService {

    private final IdempotencyChecker idempotencyChecker;
    private final ObjectMapper objectMapper;
    private final CouponIssueStrategySelector strategySelector;
    private final StockIdLookupSelector stockIdLookupSelector;
    private final CouponIssueMetrics couponIssueMetrics;

    @Override
    public CouponIssueResponse issue(String idempotencyKey, CouponIssueRequest request) {

        String requestHash = null;
        if (hasIdempotencyKey(idempotencyKey)) {
            requestHash = createRequestHash(request);
            // 1.PROCESSING 일 경우 여기서 먼저 에러 발생
            Optional<String> cachedBody =
                    idempotencyChecker.getCachedResponse(idempotencyKey, requestHash);
            // 2.에러 없이 캐시된 데이터가 존재한다 = COMPLETED 상태이다. -> 이전의 응답 데이터를 그대로 사용자에게 반환
            if (cachedBody.isPresent()) {
                log.info("[멱등성 처리] 이전 성공 응답 재반환 - key: {}", idempotencyKey);
                return parseCachedResponse(cachedBody.get());
            }
        }

        // #2. 최초 요청 처리
        //
        // PROCESSING 키 해제 여부는 "발급이 성립했는가"로 갈린다.
        // 발급이 성립한 뒤에 응답 직렬화나 캐싱이 실패했다고 키를 지우면,
        // 같은 요청이 다시 들어와 발급 로직을 처음부터 실행해 중복 발급이 난다.
        boolean issuanceCompleted = false;

        try {
            CouponIssueStrategy issueStrategy = strategySelector.current();

            // DB 전략은 MySQL, Lua 전략은 Redis에서 stockId를 조회한다.
            Long stockId =
                    stockIdLookupSelector
                            .forStrategy(issueStrategy.name())
                            .lookupStockId(
                                    request.campaignId(), request.routeId(), request.fareClass());

            // 설정으로 선택된 발급 전략 실행
            IssueResult result =
                    issueStrategy.issue(
                            request.campaignId(), request.userId(), stockId, idempotencyKey);

            if (!result.success()) {
                throw new CouponIssueException(result.reason(), request.campaignId());
            }

            // 이 시점부터 쿠폰은 이미 발급된 것으로 본다. 이후 실패는 응답 생성 실패일 뿐이다.
            issuanceCompleted = true;

            // 발급이 성립한 이 지점에서만 센다. 위쪽 멱등성 캐시 히트 경로는 쿠폰을 새로 만들지
            // 않으므로 세지 않는다 — 세면 성공 건수가 실제 발급 수보다 부풀어 부하 테스트의
            // "성공 + 재고소진 = 총 요청" 검산이 깨진다.
            couponIssueMetrics.success();

            // 응답 DTO 생성
            CouponIssueResponse response =
                    CouponIssueResponse.builder()
                            .couponId(String.valueOf(result.couponId()))
                            .status(CouponStatus.ISSUED)
                            .issuedAt(result.issuedAt())
                            .expireAt(result.expireAt())
                            .build();

            // #3. 성공 응답 JSON 직렬화 후 Redis 캐싱 (COMPLETED, TTL 10분)
            if (hasIdempotencyKey(idempotencyKey)) {
                String responseJson = toJson(response);
                idempotencyChecker.cacheResponse(idempotencyKey, requestHash, responseJson, 200);
            }

            return response;

        } catch (CouponIssueException e) {
            // 확정 실패일 때만 키를 지워 재시도를 허용한다.
            //  - SAVE_RESULT_UNKNOWN : Kafka 발행 결과 불명확 또는 Redis 보상 실패.
            //    브로커에 이미 들어갔거나 재고가 복구되지 않았을 수 있으므로 키를 유지한다.
            //  - issuanceCompleted    : 발급은 성립했고 응답 단계만 실패한 경우.
            if (canClearProgress(idempotencyKey, issuanceCompleted)
                    && e.getReason() != IssueFailReason.SAVE_RESULT_UNKNOWN) {
                idempotencyChecker.clearProgress(idempotencyKey);
            }
            throw e;

        } catch (Exception e) {
            // CouponIssueException으로 변환되지 않은 예외(캐시 누락, stockId 조회 실패 등)도
            // 발급 전이라면 키를 풀어 정상 재시도를 막지 않는다.
            if (canClearProgress(idempotencyKey, issuanceCompleted)) {
                idempotencyChecker.clearProgress(idempotencyKey);
            }
            throw e;
        }
    }

    /** 발급이 성립하기 전의 확정 실패에서만 PROCESSING 선점을 해제한다. */
    private boolean canClearProgress(String idempotencyKey, boolean issuanceCompleted) {
        return hasIdempotencyKey(idempotencyKey) && !issuanceCompleted;
    }

    private boolean hasIdempotencyKey(String idempotencyKey) {
        return idempotencyKey != null && !idempotencyKey.isBlank();
    }

    private String createRequestHash(CouponIssueRequest request) {
        return IdempotencyRequestHasher.sha256("POST", CouponApiPaths.ISSUE_URI, toJson(request));
    }

    private String toJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("[JSON 직렬화 실패]", e);
            throw new IllegalStateException("응답 데이터 직렬화에 실패했습니다.", e);
        }
    }

    /** Redis에 저장되어 있던 JSON 문자열을 DTO 객체로 변환 */
    private CouponIssueResponse parseCachedResponse(String json) {
        try {
            return objectMapper.readValue(json, CouponIssueResponse.class);
        } catch (JsonProcessingException e) {
            log.error("[멱등성 응답 복원 실패] JSON 역직렬화 오류 - body: {}", json, e);
            throw new IllegalStateException("캐시된 응답 데이터 복원 중 오류가 발생했습니다.", e);
        }
    }
}
