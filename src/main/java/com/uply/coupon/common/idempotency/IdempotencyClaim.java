package com.uply.coupon.common.idempotency;

/**
 * {@link IdempotencyChecker#acquire} 결과.
 *
 * <p>PROCESSING 선점에 성공하면 이번 요청 실행을 식별하는 {@code ownerToken}이 채워진다. 이미 완료된 요청이면 {@code
 * cachedResponse}에 최초 응답 본문이 담기고 {@code ownerToken}은 없다 — 캐시된 응답을 반환하는 요청은 PROCESSING을 소유하지 않는다.
 */
public record IdempotencyClaim(String ownerToken, String cachedResponse, boolean acquired) {

    public static IdempotencyClaim acquired(String ownerToken) {
        return new IdempotencyClaim(ownerToken, null, true);
    }

    public static IdempotencyClaim completed(String body) {
        return new IdempotencyClaim(null, body, false);
    }

    public boolean hasCachedResponse() {
        return cachedResponse != null;
    }
}
