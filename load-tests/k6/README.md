# k6 부하 테스트

`issue-level2.js`는 NoLock, 비관적 락, Redis Lua + MySQL 동기 저장 전략에 공통으로 사용하는 Level 2 발급 스크립트다. 전략은 애플리케이션의 `COUPON_STRATEGY` 설정으로 변경하며 k6 스크립트는 변경하지 않는다.

## 사전 조건

- 애플리케이션과 필요한 인프라가 실행 중이어야 한다.
- 캠페인, 재고 풀과 서로 다른 테스트 사용자들이 DB에 적재되어 있어야 한다.
- `USER_ID_START`부터 `TOTAL_REQUESTS`만큼의 사용자 ID가 연속해서 존재해야 한다.
- Redis 전략은 `stock:{stockId}`와 `issued:{campaignId}`가 공통 초기 상태로 준비되어 있어야 한다.
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
```

전략 또는 실행 회차를 바꾸기 전에는 다음 명령으로 실행 결과만 초기화한다.

```bash
./scripts/load-test/reset-level2.sh
```

초기화 후에는 사용자와 캠페인은 유지되고 쿠폰·이력·검증 결과가 삭제된다. DB와 Redis 재고는 10,000으로 돌아가고 Redis의 발급 사용자 및 멱등성 키도 제거된다.

## 실행 예시

NoLock·비관적 락의 순수 동시성 제어 성능을 비교할 때는 Redis 멱등성 계층을 끄고 애플리케이션을 실행한다. k6는 매 요청에 서로 다른 Idempotency-Key를 전송한다.

```bash
COUPON_STRATEGY=NO_LOCK \
COUPON_IDEMPOTENCY_ENABLED=false \
./gradlew bootRun
```

```bash
COUPON_STRATEGY=PESSIMISTIC_LOCK \
COUPON_IDEMPOTENCY_ENABLED=false \
./gradlew bootRun
```

일반 실행과 멱등성 검증에서는 `COUPON_IDEMPOTENCY_ENABLED`를 생략하거나 `true`로 설정한다. `false`는 Level 2 전략 비교 전용이며 운영 설정으로 사용하지 않는다.

먼저 재고 100장, 요청 200건의 스모크 테스트를 실행한다.

```bash
mkdir -p load-tests/results

k6 run \
  -e BASE_URL=http://localhost:8081 \
  -e TOTAL_REQUESTS=200 \
  -e VUS=20 \
  -e USER_ID_START=1 \
  -e CAMPAIGN_ID=1 \
  -e ROUTE_ID=JEJU \
  -e FARE_CLASS=ECONOMY \
  --summary-export load-tests/results/smoke.json \
  load-tests/k6/issue-level2.js
```

스모크 테스트가 통과한 후 Level 2 테스트를 실행한다.

```bash
k6 run \
  -e BASE_URL=http://localhost:8081 \
  -e TOTAL_REQUESTS=20000 \
  -e VUS=500 \
  -e USER_ID_START=1 \
  -e CAMPAIGN_ID=1 \
  -e ROUTE_ID=JEJU \
  -e FARE_CLASS=ECONOMY \
  --summary-export load-tests/results/pessimistic-vu500-run1.json \
  load-tests/k6/issue-level2.js
```

AWS의 별도 k6 인스턴스에서는 `BASE_URL`에 애플리케이션 EC2의 사설 IP를 지정한다.

```bash
-e BASE_URL=http://10.0.1.10:8081
```

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

## 결과 확인

콘솔과 `--summary-export` 결과에서 다음 항목을 확인한다.

- `coupon_issued`
- `coupon_out_of_stock`
- `coupon_already_issued`
- `coupon_campaign_not_open`
- `coupon_campaign_expired`
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

`coupon_concurrency_conflict`는 V0(NoLock)에서 발생하는 것이 정상이다. 동시성 제어가 없을 때 무엇이 깨지는지 재현하는 것이 V0의 목적이므로, 이 값을 오류로 취급하면 기준선 측정이 성립하지 않는다. 그래서 threshold를 두지 않고 수치만 기록한다.

409로 응답하는 캠페인 구간 거부도 별도로 집계한다.

| 항목 | 발생 지점 | Level 2 판정 |
| --- | --- | --- |
| `coupon_campaign_not_open` | `open_at` 이전 요청 (V0·V1은 DB `NOW(3)`, V2·V3은 Lua의 Redis `TIME`) | LT-01은 0건 |
| `coupon_campaign_expired` | `expire_at` 이후 요청 (판정 기준 동일) | LT-01은 0건. **E-2·E-3 경계 시나리오에서는 정상 관측값** |

두 항목도 threshold를 두지 않는다. LT-01에서는 0이어야 하지만, 인수 기준 E-2(만료 정각)·E-3(만료 1초 후)에서는 이 값이 나오는 것이 정답이라 같은 스크립트를 두 용도로 쓰기 때문이다. 판정은 결과 문서에서 한다.

`coupon_other_4xx`가 아니라 전용 카운터로 뺀 이유는 `coupon_other_4xx`에 `count==0` threshold가 걸려 있어, 만료 경계 시나리오를 돌리면 실행 자체가 실패로 끝나기 때문이다.

결과 건수는 다음 식으로 전체 요청 수와 일치해야 한다.

```text
전체 요청
= coupon_issued
+ coupon_out_of_stock
+ coupon_already_issued
+ coupon_campaign_not_open
+ coupon_campaign_expired
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
