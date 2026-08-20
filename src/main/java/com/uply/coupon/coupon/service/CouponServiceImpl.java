package com.uply.coupon.coupon.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.campaign.repository.CampaignCacheRepository;
import com.uply.coupon.campaign.service.StockIdLookupSelector;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.common.idempotency.IdempotencyChecker;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.dto.response.CouponIssueResponse;
import com.uply.coupon.coupon.strategy.CouponIssueStrategy;
import com.uply.coupon.coupon.strategy.CouponIssueStrategySelector;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import com.uply.coupon.coupon.strategy.IssueResult;
import java.time.Instant;
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
    private final CampaignCacheRepository campaignCacheRepository;

    @Override
    public CouponIssueResponse issue(String idempotencyKey, CouponIssueRequest request) {

        // #1. 멱등성 검사 (Cache Hit 시 DTO 역직렬화 후 즉시 리턴)
        /*
         * Cache Hit 에도 두가지 경우가 있다.
         * 	1.PROCESSING
         * 	2.COMPLETED
         * getCachedResponse() 수행 도중 PROCESSING 일 경우 throw 에러
         * 그게 아니면 캐시된 성공 응답 데이터 반환
         */
        if (hasIdempotencyKey(idempotencyKey)) {
            // 1.PROCESSING 일 경우 여기서 먼저 에러 발생
            Optional<String> cachedBody = idempotencyChecker.getCachedResponse(idempotencyKey);
            // 2.에러 없이 캐시된 데이터가 존재한다 = COMPLETED 상태이다. -> 이전의 응답 데이터를 그대로 사용자에게 반환
            if (cachedBody.isPresent()) {
                log.info("[멱등성 처리] 이전 성공 응답 재반환 - key: {}", idempotencyKey);
                return parseCachedResponse(cachedBody.get());
            }
        }

        // #2. 최초 요청 처리 (예외 발생 시 PROCESSING 락 해제를 위한 try-catch)
        try {
            // [승인 시점 측정] try 진입 직후 측정하여 예외 발생 시 catch문에서 락 해제 보장
            Instant now = Instant.now();

            // 캠페인 메타데이터(오픈/만료 시각) Redis 조회
            Instant openAt = campaignCacheRepository.getOpenAt(request.campaignId());
            Instant expireAt = campaignCacheRepository.getExpireAt(request.campaignId());

            // 오픈 및 만료 시각 검증
            if (now.isBefore(openAt)) {
                throw new CouponIssueException(IssueFailReason.CAMPAIGN_NOT_OPEN);
            }
            if (now.isAfter(expireAt)) {
                throw new CouponIssueException(IssueFailReason.CAMPAIGN_EXPIRED);
            }

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
                throw new CouponIssueException(result.reason());
            }

            // 응답 DTO 생성
            CouponIssueResponse response =
                    CouponIssueResponse.builder()
                            .couponId(String.valueOf(result.couponId()))
                            .status(CouponStatus.ISSUED)
                            .issuedAt(now)
                            .expireAt(expireAt)
                            .build();

            // #3. 성공 응답 JSON 직렬화 후 Redis 캐싱 (COMPLETED, TTL 10분)
            if (hasIdempotencyKey(idempotencyKey)) {
                String responseJson = toJson(response);
                idempotencyChecker.cacheResponse(idempotencyKey, responseJson, 200);
            }

            return response;

        } catch (CouponIssueException e) {
            // 비즈니스 로직 / 인프라 예외 발생 시 PROCESSING 키 삭제 (재시도 허용)
            if (hasIdempotencyKey(idempotencyKey)) {
            	// 확실한 실패 시에만 멱등성 키를 삭제하여 재시도 허용
            	if (e.getReason() != IssueFailReason.SAVE_RESULT_UNKNOWN) {
            		idempotencyChecker.clearProgress(idempotencyKey);
            	} 
            }
            throw e;
        }
    }

    private boolean hasIdempotencyKey(String idempotencyKey) {
        return idempotencyKey != null && !idempotencyKey.isBlank();
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
