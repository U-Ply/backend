package com.uply.coupon.operation.reconciliation.service;

import com.uply.coupon.operation.reconciliation.domain.KafkaSettlement;

public interface KafkaSettlementChecker {

    KafkaSettlement check();
}
