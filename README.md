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
- INV-01~12, CLOCK-01~02, REC-01 기반 정합성 검증 및 리포트
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
