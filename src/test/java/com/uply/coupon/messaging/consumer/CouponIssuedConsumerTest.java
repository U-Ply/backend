package com.uply.coupon.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.messaging.event.CouponIssuedEvent;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponIssuedConsumerTest {

    private static final CouponIssuedEvent EVENT =
            new CouponIssuedEvent(
                    1001L,
                    10L,
                    1L,
                    3L,
                    "550e8400-e29b-41d4-a716-446655440000",
                    Instant.parse("2026-08-15T01:00:00Z"),
                    Instant.parse("2026-08-15T01:00:00.050Z"));

    @Mock private CouponIssuedEventProcessor eventProcessor;

    private ObjectMapper objectMapper;
    private CouponIssuedConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        consumer = new CouponIssuedConsumer(objectMapper, eventProcessor);
    }

    // 정상 Kafka 메시지를 이벤트로 변환해 Processor에 전달하는지 확인
    @Test
    void validMessageIsDelegatedToProcessor() throws Exception {
        when(eventProcessor.process(EVENT)).thenReturn(true);
        String payload = objectMapper.writeValueAsString(EVENT);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("coupon-issued", 0, 0L, "1001", payload);

        consumer.consume(record);

        verify(eventProcessor).process(EVENT);
    }

    // Kafka 파티션 키와 이벤트의 couponId가 다르면 차단하는지 확인
    @Test
    void mismatchedPartitionKeyIsRejected() throws Exception {
        String payload = objectMapper.writeValueAsString(EVENT);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("coupon-issued", 0, 0L, "9999", payload);

        assertThatThrownBy(() -> consumer.consume(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("파티션 키");
    }

    // 잘못된 JSON 메시지를 이벤트 변환 단계에서 차단하는지 확인
    @Test
    void malformedJsonIsRejected() {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("coupon-issued", 0, 0L, "1001", "not-json");

        assertThatThrownBy(() -> consumer.consume(record))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
}
