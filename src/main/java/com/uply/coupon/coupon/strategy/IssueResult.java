package com.uply.coupon.coupon.strategy;

import java.time.Instant;

public record IssueResult(
        boolean success,
        Long couponId, // 성공 시에만 값 존재
        IssueFailReason reason, // 실패 시에만 값 존재
        Instant issuedAt, // 성공 시에만 값 존재. 전략이 실제로 저장한 발급 시각
        Instant expireAt // 성공 시에만 값 존재. 캠페인에서 상속받은 만료 시각
        ) {
    /**
     * 성공 결과 생성.
     *
     * <p>issuedAt은 전략이 DB(NOW(3)) 또는 Redis(TIME)에서 얻어 실제로 저장한 값이어야 한다. 여기서 Instant.now()를 새로 만들면
     * 「기준 시간은 DB 서버 시간」 결정을 위반한다.
     */
    public static IssueResult success(Long couponId, Instant issuedAt, Instant expireAt) {
        return new IssueResult(true, couponId, null, issuedAt, expireAt);
    }

    public static IssueResult fail(IssueFailReason reason) {
        return new IssueResult(false, null, reason, null, null);
    }
}
