<div align="center">

# 항공사 특가 이벤트, 대규모 트래픽 선착순 쿠폰 발급 시스템

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

**U-Ply**는 항공권 특가 이벤트를 소재로, 수만 건의 요청이 동시에 몰리는 환경에서 재고를 초과하지 않고 정확하게 쿠폰을 발급하고, 그렇게 처리된 데이터가 실제로 정합한지 시스템이 스스로 검증하는 백엔드 시스템입니다.

```text
캠페인 오픈 (노선·좌석등급별 재고 풀)
    ↓
선착순 발급 요청
    ↓
Redis 원자 연산으로 재고 선점
    ↓
Kafka 이벤트 발행 · 쿠폰과 이력 적재
    ↓
쿠폰 생애주기 관리 (발급 → 사용 / 취소 / 만료)
    ↓
정합성 검증 배치
```

<br>

## 🔬 발급 전략 비교 (V0~V3)

프로젝트의 핵심은 **같은 발급 기능을 네 가지 동시성 제어 방식으로 구현해 동일 조건에서 성능·정합성을 비교**하는 것입니다. 애플리케이션 환경변수만 바꿔 전환합니다(코드·k6 스크립트 불변).

| 회차 | `COUPON_STRATEGY` | `COUPON_SAVE_STRATEGY` | `COUPON_KAFKA_CONSUMER_ENABLED` | 설명 |
| --- | --- | --- | --- | --- |
| **V0** | `NO_LOCK` | `sync-db` | `false` | 동시성 제어 없음 — 초과 발급 재현용 기준선(BASELINE) |
| **V1** | `PESSIMISTIC_LOCK` | `sync-db` | `false` | MySQL 비관적 락(`SELECT … FOR UPDATE`) + 동기 저장 |
| **V2** | `LUA_SCRIPT` | `sync-db` | `false` | Redis Lua 원자 발급 + MySQL 동기 저장 |
| **V3** | `LUA_SCRIPT` | `kafka` | `true` | Redis Lua 발급 + Kafka 비동기 저장 |

환경변수를 지정하지 않으면 **V2**로 동작합니다. 회차 무효 기준을 포함한 상세 조건은 [`docs/load-test-environment.md`](docs/load-test-environment.md), 판정 기준은 [`docs/test-plan.md`](docs/test-plan.md)에 정의돼 있습니다.

> 선택 기능으로 진입 제어(Redis 토큰 버킷 레이트 리미터)가 있으며 `COUPON_GATE_ENABLED`(기본 `false`)로 켭니다. 켜면 한도 초과 요청은 `429`로 거부되고 클라이언트가 백오프 후 재시도합니다(`capacity`·`refill-per-sec`는 부하 테스트로 튜닝).

<br>

## 🚀 실행 방법

### 1. 사전 준비

* Git
* **JDK 17 또는 21**
* Docker Desktop

```text
java -version     # 17 또는 21 인지 확인
docker --version
```

> **주의:** JDK 25는 사용할 수 없습니다. Gradle 8이 아직 지원하지 않습니다.
> STS/Eclipse는 번들 JRE로 25를 포함할 수 있으므로, `Preferences → Gradle → Java home`을 JDK 17/21로 지정해 주세요. (`Java → Installed JREs`가 아니라 **Gradle 페이지**입니다)
>
> Gradle과 컴파일용 JDK는 따로 설치하지 않으셔도 됩니다. Gradle Wrapper와 toolchain 자동 프로비저닝이 처리합니다.

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

### 4. 스키마 적재

```bash
docker exec -i coupon-mysql mysql -uroot -proot1234 < docs/schema.sql
```

마지막에 테이블 7개와 CHECK 제약 10개 목록이 출력되면 성공입니다.

### 5. 데이터 시드

`schema.sql`은 테이블만 만들고 데이터는 넣지 않습니다. 로컬에서 캠페인·재고 풀·사용자 데이터가 필요하면 `DummyDataGenerator`(`dummy` 프로파일)로 적재합니다.

```bash
# 단일 캠페인 · JEJU/ECONOMY 재고 10,000 · 사용자 20,000 · 쿠폰 0 (부하 테스트 시드 형태)
./gradlew bootRun --args='--spring.profiles.active=dummy --campaigns=1 --routes=1 --fare-classes=1 --stock=10000 --users=20000 --coupons=0 --truncate'
```

* `dummy` 프로파일은 웹 서버 없이 데이터만 생성하고 종료합니다. `--truncate`는 기존 데이터를 먼저 지웁니다.
* `--fare-classes=2`로 주면 ECONOMY·BUSINESS 재고 풀이 함께 생성됩니다. 같은 인자면 항상 같은 데이터가 나옵니다(`--seed` 고정).
* 검증 배치용 대규모 시드(사용자 100만·이력 300만)와 오염 데이터 주입은 `DummyDataGenerator` 주석과 [`docs/test-plan.md`](docs/test-plan.md) 6.2 참고. 부하 테스트 회차용 시드·초기화는 [`load-tests/k6/README.md`](load-tests/k6/README.md).

### 6. 빌드 및 실행

```bash
./gradlew build
./gradlew bootRun
```

실행 후 `http://localhost:8081/` 에서 데모 웹 UI(캠페인·쿠폰·예약 흐름, 관리자 → 성공/실패 시스템 모니터링 대시보드)를 확인할 수 있습니다. 통합 테스트(`./gradlew test`)는 Testcontainers로 MySQL·Kafka 컨테이너를 띄우므로 Docker 데몬이 필요합니다.

> **IDE에서 실행할 때도 반드시 `bootRun`으로 실행하세요** (STS 터미널의 `./gradlew.bat bootRun` 또는 Gradle Tasks 뷰의 `application → bootRun`).
> STS/Eclipse의 `Run As → Spring Boot App`은 Eclipse 자체 컴파일러로 빌드하는데, 기본 설정에서는 `-parameters` 플래그가 빠져 이름을 생략한 `@PathVariable`/`@RequestParam`을 Spring이 해석하지 못합니다. 그 결과 경로 변수를 받는 모든 API(`GET /api/campaigns/{id}`, `.../status`, `GET /api/coupons/{id}`, `POST /api/coupons/{id}/use` 등)가 500 `INTERNAL_SERVER_ERROR`를 반환합니다. Gradle 빌드에는 이 플래그가 자동으로 포함되므로 `bootRun`·CI·JAR 실행은 영향이 없습니다.

### 7. 캠페인 조회 API 사용 전 준비 (전략별 소스)

`GET /api/campaigns/{campaignId}`, `GET /api/campaigns/{campaignId}/status`, `GET .../status/stream`(SSE)이 반환하는 잔여 재고(`remainingStock`)는 **발급 전략에 따라 소스가 다릅니다**(`RemainingStockReader`). 어느 경우든 DB 집계(발급 건수 COUNT)는 하지 않습니다.

| 전략 | `remainingStock` 소스 | 사전 준비 |
| --- | --- | --- |
| `LUA_SCRIPT` (V2·V3) | Redis `stock:{stockId}` | **웜업 필수** (아래 순서) |
| `NO_LOCK`·`PESSIMISTIC_LOCK` (V0·V1) | MySQL `campaign_stocks.remaining_stock` | 웜업 불필요, DB 시드만 있으면 됨 |

#### V2·V3 (Redis Lua 발급) — 웜업 순서

아래를 지키지 않으면 `stock:{stockId}` 키가 없어 세 API가 **503 `CAMPAIGN_NOT_CACHED`** 를 반환합니다 — 재고 0으로 보이는 게 아니라 API 자체가 실패합니다.

1. DB 시드 (캠페인·재고 데이터 적재)
2. `CampaignCacheWarmupService.warmupCampaign(campaignId)` 실행 — 캠페인의 `openAt`/`expireAt`과 재고 풀별 `remainingStock`을 Redis에 적재. 관리자 API `POST /api/admin/campaigns/{campaignId}/cache/warmup` 로도 호출할 수 있습니다.
3. Redis에 `stock:{stockId}` 키가 채워졌는지 확인 (예: `redis-cli GET stock:1`)
4. 확인 후에만 API·화면 공개

#### V0·V1 (DB 발급) — 웜업 불필요

`NoLockIssueStrategy`/`PessimisticLockIssueStrategy` 는 발급 판정·재고 차감을 MySQL(`campaign_stocks.remaining_stock`)로만 처리하고, 위 세 API도 `RemainingStockReader` 를 통해 **DB 값을 직접 읽습니다**. 따라서 DB 시드만 있으면 바로 사용할 수 있고, V0·V1 회차 중에도 캠페인 상세·발급 현황·SSE가 실시간 재고를 정확히 반영합니다.

> 이전에는 이 세 API가 전략과 무관하게 Redis만 읽어, V0·V1 발급이 몇 건만 들어가도 Redis 값이 MySQL 재고보다 계속 큰 채로 고정되는(= 틀린 숫자를 정상 응답으로 돌려주는) 문제가 있었습니다. `RemainingStockReader` 도입으로 해소됐습니다.

Redis-DB 대사 배치(REC-01)는 여전히 "Redis Lua(V2·V3) 회차에만 적용, 비관적 락 회차는 N/A" 입니다 — V0·V1은 Redis `stock:{stockId}` 를 아예 쓰지 않으므로 대사할 대상이 없습니다.

<details>
<summary><b>문제 해결</b></summary>
<br>

* `Unsupported class file major version 69`가 나오면 Gradle을 실행하는 JVM이 Java 25입니다. JDK 25를 직접 설치하지 않으셨더라도 STS/Eclipse가 번들 JRE로 25를 포함하고 있습니다. `Window → Preferences → Gradle → Java home`을 JDK 17/21 경로로 지정한 뒤 `Gradle → Refresh Gradle Project`를 실행하세요.
* `java.lang.Object cannot be resolved`가 나오거나 프로젝트에 빨간 X가 표시되면 IDE 설정 파일이 없어서입니다. `.classpath`, `.settings/`는 개인 환경 파일이라 저장소에서 관리하지 않습니다. `Gradle → Refresh Gradle Project`로 재생성하세요.
* 경로 변수를 받는 API(`GET /api/campaigns/{id}` 등)가 500 `INTERNAL_SERVER_ERROR`("서버 내부 오류가 발생했습니다")를 반환하고 로그에 `IllegalArgumentException: Name for argument of type [...] not specified ... Ensure that the compiler uses the '-parameters' flag`가 찍히면, STS `Run As → Spring Boot App`으로 실행한 경우입니다. **`bootRun`으로 실행하면 해결됩니다.** IDE 런처를 계속 쓰려면 `Window → Preferences → Java → Compiler`에서 **"Store information about method parameters (usable via reflection)"** 를 켠 뒤 `Project → Clean` 하세요. (이 설정은 `.settings/`에 저장돼 저장소에서 공유되지 않으므로 팀원마다 각자 켜야 합니다)
* `ports are not available (3306)`이 나오면 로컬 MySQL이 실행 중입니다. Windows 기본 서비스 이름은 보통 `MySQL80`이며, 관리자 권한 PowerShell에서 `Stop-Service -Name MySQL80`으로 중지할 수 있습니다.
* macOS / Linux에서 `./gradlew: Permission denied`가 발생하면 `chmod +x gradlew`를 한 번 실행한 뒤 다시 시도하세요.

</details>

<br>

## 🛠️ 기술 스택

### Backend

| 구분 | 기술 |
| --- | --- |
| Language | ![Java](https://img.shields.io/badge/Java_17-007396?style=flat-square&logo=openjdk&logoColor=white) |
| Framework | ![Spring Boot](https://img.shields.io/badge/Spring_Boot_3.3-6DB33F?style=flat-square&logo=springboot&logoColor=white) ![Spring Batch](https://img.shields.io/badge/Spring_Batch-6DB33F?style=flat-square&logo=spring&logoColor=white) |
| Persistence | ![Spring Data JPA](https://img.shields.io/badge/Spring_Data_JPA-6DB33F?style=flat-square&logo=spring&logoColor=white) |
| Database | ![MySQL](https://img.shields.io/badge/MySQL_8-4479A1?style=flat-square&logo=mysql&logoColor=white) |
| Cache | ![Redis](https://img.shields.io/badge/Redis_Lua-FF4438?style=flat-square&logo=redis&logoColor=white) |
| Messaging | ![Kafka](https://img.shields.io/badge/Apache_Kafka-231F20?style=flat-square&logo=apachekafka&logoColor=white) |
| Build | ![Gradle](https://img.shields.io/badge/Gradle_8.14-02303A?style=flat-square&logo=gradle&logoColor=white) |

### Infra · 성능 · 관측

| 구분 | 기술 |
| --- | --- |
| Container | ![Docker](https://img.shields.io/badge/Docker_Compose-2496ED?style=flat-square&logo=docker&logoColor=white) |
| Load Test | ![k6](https://img.shields.io/badge/k6-7D64FF?style=flat-square&logo=k6&logoColor=white) |
| Monitoring | ![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?style=flat-square&logo=prometheus&logoColor=white) ![Grafana](https://img.shields.io/badge/Grafana-F46800?style=flat-square&logo=grafana&logoColor=white) |

### Test · Quality · CI

| 구분 | 기술 |
| --- | --- |
| Test | ![JUnit5](https://img.shields.io/badge/JUnit_5-25A162?style=flat-square&logo=junit5&logoColor=white) ![Testcontainers](https://img.shields.io/badge/Testcontainers-291A3F?style=flat-square&logo=testcontainers&logoColor=white) |
| Code Style | ![Spotless](https://img.shields.io/badge/Spotless-Code_Formatting-4285F4?style=flat-square) |
| CI | ![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-2088FF?style=flat-square&logo=githubactions&logoColor=white) |

### Collaboration

| 구분 | 도구 |
| --- | --- |
| Version Control | ![Git](https://img.shields.io/badge/Git-F05032?style=flat-square&logo=git&logoColor=white) ![GitHub](https://img.shields.io/badge/GitHub-181717?style=flat-square&logo=github&logoColor=white) |
| Documentation | ![Notion](https://img.shields.io/badge/Notion-000000?style=flat-square&logo=notion&logoColor=white) |

<br>

## 📊 개발 일정

**1주차 — 설계 확정 및 개발 환경 구축**

**2주차 — 역할별 심화 구현 및 벤치마크**

**3주차 — 통합, 튜닝, 검증 배치 완성, 발표 준비**

<br>

## 👨‍💻 팀원 및 역할 분담

| 팀원 | 주요 역할 |
| --- | --- |
| **정윤희(팀장)** | 인프라 구성 및 Kafka 심화<br>k6 부하 테스트 및 락 전략 벤치마크<br>Prometheus·Grafana 관측 체계 |
| **김윤기** | 비관적 락 기반 발급 로직<br>쿠폰 발급 API<br>재고 복구 로직 |
| **이승지** | Redis Lua Script 기반 발급 로직<br>다중 재고 풀 설계<br>Kafka Producer<br>캠페인·발급 현황 조회<br>멱등성 모듈 구현 |
| **임재민** | 쿠폰 도메인 및 상태 머신<br>Kafka Consumer<br>사용·취소·조회 API<br>공통 예외 처리 |
| **장지원** | 대용량 데이터 생성 및 적재<br>정합성 검증 배치<br>DB 스키마 설계 및 빌드 환경·CI 구축 |

<br>

## 📚 프로젝트 문서

| 문서 | 설명 |
| --- | --- |
| [팀 Notion](https://app.notion.com/p/3b36a058256c80b4bbb2db67664ccd57) | 프로젝트 일정, 회의 내용, 업무 분담 및 팀 협업 문서 |
| [요구사항 분석서](docs/requirements-analysis.md) | 프로젝트 목표, 정책, 기능·비기능 요구사항 및 검증 규칙 |
| [공통 테스트 설계서](docs/test-plan.md) | 테스트 단계, 공통 데이터, 비교 조건, 측정 지표 및 판정 기준을 정의한 SSOT |
| [Level 1 검증 결과](docs/level1-result.md) | 로컬 JUnit 기준 전략별 기능·소규모 동시성 검증 결과 |
| [락·트랜잭션 설정](docs/lock-and-transaction-settings.md) | 락·타임아웃 설정과 `LOCK_TIMEOUT`·`CONCURRENCY_CONFLICT`·`CONNECTION_UNAVAILABLE`(503) 분류 근거 |
| [부하 테스트 환경 고정표](docs/load-test-environment.md) | V0~V3 공통 AWS 사양, 애플리케이션 설정, 부하 조건과 회차 무효 기준 |
| [AWS 부하 테스트 구성 가이드](docs/aws-load-test-setup.md) | Level 2·3 네트워크, 배포, 초기화, 실행 및 결과 회수 절차 |
| [k6 부하 스크립트 가이드](load-tests/k6/README.md) | V0~V3 공통 발급 스크립트 실행·초기화·판정 절차 |
| [Kafka DLT 수동 대응](docs/kafka-dlt-manual-response.md) | DLT 적재 메시지 조사 및 재처리 절차 |
| [Kafka pending 수동 대응](docs/kafka-pending-manual-response.md) | `coupon:pending:{couponId}` stale 키 운영 절차 |
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

* `main`: 배포 가능한 안정 버전
* `develop`: 개발 기능 통합
* `feat/*`: 기능 개발
* `fix/*`: 버그 수정
* `chore/*`: 빌드·설정·도구
* `docs/*`: 문서 작업

모든 작업은 브랜치에서 개발한 뒤 Pull Request와 CI 검사를 거쳐 `develop`에 병합합니다.

<br>

<div align="center">

</div>
