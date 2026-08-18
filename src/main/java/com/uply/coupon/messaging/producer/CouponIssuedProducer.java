package com.uply.coupon.messaging.producer;

import com.uply.coupon.coupon.strategy.save.CouponSaveStrategy;
import com.uply.coupon.messaging.event.CouponIssuedEvent;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** CouponSaveStrategy 전략 中 1 : Kafka 이벤트 발행 */
@Component
@ConditionalOnProperty(name = "coupon.save.strategy", havingValue = "kafka", matchIfMissing = true)
@RequiredArgsConstructor
public class CouponIssuedProducer implements CouponSaveStrategy {

    private static final String TOPIC_NAME = "coupon-issued";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    public void save(
            Long couponId, Long campaignId, Long stockId, Long userId, String idempotencyKey) {
        // 이벤트 객체 생성 후 Kafka 전송
        CouponIssuedEvent event =
                new CouponIssuedEvent(
                        couponId, userId, campaignId, stockId, idempotencyKey, Instant.now());
        kafkaTemplate.send(TOPIC_NAME, String.valueOf(couponId), event);
    }
}
