package com.uply.coupon.coupon.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.strategy.IssueResult;
import java.time.Instant;
import lombok.Builder;

/** 쿠폰 발행 응답 DTO */
@Builder
public record CouponIssueResponse(

        /** Redis 에서 재고 차감 후 직접 발급하는 TSID */
        String couponId,
        CouponStatus status,

        /** ISO-8601 UTC 시간 규격(...Z) 처리를 위해 Instant 타임스탬프 타입을 사용 */
        @JsonFormat(
                        shape = JsonFormat.Shape.STRING,
                        pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        timezone = "UTC")
                Instant issuedAt,
        @JsonFormat(
                        shape = JsonFormat.Shape.STRING,
                        pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        timezone = "UTC")
                Instant expireAt) {

    /**
     * IssueResult가 담고 있는 시각을 그대로 사용해 성공 응답 DTO를 생성한다.
     *
     * <p>issuedAt/expireAt을 여기서 새로 만들지 않는다. 「기준 시간은 DB 서버 시간」 결정에 따라 값의 출처는 전략(DB NOW(3) 또는 Redis
     * TIME)이다.
     */
    public static CouponIssueResponse from(IssueResult result) {
        return new CouponIssueResponse(
                String.valueOf(result.couponId()),
                CouponStatus.ISSUED,
                result.issuedAt(),
                result.expireAt());
    }
}
