package com.uply.coupon.coupon.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.common.exception.CouponNotFoundException;
import com.uply.coupon.common.exception.CouponNotReadyException;
import com.uply.coupon.common.exception.InvalidStateTransitionException;
import com.uply.coupon.common.idempotency.IdempotencyChecker;
import com.uply.coupon.common.idempotency.IdempotencyKeyValidator;
import com.uply.coupon.common.idempotency.IdempotencyRequestHasher;
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
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;
    private final CouponStateTransitionService couponStateTransitionService;
    private final CouponQueryService couponQueryService;
    private final IdempotencyChecker idempotencyChecker;
    private final ObjectMapper objectMapper;

    @PostMapping("/issue")
    public ResponseEntity<CouponIssueResponse> issueCoupon(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CouponIssueRequest request) {
        CouponIssueResponse response = couponService.issue(idempotencyKey, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{couponId}/use")
    public ResponseEntity<CouponUseResponse> useCoupon(
            @PathVariable Long couponId, @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return executeIdempotently(
                couponId,
                idempotencyKey,
                "/use",
                CouponUseResponse.class,
                () -> {
                    couponQueryService.awaitCouponPersistence(couponId);
                    return couponStateTransitionService.use(couponId, idempotencyKey);
                },
                coupon -> CouponUseResponse.of(coupon.getCouponId(), coupon.getUsedAt()));
    }

    @PostMapping("/{couponId}/cancel")
    public ResponseEntity<CouponCancelResponse> cancelCoupon(
            @PathVariable Long couponId, @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return executeIdempotently(
                couponId,
                idempotencyKey,
                "/cancel",
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

        Optional<String> cachedBody =
                idempotencyChecker.getCachedResponse(idempotencyKey, requestHash);
        if (cachedBody.isPresent()) {
            return ResponseEntity.ok(parseCachedResponse(cachedBody.get(), responseType));
        }

        Coupon coupon;
        try {
            coupon = transition.get();
        } catch (CouponNotReadyException
                | CouponNotFoundException
                | InvalidStateTransitionException exception) {
            idempotencyChecker.clearProgress(idempotencyKey);
            throw exception;
        }

        T response = responseFactory.apply(coupon);
        idempotencyChecker.cacheResponse(idempotencyKey, requestHash, toJson(response), 200);
        return ResponseEntity.ok(response);
    }

    private String createRequestHash(Long couponId, String actionPath) {
        return IdempotencyRequestHasher.sha256("POST", "/api/coupons/" + couponId + actionPath, "");
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
