# Kafka DLT 수동 대응 절차

## 1. 문서 목적

이 문서는 `coupon-issued` 이벤트를 Kafka Consumer가 정상 처리하지 못해
`coupon-issued.DLT`로 격리했을 때 운영자가 원인을 확인하고 안전하게 복구하는
절차를 정의한다.

DLT 메시지를 확인하지 않고 재발행하면 쿠폰, 발급 이력 또는 DB 재고가 중복
반영될 수 있다. 반드시 MySQL과 Redis 상태를 먼저 확인한 뒤 처리한다.

## 2. DLT 발생 조건

Kafka Consumer는 `coupon-issued` 메시지를 다음 순서로 처리한다.

```text
coupon-issued 메시지 수신
→ 최초 처리 실패
→ 1초 후 재시도 1
→ 1초 후 재시도 2
→ 1초 후 재시도 3
→ 계속 실패
→ 같은 파티션 번호의 coupon-issued.DLT로 이동
```

최초 처리를 포함하면 최대 4번 시도한다.

DLT로 이동할 수 있는 주요 원인은 다음과 같다.

- 이벤트 JSON 역직렬화 실패
- Kafka 메시지 key와 `couponId` 불일치
- `couponId`, `userId`, `campaignId`, `stockId` 누락 또는 0 이하 값
- `idempotencyKey`, `issuedAt` 누락
- 캠페인과 재고 풀 조합 불일치
- 쿠폰 만료 시각이 발급 시각보다 빠르거나 같음
- 쿠폰 또는 발급 이력 저장 실패
- MySQL 재고 차감 실패
- DB 연결 또는 트랜잭션 오류가 재시도 이후에도 지속됨
- 중복 제약 위반이 발생했지만 실제 중복 이벤트로 확인되지 않음

다음 상황은 DLT로 이동하지 않는다.

- 정상적인 중복 이벤트: 추가 저장 없이 성공 처리
- DB 커밋 후 Redis pending 키 삭제만 실패: 경고 로그를 남기고 TTL 만료로 정리
- 쿠폰 조회 API의 DB 반영 재조회 실패: `COUPON_NOT_READY` 응답이며 Kafka DLT와 무관

## 3. DLT 발생 시 예상 상태

DLT가 발생했다면 일반적으로 다음 상태가 된다.

### Redis

- `stock:{stockId}`는 발급 판정 시 이미 1 감소한 상태
- `issued:{campaignId}`에 사용자 ID가 이미 등록된 상태
- `coupon:pending:{couponId}`는 유지
- Consumer 실패만을 이유로 Redis 재고를 다시 차감하거나 복구하지 않음

### MySQL

Consumer의 저장 작업은 하나의 트랜잭션으로 처리된다. 명확한 실패라면 트랜잭션이
롤백되므로 일반적으로 다음 항목은 반영되지 않는다.

- `coupons` INSERT
- `coupon_history`의 `NULL → ISSUED` INSERT
- `campaign_stocks.remaining_stock` 1 감소

커밋 결과가 불명확한 장애에서는 일부 또는 전체 반영 여부를 단정하지 말고 반드시
DB를 직접 확인한다.

### Kafka

- 실패 메시지는 `coupon-issued.DLT`에 보관됨
- 실패 메시지를 격리한 뒤 같은 파티션의 다음 메시지 처리를 계속함
- DLT 메시지는 자동 재처리되지 않음

## 4. 대응 원칙

- DLT 메시지를 확인하지 않고 원본 토픽에 재발행하지 않는다.
- 발급 API를 다시 호출하지 않는다.
- 새로운 `couponId` 또는 `idempotencyKey`를 만들지 않는다.
- 원본 Kafka key인 `couponId`를 유지한다.
- 쿠폰, 발급 이력, DB 재고 반영 여부를 모두 확인한다.
- 부분 반영이 발견되면 재발행하지 않고 원인을 조사한다.
- DLT 기록은 복구 완료 및 장애 기록 보관 전까지 초기화하지 않는다.
- 이름과 이메일 등 개인정보 원문을 대응 기록에 남기지 않는다.
- `scripts/load-test/reset-level2-kafka.sh`는 운영 복구에 사용하지 않는다.

## 5. 1단계 — DLT 존재 여부 확인

다음 명령은 로컬 Docker 환경 기준이다.

### 토픽 상태 확인

```powershell
docker exec coupon-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic coupon-issued.DLT
```

공통 테스트 환경의 정상 토픽 구조는 파티션 3개, Replication Factor 1이다.
아직 DLT가 한 번도 발생하지 않아 토픽이 생성되지 않은 경우에는 현재 코드에서
`dltCount=0`으로 판단한다.

### DLT 파티션별 end offset 확인

```powershell
docker exec coupon-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic coupon-issued.DLT
```

현재 구현의 `KafkaAdminSettlementChecker`는 DLT 토픽의 파티션별 end offset 합계를
`dltCount`로 사용한다. 하나라도 0보다 크면 DLT 발생 이력이 있다는 뜻이다.

### 원본 토픽 Consumer lag 확인

```powershell
docker exec coupon-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group coupon-service --describe
```

DLT 대응 전후로 `coupon-issued`의 Consumer lag도 함께 기록한다.

## 6. 2단계 — DLT 메시지 확인

다음 명령으로 key, partition, offset, 예외 헤더와 메시지 본문을 확인한다.

```powershell
docker exec coupon-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic coupon-issued.DLT --from-beginning --property print.key=true --property print.partition=true --property print.offset=true --property print.headers=true --max-messages 10 --timeout-ms 5000
```

메시지가 10건보다 적으면 5초 동안 새 메시지가 없을 때 조회가 종료된다.

각 메시지에서 다음 값을 기록한다.

- Kafka partition 및 offset
- Kafka key
- `couponId`
- `userId`
- `campaignId`
- `stockId`
- `idempotencyKey`
- `issuedAt`
- `expireAt`
- 예외 클래스 및 메시지

다음 조건을 먼저 확인한다.

```text
Kafka key == event.couponId
```

두 값이 다르면 기존 메시지를 그대로 재발행해도 다시 실패하므로 재발행하지 않는다.

## 7. 3단계 — 이벤트 유효성 확인

| 필드 | 검증 기준 |
| --- | --- |
| `couponId` | 0보다 큰 쿠폰 ID이며 Kafka key와 같음 |
| `userId` | 0보다 큰 사용자 ID |
| `campaignId` | 0보다 큰 캠페인 ID |
| `stockId` | 0보다 큰 재고 ID |
| `idempotencyKey` | `null` 또는 빈 문자열이 아님 |
| `issuedAt` | 필수 |
| `expireAt` | 존재한다면 `issuedAt`보다 이후. 구버전 이벤트는 없을 수 있음 |
| `publishedAt` | 측정 전용이므로 없을 수 있음 |

필드가 잘못된 경우 Producer 코드와 이벤트 생성 경로를 먼저 수정한다. 원본 요청이나
신뢰할 수 있는 DB 기록에서 올바른 값을 확인할 수 없다면 값을 임의로 만들어
재발행하지 않는다. 파티션 key 불일치나 손상된 이벤트 본문(JSON 데이터)처럼 원본
이벤트 자체가 유효하지 않은 경우에는 장애 기록을 남기고 재발행 대상에서 제외한다.

## 8. 4단계 — MySQL 반영 여부 확인

아래 `:couponId`, `:campaignId`, `:userId`, `:idempotencyKey`, `:stockId`는 실제
DLT 이벤트 값으로 치환한다.

### couponId 확인

```sql
SELECT coupon_id,
       user_id,
       campaign_id,
       stock_id,
       status,
       issued_at,
       expire_at
FROM coupons
WHERE coupon_id = :couponId;
```

### 캠페인별 사용자 중복 확인

```sql
SELECT coupon_id,
       status
FROM coupons
WHERE campaign_id = :campaignId
  AND user_id = :userId;
```

### 멱등성 키 이력 확인

```sql
SELECT history_id,
       coupon_id,
       from_status,
       to_status,
       idempotency_key,
       event_at
FROM coupon_history
WHERE idempotency_key = :idempotencyKey;
```

### 재고 풀 확인

```sql
SELECT stock_id,
       campaign_id,
       total_stock,
       remaining_stock
FROM campaign_stocks
WHERE stock_id = :stockId
  AND campaign_id = :campaignId;
```

재고가 정확히 한 번 감소했는지는 단일 조회값만으로 판단하지 않는다. 테스트 시드의
초기 재고, 해당 재고 풀의 실제 발급 쿠폰 수 및 이전 장애 기록과 함께 대조한다.

### 쿠폰 상태와 최종 이력 확인

```sql
SELECT c.coupon_id,
       c.status AS coupon_status,
       h.from_status,
       h.to_status,
       h.event_at
FROM coupons c
LEFT JOIN coupon_history h
       ON h.coupon_id = c.coupon_id
WHERE c.coupon_id = :couponId
ORDER BY h.event_at DESC, h.history_id DESC;
```

## 9. 5단계 — Redis 상태 확인

### pending 키 확인

```powershell
docker exec coupon-redis redis-cli GET "coupon:pending:{couponId}"
```

DLT 처리 전 예상값은 `PENDING`이다.

### Redis 재고 확인

```powershell
docker exec coupon-redis redis-cli GET "stock:{stockId}"
```

### 사용자 발급 Set 확인

```powershell
docker exec coupon-redis redis-cli SISMEMBER "issued:{campaignId}" "{userId}"
```

발급 판정이 성공했다면 예상값은 `1`이다. DLT가 발생했더라도 Redis 재고와 발급
Set을 임의로 복구하지 않는다. Kafka Broker가 이벤트를 접수한 이후 발생한 Consumer
실패이므로 MySQL 반영을 안전하게 복구하는 것이 우선이다.

## 10. 6단계 — 상태별 처리 결정

### 경우 A — MySQL에 모든 데이터가 정상 반영됨

확인 조건:

- `coupons` 1건 존재
- `coupon_history`의 `NULL → ISSUED` 1건 존재
- 기준 데이터와 대조했을 때 DB 재고가 정확히 1 감소
- 쿠폰 상태와 최종 이력 일치

처리:

1. 이벤트를 재발행하지 않는다.
2. 중복 반영 여부를 확인한다.
3. pending 키가 남았다면 DB 반영 완료를 재확인한 뒤 삭제한다.
4. 장애 및 확인 결과를 기록한다.

### 경우 B — MySQL에 아무 데이터도 반영되지 않음

확인 조건:

- `couponId` 없음
- 동일 `campaignId + userId` 쿠폰 없음
- 동일 `idempotencyKey` 이력 없음
- 기준 데이터와 대조했을 때 DB 재고 감소 없음

처리:

1. 실패 원인을 수정한다.
2. 캠페인과 재고 풀 조합을 다시 확인한다.
3. 원본 이벤트가 유효하고 재시도 가능한 장애였을 때만 동일한 Kafka key와 동일한
   이벤트를 `coupon-issued`에 재발행한다.
4. Consumer 처리 완료 여부를 확인한다.

### 경우 C — 일부 데이터만 반영됨

예:

- 쿠폰은 있지만 이력이 없음
- 이력은 있지만 쿠폰이 없음
- 쿠폰과 이력은 있지만 DB 재고가 감소하지 않음
- 쿠폰 상태와 마지막 이력이 다름

이 상태는 하나의 MySQL 트랜잭션이 보장하는 정상 결과가 아니다.

처리:

1. 재발행하지 않는다.
2. 애플리케이션 로그와 DB 트랜잭션 로그를 확인한다.
3. 수동 DB 수정 전에 팀장 또는 DB 담당자의 확인을 받는다.
4. INV 검증 결과와 함께 별도 장애 이슈로 기록한다.

### 경우 D — 실제 중복 이벤트

다음 중 하나가 이미 존재한다.

- 동일 `couponId`
- 동일 `campaignId + userId`
- 동일 `idempotencyKey`

쿠폰, 이력 및 DB 재고가 모두 정상이라면 재발행하지 않는다.

## 11. 7단계 — 이벤트 수동 재발행

재발행 전 다음 조건을 모두 만족해야 한다.

- 실패 원인 수정 완료
- MySQL에 쿠폰과 이력 미반영 확인
- DB 재고 미차감 확인
- Redis 재고가 발급 판정 시 이미 차감됐음을 확인
- 원본 `couponId`와 `idempotencyKey` 확보
- Kafka key와 `couponId` 일치 확인

발급 API를 다시 호출하지 않고 원래 이벤트를 `coupon-issued`로 재발행한다.

로컬 Docker 환경 예시:

```powershell
'<couponId>|<CouponIssuedEvent JSON>' | docker exec -i coupon-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic coupon-issued --property parse.key=true --property "key.separator=|"
```

입력 예시:

```text
123456789|{"couponId":123456789,"userId":100,"campaignId":1,"stockId":1,"idempotencyKey":"원본 UUID","issuedAt":"2026-08-26T01:00:00Z","expireAt":"2026-09-26T01:00:00Z","publishedAt":"2026-08-26T01:00:01Z"}
```

주의사항:

- Kafka key는 `couponId`다.
- 원본 `couponId`를 유지한다.
- 원본 `idempotencyKey`를 유지한다.
- Redis 재고를 다시 차감하지 않는다.
- `/api/coupons/issue`를 다시 호출하지 않는다.

## 12. 8단계 — 재처리 결과 확인

### Kafka Consumer lag 확인

```powershell
docker exec coupon-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group coupon-service --describe
```

`coupon-issued`의 lag가 0이 될 때까지 기다린다.

### DB 확인

- `coupons`가 정확히 1건인지 확인
- `coupon_history`의 `NULL → ISSUED`가 정확히 1건인지 확인
- 기준 데이터와 대조해 `campaign_stocks.remaining_stock`이 정확히 1 감소했는지 확인
- 동일 사용자에게 중복 쿠폰이 없는지 확인
- 동일 멱등성 키의 이력이 1건인지 확인

### Redis 확인

```powershell
docker exec coupon-redis redis-cli GET "coupon:pending:{couponId}"
```

정상 처리 후 결과는 `(nil)`이다. Consumer 재처리 과정에서는 Redis 재고가 추가로
감소하지 않아야 한다.

## 13. 9단계 — 검증 및 대사 실행

재처리 완료 후 다음을 확인한다.

- Consumer lag 0
- DB 쿠폰 수 정상
- DB 이력 수 정상
- Redis와 DB의 최종 재고 일치
- INV 규칙 위반 0
- 중복 쿠폰 0
- 중복 이력 0

DLT가 남아 있는 동안 현재 코드의 Redis–DB 대사와 캐시 복구는 미정착 상태로
판단된다. 따라서 모든 DLT 원본의 대응 기록과 정리가 끝난 뒤 검증 및 대사를
실행한다.

## 14. 10단계 — DLT 원본 보관

DLT 토픽을 정리하기 전에 다음 정보를 별도 장애 기록으로 보관한다.

- 발생 일시
- 환경 ID
- Git commit SHA
- partition 및 offset
- Kafka key
- `couponId`
- 예외 클래스 및 메시지
- DB 확인 결과
- Redis 확인 결과
- 재처리 여부 및 일시
- 최종 검증 결과
- 처리 담당자

개인정보 원문은 기록하지 않는다. DLT 메시지 원본 파일은 Git 저장소에 커밋하지
않는다.

## 15. 11단계 — DLT 정리

현재 `KafkaAdminSettlementChecker`는 DLT 토픽의 누적 end offset을 `dltCount`로
판단한다. 따라서 메시지를 성공적으로 재처리해도 기존 DLT 레코드가 남아 있으면
`DLT=0`이 되지 않는다.

Kafka에서는 특정 DLT 레코드 한 건만 일반적인 소비 방식으로 삭제할 수 없다. 아래
토픽 삭제 및 재생성은 **모든 DLT 메시지의 대응이 끝난 경우에만** 수행한다.

### 로컬 및 부하테스트 환경

다음 조건을 먼저 만족해야 한다.

- 장애 메시지 원본 보관 완료
- 모든 DLT 메시지의 처리 방향 결정 및 필요한 재처리 성공
- DB 정합성 확인
- 발급 요청 중지
- 모든 애플리케이션 Consumer 중지
- 팀원에게 DLT 초기화 사실 공유

DLT 토픽 삭제:

```powershell
docker exec coupon-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic coupon-issued.DLT
```

토픽 삭제 완료를 확인한 뒤 DLT 토픽 재생성:

```powershell
docker exec coupon-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic coupon-issued.DLT --partitions 3 --replication-factor 1
```

재생성 확인:

```powershell
docker exec coupon-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic coupon-issued.DLT
```

### 운영 및 공용 환경

- 개인 판단으로 DLT 토픽을 삭제하지 않는다.
- 팀장과 Kafka 인프라 담당자의 승인을 받는다.
- DLT 메시지를 외부 장애 기록에 보관한 뒤 정리한다.
- 발급 트래픽과 Consumer를 중지하거나 안전한 작업 시간을 확보한다.
- 환경별 Kafka 브로커 주소와 인증 설정을 사용한다.

## 16. 금지 사항

다음 작업은 하지 않는다.

- DLT 메시지를 검증 없이 일괄 재발행
- 발급 API 재호출
- 새로운 `couponId` 생성
- 새로운 `idempotencyKey` 생성
- Redis 재고 수동 증가 또는 감소
- `issued:{campaignId}`에서 사용자 임의 삭제
- DB 확인 전 pending 키 삭제
- 장애 기록 없이 DLT 토픽 삭제
- 운영 환경에서 `reset-level2-kafka.sh` 실행
- `coupon-issued` 원본 토픽 또는 Consumer group offset 무단 초기화

## 17. 대응 완료 조건

다음 조건을 모두 만족하면 대응을 완료한다.

- 실패 원인 확인 및 수정
- 모든 DLT 이벤트의 처리 방향 결정
- 필요한 이벤트만 안전하게 재발행
- `coupons` 중복 0건
- `coupon_history` 중복 0건
- DB 재고 중복 차감 0건
- Redis 재고 추가 차감 또는 임의 복구 없음
- pending 키 정상 삭제
- Consumer lag 0
- DLT 처리 기록 보관
- 검증 규칙 위반 0건
- Redis–DB 재고 불일치 0건
- 대응 담당자 및 완료 시각 기록
