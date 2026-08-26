# Kafka pending 키 수동 대응 절차

## 1. 문서 목적

이 문서는 `coupon:pending:{couponId}`가 Consumer에 도달하지 못한 채 오래
남아있을 때 운영자가 이를 감지하고 안전하게 대응하는 절차를 정의한다.

정상 경로(발행 → Consumer 저장 → pending 삭제, 또는 확실한 발행 실패 시
즉시 삭제)는 이미 구현돼 있다. 이 문서는 그 경로를 벗어나 TTL(24시간) 만료
전까지 원인 없이 남아있는 pending 건을 다룬다. **자동 재처리 Worker는 범위
밖이며, 이 문서는 감지와 수동 대응까지만 다룬다.**

## 2. 감지 수단

`CouponPendingMonitor`가 주기적으로 `coupon:pending:*` 키를 스캔해 생성된 지
`stale-threshold`(기본 10분) 이상 지난 키 개수를 `coupon.pending.stale.count`
Gauge로 노출한다. 기본은 비활성화 상태다.

```yaml
coupon:
  pending-monitor:
    scheduler-enabled: ${PENDING_MONITOR_SCHEDULER_ENABLED:false}
    stale-threshold: ${PENDING_MONITOR_STALE_THRESHOLD:PT10M}
    fixed-delay: ${PENDING_MONITOR_FIXED_DELAY:PT5M}
```

Prometheus/Grafana에서 `coupon.pending.stale.count`가 0보다 크면 이상 징후로
본다.

지표를 켜지 않았거나 즉시 확인이 필요하면 Redis에서 직접 SCAN한다.

```powershell
docker exec coupon-redis redis-cli --scan --pattern "coupon:pending:*"
```

특정 키의 남은 TTL을 확인해 얼마나 오래 남아있었는지 역산한다(전체 TTL은
24시간 = 86400초).

```powershell
docker exec coupon-redis redis-cli TTL "coupon:pending:{couponId}"
```

경과 시간 = 86400 − TTL 조회 결과.

## 3. 임계치 초과 시 확인 순서

1. 위 SCAN 명령으로 stale한 `couponId` 목록을 확보한다.
2. 각 `couponId`에 대해 DLT 존재 여부를 확인한다.

   ```powershell
   docker exec coupon-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic coupon-issued.DLT --from-beginning --property print.key=true --max-messages 50 --timeout-ms 5000
   ```

   해당 `couponId`가 key로 보이면 **DLT에 있는 것이므로, 이후 확인·재발행
   절차는 [`kafka-dlt-manual-response.md`](kafka-dlt-manual-response.md)의
   2단계(DLT 메시지 확인)부터 그대로 따른다.** 이 문서에서 중복 작성하지
   않는다.

3. DLT에도 없다면 Consumer가 아직 메시지를 받지 못했거나 처리 중일 가능성이
   높다. Consumer lag과 상태를 확인한다.

   ```powershell
   docker exec coupon-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group coupon-service --describe
   ```

   lag이 계속 쌓이고 있으면 Consumer 인스턴스가 죽어있거나 처리 지연 중인
   것이므로 애플리케이션 상태를 먼저 확인한다. 이 경우 pending 키를 임의로
   건드리지 않고 Consumer가 정상화되어 자연스럽게 처리되기를 기다린다.

4. DLT에도 없고 Consumer lag도 정상(0에 가까움)인데 pending 키만 남아있다면,
   DB에는 이미 반영됐지만 pending 삭제 호출만 실패했을 가능성이 크다
   (`kafka-dlt-manual-response.md` 2장에도 명시된 알려진 케이스: "DB 커밋 후
   Redis pending 키 삭제만 실패"). DB를 먼저 확인한다.

   ```sql
   SELECT coupon_id, status, issued_at
   FROM coupons
   WHERE coupon_id = :couponId;
   ```

   `coupons`에 정상 반영돼 있으면 안전한 정상 케이스다. pending 키는 TTL
   만료로 자동 정리되므로 그대로 두어도 되고, 즉시 정리하고 싶다면 DB
   반영을 확인한 뒤에만 수동 삭제한다.

   ```powershell
   docker exec coupon-redis redis-cli DEL "coupon:pending:{couponId}"
   ```

   DB에 반영되지 않았다면 2번으로 돌아가 DLT를 다시 확인하거나, 계속
   재현되면 별도 장애로 조사한다.

## 4. 금지 사항

- DB 반영 여부를 확인하기 전에 pending 키를 삭제하지 않는다.
- pending 키가 남아있다는 이유만으로 Redis 재고나 `issued:{campaignId}`를
  임의로 되돌리지 않는다 — Kafka 저장 전략은 확실한 실패가 아니면 Redis를
  보상하지 않는 정책이다.
- 자동 재처리 Worker는 만들지 않는다. 감지(2장)와 수동 판단(3장)까지만
  이 문서의 범위다.

## 5. 완료 조건

- [ ] `coupon.pending.stale.count` 지표(또는 수동 SCAN)로 오래된 pending 건
      개수를 확인할 수 있다.
- [ ] 임계치 초과 시 DLT 문서와 연계되는 확인 절차가 정리돼 있다.
- [ ] "자동 재처리는 범위 밖"이 명시돼 있다.
