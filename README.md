<div align="center">

# 항공사 특가 이벤트, 대규모 트래픽 선착순 쿠폰 발급 시스템

**U-Ply** — 한정 재고 발급부터 최종 정합성 검증까지 수행하는 백엔드 시스템

</div>

## 📌 프로젝트 개요

| 구분 | 내용 |
| --- | --- |
| **프로젝트명** | 항공권 특가 이벤트 기반 대규모 트래픽 선착순 쿠폰 발급 시스템 |
| **팀명** | U-Ply |
| **개발 기간** | 2026.08.11 ~ 2026.08.29 |
| **개발 인원** | 5명 |
| **팀원** | 정윤희 · 김윤기 · 이승지 · 임재민 · 장지원 |

<br>

## 💡 프로젝트 소개

**U-Ply**는 항공권 특가 이벤트를 소재로, 수만 건의 요청이 동시에 몰리는 환경에서도 재고를 초과하지 않고 쿠폰을 발급하며, 처리된 쿠폰·이력·재고의 정합성을 시스템이 스스로 검증하는 프로젝트입니다.

동일한 코드·데이터·인프라 조건에서 발급 전략만 교체해 다음 네 가지 방식을 비교했습니다.

| 버전 | 발급 판정 | 영속화 방식 |
| --- | --- | --- |
| **V0** | 동시성 제어 없음(NoLock) | MySQL 동기 저장 |
| **V1** | DB 비관적 락 | MySQL 동기 저장 |
| **V2** | Redis Lua 원자 연산 | MySQL 동기 저장 |
| **V3** | Redis Lua 원자 연산 | Kafka 비동기 저장 |

최종 구조인 V3의 처리 흐름은 다음과 같습니다.

```text
캠페인 오픈 (노선·좌석 등급별 독립 재고 풀)
    ↓
선착순 발급 요청 · Redis 멱등성 검사
    ↓
Redis Lua로 오픈 시각·중복·재고를 원자적으로 판정
    ↓
Kafka 이벤트 발행 후 API 응답
    ↓
Consumer가 쿠폰·이력·DB 재고를 하나의 트랜잭션으로 저장
    ↓
쿠폰 상태 관리 (발급 → 사용 / 예약 취소 / 만료 / 항공사 회수)
    ↓
INV·CLOCK·REC 규칙을 이용한 정합성 검증
```

<br>

## ✨ 주요 기능

- 캠페인 목록·상세 및 노선·좌석 등급별 재고 조회
- SSE 기반 실시간 발급 현황 조회
- 선착순 쿠폰 발급과 1인 1매 보장
- Redis 기반 API 멱등성 처리와 DB UNIQUE 제약을 이용한 이중 방어
- 쿠폰 사용, 사용자 예약 취소, 항공사 미사용 쿠폰 일괄 회수
- 미사용 쿠폰 만료 배치
- Redis 캐시 사전 웜업·운영 중 부분 복구·선택적 자동 복구
- Kafka 재시도·DLT·pending 발급 상태 관리
- INV, CLOCK, REC 기반 정합성 검증 및 리포트
- 관리자 대시보드와 Prometheus·Grafana 기반 관측

<br>

## 🏆 핵심 결과

### Level 2 — 발급 전략 비교

동일한 AWS 환경에서 **재고 10,000장, 요청 20,000건, VU 500** 조건으로 측정했습니다. 처리량과 응답 시간은 k6가 관측한 전체 HTTP 요청 기준입니다.

| 전략 | 발급 성공 | 정상 재고 소진 | 주요 실패 | 처리량 | 평균 응답 | p95 |
| --- | ---: | ---: | --- | ---: | ---: | ---: |
| **V0 NoLock** | 5,713 | 0 | 동시성 충돌 14,278, 커넥션 실패 9 | 314.90 req/s | 1.56s | 2.50s |
| **V1 비관적 락** | 10,000 | 7,152 | 커넥션 획득 실패 2,848 | 93.95 req/s | 5.28s | 8.34s |
| **V2 Redis + MySQL** | 10,000 | 10,000 | 0 | 189.95 req/s | 2.61s | 5.31s |
| **V3 Redis + Kafka** | 10,000 | 10,000 | 0 | **2,406.92 req/s** | **197.61ms** | **389.74ms** |

- V0는 동시성 제어가 없을 때 발생하는 오류를 확인하기 위한 기준선입니다.
- V1은 초과 발급을 막았지만 DB 락·커넥션 대기로 처리량과 지연에 한계가 있었습니다.
- V2는 Redis에서 발급 판정을 원자적으로 처리했지만 MySQL 동기 저장이 요청 경로에 남았습니다.
- V3는 발급 판정과 영속화를 분리해 V2 대비 처리량을 약 **12.7배** 높이고 p95를 약 **13.6배** 낮췄습니다.
- V3 최종 정착 후 DB 쿠폰 10,000건, Redis·DB 잔여 재고 0, Kafka lag 0, DLT 0건, 정합성 위반 0건을 확인했습니다.

### 추가 부하 시나리오

- **핫키 편중:** 제주 90% · 후쿠오카 7% · 방콕 3% 요청에서도 재고 풀별 한도 준수
- **다중 재고 풀:** Economy 8,000장 · Business 2,000장을 독립적으로 차감해 총 10,000건 발급
- **멱등성 재시도:** 동일 키의 반복 요청이 한 번만 반영되는지 검증

### 대용량 정합성 검증

- 사용자 **1,000,000명**, 쿠폰 **3,000,000건**, 이력 **3,898,982건** 생성·적재
- 정상 데이터에서 15개 규칙 중 적용 대상 13개 규칙 모두 통과, 총 위반 0건
- 같은 데이터로 재실행했을 때 규칙 상태·위반 수·검사 행 수가 모두 일치
- 오염 데이터 5건을 주입했을 때 `INV-04`가 해당 5건만 정확히 검출

상세 결과는 [300만 건 정합성 검증 결과](docs/round-results/bulk-verification.md)에서 확인할 수 있습니다.

<br>

## 🚀 실행 방법

### 1. 사전 준비

- Git
- **JDK 17 또는 21**
- Docker Desktop

```bash
java -version
docker --version
```

> JDK 25는 현재 Gradle 실행 JVM으로 사용할 수 없습니다. STS/Eclipse가 번들 JRE로 25를 사용하는 경우 `Preferences → Gradle → Java home`을 JDK 17 또는 21로 지정해 주세요. Gradle은 Wrapper를 사용하며, 컴파일 toolchain은 Java 17입니다.

### 2. 프로젝트 내려받기

```bash
git clone https://github.com/U-Ply/backend.git
cd backend
```

### 3. 인프라 기동

```bash
docker compose up -d
docker compose ps
```

MySQL, Redis, Kafka와 Prometheus, Grafana, exporter, cAdvisor가 함께 실행됩니다.

### 4. 스키마 적재

```bash
docker exec -i coupon-mysql mysql -uroot -proot1234 < docs/schema.sql
```

마지막에 테이블 7개와 CHECK 제약 11개 목록이 출력되면 성공입니다.

> 이 명령은 `coupon_db`의 기존 테이블을 다시 생성합니다. 보존해야 하는 데이터가 있다면 실행하지 마세요.

### 5. 시연용 데이터 준비

아래 스크립트는 사용자 20,000명, 캠페인 1개, 재고 10,000장을 MySQL과 Redis에 준비합니다.

```bash
./scripts/load-test/seed-level2.sh
# 확인 문구가 나오면 SEED 입력
```

> 이 스크립트는 기존 `coupon_db`와 Redis 데이터를 Level 2 시드로 교체합니다.

### 6. 테스트 및 애플리케이션 실행

```bash
./gradlew clean test spotlessCheck
```

통합 테스트는 Testcontainers로 격리된 MySQL·Redis·Kafka를 실행하므로 Docker가 실행 중이어야 합니다.

일반 실행의 기본값은 `Redis Lua + MySQL 동기 저장(V2)`입니다.

```bash
./gradlew bootRun
```

V3로 실행하려면 다음 환경변수를 지정합니다.

```bash
COUPON_STRATEGY=LUA_SCRIPT \
COUPON_SAVE_STRATEGY=kafka \
COUPON_KAFKA_CONSUMER_ENABLED=true \
./gradlew bootRun
```

일반 실행에서는 Redis 멱등성 처리가 기본 활성화됩니다. `COUPON_IDEMPOTENCY_ENABLED=false`는 순수 전략 비교를 위한 Level 2 회차에서만 사용합니다.

### 7. Redis 캠페인 캐시 준비·복구

Redis Lua 전략(V2·V3)을 사용하기 전, 캠페인 오픈 전에 DB 기준 캐시를 전체 구성합니다.

`GET /api/campaigns/{campaignId}`와 `GET /api/campaigns/{campaignId}/status`는 잔여 재고를 Redis에서 읽습니다. `stock:{stockId}` 키가 없으면 재고를 0으로 표시하지 않고 `503 CAMPAIGN_NOT_CACHED`를 반환합니다. 자동 캐시 복구가 활성화된 경우 조회 API의 캐시 미스도 복구 트리거에 포함됩니다.

```bash
curl -X POST http://localhost:8081/api/admin/campaigns/1/cache/warmup
```

운영 중 일부 캐시 키가 유실됐다면 기존 재고를 덮어쓰지 않고 누락된 키만 복구합니다.

```bash
curl -X POST http://localhost:8081/api/admin/campaigns/1/cache/recover
```

- `warmup`: 오픈 전 또는 발급 트래픽을 차단한 상태에서만 사용
- `recover`: 운영 중 누락된 키를 `SETNX` 방식으로 복구
- V3에서는 Kafka lag·DLT가 정착되지 않으면 두 작업 모두 503으로 거부
- 캐시가 준비되지 않은 발급·재고 조회 요청은 `503 CAMPAIGN_NOT_CACHED`로 응답

V0·V1은 MySQL 재고만 차감하고 Redis를 갱신하지 않습니다. 따라서 전략 비교 회차 중에는 Redis 기반 실시간 재고 화면을 정합성 판정 근거로 사용하지 않습니다.

두 엔드포인트의 차이와 장애 대응 절차는 [Redis 캐시 미스 대응 문서](docs/redis-cache-miss-response.md)를 참고하세요.

### 8. 접속 주소

| 서비스 | 주소 | 비고 |
| --- | --- | --- |
| U-Ply 사용자·관리자 화면 | http://localhost:8081 | Spring Boot 정적 화면 |
| Actuator Health | http://localhost:8081/actuator/health | 애플리케이션 상태 |
| Prometheus | http://localhost:9090 | 메트릭 수집·조회 |
| Grafana | http://localhost:3000 | `admin / admin1234` |
| cAdvisor | http://localhost:8085 | 컨테이너 CPU·메모리 |

> Grafana의 익명 조회와 기본 비밀번호는 부하 테스트 환경 전용 설정입니다. 공개 환경에서는 반드시 인증과 접근 제어를 적용해야 합니다.

<details>
<summary><b>문제 해결</b></summary>
<br>

- `Unsupported class file major version 69`: Gradle 실행 JVM을 JDK 17 또는 21로 변경합니다.
- `java.lang.Object cannot be resolved`: IDE에서 `Gradle → Refresh Gradle Project`를 실행합니다.
- `ports are not available (3306)`: 로컬 MySQL을 중지하거나 Compose 포트 설정을 변경합니다.
- `./gradlew: Permission denied`: `chmod +x gradlew`를 실행한 뒤 다시 시도합니다.

</details>

<br>

## 🔌 주요 API

| 영역 | Method | Endpoint | 설명 |
| --- | --- | --- | --- |
| 캠페인 | GET | `/api/campaigns` | 캠페인 목록 조회 |
| 캠페인 | GET | `/api/campaigns/{campaignId}` | 캠페인·재고 풀 상세 조회 |
| 캠페인 | GET | `/api/campaigns/{campaignId}/status` | 재고 풀 발급 현황 조회 |
| 캠페인 | GET(SSE) | `/api/campaigns/{campaignId}/status/stream` | 실시간 재고 현황 구독 |
| 쿠폰 | POST | `/api/coupons/issue` | 쿠폰 발급 |
| 쿠폰 | GET | `/api/coupons/{couponId}` | 쿠폰 단건 조회 |
| 쿠폰 | GET | `/api/users/{userId}/coupons` | 사용자 보유 쿠폰 조회 |
| 쿠폰 | POST | `/api/coupons/{couponId}/use` | 쿠폰 사용 |
| 쿠폰 | POST | `/api/coupons/{couponId}/cancel` | 쿠폰을 사용한 예약 취소 |
| 관리자 | POST | `/api/admin/campaigns/{campaignId}/coupons/revoke` | 미사용 쿠폰 일괄 회수 |
| 관리자 | POST | `/api/admin/campaigns/{campaignId}/cache/warmup` | Redis 캐시 전체 준비 |
| 관리자 | POST | `/api/admin/campaigns/{campaignId}/cache/recover` | Redis 누락 캐시 복구 |
| 배치 | POST | `/api/admin/batch/{jobKey}` | 만료·검증·재고 대사 실행 |
| 배치 | GET | `/api/admin/batch/executions/{executionId}` | 배치 실행 상태 조회 |

발급·사용·취소·회수 요청의 `Idempotency-Key` 헤더에는 UUID v4를 사용합니다.

<br>

## 🛠️ 기술 스택

### Backend

| 구분 | 기술 |
| --- | --- |
| Language | Java 17 |
| Framework | Spring Boot 3.3.2, Spring Batch |
| Persistence | Spring Data JPA |
| Database | MySQL 8.0.46 |
| Cache | Redis 7.4.10, Lua Script |
| Messaging | Apache Kafka 3.7.0 |
| Observability | Spring Boot Actuator, Micrometer |
| Build | Gradle 8.14 |

### Frontend · Infra · 성능 · 관측

| 구분 | 기술 |
| --- | --- |
| Frontend | HTML, CSS, JavaScript, Spring Boot Static Resources |
| Cloud | AWS EC2, Application Load Balancer |
| Container | Docker Compose |
| Load Test | k6 |
| Monitoring | Prometheus 2.53, Grafana 13.1.3 |
| Exporter | MySQL Exporter, Redis Exporter, Kafka Exporter, cAdvisor |

### Test · Quality · CI

| 구분 | 기술 |
| --- | --- |
| Test | JUnit 5, Testcontainers 1.21.4, Awaitility 4.2.2 |
| Code Style | Spotless, Google Java Format AOSP |
| CI | GitHub Actions (`clean test spotlessCheck`) |

<br>

## 🧪 테스트 구성

```text
Level 1  소규모 동시성·상태 전이·실패 보상 검증
Level 2  동일한 AWS 환경에서 V0~V3 전략 비교
Level 3  애플리케이션 2대와 ALB를 이용한 최종 인수 테스트
Bulk     사용자 100만 명·쿠폰 300만 건 전체 정합성 검증
```

부하테스트 실행법과 판정 기준은 [k6 실행 가이드](load-tests/k6/README.md)와 [공통 테스트 설계서](docs/test-plan.md)를 따릅니다.

<br>

## 🧯 주요 트러블슈팅

<details>
<summary><b>1. 타임존 불일치로 검증 배치가 데이터를 검사하지 않고 통과한 문제</b></summary>
<br>

### 문제

애플리케이션 JVM은 KST, MySQL은 UTC를 사용하고 있었습니다.  
이로 인해 애플리케이션이 기록한 `created_at`이 검증 기준 시각보다 미래로 해석되면서, 실제 발급 데이터가 검증 대상에서 제외됐습니다.

검증 결과는 모든 규칙이 0건으로 표시됐지만, 실제로는 정상 통과가 아니라 **검사 대상 행 자체가 누락된 거짓 통과(false pass)**였습니다.

### 원인

- JVM·MySQL·JDBC 세션의 타임존 불일치
- 위반 건수만 확인하고 실제 검사 대상 행 수를 확인하지 않음
- 검증 쿼리의 기준 시각 필터에서 애플리케이션 생성 행이 제외됨

### 해결

- JVM, MySQL, JDBC 세션의 타임존을 모두 UTC로 통일
- Redis `TIME`과 DB `NOW(3)`의 차이를 검사하는 `CLOCK-02` 추가
- 규칙별 위반 건수와 함께 `checkedRows`를 기록
- 시계 조건을 만족하지 못한 회차는 성공이 아닌 `INVALID`로 판정
- 적용 대상이 아닌 규칙은 통과로 표시하지 않고 `NOT_APPLICABLE`로 구분

### 결과

검증 배치가 실제 검사한 행 수를 확인할 수 있게 되었으며, 시계 설정 오류나 검사 대상 누락을 정상 통과로 오인하지 않도록 개선했습니다.

</details>

<details>
<summary><b>2. 비관적 락에서 DB 커넥션 대기로 처리량이 감소한 문제</b></summary>
<br>

### 문제

비관적 락은 초과 발급을 방지했지만, 20,000건 부하에서 요청이 특정 재고 행의 락과 DB 커넥션을 기다리면서 응답 시간이 크게 증가했습니다.

Level 2 측정 결과는 다음과 같습니다.

- 처리량: `93.95 req/s`
- 평균 응답 시간: `5.28초`
- p95 응답 시간: `8.34초`
- HikariCP pending 최대: `189`
- DB 커넥션 획득 실패: `2,848건`

### 원인

`SELECT ... FOR UPDATE`로 동일 재고 행에 대한 요청이 직렬화됐고, 락을 기다리는 트랜잭션이 DB 커넥션을 계속 점유했습니다. 그 결과 HikariCP 대기열까지 증가하며 애플리케이션 병목으로 이어졌습니다.

### 해결

- DB 내부 경쟁을 줄이기 위해 발급 판정을 Redis Lua Script로 이동
- 재고 확인, 중복 확인, 재고 차감, 발급 Set 등록을 하나의 원자 연산으로 처리
- MySQL 동기 저장이 남아 있는 V2와 Kafka로 영속화를 분리한 V3를 추가해 병목 지점을 단계적으로 비교

### 결과

비관적 락의 정합성은 유지하면서도, Redis와 Kafka를 적용했을 때 처리량과 응답 지연이 어떻게 달라지는지 동일한 환경에서 정량적으로 확인할 수 있었습니다.

</details>

<details>
<summary><b>3. Redis 차감 후 MySQL 저장 실패 시 재고가 불일치하는 문제</b></summary>
<br>

### 문제

V2에서는 Redis Lua로 재고를 먼저 차감한 뒤 MySQL에 쿠폰과 이력을 동기 저장합니다. 이때 DB 커넥션 획득 실패나 저장 예외가 발생하면 Redis 재고만 감소하고 MySQL에는 쿠폰이 없는 불일치가 발생할 수 있었습니다.

### 해결

- MySQL 저장 실패를 명확한 실패와 결과 불명확 상태로 구분
- 저장이 확실히 실패한 경우 Redis 재고와 발급 Set을 원자적으로 복구
- 동일한 보상 요청이 반복돼도 한 번만 적용되도록 Lua 보상 스크립트를 멱등하게 구현
- `uk_campaign_user` 위반은 일반 DB 장애와 분리해 `ALREADY_ISSUED`로 처리
- Redis–MySQL 재고 차이를 검사하는 `REC-01` 대사 규칙 추가

### 결과

DB 저장 실패 시 Redis 재고와 발급 Set이 함께 복구되며, 보상이 중복 실행돼도 재고가 여러 번 증가하지 않도록 방어했습니다.

</details>

<details>
<summary><b>4. Kafka 발행 타임아웃에서 성급한 Redis 보상이 중복 발급을 만들 수 있는 문제</b></summary>
<br>

### 문제

Kafka 발행 중 타임아웃이 발생했다고 해서 이벤트가 브로커에 저장되지 않았다고 단정할 수 없습니다.

브로커에는 이벤트가 저장됐지만 Producer가 ACK를 받지 못한 상황에서 Redis 재고를 즉시 복구하면, 동일 재고가 다시 발급되어 초과 발급으로 이어질 수 있습니다.

### 해결

Kafka 발행 결과를 다음과 같이 구분했습니다.

| 상황 | 처리 |
| --- | --- |
| 직렬화 오류 등 명확한 발행 실패 | Redis 재고와 발급 Set 보상 |
| Broker ACK 확인 | 정상 응답 |
| Timeout·네트워크 단절 등 결과 불명확 | 즉시 보상하지 않고 `SAVE_RESULT_UNKNOWN` 반환 |
| 결과 불명확 요청 | `coupon:pending:{couponId}` 유지 |
| Consumer의 DB 트랜잭션 커밋 완료 | pending 키 삭제 |

추가로 다음 신뢰성 설정을 적용했습니다.

- Kafka Producer 멱등성 활성화
- Consumer auto commit 비활성화
- DB UNIQUE 제약과 조건부 UPDATE를 이용한 중복 소비 방어
- Consumer 처리 실패 시 재시도 후 DLT 격리
- pending 잔존 수, Consumer lag, DLT를 모니터링 지표로 제공

### 결과

발행 결과가 불명확한 상황에서 성급하게 Redis 재고를 복구하지 않도록 하여, 이벤트 중복 처리와 초과 발급 위험을 줄였습니다.

</details>

<details>
<summary><b>5. Kafka 비동기 저장이 끝나기 전에 정합성 검증을 실행한 문제</b></summary>
<br>

### 문제

V3는 Redis 재고가 먼저 차감되고 Kafka Consumer가 MySQL을 나중에 갱신합니다. 따라서 Consumer가 처리 중인 상태에서 Redis와 MySQL을 비교하면 정상적인 처리 중 상태도 재고 불일치로 판단될 수 있습니다.

### 해결

V3 검증 시작 조건을 다음과 같이 정의했습니다.

- Kafka Consumer lag `0`
- DLT `0건`
- Redis pending 키 `0건`
- MySQL 쿠폰 저장 완료

Kafka가 정착되지 않은 상태에서 검증 또는 캐시 복구를 요청하면 작업을 강제로 실행하지 않고 `SKIPPED_NOT_SETTLED` 또는 `CACHE_RECOVERY_NOT_SETTLED`로 종료하도록 구성했습니다.

### 결과

최종 정착 후 다음 값을 확인했습니다.

- 발급 성공: `10,000건`
- 재고 소진 응답: `10,000건`
- MySQL 쿠폰: `10,000건`
- Redis·MySQL 잔여 재고: `0`
- Kafka Consumer lag: `0`
- DLT: `0건`
- pending 키: `0건`
- 정합성 규칙 위반: `0건`

</details>

<details>
<summary><b>6. Redis 캠페인 캐시 유실 시 조회 API가 500을 반환한 문제</b></summary>
<br>

### 문제

Redis의 캠페인 재고 키가 유실된 경우 발급 API는 캐시 미스를 구분했지만, 캠페인 상세 및 재고 조회 API는 일반 예외로 처리되어 `500 INTERNAL_SERVER_ERROR`를 반환했습니다.

또한 캐시가 없다는 이유로 재고를 `0`으로 표시하면 실제 재고 소진과 캐시 장애를 구분할 수 없었습니다.

### 해결

- Redis 재고 키 누락을 `503 CAMPAIGN_NOT_CACHED`로 통일
- 재고 `0`과 캐시 미스를 명확하게 분리
- 캐시 미스 발생 횟수를 Micrometer 지표로 기록
- 일정 횟수 이상 발생하면 선택적 자동 복구 실행
- 여러 애플리케이션 인스턴스가 동시에 복구하지 않도록 Redis 분산 락 적용
- 기존 키를 덮어쓰지 않고 누락된 키만 `SETNX`로 복구
- V3에서는 Kafka가 정착된 경우에만 캐시 복구 허용

### 결과

사용자는 일시적인 캐시 장애를 재고 소진이나 서버 내부 오류로 오해하지 않게 되었으며, 운영 중 일부 Redis 키가 유실돼도 안전하게 복구할 수 있게 됐습니다.

</details>

<details>
<summary><b>7. 로컬 인프라에 의존해 통합 테스트 결과가 달라지는 문제</b></summary>
<br>

### 문제

초기 통합 테스트는 개발자 PC에 실행 중인 MySQL·Redis·Kafka와 기존 데이터에 의존했습니다. 이 때문에 개발자별 환경 차이와 테스트 실행 순서에 따라 결과가 달라질 수 있었고, CI에서는 테스트를 안정적으로 실행하기 어려웠습니다.

### 해결

- MySQL, Redis, Kafka 통합 테스트를 Testcontainers 기반으로 이전
- 테스트 실행마다 독립된 컨테이너와 스키마 구성
- 테스트 데이터와 Redis·Kafka 상태를 회차별 초기화
- 로컬 인프라가 없어도 동일한 명령으로 테스트할 수 있도록 구성
- GitHub Actions에서 실제 테스트와 Spotless 검사를 수행

```bash
./gradlew clean test spotlessCheck
```

### 결과

개발자 환경과 관계없이 동일한 조건에서 통합 테스트를 재현할 수 있게 되었으며, 기능 회귀를 PR 단계에서 확인할 수 있게 됐습니다.

</details>

<br>

## 👨‍💻 팀원 및 역할 분담

| 팀원 | 주요 역할 |
| --- | --- |
| **정윤희(팀장)** | 인프라·Kafka 신뢰성 설정<br>k6 부하테스트와 전략 벤치마크<br>Prometheus·Grafana 관측 및 프론트엔드 연동 |
| **김윤기** | NoLock·비관적 락 발급 전략<br>쿠폰 발급 API<br>캠페인·재고 조회와 다중 재고 풀 검증 |
| **이승지** | Redis Lua 발급 전략<br>Kafka Producer와 실패 보상<br>Redis 캠페인 캐시 복구·실시간 발급 현황 |
| **임재민** | 쿠폰 도메인·상태 머신<br>Kafka Consumer<br>쿠폰 사용·취소·조회 및 항공사 회수 API |
| **장지원** | 대용량 데이터 생성·적재<br>정합성 검증·만료·재고 대사 배치<br>DB 스키마와 Testcontainers·CI |

<br>

## 📚 프로젝트 문서

| 문서 | 설명 |
| --- | --- |
| [팀 Notion](https://app.notion.com/p/3b36a058256c80b4bbb2db67664ccd57) | 프로젝트 일정, 회의 내용, 업무 분담 및 팀 협업 문서 |
| [요구사항 분석서](docs/requirements-analysis.md) | 프로젝트 목표, 정책, 기능·비기능 요구사항 및 검증 규칙 |
| [공통 테스트 설계서](docs/test-plan.md) | 테스트 단계, 공통 데이터, 비교 조건, 측정 지표 및 판정 기준을 정의한 SSOT |
| [V0~V3 통합 테스트 결과](docs/round-results.md) | 전략별 격리 통합 테스트와 회차별 결과 |
| [300만 건 정합성 검증 결과](docs/round-results/bulk-verification.md) | 전수 검증, 재실행 동일성 및 오염 주입 검출력 |
| [부하 테스트 환경 고정표](docs/load-test-environment.md) | V0~V3 공통 AWS 사양, 애플리케이션 설정과 회차 무효 기준 |
| [AWS 부하 테스트 구성 가이드](docs/aws-load-test-setup.md) | Level 2·3 네트워크, 배포, 초기화, 실행 및 결과 회수 절차 |
| [k6 실행 가이드](load-tests/k6/README.md) | 기본·핫키·다중 재고 풀·멱등성 시나리오 실행법 |
| [Testcontainers 이전 내역](docs/testcontainers-migration.md) | MySQL·Redis·Kafka 통합 테스트 격리 구성 |
| [Kafka DLT 운영 대응](docs/kafka-dlt-manual-response.md) | DLT 확인, 판정 및 수동 재처리 절차 |
| [데이터베이스 스키마](docs/schema.sql) | 테이블, 제약 조건, 인덱스 및 검증 결과 저장 구조 |

<br>

## 🌿 브랜치 전략

```text
main
 └── develop
      ├── feat/*
      ├── fix/*
      ├── chore/*
      └── docs/*
```

- `main`: 배포 가능한 안정 버전
- `develop`: 개발 기능 통합
- `feat/*`: 기능 개발
- `fix/*`: 버그 수정
- `chore/*`: 빌드·설정·도구
- `docs/*`: 문서 작업

모든 작업은 브랜치에서 개발한 뒤 Pull Request와 CI 검사를 거쳐 `develop`에 병합합니다.
