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

## 📊 개발 현황

**1주차 — 설계 확정 및 개발 환경 구축**

- [x] 프로젝트 기획안 및 기능 명세 작성
- [x] DB 스키마 설계 (테이블 7개, 제약 조건 및 인덱스)
- [x] Docker Compose 인프라 구성 (MySQL / Redis / Kafka / Prometheus / Grafana)
- [x] Gradle Wrapper 및 toolchain 자동 프로비저닝
- [x] Spotless 코드 포맷 통일
- [x] GitHub Actions CI 구성
- [ ] 쿠폰 도메인 및 상태 머신
- [ ] 발급 전략 인터페이스 및 구현 (락 없음 / 비관적 락 / Redis Lua)
- [ ] Kafka Producer · Consumer
- [ ] 대용량 더미 데이터 생성 및 적재

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

## 💬 멘토링 질문 리스트
<details>
<summary><b>질문 목록 펼치기</b></summary>
<br>

### 1. 기술 선택과 평가 기준

1. 실무에서 선착순 한정 재고를 처리할 때도 DB 비관적 락을 선택하는 경우가 있나요? 반드시 Redis를 도입해야 한다고 판단할만한 처리량/지연/운영 조건은 무엇인가요?

2. 저희는 `NoLock -> 비관적 락 -> Redis Lua + MySQL 동기 저장 -> Redis Lua + Kafka` 순서로 비교하려고 합니다. 기술 선택의 타당성을 검증하기 위한 비교군으로 적절한가요?

3. 동일한 코드, 데이터, 인스턴스 사양을 유지하고 발급 전략만 변경하면 공정한 비교라고 볼 수 있을까요? 반드시 함께 통제해야 할 JVM, DB, 커넥션 풀 등의 조건이 있을까요?

4. 이번 프로젝트를 평가할 때 다음 항목 중 어떤 부분을 가장 중요하게 보시는지 궁금합니다.

   - 전략별 기술/성능 비교
   - 기술 선택의 근거와 실험 과정
   - 검증 규칙의 정교함
   - 장애 복구 설계
   - 부하 시나리오의 현실성

### 2. 부하 테스트 설계

5. 요구사항의 '20,000명 동시 요청'은 실제로 20,000 VU가 같은 순간 요청해야 한다는 의미인가요? 총 20,000건을 일정한 도착률로 발생시키는 방식도 기술 비교에 유효한가요?

6. TPS와 p95·p99 응답 시간 외에 DB lock wait, 커넥션 대기, Redis latency 중 반드시 제시하면 좋은 지표가 있나요?

7. 성능 튜닝은 전략 비교 전에는 동일하게 고정하고, 전략을 선택한 후 별도로 수행하는 편이 맞나요? 아니면 각 전략을 최적 설정으로 튜닝한 후 비교하는 게 좋을까요?

### 3. Redis와 Kafka 정합성

8. 아직 구현을 해본 것은 아니지만 Redis에서 재고를 차감한 뒤 Kafka 발행 또는 Consumer의 DB 저장이 실패할 수도 있을 것 같은데, 실무에서는 이런 분산 시스템의 데이터 불일치를 어떤 방식으로 복구하고 모니터링 하나요?

9. Redis Lua 이후 MySQL에 동기로 저장하는 방식도 Redis 차감과 DB 저장 사이에 원자성이 없습니다. Kafka의 효과를 분리하기 위한 실험용 비교군으로 의미가 있는지, 이 단계에도 보상 로직이 필요한지 궁금합니다.

10. 발급 API가 Kafka 발행까지만 확인한 후 `200 OK`를 반환하는 설계가 적절한가요? DB 저장 전 사용/취소/조회 요청이 들어오는 짧은 구간은 실무에서 어떻게 처리하나요?

11. Kafka Consumer의 중복 수신은 DB UNIQUE 제약과 조건부 UPDATE로 방어하려고 합니다. 이 정도로 충분한지, 별도의 인박스 테이블이나 이벤트 버전 관리가 필요한지 궁금합니다.

### 4. 재고 정책과 장애 복구

12. 저희는 쿠폰이 한 번 발급되면 `USED`, `CANCELLED`, `EXPIRED` 상태와 관계없이 재고를 영구 소진하도록 정했습니다. 실무 이벤트에서도 이런 정책을 사용하는지 궁금합니다.

13. Redis 장애 시 교육 프로젝트에서는 다음 중 어느 수준까지 구현하거나 검증하는 것이 적절한가요?

   - 요청 차단
   - MySQL 기준 Redis 재구축
   - 장애 중 DB 경로로 전환
   - 재시도 및 대사 배치
   - 자동 장애조치

14. Redis를 발급 재고의 기준 저장소로 사용할 경우, Redis 재시작이나 데이터 유실 이후 MySQL 기준으로 Redis를 초기화하는 동안 들어오는 요청은 실무에서 어떻게 통제하나요?

15. Redis 핫키 문제는 어느 정도의 부하에서 의미 있게 검증해야 하나요? 이번 프로젝트에서는 샤딩이나 대기열까지 구현해야 하는지, 현상 재현과 측정만으로 충분한지 궁금합니다.

### 5. 검증과 프로젝트 범위

16. Kafka Consumer lag가 0이 된 후 DB 검증을 수행하려고 합니다. 최종 일관성 시스템의 검증 시작 조건으로 충분한가요? 아니면 DLT와 재시도 중인 이벤트도 함께 확인해야 하나요?

17. 검증 규칙을 여러 개 구현하는 것과 핵심 규칙에 오염 데이터를 주입해 실제 탐지 과정을 보여주는 것 중 어느 쪽을 더 중요하게 보시나요?

18. 100만 회원과 300만 건의 이력은 모든 성능 테스트에 적재해야 하나요? 기술 비교 단계에서는 20,000명으로 실행하고 최종 검증 배치에서만 전체 데이터를 사용하는 방식도 괜찮은가요?

</details>

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
