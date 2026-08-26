package com.uply.coupon.common.idempotency;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyCache {
    private String status; // PROCESSING 또는 COMPLETED
    private String ownerToken; // PROCESSING을 선점한 요청 실행의 UUID. COMPLETED에는 없다.
    private int httpStatus;
    private String body;
    private String requestHash;
}
