
# 통합 테스트 격리 및 미니 인수 테스트 보강 내역

기존 로컬 DB/Redis/Kafka 공유 환경에서 발생할 수 있는 데이터 오염과 테스트 간 간섭을 제거하고, V0~V3 통합 테스트를 **실제 MySQL·Redis·Kafka Testcontainers 기반의 완전 격리 환경**에서 실행하도록 변경했다.

## 1. 통합 테스트 전용 인프라를 Testcontainers로 완전 격리

모든 통합 테스트가 `IntegrationTestContainers`를 상속하도록 구성했다.

테스트 JVM이 시작되면 다음 컨테이너를 독립적으로 기동한다.

* MySQL `8.0.46`
* Redis `7.4.10`
* Kafka `apache/kafka:3.7.0`

MySQL은 `docs/schema.sql`을 컨테이너 초기화 시 적용하며, 테스트 전용 데이터베이스와 계정을 사용한다.

```text
MySQL
  └─ coupon_db
Redis
Kafka
```

각 테스트는 기존 개발/로컬 실행 환경의 DB, Redis, Kafka를 사용하지 않고 **테스트 컨테이너 내부의 리소스만 사용**한다.

Spring Context에는 `@DynamicPropertySource`를 통해 컨테이너의 실제 접속 정보를 주입한다.

```text
spring.datasource.url        -> Testcontainers MySQL
spring.data.redis.host      -> Testcontainers Redis
spring.data.redis.port      -> Testcontainers Redis
spring.kafka.bootstrap-servers -> Testcontainers Kafka
```

따라서 테스트 실행 시 실제 개발 환경의 공유 DB나 Redis 상태에 의존하지 않는다.

---

# 2. Kafka topic을 Spring Context 생성 전에 명시적으로 생성

기존처럼 `@BeforeEach`에서 Kafka topic을 생성하는 방식은 제거했다.

`IntegrationTestContainers`의 static 초기화 단계에서:

```text
MySQL start
Redis start
Kafka start
    ↓
Kafka AdminClient로 topic 생성
    ↓
partition 수 검증
    ↓
Spring ApplicationContext 생성
```

순서가 보장되도록 변경했다.

필수 topic은 다음 두 개다.

```text
coupon-issued
coupon-issued.DLT
```

두 topic 모두 **3 partitions**를 요구한다.

```java
static final int ISSUE_TOPIC_PARTITIONS = 3;
```

topic이 없으면 명시적으로 3개 partition으로 생성하고, 이미 존재하더라도 단순히 `TopicExistsException`을 무시하지 않는다.

실제 broker에 생성된 topic을 `describeTopics()`로 조회하여 다음을 검증한다.

```java
assertThat(description.partitions())
        .hasSize(ISSUE_TOPIC_PARTITIONS);
```

따라서 Kafka가 잘못된 partition 수로 topic을 자동 생성했을 경우 테스트 초기화 단계에서 즉시 실패한다.

`coupon-issued`뿐 아니라:

```text
coupon-issued.DLT
```

역시 동일하게 **3 partitions**인지 검증한다.

즉, 이제 V3 테스트가 실행될 때는 Spring의 `@KafkaListener`가 기동하기 전에 Kafka topic 구조가 이미 확정되어 있다.

---

# 3. V0~V3 테스트를 실제 격리된 컨테이너 환경에서 실행

V0~V3 테스트는 모두 다음 공통 기반을 사용한다.

```java
extends IntegrationTestContainers
```

각 회차의 테스트 데이터는 `CouponIntegrationFixture`를 통해 생성하고 테스트 종료 전후로 정리한다.

Redis 역시 테스트 시작/종료 시 flush하여 이전 회차의 cache state가 다음 회차로 넘어가지 않도록 한다.

특히 V2/V3에서는:

```text
Redis flush
↓
DB fixture reset
↓
campaign 생성
↓
user 생성
↓
Redis warmup
```

순서를 지켜서 warmup으로 생성한 Redis 상태를 다시 삭제하지 않도록 했다.

---

# 4. V0 DB-only baseline 검증 강화

V0은:

```text
NO_LOCK
sync-db
idempotency disabled
```

조건으로 실행한다.

DB-only 경로가 실제로 Redis campaign/stock cache에 의존하지 않는지를 검증하기 위해 발급 전에 Redis를 warmup하지 않는다.

발급 이후 다음 Redis key가 존재하지 않는 것을 확인한다.

```text
campaign:{campaignId}:*
stockId:{campaignId}:*
```

즉 V0은 Redis cache가 없어도 MySQL 기반으로 발급되는 경로임을 실제 통합 테스트에서 확인한다.

또한 동시 발급 baseline에서는:

```text
stock = 10
requests = 30
```

조건으로 실제 경합을 발생시킨다.

V0에서는 설계상 동시성 오류 자체를 무조건 실패로 판정하지 않고:

```text
outOfStock
dbConflicts
issued
remaining
```

등의 실제 실행 결과를 기록한다.

동시에 다음과 같은 불변 조건은 단언한다.

* 동일 campaign에서 사용자 중복 발급 없음
* coupon/history 개수 일치
* 최소 1건 이상의 실제 발급 발생
* 모든 worker가 제한 시간 안에 종료
* 예상하지 못한 예외 없음

그리고 `RoundReportWriter`를 통해 V0 baseline 결과를 기록한다.

---

# 5. V0의 무제한 await 제거

기존 V0 동시성 테스트에 존재하던 무제한:

```java
ready.await();
```

형태를 제거하고 제한 시간을 적용했다.

현재는:

```java
assertThat(
        ready.await(10, TimeUnit.SECONDS))
        .as("모든 발급 작업이 10초 안에 준비되지 않았습니다.")
        .isTrue();
```

형태로 worker 준비 단계부터 timeout을 갖는다.

또한 V1~V3와 동일하게 Executor를 `try/finally`로 관리하여 테스트 성공/실패와 관계없이 worker pool이 정리되도록 구성했다.

따라서 스레드 생성이나 작업 제출에 문제가 생겼을 때 CI가 무한 대기하는 문제를 방지한다.

---

# 6. V1 Pessimistic Lock 동시성 검증

V1은:

```text
PESSIMISTIC_LOCK
sync-db
idempotency disabled
```

조건으로 실행한다.

테스트 조건은:

```text
초기 재고 = 10
동시 요청 = 30
```

이다.

모든 worker가 준비된 후 하나의 `CountDownLatch`로 동시에 출발시켜 실제 재고 경합을 발생시킨다.

최종적으로 다음을 검증한다.

```text
성공 = 10
실패 = 20
실패 사유 = OUT_OF_STOCK
coupon = 10
history = 10
remaining = 0
```

또한 Redis campaign/stock cache 없이도 DB 기반 Pessimistic Lock 경로가 동작하는 것을 확인한다.

---

# 7. V2 Redis Lua 동시성 검증

V2는:

```text
LUA_SCRIPT
sync-db
idempotency disabled
```

조건이다.

테스트 시작 시 fixture 데이터를 생성한 뒤 `CampaignCacheWarmupService`를 통해 Redis의 campaign/stock/issued 상태를 준비한다.

동일하게:

```text
재고 10
요청 30
```

을 동시에 실행한다.

Lua script의 원자적인 재고 차감 결과를 검증하여:

```text
성공 = 10
실패 = 20
실패 사유 = OUT_OF_STOCK
```

을 확인한다.

그리고 Redis와 DB 양쪽 상태를 함께 검증한다.

```text
Redis stock = 0
Redis issued set size = 10
DB coupon = 10
DB history = 10
DB remaining = 0
```

따라서 V2는 Redis Lua의 원자적 재고 차감과 DB 동기 저장 결과가 일치하는지를 실제 컨테이너 환경에서 확인한다.

---

# 8. V3 Lua → Kafka → DB 전체 비동기 경로 검증

V3은:

```text
LUA_SCRIPT
kafka
idempotency disabled
```

조건으로 실행한다.

전체 흐름은 실제 Kafka Testcontainer를 통해:

```text
Client
  ↓
Redis Lua
  ↓
Kafka coupon-issued
  ↓
Kafka Consumer
  ↓
MySQL settlement
```

으로 검증한다.

테스트 조건은:

```text
재고 = 10
요청 = 30
```

이며 최종 발급 결과는:

```text
성공 = 10
실패 = 20
실패 사유 = OUT_OF_STOCK
```

이어야 한다.

여기서 V3는 DB 저장이 비동기이므로 `couponService.issue()`가 끝난 직후 DB를 단언하지 않는다.

Awaitility를 이용하여 Kafka consumer가 settlement를 완료할 때까지 기다린다.

최종적으로:

```text
DB coupon count = 10
DB history count = 10
DB remaining = 0
```

을 확인한다.

---

# 9. Kafka settlement 완료까지 별도 검증

DB 값이 맞는 것만으로 Kafka 처리가 완전히 끝났다고 판단하지 않도록 했다.

`KafkaSettlementChecker`를 사용하여:

```text
consumer lag
DLT 상태
settlement 상태
```

가 모두 정착될 때까지 별도로 기다린다.

즉 V3의 검증 순서는:

```text
동시 발급 완료
        ↓
DB settlement 완료
        ↓
Kafka lag / DLT 정착 확인
        ↓
Redis 상태 확인
        ↓
최종 verification report 생성
```

으로 고정했다.

따라서 settlement가 끝나기 전에 report가 실행되어 `SKIPPED_NOT_SETTLED`가 발생하는 문제도 방지한다.

---

# 10. V3 Kafka partition 조건을 실제 broker에서 검증

V3 테스트 자체에서 topic을 생성하지 않는다.

Kafka topic의 생성 책임은 `IntegrationTestContainers`로 이동했다.

컨테이너 기동 직후:

```text
coupon-issued       -> 3 partitions
coupon-issued.DLT   -> 3 partitions
```

을 생성하고 실제 broker의 metadata를 조회하여 partition 수를 검증한다.

따라서 다음과 같은 잘못된 상태가 더 이상 테스트를 통과할 수 없다.

```text
coupon-issued = 1 partition
coupon-issued.DLT = 1 partition
```

또는

```text
topic이 자동 생성된 후
createTopics()에서 TopicExistsException만 무시
```

하는 경우도 허용하지 않는다.

---

# 11. 관리자 API의 판정 테스트도 Mock 기반 검증에서 실제 SQL 통합 검증으로 변경

관리자 API 테스트 역시 단순히:

```java
given(jdbcTemplate.queryForList(...))
        .willReturn(...)
```

하여 controller가 가짜 결과를 전달하는지만 확인하는 방식에서 벗어난다.

실제 MySQL Testcontainer에 테스트 데이터를 구성하고 **실제 SQL 판정 결과를 검증하는 통합 테스트**로 변경한다.

판정 규칙은 다음을 실제 DB 상태에서 검증한다.

| 조건                | 판정           |
| ----------------- | ------------ |
| V0 baseline + 위반  | `BASELINE`   |
| `SKIPPED` 존재      | `INCOMPLETE` |
| `CHECKED` 위반 존재   | `FAILED`     |
| 위반 및 `SKIPPED` 없음 | `PASSED`     |

또한 관리자 API의 상세 응답에서:

```json
"passed": true
```

또는

```json
"passed": false
```

가 실제 JSON boolean으로 반환되는지도 검증한다.

즉 SQL 판정 로직 자체를 삭제하거나 변경했을 때 테스트가 함께 실패하도록 테스트의 검증 범위를 확대했다.

---

# 12. 테스트 결과를 Round Report로 남기도록 통일

각 회차 테스트가 단순 assertion만 수행하는 것이 아니라 실제 실행 결과를 회차별 report로 남긴다.

```text
V0 -> build/round-results/V0.md
V1 -> build/round-results/V1.md
V2 -> build/round-results/V2.md
V3 -> build/round-results/V3.md
```

V0은 baseline 결과를 기록하고, V1~V3은 해당 회차의 최종 검증을 통과했는지 확인한다.

특히 V3은 Kafka settlement가 완료된 이후 report를 생성하여 비동기 처리 중간 상태를 최종 결과로 기록하지 않는다.

---

# 13. 테스트 규모와 SSOT 기준 정리

테스트 계획과 실제 테스트 규모가 서로 다른 문제도 정리한다.

Level 1 미니 통합 테스트의 기준은:

```text
재고 10
사용자/요청 30
```

으로 통일한다.

따라서 현재 V0~V3의 기본 동시성 검증은 모두 동일한 기준에서 비교할 수 있다.

```text
V0: stock 10 / requests 30
V1: stock 10 / requests 30
V2: stock 10 / requests 30
V3: stock 10 / requests 30
```

이를 통해 V0 → V1 → V2 → V3 전략 변경에 따른 동시성/정합성 차이를 동일한 조건에서 비교할 수 있다.

별도의 미니 인수 테스트가 더 큰 부하를 사용하는 경우에는 Level 1 테스트와 목적을 분리하여 문서에 명시한다.

---

## 최종적으로 반영된 구조

```text
                    IntegrationTestContainers
                              │
          ┌───────────────────┼───────────────────┐
          │                   │                   │
       MySQL               Redis               Kafka
   Testcontainer        Testcontainer       Testcontainer
          │                   │                   │
          │                   │            topic 생성/검증
          │                   │             ├─ coupon-issued (3)
          │                   │             └─ coupon-issued.DLT (3)
          │                   │                   │
          └───────────────────┼───────────────────┘
                              │
                       Spring Context
                              │
                ┌─────────────┼─────────────┐
                │             │             │
               V0            V1            V2/V3
             NO_LOCK    PESSIMISTIC       LUA
                          LOCK             │
                                          │
                                     V2 → DB
                                     V3 → Kafka → DB
```

