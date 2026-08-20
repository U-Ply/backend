package com.uply.coupon.operation.reconciliation.domain;

public record KafkaSettlement(long lag, long dltCount) {

    public boolean settled() {
        return lag == 0 && dltCount == 0;
    }
}
