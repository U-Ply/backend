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

### 5. 빌드 및 실행

```bash
./gradlew build
./gradlew bootRun
```

### 6. 캠페인 조회 API 사용 전 준비 (Redis 웜업)

`GET /api/campaigns/{campaignId}`와 `GET /api/campaigns/{campaignId}/status`는 잔여 재고(`remainingStock`)를 DB 집계가 아니라 **Redis에서만** 읽습니다. 아래 순서를 지키지 않으면 `stock:{stockId}` 키가 없어 두 API가 500(서버 오류)을 반환합니다 — 재고 0으로 보이는 게 아니라 API 자체가 실패합니다.

1. DB 시드 (캠페인·재고 데이터 적재)
2. `CampaignCacheWarmupService.warmupCampaign(campaignId)` 실행 — 캠페인의 `openAt`/`expireAt`과 재고 풀별 `remainingStock`을 Redis에 적재
3. Redis에 `stock:{stockId}` 키가 채워졌는지 확인 (예: `redis-cli GET stock:1`)
4. 확인 후에만 API·화면 공개

> **주의:** 현재 `warmupCampaign()`을 호출하는 관리자 API나 자동 실행 트리거가 없습니다. IDE에서 직접 호출하거나 테스트 코드를 통해 실행해야 합니다. 시연 전에 관리자 엔드포인트 또는 배치 트리거 추가 여부를 팀에서 확정해야 합니다.

**V0(NoLock)·V1(비관적 락) 회차에서는 웜업을 해도 얼마 못 갑니다.** `docs/test-plan.md`의 V0~V3 비교표대로 V0·V1은 발급 판정과 재고 차감을 MySQL(`campaign_stocks.remaining_stock`)로만 처리하고 Redis는 전혀 건드리지 않습니다(`NoLockIssueStrategy`/`PessimisticLockIssueStrategy` 어디에도 Redis 호출이 없음). 반면 이 API들의 `remainingStock`은 스펙상 Redis 값을 그대로 반환하므로, 웜업 직후에는 맞다가 V0·V1 발급이 몇 건만 들어가도 **Redis 값이 실제 MySQL 재고보다 계속 커진 채로 고정**됩니다. 500 오류가 나는 게 아니라 **잘못된 숫자를 정상 응답으로 돌려주는** 문제라 더 위험합니다. Redis-DB 대사 배치(REC-01)도 "Redis Lua(V2·V3) 전략 회차에만 적용, 비관적 락 회차는 N/A"로 문서화돼 있어 자동으로 잡아주지 않습니다.

그러니 V0·V1 회차 중에는 캠페인 상세·발급 현황 API를 호출하지 말고, 호출이 필요하면(화면 시연 등) 그 시점 직전에 다시 웜업해서 Redis를 MySQL 기준으로 맞춘 뒤 사용하세요. remainingStock이 Redis와 항상 정확히 맞는 건 V2·V3(Redis Lua 발급) 회차뿐입니다.

<details>
<summary><b>문제 해결</b></summary>
<br>

* `Unsupported class file major version 69`가 나오면 Gradle을 실행하는 JVM이 Java 25입니다. JDK 25를 직접 설치하지 않으셨더라도 STS/Eclipse가 번들 JRE로 25를 포함하고 있습니다. `Window → Preferences → Gradle → Java home`을 JDK 17/21 경로로 지정한 뒤 `Gradle → Refresh Gradle Project`를 실행하세요.
* `java.lang.Object cannot be resolved`가 나오거나 프로젝트에 빨간 X가 표시되면 IDE 설정 파일이 없어서입니다. `.classpath`, `.settings/`는 개인 환경 파일이라 저장소에서 관리하지 않습니다. `Gradle → Refresh Gradle Project`로 재생성하세요.
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
| Test | ![JUnit5](https://img.shields.io/badge/JUnit_5-25A162?style=flat-square&logo=junit5&logoColor=white) |
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
| **이승지** | Redis Lua Script 기반 발급 로직<br>다중 재고 풀 설계<br>Kafka Producer<br>캠페인·발급 현황 조회 |
| **임재민** | 쿠폰 도메인 및 상태 머신<br>Kafka Consumer<br>사용·취소·조회 API |
| **장지원** | 대용량 데이터 생성 및 적재<br>정합성 검증 배치<br>DB 스키마 설계 및 빌드 환경·CI 구축 |

<br>

## 📚 프로젝트 문서

| 문서 | 설명 |
| --- | --- |
| [팀 Notion](https://app.notion.com/p/3b36a058256c80b4bbb2db67664ccd57) | 프로젝트 일정, 회의 내용, 업무 분담 및 팀 협업 문서 |
| [요구사항 분석서](docs/requirements-analysis.md) | 프로젝트 목표, 정책, 기능·비기능 요구사항 및 검증 규칙 |
| [공통 테스트 설계서](docs/test-plan.md) | 테스트 단계, 공통 데이터, 비교 조건, 측정 지표 및 판정 기준을 정의한 SSOT |
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
