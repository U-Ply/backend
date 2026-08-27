# Kafka 신뢰성 보장 사항

이 문서는 V3(Redis Lua + Kafka 비동기 저장) 경로에서 **이미 갖춰져 있는** 장애 대응
메커니즘을 정리한다. 장애가 실제로 발생했을 때의 대응 절차는
[`kafka-dlt-manual-response.md`](kafka-dlt-manual-response.md),
[`kafka-pending-manual-response.md`](kafka-pending-manual-response.md)를 참고하고,
정상 흐름 전체는 [`v3-issuance-flow.md`](v3-issuance-flow.md)를 참고한다.

## 대응 요약

| 상황 | 대응 방식 | 근거 |
| --- | --- | --- |
| Producer 발행 중 확실한 실패(직렬화 오류 등) | 즉시 `KAFKA_PUBLISH_FAILED` 발생 → 상위 레이어에서 Redis 선점 재고 자동 롤백 | [`CouponIssuedProducer.java`](../src/main/java/com/uply/coupon/messaging/producer/CouponIssuedProducer.java) — [`toJson()`](../src/main/java/com/uply/coupon/messaging/producer/CouponIssuedProducer.java#L176-L184), [`ExecutionException` 확정 실패 분기](../src/main/java/com/uply/coupon/messaging/producer/CouponIssuedProducer.java#L112-L135) |
| Producer 발행 중 타임아웃·네트워크 예외 | `SAVE_RESULT_UNKNOWN`으로 분류, Redis 재고를 롤백하지 않고 pending 유지 | `CouponIssuedProducer.`[`isUnknownCause()`](../src/main/java/com/uply/coupon/messaging/producer/CouponIssuedProducer.java#L164-L173) |
| 브로커 재전송으로 인한 중복 발행 | `enable.idempotence=true`로 브로커 프로토콜 수준에서 방지 | [`KafkaProducerConfig.java`](../src/main/java/com/uply/coupon/messaging/config/KafkaProducerConfig.java#L33) |
| Consumer 인스턴스가 처리 도중 죽음 | 메시지 유실 없음 — DB 반영이 끝난 뒤에만 offset을 커밋하므로, 죽으면 커밋 전 상태라 리밸런싱 후 재전달된다 | [`KafkaConsumerConfig.java`](../src/main/java/com/uply/coupon/messaging/config/KafkaConsumerConfig.java#L38) (`ENABLE_AUTO_COMMIT_CONFIG=false`) |
| 재전달로 인한 중복 처리 | `couponId` 존재 여부, `campaignId+userId` 중복, `idempotencyKey` 이력 3중 확인 후 스킵 + DB unique 제약이 최종 방어선 | `CouponIssuedEventProcessor.`[`isDuplicate()`](../src/main/java/com/uply/coupon/messaging/consumer/CouponIssuedEventProcessor.java#L45-L49) |
| Consumer 처리가 반복 실패 | 1초 간격 3회 재시도 후 같은 파티션의 `{topic}.DLT`로 격리, 원본 토픽 처리는 계속 진행 | [`KafkaConsumerConfig.java`](../src/main/java/com/uply/coupon/messaging/config/KafkaConsumerConfig.java#L58-L60) (`DefaultErrorHandler` + `FixedBackOff(1000L, 3)`) |

## 설명

- **발행 실패를 두 갈래로 나눈 것**이 이 설계의 핵심이다. "확실히 실패했다"와 "결과를 모른다"를
  구분해서, 후자에서는 절대 Redis 재고를 되돌리지 않는다 — 브로커에 실제로는 들어갔는데
  ACK만 유실됐을 가능성을 배제할 수 없기 때문이다. 재고를 잘못 되돌리면 초과 발급으로
  이어질 수 있으므로, 불확실할 때는 보수적으로 아무것도 하지 않는 쪽을 택했다.
- **Consumer의 manual commit**은 Kafka의 at-least-once 전달을 애플리케이션이 올바르게
  활용하는 표준적인 방식이다. offset을 먼저 커밋하고 처리하면(auto-commit) 처리 중 크래시 시
  그 메시지는 영영 사라진다. 이 프로젝트는 반대로 처리를 다 끝낸 뒤에만 커밋하므로, 최소
  한 번은 반드시 처리된다.
- **at-least-once가 만드는 중복은 3중 확인 + DB unique 제약으로 흡수한다.** "한 번은 반드시
  처리"의 대가는 "두 번 처리될 수도 있음"인데, 이 프로젝트는 그 중복을 애플리케이션 로직과
  DB 제약 양쪽에서 막아 정확히 한 번 저장되는 것과 같은 결과를 만든다.
- **DLT 격리는 전체 파이프라인을 막지 않는다.** 한 메시지가 반복 실패해도 같은 파티션의
  다음 메시지 처리가 계속되므로, 특정 건의 장애가 다른 사용자의 발급까지 지연시키지 않는다.
