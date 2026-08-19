# V0·V1 실행 조건과 락·트랜잭션 설정

이 문서는 NoLock(V0)과 비관적 락(V1) 발급 경로의 실행 조건, 락·트랜잭션·커넥션 설정,
결과 해석 기준을 한곳에 모은다. Level 2 결과를 해석하려면 어떤 값이 어디에 설정돼 있고
어떤 지표로 나타나는지 알아야 하므로 작성되었다.

담당: 1-A
관련 전략: `NoLockIssueStrategy`, `PessimisticLockIssueStrategy`

실행 절차(전략 전환 명령, 시드·초기화 스크립트)는 `load-tests/k6/README.md`에 있다.
이 문서는 절차를 다시 쓰지 않고 **그 조건이 왜 그래야 하는지**를 다룬다.
테스트 조건·판정 기준의 SSOT는 `docs/test-plan.md`다.

## 1. 발급 API

두 전략 모두 같은 엔드포인트를 쓴다. 전략은 애플리케이션 설정으로 바뀌며 요청·응답 형태는 동일하다.

```text
POST /api/coupons/issue
Idempotency-Key: <UUID v4>
Content-Type: application/json

{
  "userId": 1,
  "campaignId": 1,
  "routeId": "JEJU",
  "fareClass": "ECONOMY"
}
```

성공(200) 응답:

```json
{
  "couponId": "877366376494186518",
  "status": "ISSUED",
  "issuedAt": "2026-08-18T15:22:53.120Z",
  "expireAt": "2026-09-17T15:22:53.120Z"
}
```

`couponId`는 애플리케이션이 TSID로 사전 생성한 값을 문자열로 반환한다.
`issuedAt`과 `expireAt`은 JVM 시각이 아니라 DB의 `NOW(3)`와 캠페인의 `expire_at`을 따른다.

실패 응답의 상태 코드와 오류 코드는 6절 표를 참고한다.

## 2. 실행 조건

V0·V1은 **MySQL만 있으면 동작한다.** Redis와 Kafka는 이 경로에 관여하지 않는다.

| 설정 | 값 | 이유 |
| --- | --- | --- |
| `COUPON_STRATEGY` | `NO_LOCK` 또는 `PESSIMISTIC_LOCK` | 전략 선택. 재빌드 없이 환경변수로 전환된다 |
| `COUPON_IDEMPOTENCY_ENABLED` | `false` | 아래 설명 참고 |

### 멱등성 계층을 끄는 이유

`true`로 두면 요청이 발급 전략에 도달하기 전에 Redis 기반 `IdempotencyChecker`를 거친다.
그러면 측정값에 Redis 왕복 시간이 섞여 **순수한 동시성 제어 비용을 비교할 수 없다.**
V1의 락 대기와 V2의 Redis Lua를 비교하는 것이 목적인데, 양쪽에 Redis가 끼면 비교가 흐려진다.

`false`이면 `NoOpIdempotencyChecker`가 주입되어 이 계층이 통과된다. 멱등성 자체가 사라지는 것은
아니다. 두 전략 모두 `coupon_history.idempotency_key` UNIQUE 제약과 트랜잭션 내 조회로
DB 수준의 멱등성을 유지한다.

**이 값은 Level 2 전략 비교 전용이며 운영 설정으로 사용하지 않는다.**

### NoLock 실행 시 유의

NoLock에서 관측되는 교착과 재고 차감 유실은 **버그가 아니라 의도된 결과다.**
V0은 동시성 제어가 없을 때 무엇이 깨지는지 재현하기 위한 기준선이므로,
테스트 계획 5.4의 NoLock 예외 규정에 따라 정합성 위반을 실패로 처리하지 않고 수치로 기록한다.

다만 다음은 NoLock에서도 성립해야 한다.

- 전체 요청이 제한 시간 안에 종료된다
- 응답 분류의 합이 전체 요청 수와 일치한다
- 캠페인별 1인 1매 UNIQUE 제약이 동작한다

교착은 `503 CONCURRENCY_CONFLICT`로 응답된다(6.1 참고). 500이 아니므로 k6에서 별도 카운터로
집계해야 하며, 이것을 `coupon_5xx`와 같이 취급하면 V0의 정상적인 관측값이 오류로 잡힌다.

## 3. 설정 요약

| 항목 | 값 | 설정 위치 |
| --- | --- | --- |
| JPA 락 대기 힌트 | 3,000ms | `CampaignStockRepository.findByIdForUpdate`의 `@QueryHints` |
| MySQL 락 대기 | 50s | `innodb_lock_wait_timeout` (서버 기본값, 변경하지 않음) |
| 교착 검출 | ON | `innodb_deadlock_detect` |
| 트랜잭션 격리 수준 | REPEATABLE-READ | MySQL 서버 기본값 |
| 트랜잭션 경계 | `issue()` 메서드 1개 | 두 전략의 `@Transactional` |
| HikariCP 최대 커넥션 | 10 | `application.yml` `spring.datasource.hikari.maximum-pool-size` |
| HikariCP 커넥션 대기 | 3,000ms | `application.yml` `spring.datasource.hikari.connection-timeout` |
| Tomcat 최대 스레드 | 200 | 미설정(Spring Boot 기본값) |

Level 2에서 전략을 비교할 때 위 값은 전부 고정한다. 하나라도 바뀌면 같은 환경 ID로 비교하지 않는다.

## 4. 트랜잭션 경계

두 전략 모두 `issue()` 하나가 트랜잭션 단위다. 그 안에서 다음이 순서대로 일어난다.

```text
1. DB 시각 조회            (currentDatabaseTime)
2. 캠페인 만료 시각 조회    (findCouponExpireAt)  ← campaignId-stockId 조합 검증 겸함
3. 재고 행 조회            (V1: FOR UPDATE / V0: 일반 조회)
4. 멱등성 확인             (coupon_history 조회)
5. 중복 발급 확인          (coupons exists)
6. 재고 차감
7. coupons INSERT
8. coupon_history INSERT
```

**1~2는 락을 잡기 전에 실행한다.** 락 보유 구간에서 쿼리를 돌리면 모든 요청이 직렬화되는
임계 구역이 그만큼 길어져 TPS에 직접 반영되기 때문이다.

**V1의 직렬 구간은 3~8이다.** 동일 재고 행에 요청이 집중되면 이 구간의 길이가 전체 처리량을
결정한다. V0에는 락이 없어 직렬 구간이 없지만, 대신 커밋 시점의 flush 순서 때문에
DB 수준 교착이 발생한다(Level 1 결과 문서 3.1 참고).

## 5. 락 대기 값 검증 결과

JPA 힌트(3초)와 MySQL 서버 설정(50초)이 서로 다르다.
**실측 결과 JPA 힌트는 적용되지 않으며, 실제 대기 한계는 `innodb_lock_wait_timeout` 50초다.**

`jakarta.persistence.lock.timeout` 힌트는 방언이 지원할 때만 SQL로 번역된다.
Oracle·PostgreSQL은 `FOR UPDATE WAIT n` 형태를 지원하지만, MySQL 8은 `FOR UPDATE NOWAIT`와
`SKIP LOCKED`만 지원하고 대기 시간을 지정하는 문법이 없다. Hibernate의 MySQL 방언은
숫자 타임아웃을 무시하고 평범한 `FOR UPDATE`를 생성한다.

### 5.1 검증 방법

`COUPON_STRATEGY=PESSIMISTIC_LOCK`으로 애플리케이션을 띄운 뒤, 다른 MySQL 세션에서
재고 행을 잡아둔 채 발급 요청을 보내고 응답 시간을 측정했다.

```sql
BEGIN; SELECT * FROM campaign_stocks WHERE stock_id = 1 FOR UPDATE;
```

### 5.2 검증 결과

**단일 요청**

```text
HTTP 503 LOCK_TIMEOUT / 50.47초
```

3초가 아니라 50초다. JPA 힌트가 무시되고 `innodb_lock_wait_timeout`이 적용된 것이다.

**동시 요청 15건 (같은 조건)**

| 요청 | 결과 | 소요 |
| --- | --- | ---: |
| 10건 | 503 `LOCK_TIMEOUT` | 50.3 ~ 50.6초 |
| 5건 | **500** | 정확히 3.0초 |

이 측정은 오류 분류를 적용하기 전의 결과다. 현재는 같은 상황이
503 `CONNECTION_UNAVAILABLE`로 응답된다(6절 참고). 응답 코드만 바뀌었을 뿐
발생 조건과 소요 시간은 동일하다.

애플리케이션 로그에 남은 원인이다.

```text
Connection is not available, request timed out after 3002ms   (5건)
Unhandled exception                                           (5건)
```

커넥션 풀(10개)을 락 대기 요청이 전부 점유하고, 초과분은 `connection-timeout` 3초에 걸려
실패한다. 실패 시점의 풀 상태가 로그에 그대로 남는다.

```text
HikariPool-1 - Connection is not available, request timed out after 3014ms
(total=10, active=10, idle=0, waiting=4)
```

던져지는 예외는 `CannotCreateTransactionException`이다. 스택을 보면
`TransactionInterceptor` → `createTransactionIfNecessary` → `JpaTransactionManager.doBegin`으로,
**전략 메서드에 진입하기도 전에 `@Transactional`이 트랜잭션을 열려다 실패한 것**이다.
따라서 전략 내부의 어떤 catch로도 처리할 수 없다.

이 예외는 `TransactionException` 계열이며 `DataAccessException`과 계보가 다르다.
`DataAccessResourceFailureException`이나 `CannotGetJdbcConnectionException`으로 핸들러를 걸면
잡히지 않는다.

### 5.3 Level 2에 미치는 영향

**락 경합이 길어지면 커넥션 풀 크기를 넘는 요청은 전부 503 `CONNECTION_UNAVAILABLE`이 된다.**
오류 분류로 원인은 알 수 있게 됐지만 503도 5xx이므로, Level 2·3 인수 기준
"기타 5xx 0건"과 "20,000건이 모두 성공 또는 재고 소진으로 끝나야 한다"를
동시에 만족할 수 없는 구조라는 사실은 달라지지 않는다.

다만 이 실험은 락을 인위적으로 50초간 붙잡은 극단적 조건이다. 실제 Level 2에서는 재고 10,000장이
소진된 뒤 나머지 요청이 빠르게 `OUT_OF_STOCK`으로 끝나므로 경합 지속 시간이 훨씬 짧을 수 있다.
발생 여부와 규모는 실제 측정으로 확인해야 한다.

Level 1 규모(재고 10 / 사용자 30)에서는 락 timeout이 0건이라 이 문제가 드러나지 않았다.

## 6. 실패가 응답으로 바뀌는 경로

| 원인 | 발생 시점 | 예외 | 오류 코드 | HTTP |
| --- | --- | --- | --- | --- |
| 락 대기 초과 (V1) | `FOR UPDATE` 실행 중 | `PessimisticLockingFailureException` | `LOCK_TIMEOUT` | 503 |
| 쿼리 타임아웃 | 쿼리 실행 중 | `QueryTimeoutException` | `LOCK_TIMEOUT` | 503 |
| InnoDB 교착 | 트랜잭션 커밋 시점 | `PessimisticLockingFailureException` | `CONCURRENCY_CONFLICT` | 503 |
| 커넥션 획득 실패 | 트랜잭션 시작 단계 | `CannotCreateTransactionException` | `CONNECTION_UNAVAILABLE` | 503 |
| 캠페인–재고 조합 불일치 | 사전 검증 | `CampaignNotFoundException` | `CAMPAIGN_NOT_FOUND` | 404 |
| 캠페인 만료 | 사전 검증 | — | `CAMPAIGN_EXPIRED` | 409 |

발급 경로에서 분류되지 않은 500은 더 이상 없다. 다만 **503도 5xx이므로
`LOCK_TIMEOUT`·`CONCURRENCY_CONFLICT`·`CONNECTION_UNAVAILABLE`은 여전히
Level 2·3 인수 기준에 걸린다.** 분류의 목적은 판정 통과가 아니라 원인 구분이다.
20,000건 규모에서 "500이 몇 건"만 알면 풀 고갈인지 예상 못 한 결함인지 추적할 수 없다.

라벨 없는 500은 "예상하지 못한 일이 일어났다"는 신호로 남겨둔다. 알려진 실패를 전부
도메인 코드로 바꾸면 그 신호가 사라진다.

### 6.1 교착이 전략 내부에서 잡히지 않는 이유

`save()`는 즉시 SQL을 발행하지 않고 트랜잭션 커밋 시점에 flush된다. `@Transactional` 프록시는
메서드가 **반환된 뒤에** 커밋하므로, 커밋 중 발생하는 교착은 전략 안의 `try/catch` 범위를
이미 벗어나 있다. 따라서 `GlobalExceptionHandler`에서
`PessimisticLockingFailureException`을 받아 `CONCURRENCY_CONFLICT`로 분류한다.

이 처리가 없으면 교착이 `handleUnexpected`를 거쳐 500이 되고, k6에서 `coupon_5xx`에 묻혀
건수를 셀 수 없다. "동시성 오류가 발생해도 결과를 측정하고 기록할 수 있어야 한다"를 충족하려면 이 분리가 필요하다.

`LOCK_TIMEOUT`과 `CONCURRENCY_CONFLICT`는 같은 예외 타입에서 나오지만 발생 지점이 다르다.
전자는 V1이 `FOR UPDATE`로 락을 기다리다 한계를 넘긴 경우이고, 후자는 커밋 단계의 교착이다.
V0에서 관측되는 것은 후자다.

### 6.2 실측 (NoLock, 재고 10 / 동시 30건, 로컬 API 호출)

```text
200 ISSUED                10건
409 OUT_OF_STOCK          18건
503 CONCURRENCY_CONFLICT   2건
500                        0건
```

응답 30건이 빠짐없이 세 갈래로 분류됐고, 최종 상태는 쿠폰 10장·잔여 재고 0으로 유실이 없었다.

**같은 조건의 JUnit 동시성 테스트에서는 교착이 22~24건이었다.** API 경로에서는 HTTP 왕복 때문에
요청 도착 시점이 분산되어 경합 강도가 크게 낮아진다. Level 1의 교착 건수를 Level 2 예측에
그대로 사용하지 않는다.

## 7. Level 2에서 함께 볼 지표

락·커넥션 설정이 결과에 어떻게 나타나는지 대응 관계다.

| 설정 | 관측 지표 |
| --- | --- |
| `innodb_lock_wait_timeout`, 락 경합 | `innodb_row_lock_waits`, `innodb_row_lock_time_avg` |
| HikariCP `maximum-pool-size` | `hikaricp.connections.active`, `hikaricp.connections.pending` |
| HikariCP `connection-timeout` | `coupon_connection_unavailable`, `hikaricp.connections.timeout` |
| 커밋 시점 교착 / 커넥션 획득 실패 | `coupon.issue.failure{reason="concurrency_conflict"}`, `{reason="connection_unavailable"}` |
| 트랜잭션 직렬 구간 길이 | p95·p99 지연, TPS |
| Tomcat 스레드 200 | `tomcat.threads.busy` |

**HikariCP pending이 지속적으로 0보다 크면** 커넥션 풀이 병목이라는 뜻이며, 그 상태에서 나온
TPS는 락 경합 비용이 아니라 풀 크기 제약을 측정한 값이다. V1의 가설
("동일 재고 행에 요청이 집중되면 락 대기와 DB 커넥션 점유가 증가한다")을 검증하려면
이 구분이 필요하다.

## 8. 결과 해석 시 주의

- **`LOCK_TIMEOUT`이 0건이 아니면 Level 2 실패다.** 테스트 계획 6.6에 명시돼 있다.
  재고 정합성을 직접 깨뜨리지 않더라도, 성공이나 재고 소진으로 분류되지 못한 요청이기 때문이다.
- **풀 크기를 키워 5xx를 없애는 것은 튜닝이지 수정이 아니다.** 전략 비교 중에 설정을 바꾸면
  §6.3의 "전략 변경 이외의 설정은 고정한다"에 어긋난다. 튜닝 효과를 보려면 별도 회차로 기록한다.
- **run 3 코드부터 요청당 쿼리가 2개 늘었다**(`currentDatabaseTime`, `findCouponExpireAt`).
  둘 다 락 획득 이전이라 직렬 구간에는 포함되지 않지만 전체 처리량에는 반영된다.
  이전 커밋의 수치와 직접 비교하지 않는다.

## 9. 락 대기 대응 방안 (미결)

5절 검증 결과에 대한 대응이 필요하다. 세 가지 선택지가 있으며 어느 것도 단독으로
Level 2 인수 기준을 충족시키지 못한다. 4번과 협의해 결정한다.

| 방안 | 효과 | 한계 |
| --- | --- | --- |
| `innodb_lock_wait_timeout` 하향 (예: 3초) | 대기가 짧아져 커넥션 회전이 빨라지고 `CONNECTION_UNAVAILABLE`이 줄어든다 | MySQL 서버 전역 설정이다. `LOCK_TIMEOUT`은 여전히 발생하며 그 자체가 판정 실패 사유다 |
| `@Lock` + `NOWAIT` | 대기 없이 즉시 실패해 커넥션 점유가 사라진다 | `LOCK_TIMEOUT`이 대량 발생한다 |
| HikariCP 풀 확대 | 커넥션 획득 실패가 줄어든다 | 튜닝이므로 전략 비교 중에 바꾸면 §6.3 위반이다. 별도 회차로 기록해야 한다 |

오류 분류(6절)는 이미 적용했다. 커넥션 획득 실패가 라벨 없는 500 대신
`CONNECTION_UNAVAILABLE`(503)로 나가므로 원인 추적은 가능해졌지만, 503도 5xx이므로
판정 실패라는 사실은 달라지지 않는다.

근본적으로는 **동일 재고 행에 요청이 집중되는 한 직렬 구간의 길이가 처리량 상한을 정한다**는
V1의 구조적 특성이다. 이것이 V1 가설("락 대기와 DB 커넥션 점유가 증가할 것이다")의 실체이며,
Redis Lua와 비교하는 근거가 된다. 대응 방안 선택은 이 특성을 없애는 것이 아니라
측정 가능한 형태로 만드는 것이 목적이다.
