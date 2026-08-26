package com.uply.coupon.coupon.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.common.exception.CouponNotFoundException;
import com.uply.coupon.common.exception.CouponNotReadyException;
import com.uply.coupon.common.exception.InvalidStateTransitionException;
import com.uply.coupon.common.idempotency.IdempotencyChecker;
import com.uply.coupon.common.idempotency.IdempotencyClaim;
import com.uply.coupon.common.idempotency.IdempotencyKeyValidator;
import com.uply.coupon.common.idempotency.IdempotencyOwnershipMetrics;
import com.uply.coupon.common.idempotency.IdempotencyRequestHasher;
import com.uply.coupon.coupon.api.CouponApiPaths;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.dto.response.CouponCancelResponse;
import com.uply.coupon.coupon.dto.response.CouponDetailResponse;
import com.uply.coupon.coupon.dto.response.CouponIssueResponse;
import com.uply.coupon.coupon.dto.response.CouponUseResponse;
import com.uply.coupon.coupon.service.CouponQueryService;
import com.uply.coupon.coupon.service.CouponService;
import com.uply.coupon.coupon.service.CouponStateTransitionService;
import jakarta.validation.Valid;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(CouponApiPaths.COUPONS)
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;
    private final CouponStateTransitionService couponStateTransitionService;
    private final CouponQueryService couponQueryService;
    private final IdempotencyChecker idempotencyChecker;
    private final IdempotencyOwnershipMetrics idempotencyOwnershipMetrics;
    private final ObjectMapper objectMapper;

    @PostMapping(CouponApiPaths.ISSUE)
    public ResponseEntity<CouponIssueResponse> issueCoupon(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CouponIssueRequest request) {
        IdempotencyKeyValidator.validateUuidV4(idempotencyKey);
        CouponIssueResponse response = couponService.issue(idempotencyKey, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{couponId}" + CouponApiPaths.USE)
    public ResponseEntity<CouponUseResponse> useCoupon(
            @PathVariable Long couponId, @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return executeIdempotently(
                couponId,
                idempotencyKey,
                CouponApiPaths.USE,
                CouponUseResponse.class,
                () -> {
                    couponQueryService.awaitCouponPersistence(couponId);
                    return couponStateTransitionService.use(couponId, idempotencyKey);
                },
                coupon -> CouponUseResponse.of(coupon.getCouponId(), coupon.getUsedAt()));
    }

    @PostMapping("/{couponId}" + CouponApiPaths.CANCEL)
    public ResponseEntity<CouponCancelResponse> cancelCoupon(
            @PathVariable Long couponId, @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return executeIdempotently(
                couponId,
                idempotencyKey,
                CouponApiPaths.CANCEL,
                CouponCancelResponse.class,
                () -> {
                    couponQueryService.awaitCouponPersistence(couponId);
                    return couponStateTransitionService.cancel(couponId, idempotencyKey);
                },
                coupon -> CouponCancelResponse.of(coupon.getCouponId(), coupon.getCancelledAt()));
    }

    @GetMapping("/{couponId}")
    public ResponseEntity<CouponDetailResponse> getCoupon(@PathVariable Long couponId) {
        return ResponseEntity.ok(couponQueryService.getCoupon(couponId));
    }

    private <T> ResponseEntity<T> executeIdempotently(
            Long couponId,
            String idempotencyKey,
            String actionPath,
            Class<T> responseType,
            Supplier<Coupon> transition,
            Function<Coupon, T> responseFactory) {
        IdempotencyKeyValidator.validateUuidV4(idempotencyKey);
        String requestHash = createRequestHash(couponId, actionPath);

        IdempotencyClaim claim = idempotencyChecker.acquire(idempotencyKey, requestHash);
        if (claim.hasCachedResponse()) {
            return ResponseEntity.ok(parseCachedResponse(claim.cachedResponse(), responseType));
        }
        String ownerToken = claim.ownerToken();

        Coupon coupon;
        try {
            coupon = transition.get();
        } catch (CouponNotReadyException
                | CouponNotFoundException
                | InvalidStateTransitionException exception) {
            if (!idempotencyChecker.release(idempotencyKey, ownerToken)) {
                idempotencyOwnershipMetrics.recordReleaseRejected(idempotencyKey);
            }
            throw exception;
        }

        T response = responseFactory.apply(coupon);
        if (!idempotencyChecker.complete(
                idempotencyKey, ownerToken, requestHash, toJson(response), 200)) {
            idempotencyOwnershipMetrics.recordCompleteRejected(idempotencyKey);
        }
        return ResponseEntity.ok(response);
    }

    private String createRequestHash(Long couponId, String actionPath) {
        return IdempotencyRequestHasher.sha256(
                "POST", CouponApiPaths.couponActionUri(couponId, actionPath), "");
    }

    private String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("응답 데이터 직렬화에 실패했습니다.", exception);
        }
    }

    private <T> T parseCachedResponse(String json, Class<T> responseType) {
        try {
            return objectMapper.readValue(json, responseType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("캐시된 응답 데이터 복원에 실패했습니다.", exception);
        }
    }
}
