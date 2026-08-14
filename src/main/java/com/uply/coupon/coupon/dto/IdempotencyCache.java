package com.uply.coupon.coupon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyCache {
    private int httpStatus;
    private String body;
    private String requestHash;
}
