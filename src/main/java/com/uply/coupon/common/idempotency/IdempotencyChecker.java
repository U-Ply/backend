package com.uply.coupon.common.idempotency;

/**
 * 발급/사용/취소/일괄회수 API 공통 멱등성 처리 인터페이스.
 *
 * <p>PROCESSING 선점마다 {@link IdempotencyClaim#ownerToken}을 발급하고, 완료·해제·TTL 연장을 모두 그 ownerToken이 일치할
 * 때만 수행한다. 그러지 않으면 TTL 만료 후 다른 요청이 같은 키를 새로 선점했을 때, 뒤늦게 끝난 이전 요청이 새 PROCESSING을 지우거나 COMPLETED로 덮어써
 * 데이터 정합성이 깨진다.
 */
public interface IdempotencyChecker {

    /**
     * PROCESSING 선점을 시도한다.
     *
     * <p>최초 요청이면 {@link IdempotencyClaim#acquired}를 반환하고, 이미 완료된 동일 요청(같은 requestHash)이면 {@link
     * IdempotencyClaim#completed}를 반환한다. 처리 중인 요청, 캐시 상태가 불완전한 요청, 다른 요청에 재사용된 키는 각각 전용 예외를 던져 중복
     * 실행을 차단한다.
     */
    IdempotencyClaim acquire(String idempotencyKey, String requestHash);

    /**
     * PROCESSING을 COMPLETED로 전환한다. {@code ownerToken}과 {@code requestHash}가 현재 Redis 값과 모두 일치할 때만
     * 반영되며(Compare-And-Swap), 실패하면 이 요청이 이미 소유권을 잃었다는 뜻이므로 현재 값을 건드리지 않고 {@code false}를 반환한다.
     */
    boolean complete(
            String idempotencyKey,
            String ownerToken,
            String requestHash,
            String responseBody,
            int httpStatus);

    /**
     * 확정 실패로 재시도를 허용해야 할 때 PROCESSING 선점을 해제한다. {@code ownerToken}이 일치할 때만 삭제되며, 실패하면(이미 다른 요청이
     * 선점했거나 소유권을 잃음) 현재 키를 건드리지 않고 {@code false}를 반환한다.
     */
    boolean release(String idempotencyKey, String ownerToken);

    /** 처리 중인 소유자만 PROCESSING TTL을 연장(lease 갱신)한다. */
    boolean renew(String idempotencyKey, String ownerToken);
}
