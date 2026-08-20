# 선착순 쿠폰 발급 공통 테스트 설계서 (SSOT)

## 1. 문서 목적

이 문서는 쿠폰 발급 시스템의 테스트 조건, 실행 절차, 측정 지표 및 판정 기준을 정의하는 단일 기준 문서(Single Source of Truth)다.

모든 팀원은 NoLock, 비관적 락, Redis Lua 및 Redis Lua + Kafka 전략을 비교할 때 이 문서를 기준으로 한다. 개인별로 데이터 규모, 초기 재고, 실행 환경 또는 판정 기준을 임의로 변경하지 않는다.

테스트 조건을 변경해야 한다면 이 문서를 먼저 수정하고 팀 리뷰를 거친다.

## 2. 적용 원칙

1. 같은 레벨과 환경에서 비교하는 전략은 동일한 데이터와 부하 조건을 사용한다.
2. 전략별 테스트 전에 DB, Redis 및 Kafka 상태를 동일한 초기 상태로 만든다.
3. 결과에는 실행 코드의 Git commit SHA와 환경 정보를 기록한다.
4. 로컬과 외부 서버에서 얻은 성능 수치를 직접 비교하지 않는다.
5. 기능·정합성 판정과 성능 비교를 구분한다.
6. 아직 구현되지 않은 테스트는 실행된 것으로 기록하지 않는다.
7. 테스트 실패도 삭제하지 않고 원인과 함께 결과에 남긴다.
8. 발급된 재고는 쿠폰의 이후 상태와 관계없이 영구 소진하며, 사용·취소·만료 시 복구하지 않는다.

## 3. 테스트 레벨

| 레벨 | 핵심 목적 | 규모 | 실행 환경·도구 |
| --- | --- | --- | --- |
| Level 1 | 전략별 기능, 비즈니스 규칙 및 소규모 동시성 오류를 빠르게 검출한다. 성능 수치가 아니라 코드 회귀 여부를 판정한다. | 재고 10장, 사용자 30명 | 로컬 Docker, JUnit, Spring Boot Test |
| Level 2 | 동일한 AWS 환경에서 V0~V3을 비교해 Redis와 Kafka 도입 효과 및 비용을 분리하고 기술 선택의 근거를 만든다. | 사용자·요청 20,000건, VU 단계적 증가 | AWS EC2, k6, Prometheus, Grafana |
| Level 3 | 최종 채택 전략이 API 서버 2대 이상의 분산 환경에서 필수 요구사항을 만족하는지 인수 판정한다. | 재고 10,000장, 동시 사용자 20,000명 | AWS EC2, k6, 검증 배치, Prometheus, Grafana |

## 4. 비교 대상

| 버전 | 발급 판정 | DB 저장 | 비교 목적 |
| --- | --- | --- | --- |
| V0 | 락 없음 | MySQL 동기 | 동시성 제어가 없을 때 발생하는 초과 발급·재고 불일치를 재현해 기준선으로 사용한다. |
| V1 | MySQL 비관적 락 | MySQL 동기 | 단일 DB 트랜잭션의 정합성 이점과 락 대기·커넥션 점유 비용을 측정한다. |
| V2 | Redis Lua | MySQL 동기 | 발급 판정만 Redis로 옮겨 Redis Lua 자체의 효과와 Redis–MySQL 이중 쓰기 비용을 측정한다. |
| V3 | Redis Lua | Kafka 비동기 | DB 쓰기를 비동기로 분리했을 때의 부하 완충 효과와 최종 일관성·장애 복구 비용을 측정한다. |

V0은 운영 후보가 아니라 안전장치가 없을 때 발생하는 문제를 재현하기 위한 기준선이다.

V2는 Redis와 Kafka의 효과를 분리해 평가하기 위한 실험용 비교군이다. 최종 제품 경로로 채택하지 않더라도 비교 실험에 필요한 최소 구현을 둘 수 있다.

## 5. Level 1 — 단위·소규모 통합 테스트

### 5.1 목적

- 전략의 기본 비즈니스 규칙을 빠르게 검증한다.
- 대규모 부하 테스트 전에 기능 및 소규모 동시성 오류를 발견한다.
- 외부 성능 결과가 아니라 코드 변경에 따른 회귀 여부를 판단한다.

### 5.2 공통 테스트 조건

전략별 기준 테스트 파일:

```text
src/test/java/com/uply/coupon/coupon/strategy/NoLockIssueStrategyTest.java
src/test/java/com/uply/coupon/coupon/strategy/PessimisticLockIssueStrategyTest.java
src/test/java/com/uply/coupon/coupon/strategy/LuaScriptIssueStrategyUnitTest.java
```

공통 데이터:

| 항목 | 값 |
| --- | ---: |
| 캠페인 | 1건 |
| 재고 풀 | 1건 |
| 노선 | JEJU |
| 좌석 등급 | ECONOMY |
| 초기 재고 | 10장 |
| 사용자 | 30명 |

### 5.3 실행 조건

MySQL 통합 테스트는 `@SpringBootTest`와 실제 MySQL을 사용한다.

Spring 통합 테스트의 공통 설정은 다음 테스트 프로파일을 사용한다.

```text
src/test/resources/application-test.yml
```

`@SpringBootTest`로 실제 MySQL을 사용하는 테스트 클래스에는 `@ActiveProfiles("test")`를 적용한다. 순수 JUnit/Mockito 단위 테스트에는 적용하지 않는다.

실행 전 다음 조건을 만족해야 한다.

- MySQL이 실행 중이어야 한다.
- `coupon_db`가 생성되어 있어야 한다.
- `docs/schema.sql`이 적용되어 있어야 한다.
- `application.yml` 또는 환경 변수의 DB 접속 정보가 올바라야 한다.

실행 명령:

```bash
docker compose up -d mysql
./gradlew test
```

특정 테스트만 실행하려면 다음 형식을 사용한다.

```bash
./gradlew test --tests "<테스트클래스명>"
```

`docs/schema.sql`은 테스트 실행마다 다시 적용하지 않는다. 로컬 스키마를 완전히 재생성해야 할 때만 운영체제별 `reset-schema` 스크립트를 사용하며, 이 작업은 기존 테스트 데이터를 모두 삭제한다.

### 5.4 Level 1 공통 검증 항목 및 전략별 판정

- 처리되지 않은 예외가 없어야 한다.
- 성공 건수는 초기 재고와 일치해야 한다.
- 쿠폰 수는 성공 건수와 일치해야 한다.
- 잔여 재고는 `초기 재고 - 성공 건수`와 일치해야 한다.
- 같은 캠페인에서 같은 사용자의 쿠폰은 최대 한 장이어야 한다.
- 동일 상태 변경 이력이 중복 저장되지 않아야 한다.
- 동시 테스트는 제한 시간 안에 모든 작업이 종료되어야 한다.

#### NoLock 기준선 예외

위 공통 검증 항목은 안전한 발급 전략이 최종적으로 만족해야 할 정합성 기준이다.

V0 NoLock은 동시성 제어 부재로 발생하는 문제를 재현하기 위한 기준선이므로 다음 항목의 위반이 예상된다.

- 성공 건수와 초기 재고의 불일치
- 실제 쿠폰 수와 재고 차감량의 불일치
- `remaining_stock != total_stock - 전체 쿠폰 수`

NoLock은 위 항목을 통과시키는 것이 목적이 아니다. 위반 건수와 차이를 실제 측정값으로 기록해 동시성 제어가 필요한 근거로 사용한다.

다만 다음 항목은 NoLock에서도 별도로 확인한다.

- 전체 요청이 제한 시간 안에 종료되는가
- 처리되지 않은 예외와 DB 오류가 몇 건 발생했는가
- 캠페인별 1인 1매 UNIQUE 제약이 동작하는가
- 쿠폰과 이력 저장 결과가 일치하는가

NoLock의 동시성 문제는 스레드 스케줄링에 따라 매 실행에서 동일하게 발생한다고 보장할 수 없으므로 반복 실행 결과를 기록한다.

각 전략의 구현이 변경되면 동일한 비즈니스 규칙을 검증하는 Level 1 테스트도 함께 변경한다.

## 6. Level 2 — 기술 선택 비교 실험

### 6.1 목적

다음 두 기술 선택을 분리해 검증한다.

1. 발급 동시성 제어: MySQL 비관적 락과 Redis Lua 비교
2. 영속화 방식: MySQL 동기 저장과 Kafka 비동기 저장 비교

Redis와 Kafka를 하나의 기술 선택으로 묶어 결론을 내리지 않는다.

Level 2는 로컬 PC 성능이 아니라 팀이 합의한 동일한 AWS 환경에서 실행한다. k6 부하 발생기와 테스트 대상 서버를 서로 다른 EC2 인스턴스로 분리하고, 같은 VPC와 리전에 배치한다. 전략별 비교 동안 EC2 사양, 애플리케이션 인스턴스 수, JVM, 컨테이너 자원, DB 및 커넥션 풀 설정을 고정한다.

### 6.2 공통 테스트 데이터

| 항목 | 기준값 |
| --- | ---: |
| 가상 사용자 | 최소 20,000명 |
| 전체 발급 요청 | 20,000건 |
| 사용자당 요청 | 1회 |
| 캠페인 | 1건 |
| 재고 풀 | 1건 |
| 초기 재고 | 10,000장 |
| 노선 | JEJU |
| 좌석 등급 | ECONOMY |

사용자는 서로 다른 `userId`를 사용한다. 각 최초 비즈니스 요청은 고유한 UUID v4 `Idempotency-Key`를 사용한다.

### 6.3 공통 부하 조건

- 동일한 k6 시나리오를 모든 전략에 적용한다.
- 전체 요청은 정확히 20,000건이다.
- 워밍업 결과는 본 측정 결과에서 제외한다.
- 본 측정은 전략별 최소 3회 실행한다.
- 비교값은 기본적으로 3회 실행의 중앙값을 사용한다.
- 전략 변경 이외의 애플리케이션·인프라 설정은 고정한다.

동시 VU는 AWS 환경이 부하를 감당할 수 있는지 단계적으로 확인한다.

```text
500 → 1,000 → 5,000 VU
```

각 단계의 전체 요청 수는 20,000건으로 유지한다. 모든 전략은 팀이 선택한 동일한 VU 단계에서 비교하며, 특정 전략에만 유리한 VU 결과를 선택하지 않는다. 20,000명 동시 요청 요구사항 충족 여부는 Level 3에서 별도로 판정한다.

### 6.4 전략별 가설

#### V0 NoLock

> 재고 조회와 차감을 원자적으로 처리하지 않으면 동시 요청에서 초과 발급 또는 재고 불일치가 발생할 것이다.

#### V1 비관적 락

> 단일 DB 트랜잭션으로 정합성을 단순하게 보장할 수 있지만, 동일 재고 행에 요청이 집중되면 락 대기와 DB 커넥션 점유가 증가할 것이다.

#### V2 Redis Lua + MySQL 동기 저장

> Redis Lua의 짧은 원자 연산으로 발급 판정의 DB 락 경합은 줄지만, 요청 경로의 MySQL 쓰기 부하는 남을 것이다.

#### V3 Redis Lua + Kafka 비동기 저장

> DB 쓰기를 Kafka Consumer로 분리하면 요청 시점의 DB 부하를 완충할 수 있지만, 최종 일관성 지연과 분산 장애 복구 복잡성이 증가할 것이다.

### 6.5 공통 측정 항목

| 구분 | 항목 |
| --- | --- |
| 요청 결과 | 전체 요청, 성공, `OUT_OF_STOCK`, `ALREADY_ISSUED`, `LOCK_TIMEOUT`, `CONCURRENCY_CONFLICT`, `CONNECTION_UNAVAILABLE`, 기타 4xx, 기타 5xx |
| 정합성 | 초과 발급, 중복 발급, DB 쿠폰 수, DB 잔여 재고 |
| API 성능 | TPS, 평균 지연, p95, p99, 최대 지연 |
| MySQL | lock wait, 활성 커넥션, HikariCP pending, CPU |
| Redis | 명령 지연, 오류, 잔여 재고 |
| Kafka | Producer 성공·실패, 최대 Consumer lag, DLT, 최종 반영 시간 |

사용하지 않는 기술의 지표는 `N/A`로 기록한다.

### 6.6 Level 2 정합성 판정

V1~V3의 필수 정합성 조건:

- 발급 성공 응답 정확히 10,000건
- `OUT_OF_STOCK` 응답 정확히 10,000건
- 초과 발급 0건
- 캠페인별 사용자 중복 발급 0건
- 최종 쿠폰 수 10,000건
- 최종 DB 잔여 재고 0장
- `LOCK_TIMEOUT` 0건
- 기타 처리되지 않은 5xx 0건

`LOCK_TIMEOUT`은 비관적 락 획득 제한 시간을 초과한 `503` 응답이다. 트랜잭션이 롤백되어 재고 정합성을 직접 깨뜨리지는 않더라도, 정상 성공 또는 재고 소진으로 처리되지 못한 요청이므로 Level 2 실패로 판정한다.

`CONCURRENCY_CONFLICT`와 `CONNECTION_UNAVAILABLE`도 `503` 응답이며 발생 지점이 서로 다르다. 전자는 트랜잭션 커밋 단계의 DB 교착, 후자는 트랜잭션 시작 단계의 커넥션 풀 획득 실패다. 세 코드 모두 일반 `기타 5xx`에 중복 집계하지 않고 각각 센다.

`CONCURRENCY_CONFLICT`는 V0에서 발생하는 것이 정상이다. 동시성 제어 부재를 재현하는 것이 V0의 목적이므로 5.4의 NoLock 예외 규정과 같은 취지로 실패로 처리하지 않고 수치로 기록한다. V1~V3에서는 0건이어야 한다.

응답 분류 합계는 전체 요청 수와 일치해야 한다.

```text
전체 요청
= 성공
+ OUT_OF_STOCK
+ ALREADY_ISSUED
+ LOCK_TIMEOUT
+ CONCURRENCY_CONFLICT
+ CONNECTION_UNAVAILABLE
+ 기타 4xx
+ 기타 5xx
+ 예상하지 못한 응답
```

실제 발급 수는 상태와 관계없이 해당 `stock_id`로 생성된 전체 쿠폰 수다. 사용·취소·만료된 쿠폰도 발급 수에 포함하며, DB 재고는 다음 공식을 만족해야 한다.

```text
remaining_stock = total_stock - COUNT(coupons WHERE stock_id = 대상 재고 풀)
```

V3은 다음 조건을 추가한다.

- Consumer lag가 0이 된 이후 DB 결과를 판정한다.
- DLT가 0건이어야 한다.
- Redis와 DB의 최종 잔여 재고가 일치해야 한다.

V0은 문제 재현용이므로 위 정합성 조건을 만족하지 않을 수 있다. 발생한 오류를 숨기지 않고 실제 수치로 기록한다.

## 7. Level 3 — 최종 인수 테스트

### 7.1 목적

최종 채택 전략이 프로젝트의 필수 요구사항을 만족하는지 검증한다.

### 7.2 필수 환경

- AWS의 동일 리전과 VPC 사용
- k6 부하 발생기와 애플리케이션 서버 분리
- API 서버 2대 이상
- 재고 10,000장
- 동시 사용자 20,000명
- 사용자당 발급 요청 1회
- 공통 k6 시나리오 사용
- 공통 모니터링 활성화

### 7.3 최종 판정 기준

| 항목 | 기준 |
| --- | ---: |
| 전체 요청 | 20,000건 |
| 성공 | 정확히 10,000건 |
| `OUT_OF_STOCK` | 정확히 10,000건 |
| 초과 발급 | 0건 |
| 중복 발급 | 0건 |
| `LOCK_TIMEOUT` | 0건 |
| `CONCURRENCY_CONFLICT` | 0건 |
| `CONNECTION_UNAVAILABLE` | 0건 |
| 기타 5xx | 0건 |
| Kafka 최종 lag | 0 |
| DLT | 0건 |
| MySQL 쿠폰 수 | 10,000건 |
| MySQL 잔여 재고 | 0장 |
| Redis 잔여 재고 | 0장 |

Kafka를 사용하지 않는 전략의 Kafka 항목과 Redis를 사용하지 않는 전략의 Redis 항목은 `N/A`다.

비동기 경로는 Consumer lag가 0이 될 때까지 기다린 뒤 DB 정합성을 판정한다.

검증 배치가 구현된 이후에는 다음 조건도 만족해야 한다.

- INV-01~INV-10 위반 0건
- Redis 전략은 REC-01 불일치 0건

## 8. 공통 실행 환경

### 8.1 환경 ID

각 테스트 환경에 고유 ID를 부여한다.

예:

```text
LOCAL-DOCKER-01
AWS-EC2-01
```

서로 다른 환경 ID에서 측정한 TPS와 지연 시간은 직접 비교하지 않는다.

### 8.2 반드시 기록할 환경 정보

- OS 및 아키텍처
- CPU와 메모리
- Java 버전
- MySQL 버전
- Redis 버전
- Kafka 버전
- k6 버전
- Docker 및 Docker Compose 버전
- 애플리케이션 인스턴스 수
- 각 컨테이너의 CPU·메모리 제한
- HikariCP 설정
- Tomcat thread 설정
- k6 실행 위치
- 애플리케이션과 부하 발생기 사이의 네트워크 구성

Docker 이미지에는 `latest` 대신 명시적인 버전 태그를 사용한다.

현재 공통 Docker 이미지 버전은 다음과 같다.

| 서비스 | 이미지 | 확인된 버전 |
| --- | --- | --- |
| MySQL | `mysql:8.0.46` | 8.0.46 |
| Redis | `redis:7.4.10` | 7.4.10 |
| Kafka | `apache/kafka:3.7.0` | 3.7.0 |
| Prometheus | `prom/prometheus:v2.53.0` | 2.53.0 |
| Grafana | `grafana/grafana:13.1.3` | 13.1.3 |

위 버전은 `LOCAL-DOCKER-01` 환경에서 실제 실행 중인 컨테이너를 기준으로 확인했다. 공통 버전을 변경할 때는 Compose와 이 표를 함께 수정하고 새로운 테스트 환경 또는 실행 회차로 기록한다.

### 8.3 MySQL 스키마 초기화 정책

루트 `docker-compose.yml`은 `docs/schema.sql`을 MySQL의 `/docker-entrypoint-initdb.d/01-schema.sql`에 읽기 전용으로 마운트한다.

- 빈 `mysql_data` volume을 처음 생성할 때만 스키마가 자동 적용된다.
- 기존 volume에서 `docker compose up -d`를 실행해도 기존 데이터는 삭제되지 않는다.
- `docker compose down`은 데이터를 유지한다.
- `docker compose down -v`는 volume과 모든 DB 데이터를 삭제하므로 명시적인 초기화가 필요할 때만 사용한다.

DDL 변경 후 로컬 DB를 완전히 재생성해야 할 때는 운영체제에 맞는 스크립트를 사용한다.

Mac/Linux:

```bash
./scripts/test/reset-schema.sh
```

Windows PowerShell:

```powershell
.\scripts\test\reset-schema.ps1
```

두 스크립트는 `RESET`을 직접 입력한 경우에만 실행된다. 현재 `docs/schema.sql`은 테이블을 DROP한 뒤 다시 생성하므로, 스크립트를 실행하면 `coupon_db`의 사용자·캠페인·재고·쿠폰·이력·검증 데이터가 모두 삭제된다.

스키마 재생성과 부하 테스트 데이터 초기화는 구분한다. Level 2 공통 데이터는 다음 명령으로 최초 한 번 생성한다.

```bash
./scripts/load-test/seed-level2.sh
```

전략 또는 실행 회차를 바꾸기 전에는 스키마를 다시 만들지 않고 다음 명령으로 쿠폰·이력·검증 결과와 Redis 상태만 초기화한다.

```bash
./scripts/load-test/reset-level2.sh
```

### 8.4 부하 발생기 분리

Level 2와 Level 3에서 k6는 애플리케이션과 다른 AWS EC2 인스턴스에서 실행한다. 같은 머신에서 실행하면 부하 발생기가 CPU와 메모리를 점유해 API 결과를 왜곡할 수 있다.

Level 2의 최소 AWS 구성은 다음과 같다.

```text
[부하 발생기 EC2: k6]
          ↓ private IP
[테스트 대상 EC2: Spring Boot + 공통 인프라]
```

Level 3에서는 별도의 k6 인스턴스가 API 서버 2대 이상에 요청을 분산한다. MySQL, Redis, Kafka를 별도 인스턴스로 분리하는 경우에도 모든 비교 전략에 같은 구성을 적용한다.

로컬 Docker 테스트는 Level 1과 AWS 배포 전 스모크 테스트에만 사용한다. 로컬과 AWS에서 얻은 성능 수치는 직접 비교하지 않는다.

## 9. 테스트 전 초기 상태

모든 전략은 다음 초기 상태에서 시작한다.

```text
campaign_stocks.total_stock = 10,000
campaign_stocks.remaining_stock = 10,000
테스트 캠페인의 coupons = 0건
테스트 쿠폰의 coupon_history = 0건
Redis stock:{stockId} = 10,000
Redis issued:{campaignId} = 빈 Set
테스트용 idempotency 키 = 없음
Kafka Consumer lag = 0
DLT = 0건
```

사용하지 않는 저장소의 조건은 `N/A`로 기록한다.

스키마 재생성은 `reset-schema` 스크립트를 사용한다. Level 2 공통 시드는 `seed-level2.sh`, 반복 실행 초기화는 `reset-level2.sh`를 사용한다. 두 스크립트는 전용 테스트 MySQL과 Redis의 데이터를 삭제하므로 운영 또는 공유 개발 환경에서 실행하지 않는다.

## 10. 공통 실행 순서

1. 테스트할 Git commit을 확정한다.
2. 환경 정보와 환경 ID를 기록한다.
3. 공통 인프라를 실행한다.
4. DB 스키마를 적용한다.
5. 이전 테스트 데이터를 초기화한다.
6. 공통 시드 데이터를 적재한다.
7. DB·Redis·Kafka의 초기 상태를 검증한다.
8. 대상 전략과 애플리케이션 인스턴스 수를 설정한다.
9. 애플리케이션 health를 확인한다.
10. 워밍업을 실행한다.
11. 본 테스트를 실행한다.
12. 비동기 경로는 Consumer lag가 0이 될 때까지 기다린다.
13. DB·Redis·Kafka 최종 상태를 검증한다.
14. 결과와 관측 지표를 저장한다.
15. 다음 실행 전 초기화한다.

단계가 실패하면 다음 단계로 넘어가지 않고 실패 원인과 시점을 기록한다.

## 11. 결과 기록 형식

모든 실행 결과에는 다음 메타데이터를 남긴다.

```json
{
  "gitCommit": "f27786d",
  "environmentId": "LOCAL-DOCKER-01",
  "strategy": "PESSIMISTIC_LOCK",
  "appInstances": 1,
  "totalRequests": 20000,
  "initialStock": 10000,
  "runNumber": 1,
  "startedAt": "2026-08-13T10:00:00+09:00"
}
```

전략별 비교표:

| 항목 | V0 NoLock | V1 비관적 락 | V2 Redis+동기 DB | V3 Redis+Kafka |
| --- | ---: | ---: | ---: | ---: |
| Git commit |  |  |  |  |
| 환경 ID |  |  |  |  |
| 전체 요청 |  |  |  |  |
| 성공 |  |  |  |  |
| `OUT_OF_STOCK` |  |  |  |  |
| `ALREADY_ISSUED` |  |  |  |  |
| `LOCK_TIMEOUT` |  |  |  |  |
| `CONCURRENCY_CONFLICT` |  |  |  |  |
| `CONNECTION_UNAVAILABLE` |  |  |  |  |
| 기타 5xx |  |  |  |  |
| 초과 발급 |  |  |  |  |
| 중복 발급 |  |  |  |  |
| DB 쿠폰 수 |  |  |  |  |
| DB 잔여 재고 |  |  |  |  |
| Redis 잔여 재고 | N/A | N/A |  |  |
| TPS |  |  |  |  |
| 평균 지연 |  |  |  |  |
| p95 |  |  |  |  |
| p99 |  |  |  |  |
| DB lock wait |  |  |  |  |
| HikariCP pending |  |  |  |  |
| Redis latency | N/A | N/A |  |  |
| 최대 Kafka lag | N/A | N/A | N/A |  |
| DLT | N/A | N/A | N/A |  |
| DB 최종 반영 시간 | 즉시 | 즉시 | 즉시 |  |

공통 k6 스크립트는 `load-tests/k6/issue-level2.js`를 사용한다. 실행 요약 JSON은 `load-tests/results/`에 저장하며 파일명에 전략, VU, 실행 회차를 포함한다.

예:

```text
load-tests/results/pessimistic-vu500-run1.json
```

## 12. 기술 선택 판정 기준

기술 선택은 TPS만으로 결정하지 않는다.

| 기준 | 비관적 락 | Redis Lua | Redis Lua + Kafka |
| --- | --- | --- | --- |
| 초과 발급 방지 | 검증 대상 | 검증 대상 | 검증 대상 |
| 구현 복잡도 | 낮음 | 중간 | 높음 |
| 단일 트랜잭션 정합성 | 강함 | 분산 정합성 필요 | 분산 정합성 필요 |
| DB 락 경합 | 발생 가능 | 낮음 | 낮음 |
| DB 쓰기 완충 | 없음 | 없음 | 가능 |
| 즉시 조회 일관성 | 높음 | 저장 방식에 따라 다름 | 낮을 수 있음 |
| 장애 복구 난이도 | 낮음 | 중간 | 높음 |
| Kafka 운영 부담 | 없음 | 없음 | lag·DLT 관리 필요 |

비관적 락이 목표 정합성과 성능을 충족한다면 단순성과 즉시 일관성 때문에 합리적인 선택일 수 있다. Redis Lua 또는 Kafka는 실험에서 확인된 개선 효과가 추가 복잡성과 장애 위험보다 클 때 채택한다.

## 13. 담당 구분

| 담당 | 테스트 책임 |
| --- | --- |
| 1-A | NoLock·비관적 락 비교군과 관련 Level 1 테스트 |
| 1-B | Redis Lua·Redis 동기 저장 비교군·Kafka Producer |
| 2번 | 동기 저장과 Kafka Consumer의 DB 최종 결과 검증 |
| 3번 | 테스트 데이터, 검증 SQL·배치, INV-01~10 및 REC-01 |
| 4번 | 공통 실행 환경, k6, 모니터링, 결과 수집 및 비교표 |

테스트 결과의 최종 판정은 한 담당자의 자체 기준이 아니라 이 문서의 공통 기준으로 수행한다.
