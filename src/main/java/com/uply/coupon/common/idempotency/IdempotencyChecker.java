package com.uply.coupon.common.idempotency;

/** 발급/사용/취소 API 공통 멱등성 처리 인터페이스. */
public interface IdempotencyChecker {

    /** 이미 처리된 요청이면 캐시된 응답을 반환, 없으면 empty */
    java.util.Optional<String> getCachedResponse(String idempotencyKey);

    /** 처리 결과를 캐시에 저장 (TTL 적용) */
    void cacheResponse(String idempotencyKey, String responseBody, int httpStatus);

    /** 로직 처리 중 예외 발생 시 선점된 PROCESSING 키를 삭제하여 재시도 허용 */
    void clearProgress(String idempotencyKey);
}
