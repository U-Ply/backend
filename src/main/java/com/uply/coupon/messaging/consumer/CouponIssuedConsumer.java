package com.uply.coupon.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.messaging.event.CouponIssuedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "coupon.save.strategy", havingValue = "kafka")
@RequiredArgsConstructor
public class CouponIssuedConsumer {

    private final ObjectMapper objectMapper;
    private final CouponIssuedEventProcessor eventProcessor;

    // coupon-issued 토픽에 메시지가 들어오면 Spring Kafka가 메시지를 가져와서 이 메서드를 자동 호출 함

    // ConsumerRecord<String, String> record 이 안에
    // record.key()   -> Kafka 파티션 키인 couponId
    // record.value() -> CouponIssuedEvent JSON 문자열  이 두 정보가 들어감

    @KafkaListener(
            topics = "coupon-issued",
            containerFactory = "kafkaListenerContainerFactory",
            autoStartup = "${coupon.kafka.consumer.enabled:true}")
    public void consume(ConsumerRecord<String, String> record) throws JsonProcessingException {
        CouponIssuedEvent event = objectMapper.readValue(record.value(), CouponIssuedEvent.class);
        validatePartitionKey(record.key(), event.couponId());

        boolean processed = eventProcessor.process(event);
        log.info("쿠폰 발급 이벤트 처리를 완료했습니다. couponId={}, processed={}", event.couponId(), processed);
    }

    private void validatePartitionKey(String partitionKey, Long couponId) {
        if (couponId == null || !String.valueOf(couponId).equals(partitionKey)) {
            throw new IllegalArgumentException("Kafka 파티션 키와 couponId가 일치하지 않습니다.");
        }
    }
}
