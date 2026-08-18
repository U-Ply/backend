package com.uply.coupon.messaging.producer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.uply.coupon.messaging.event.CouponIssuedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class CouponIssuedProducerTest {

    @Mock private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks private CouponIssuedProducer kafkaEventCouponSaver;

    @Test
    @DisplayName("KafkaEventCouponSaver 호출 시 coupon-issued 토픽으로 이벤트가 발행된다")
    void save_Success() {
        // given
        Long couponId = 1000L;
        Long campaignId = 1L;
        Long stockId = 10L;
        Long userId = 100L;
        String idempotencyKey = "idempotency-key-123";

        // when
        kafkaEventCouponSaver.save(couponId, campaignId, stockId, userId, idempotencyKey);

        // then
        verify(kafkaTemplate)
                .send(
                        eq("coupon-issued"),
                        eq(String.valueOf(couponId)),
                        any(CouponIssuedEvent.class));
    }
}
