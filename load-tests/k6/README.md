# k6 부하 테스트

`issue-level2.js`는 NoLock, 비관적 락, Redis Lua + MySQL 동기 저장 전략에 공통으로 사용하는 Level 2 발급 스크립트다. 전략은 애플리케이션의 `COUPON_STRATEGY` 설정으로 변경하며 k6 스크립트는 변경하지 않는다.

## 사전 조건

- 애플리케이션과 필요한 인프라가 실행 중이어야 한다.
- 캠페인, 재고 풀과 서로 다른 테스트 사용자들이 DB에 적재되어 있어야 한다.
- `USER_ID_START`부터 `TOTAL_REQUESTS`만큼의 사용자 ID가 연속해서 존재해야 한다.
- Redis 전략은 `stock:{stockId}`와 `issued:{campaignId}`가 공통 초기 상태로 준비되어 있어야 한다.
- Redis 전략은 `stockId:{campaignId}:{routeId}:{fareClass}`와 `campaign:{campaignId}:openAt`도 준비되어 있어야 한다.
- 각 실행 전 쿠폰, 이력, DB 재고, Redis 재고와 멱등성 키를 초기화해야 한다.

## 공통 시드와 초기화

최초 한 번 공통 데이터를 생성한다. 이 작업은 `coupon_db`와 Redis의 기존 데이터를 모두 삭제한다.

```bash
./scripts/load-test/seed-level2.sh
```

생성되는 고정 데이터:

```text
userId       = 1~20000
campaignId   = 1
stockId      = 1
routeId      = JEJU
fareClass    = ECONOMY
totalStock   = 10000
remaining    = 10000

Redis keys:
stock:1                              = 10000
stockId:1:JEJU:ECONOMY               = 1
campaign:1:openAt                    = DB open_at의 UTC epoch milliseconds
issued:1                             = 비어 있음
```

전략 또는 실행 회차를 바꾸기 전에는 다음 명령으로 실행 결과만 초기화한다.

```bash
./scripts/load-test/reset-level2.sh
```

초기화 후에는 사용자와 캠페인은 유지되고 쿠폰·이력·검증 결과가 삭제된다. DB와 Redis 재고는 10,000으로 돌아가고 Redis의 발급 사용자 및 멱등성 키도 제거된다.

## 전략별 애플리케이션 실행

Level 2에서는 전략 이외의 조건을 고정한다. Redis 멱등성 계층은 순수 전략 비교에서 제외하고, Kafka를 사용하지 않는 V0~V2에서는 이전 메시지가 DB에 반영되지 않도록 Consumer도 중지한다. k6는 매 요청에 서로 다른 Idempotency-Key를 전송한다.

| 버전 | `COUPON_STRATEGY` | `COUPON_SAVE_STRATEGY` | `COUPON_KAFKA_CONSUMER_ENABLED` | k6 `TEST_STRATEGY` |
| --- | --- | --- | --- | --- |
| V0 | `NO_LOCK` | `sync-db` | `false` | `V0` |
| V1 | `PESSIMISTIC_LOCK` | `sync-db` | `false` | `V1` |
| V2 | `LUA_SCRIPT` | `sync-db` | `false` | `V2` |
| V3 | `LUA_SCRIPT` | `kafka` | `true` | `V3` |

### V0 — NoLock + MySQL 동기 저장

```bash
COUPON_STRATEGY=NO_LOCK \
COUPON_SAVE_STRATEGY=sync-db \
COUPON_IDEMPOTENCY_ENABLED=false \
COUPON_KAFKA_CONSUMER_ENABLED=false \
./gradlew bootRun
```

### V1 — 비관적 락 + MySQL 동기 저장

```bash
COUPON_STRATEGY=PESSIMISTIC_LOCK \
COUPON_SAVE_STRATEGY=sync-db \
COUPON_IDEMPOTENCY_ENABLED=false \
COUPON_KAFKA_CONSUMER_ENABLED=false \
./gradlew bootRun
```

### V2 — Redis Lua + MySQL 동기 저장

```bash
COUPON_STRATEGY=LUA_SCRIPT \
COUPON_SAVE_STRATEGY=sync-db \
COUPON_IDEMPOTENCY_ENABLED=false \
COUPON_KAFKA_CONSUMER_ENABLED=false \
./gradlew bootRun
```

### V3 — Redis Lua + Kafka 비동기 저장

```bash
COUPON_STRATEGY=LUA_SCRIPT \
COUPON_SAVE_STRATEGY=kafka \
COUPON_IDEMPOTENCY_ENABLED=false \
COUPON_KAFKA_CONSUMER_ENABLED=true \
./gradlew bootRun
```

일반 실행과 멱등성 검증에서는 `COUPON_IDEMPOTENCY_ENABLED`를 생략하거나 `true`로 설정한다. `false`는 Level 2 전략 비교 전용이며 운영 설정으로 사용하지 않는다.

## 공통 k6 실행

먼저 요청 200건의 스모크 테스트를 실행한다. `TEST_STRATEGY`는 실제 애플리케이션 전략과 동일하게 지정한다.

```bash
mkdir -p load-tests/results

k6 run \
  -e TEST_STRATEGY=V2 \
  -e BASE_URL=http://localhost:8081 \
  -e TOTAL_REQUESTS=200 \
  -e VUS=20 \
  -e USER_ID_START=1 \
  -e CAMPAIGN_ID=1 \
  -e ROUTE_ID=JEJU \
  -e FARE_CLASS=ECONOMY \
  --summary-export load-tests/results/v2-smoke.json \
  load-tests/k6/issue-level2.js
```

스모크 테스트 후에는 다시 `reset-level2.sh`를 실행해 재고와 발급 결과를 초기화한다. 그다음 동일한 전략으로 본 테스트를 실행한다.

```bash
k6 run \
  -e TEST_STRATEGY=V2 \
  -e BASE_URL=http://localhost:8081 \
  -e TOTAL_REQUESTS=20000 \
  -e VUS=500 \
  -e USER_ID_START=1 \
  -e CAMPAIGN_ID=1 \
  -e ROUTE_ID=JEJU \
  -e FARE_CLASS=ECONOMY \
  --summary-export load-tests/results/v2-vu500-run1.json \
  load-tests/k6/issue-level2.js
```

AWS의 별도 k6 인스턴스에서는 `BASE_URL`에 애플리케이션 EC2의 사설 IP를 지정한다.

```bash
-e BASE_URL=http://10.0.1.10:8081
```

## V2 실행 및 종료 절차

1. 모든 애플리케이션 인스턴스를 중지한다.
2. `./scripts/load-test/reset-level2.sh`로 MySQL과 Redis를 초기화한다.
3. V2 환경변수로 애플리케이션을 실행한다.
4. `TEST_STRATEGY=V2`로 k6를 실행한다.
5. k6가 끝나면 바로 MySQL과 Redis 정합성을 확인한다. V2는 MySQL 동기 저장이므로 별도 정착 대기가 필요 없다.
6. REC-01을 실행하고 결과를 저장한다.

```bash
docker exec -i coupon-mysql mysql -uroot -proot1234 < load-tests/sql/verify-level2.sql
docker exec coupon-redis redis-cli GET stock:1
docker exec coupon-redis redis-cli SCARD issued:1

curl -X POST "http://localhost:8081/api/admin/batch/reconcile?failOnViolation=true"
```

배치 실행 응답의 `jobExecutionId`로 완료 여부를 조회한다.

```bash
curl "http://localhost:8081/api/admin/batch/executions/{jobExecutionId}"
```

## V3 실행 및 종료 절차

1. 모든 애플리케이션 인스턴스를 중지한다.
2. `./scripts/load-test/reset-level2.sh`로 MySQL과 Redis를 초기화한다.
3. `./scripts/load-test/reset-level2-kafka.sh`로 토픽과 Consumer offset을 초기화한다.
4. V3 환경변수로 애플리케이션을 실행한다.
5. `TEST_STRATEGY=V3`로 k6를 실행한다.
6. k6 종료 시각부터 MySQL 쿠폰 수가 성공 응답 수에 도달할 때까지 걸린 시간을 기록한다.
7. Consumer lag가 0이고 DLT가 0건인지 확인한다.
8. MySQL과 Redis 정합성을 확인한 뒤 REC-01을 실행한다.

Consumer lag 확인:

```bash
docker exec coupon-kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --group coupon-service
```

`LAG` 열의 합이 0이어야 한다. DLT 메시지 수는 파티션별 최신 offset의 합으로 확인한다.

```bash
docker exec coupon-kafka \
  /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server localhost:9092 \
  --topic coupon-issued.DLT \
  --time -1
```

MySQL 최종 반영 건수 확인:

```bash
docker exec coupon-mysql mysql -ucoupon -pcoupon1234 coupon_db \
  -Nse "SELECT COUNT(*) FROM coupons WHERE stock_id = 1;"
```

쿠폰 수가 k6의 `coupon_issued`와 같고 lag와 DLT가 모두 0이 된 후에만 최종 검증 및 REC-01 결과를 판정한다.

## 환경변수

| 이름 | 기본값 | 설명 |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:8081` | 테스트 대상 애플리케이션 주소 |
| `TOTAL_REQUESTS` | `20000` | 전체 요청 수 |
| `VUS` | `500` | 동시 가상 사용자 수 |
| `USER_ID_START` | `1` | 첫 번째 테스트 사용자 ID |
| `CAMPAIGN_ID` | `1` | 테스트 캠페인 ID |
| `ROUTE_ID` | `JEJU` | 노선 ID |
| `FARE_CLASS` | `ECONOMY` | 좌석 등급 |
| `MAX_DURATION` | `10m` | 테스트 최대 실행 시간 |
| `TEST_STRATEGY` | 필수 | 판정 대상 전략: `V0`, `V1`, `V2`, `V3` |

## 결과 확인

콘솔과 `--summary-export` 결과에서 다음 항목을 확인한다.

- `coupon_issued`
- `coupon_out_of_stock`
- `coupon_already_issued`
- `coupon_lock_timeout`
- `coupon_concurrency_conflict`
- `coupon_connection_unavailable`
- `coupon_other_4xx`
- `coupon_5xx`
- `coupon_unexpected_response`
- `http_reqs`, `http_req_duration`, `iterations`

503으로 응답하는 세 항목은 발생 지점이 서로 다르므로 각각 집계한다. 셋 다 일반 `coupon_5xx`에 중복 집계되지 않는다.

| 항목 | 발생 지점 | Level 2 판정 |
| --- | --- | --- |
| `coupon_lock_timeout` | `SELECT ... FOR UPDATE`로 락을 기다리다 한계 초과 | 0건 |
| `coupon_concurrency_conflict` | 트랜잭션 커밋 단계의 DB 교착 | V1은 0건. **V0은 정상 관측값** |
| `coupon_connection_unavailable` | 트랜잭션 시작 단계에서 커넥션 풀 획득 실패 | 0건 |

`TEST_STRATEGY=V0`일 때 `CONCURRENCY_CONFLICT`는 예상 응답으로 check에 포함하고 threshold를 두지 않아 수치만 기록한다. `V1`~`V3`에서는 `coupon_concurrency_conflict`에 `count==0` threshold를 적용한다. `TEST_STRATEGY`를 실제 실행 전략과 다르게 지정하면 판정이 왜곡되므로 애플리케이션 설정과 반드시 맞춘다.

V0 실행에는 `-e TEST_STRATEGY=V0`, 비관적 락에는 `V1`, Redis Lua + MySQL 동기 저장에는 `V2`, Redis Lua + Kafka에는 `V3`를 사용한다.

V0의 `CONCURRENCY_CONFLICT`는 HTTP 상태가 503이므로 k6 기본 지표인 `http_req_failed`에는 실패로 집계된다. V0 판정에서는 `http_req_failed`만 보지 않고 `coupon_concurrency_conflict`와 전략별 check를 함께 확인한다.

결과 건수는 다음 식으로 전체 요청 수와 일치해야 한다.

```text
전체 요청
= coupon_issued
+ coupon_out_of_stock
+ coupon_already_issued
+ coupon_lock_timeout
+ coupon_concurrency_conflict
+ coupon_connection_unavailable
+ coupon_other_4xx
+ coupon_5xx
+ coupon_unexpected_response
```

k6 결과만으로 정합성을 판정하지 않는다. 실행이 끝난 후 DB 쿠폰 수, 중복 발급 수, DB 잔여 재고와 Redis 잔여 재고를 별도 검증한다.

```bash
docker exec -i coupon-mysql mysql -uroot -proot1234 < load-tests/sql/verify-level2.sql
docker exec coupon-redis redis-cli GET stock:1
docker exec coupon-redis redis-cli SCARD issued:1
```

실행 결과 문서는 `load-tests/templates/level2-result-template.md`를 복사해 작성한다.
