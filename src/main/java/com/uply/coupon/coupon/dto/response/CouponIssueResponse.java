package com.uply.coupon.coupon.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.strategy.IssueResult;

import java.time.Instant;

/**
 * 쿠폰 발행 응답 DTO
 */
public record CouponIssueResponse(
	
	/**
	 * Redis 에서 재고 차감 후 직접 발급하는 TSID
	 */
	String couponId,
	
	CouponStatus status,
	
	/**
	 * ISO-8601 UTC 시간 규격(...Z) 처리를 위해 Instant 타임스탬프 타입을 사용
	 */
	@JsonFormat(
	    shape = JsonFormat.Shape.STRING,
	    pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
	    timezone = "UTC"
	)
	Instant issuedAt,
	
	@JsonFormat(
        shape = JsonFormat.Shape.STRING,
        pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        timezone = "UTC"
    )
    Instant expireAt
	        
) {
	
	/**
	 * IssueResult의 Long 타입 couponId를 String으로 변환하여 성공 응답 DTO를 생성하는 팩토리 메서드
	 * expireAt 은 캠페인의 쿠폰 정책에서 가져올 것으로 예상
	 */
	public static CouponIssueResponse from(IssueResult result, Instant expireAt) {
		return new CouponIssueResponse(
			String.valueOf(result.couponId()),
			CouponStatus.ISSUED,
			Instant.now(),
			expireAt
		);
	}
	
	/**
	 * couponId(Long)를 직접 전달받을 때 사용하는 팩토리 메서드
	 */
	public static CouponIssueResponse of(Long couponId, Instant expireAt) {
		return new CouponIssueResponse(
			String.valueOf(couponId),
			CouponStatus.ISSUED,
			Instant.now(),
			expireAt
		);
	}
}
