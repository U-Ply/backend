package com.uply.coupon.coupon.service;

import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.dto.response.CouponIssueResponse;
import com.uply.coupon.coupon.strategy.CouponIssueStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CouponServiceImpl implements CouponService {

    private final CouponIssueStrategy couponIssueStrategy;

    /**
     * stockId 찾기 -> LuaScriptIssueStrategy 쿠폰 발급 시도 -> IssueResult 반환 -> 실패 -> 예외처리 / 성공 ->
     * CouponIssueResponse 반환
     */
    @Override
    public CouponIssueResponse issue(String idempotencyKey, CouponIssueRequest request) {

        // #1. LuaScriptIssueStrategy 쿠폰 발급
        //		IssueResult result = couponIssueStrategy
        //				.issue();

        // #2-1 실패
        //		if(!success) {
        //			throw new CouponIssueException(result.reason());
        //		}

        // #2-2 성공
        //		return CouponIssueResponse.from(result, expireAt);
        return null;
    }
}
