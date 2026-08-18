package com.uply.coupon.coupon.domain;

// - ISSUED: 쿠폰 발급 완료
// - USED: 쿠폰을 사용해 예매한 상태
// - CANCELLED: 항공사가 미사용 쿠폰을 취소하거나 사용자가 예매를 취소한 상태 (재고 복구 없이 소멸)
// - EXPIRED: 유효 기간 만료 (재고 복구 없이 소멸)
public enum CouponStatus {
    ISSUED,
    USED,
    CANCELLED,
    EXPIRED
}
