package com.uply.coupon.common.idempotency;

/** 발급/사용/취소 API 공통 멱등성 처리 인터페이스. */
public interface IdempotencyChecker {

    /** 이미 처리된 요청이면 캐시된 응답을 반환, 없으면 empty */
    java.util.Optional<String> getCachedResponse(String idempotencyKey);

    /** requestHash까지 비교하여 같은 키가 다른 요청에 재사용되는 것을 차단 */
    default java.util.Optional<String> getCachedResponse(
            String idempotencyKey, String requestHash) {
        return getCachedResponse(idempotencyKey);
    }

    /** 처리 결과를 캐시에 저장 (TTL 적용) */
    void cacheResponse(String idempotencyKey, String responseBody, int httpStatus);

    /** 완료 응답과 함께 최초 요청의 requestHash를 저장 */
    default void cacheResponse(
            String idempotencyKey, String requestHash, String responseBody, int httpStatus) {
        cacheResponse(idempotencyKey, responseBody, httpStatus);
    }

    /** 로직 처리 중 예외 발생 시 선점된 PROCESSING 키를 삭제하여 재시도 허용 */
    void clearProgress(String idempotencyKey);
}
