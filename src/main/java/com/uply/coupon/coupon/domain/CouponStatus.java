package com.uply.coupon.coupon.domain;

// - ISSUED: 쿠폰 발급 완료
// - USED: 쿠폰 사용 완료
// - CANCELLED: 취소 (재고 복구 없이 소멸)
// - EXPIRED: 유효 기간 만료 (재고 복구 없이 소멸)
public enum CouponStatus {
	ISSUED, USED, CANCELLED, EXPIRED
}
