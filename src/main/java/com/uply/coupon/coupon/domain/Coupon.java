package com.uply.coupon.coupon.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
//같은 campaign에 같은 user가 두번 발급 차단
@Table(name = "coupons", uniqueConstraints = @UniqueConstraint(columnNames = {"campaign_id", "user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon {
	
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long couponId;
	
	private Long userId;
	private Long campaignId;
	private Long stockId;
	
	@Enumerated(EnumType.STRING)	// DB에 숫자(0, 1, 2)로 상태 저장이 아닌 문자열(ISSUED, USED)등 으로 저장
	private CouponStatus status;
	
	private LocalDateTime issuedAt;
	private LocalDateTime expireAt;
	private LocalDateTime cancelledAt;
	
	public static Coupon issue(Long userId, Long campaignId, Long stockId, LocalDateTime expireAt) {
		Coupon coupon = new Coupon();
		coupon.userId = userId;
        coupon.campaignId = campaignId;
        coupon.stockId = stockId;
        coupon.status = CouponStatus.ISSUED;
        coupon.issuedAt = LocalDateTime.now();
        coupon.expireAt = expireAt;
		
		return coupon;
	}
}
