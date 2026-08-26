# Level 2·3 부하 테스트 환경 고정표

이 문서는 V0~V3 성능 비교와 최종 인수 테스트에서 변경하면 안 되는 조건을 정의하는 SSOT다. 실행 중 설정을 바꿨다면 같은 비교 묶음으로 사용하지 않는다.

## 1. 비교 원칙

- 같은 Level 안에서는 V0~V3 모두 같은 AWS 리전·AZ 배치와 인스턴스 사양을 사용한다.
- 같은 Git commit SHA와 동일한 애플리케이션 빌드 산출물을 사용한다.
- 같은 MySQL·Redis·Kafka 데이터와 초기화 스크립트를 사용한다.
- JVM, Tomcat, HikariCP 및 인프라 설정을 고정한다.
- V0~V3 사이에는 아래 전략 환경변수만 변경한다.
- V0은 장애 재현용 `BASELINE`이며 V1~V3의 통과 기준을 적용하지 않는다.
- 성능 튜닝은 V0~V3 기본 비교가 끝난 뒤 별도 회차로 실행한다.

## 2. 환경 식별

| 항목 | Level 2 공통값 | Level 3 공통값 |
| --- | --- | --- |
| 환경 ID | `AWS-L2-01` | `AWS-L3-01` |
| AWS 리전 | 서울 `ap-northeast-2` | 서울 `ap-northeast-2` |
| 가용 영역 | `ap-northeast-2a` 단일 AZ | 앱·ALB는 `ap-northeast-2a`, `2b`; 데이터·k6는 `2a` |
| 애플리케이션 인스턴스 유형 | `c7i.xlarge` (4 vCPU, 8 GiB) | `c7i.xlarge` 2대 (AZ별 1대, 각 4 vCPU, 8 GiB) |
| 애플리케이션 인스턴스 수 | 1 | 2 |
| k6 인스턴스 유형 | `c7i.2xlarge` (8 vCPU, 16 GiB) | `c7i.2xlarge` (8 vCPU, 16 GiB) |
| 데이터·관측 인스턴스 | `m7i.2xlarge` (8 vCPU, 32 GiB) 1대 | `m7i.2xlarge` (8 vCPU, 32 GiB) 1대 |
| 인프라 배치 | 데이터·관측 EC2에 Docker Compose | 데이터·관측 EC2에 Docker Compose |
| 요청 진입점 | 앱 EC2 사설 IP:8081 직접 호출 | ALB를 통해 앱 EC2 2대로 분산 |
| OS | Ubuntu Server 22.04 LTS x86_64 | Ubuntu Server 22.04 LTS x86_64 |
| EBS | 앱·k6 각 gp3 20 GiB, 인프라 gp3 100 GiB(3,000 IOPS) | Level 2와 동일 |
| 대상 Git SHA | 회차 시작 직전 기록 | 회차 시작 직전 기록 |
| 실행자 | 회차 시작 직전 기록 | 회차 시작 직전 기록 |

Level 2에서 네 전략은 반드시 같은 환경 ID를 사용한다. 인스턴스를 다시 만들었다면 동일 사양이어도 새 환경 ID를 부여하고 이전 결과와 직접 비교하지 않는다. Level 2의 `ap-northeast-2a`에서 위 유형을 생성할 수 없다면 임의로 일부 서버만 바꾸지 말고, 생성 가능한 단일 AZ와 동급 유형을 정해 표 전체를 수정한 후 모든 회차를 새 환경 ID로 다시 실행한다. Level 3의 ALB는 서로 다른 AZ의 서브넷이 최소 2개 필요하므로 앱을 `2a`, `2b`에 한 대씩 둔다.

애플리케이션은 EC2 호스트에서 JAR로 실행한다. MySQL·Redis·Kafka·Prometheus·Grafana와 exporter만 데이터·관측 EC2의 Docker Compose로 실행한다. Level 3의 두 앱은 같은 JAR와 환경변수를 사용한다.

## 3. 소프트웨어 버전

| 항목 | 고정값 |
| --- | --- |
| JDK | 17 |
| Spring Boot | 3.3.x — `build.gradle` 기준 |
| Gradle | Wrapper 8.14 |
| MySQL | 8.0.46 |
| Redis | 7.4.10 |
| Kafka | 3.7.0 |
| Prometheus | 2.53.0 |
| Grafana | 13.1.3 |
| k6 | 회차 시작 전 `k6 version` 기록 |
| Docker / Compose | 회차 시작 전 버전 기록 |

공식 회차의 실제 버전은 `load-tests/results/<runId>/environment-<phase>.md`에 다시 저장한다. k6와 인프라 호스트를 분리하면 `environment-load.md`와 `environment-finalize.md`가 각각 남는다.

## 4. 애플리케이션 공통 설정

| 항목 | 기본 비교값 | 변경 규칙 |
| --- | --- | --- |
| JVM timezone | UTC | 변경 금지 |
| JVM heap | `-Xms2g -Xmx2g` | 기본 비교 중 변경 금지 |
| GC | G1GC (`-XX:+UseG1GC`) | 기본 비교 중 변경 금지 |
| HikariCP maximumPoolSize | 10 | 튜닝 회차 외 변경 금지 |
| HikariCP connectionTimeout | 3,000ms | 변경 금지 |
| Tomcat max threads | 200 | 변경 금지 |
| Tomcat accept count | 100 | 변경 금지 |
| Tomcat max connections | 8,192 | 변경 금지 |
| 서버 포트 | 8081 | 환경에 따라 포트만 기록 |
| Redis 멱등성 계층 | Level 2에서는 `false` | 최종 운영 시나리오는 `true` |
| 대사 스케줄러 | `false` | 부하 종료 후 수동 실행 |
| 애플리케이션 로그 레벨 | root `WARN` | 네 전략 동일 |

공통 JVM 옵션은 `-Xms2g -Xmx2g -XX:+UseG1GC -Duser.timezone=UTC`로 고정한다. Tomcat 설정은 `SERVER_TOMCAT_THREADS_MAX=200`, `SERVER_TOMCAT_ACCEPT_COUNT=100`, `SERVER_TOMCAT_MAX_CONNECTIONS=8192`로 전달한다. 실제 실행값도 Actuator 또는 JVM 명령으로 기록한다.

## 5. 전략별 유일한 변경값

| Round | `COUPON_STRATEGY` | `COUPON_SAVE_STRATEGY` | `COUPON_KAFKA_CONSUMER_ENABLED` | `COUPON_IDEMPOTENCY_ENABLED` |
| --- | --- | --- | --- | --- |
| V0 | `NO_LOCK` | `sync-db` | `false` | `false` |
| V1 | `PESSIMISTIC_LOCK` | `sync-db` | `false` | `false` |
| V2 | `LUA_SCRIPT` | `sync-db` | `false` | `false` |
| V3 | `LUA_SCRIPT` | `kafka` | `true` | `false` |

`round`와 실제 전략 쌍이 다르면 해당 회차는 무효다. 검증 배치도 동일한 `round`를 사용한다.

## 6. 공통 데이터와 부하

| 항목 | Level 2 기본 비교 | Level 3 최종 인수 |
| --- | ---: | ---: |
| 사용자 | 20,000명 | 20,000명 |
| 요청 | 20,000건 | 20,000건 |
| 캠페인 | 1개 | 1개 |
| 재고 풀 | JEJU / ECONOMY | JEJU / ECONOMY |
| 초기 재고 | 10,000 | 10,000 |
| userId | 1~20,000 | 1~20,000 |
| Idempotency-Key | 요청마다 다른 UUID v4 | 요청마다 다른 UUID v4 |
| 부하 발생 방식 | `shared-iterations`, 500 VU 즉시 시작 | 20,000 요청을 60초 동안 유입하는 최종 시나리오 |

Level 2는 200건 스모크 후 500 → 1,000 → 5,000 → 20,000 요청으로 키운다. 요청 수만 단계적으로 올리고 공식 비교 회차의 VU는 500으로 고정한다. 단계별 결과는 공식 20,000건 결과와 별도 폴더에 저장한다. Level 3의 60초 유입은 별도 최종 인수 스크립트로 실행하며 Level 2 결과와 직접 비교하지 않는다.

## 7. 시간과 데이터 초기화

- JVM, MySQL 세션 및 컨테이너 timezone은 UTC로 고정한다.
- 매 회차 전에 모든 애플리케이션 인스턴스를 중지한다.
- `seed-level2.sh`는 최초 데이터 적재에만 사용한다.
- 이후 회차는 `reset-level2.sh`로 쿠폰·이력·검증 결과와 재고를 초기화한다.
- V3는 애플리케이션을 중지한 상태에서 Kafka topic과 consumer offset도 초기화한다.
- Redis는 MySQL 캠페인·재고와 같은 값으로 초기화한다.
- 초기화 이후 쿠폰 0건, 이력 0건, DB·Redis 재고 10,000인지 확인한다.

## 8. 관측 도구와 필수 지표

| 영역 | 수집 도구 | 필수 지표 |
| --- | --- | --- |
| API | k6, Micrometer | TPS, 평균, p95, p99, 4xx, 5xx |
| JVM | Micrometer | CPU, heap, GC, thread |
| Tomcat | Micrometer | busy threads, max threads |
| HikariCP | Micrometer | active, idle, pending, timeout |
| EC2 | CloudWatch Agent | CPU, 메모리, disk, network, swap |
| 컨테이너 | cAdvisor | 컨테이너별 CPU, 메모리, 재시작 |
| MySQL | mysqld_exporter | connection, threads, buffer pool, lock wait |
| Redis | redis_exporter | CPU, 메모리, 처리량, command latency |
| Kafka | kafka_exporter + 애플리케이션 AdminClient 확인 | consumer lag, topic offset, DLT |

Prometheus scrape interval은 5초, Grafana 기본 refresh는 5초로 고정한다. CloudWatch Agent는 모든 EC2에 설치하고, cAdvisor와 exporter는 데이터·관측 EC2에서 실행한다. 애플리케이션 Actuator는 각 앱 인스턴스를 개별 scrape한다. EC2 CPU·메모리와 HikariCP pending을 볼 수 없는 공식 회차는 성능 비교 자료로 사용하지 않는다.

## 9. 회차 무효 조건

다음 중 하나라도 해당하면 설정을 고친 뒤 새 runId로 다시 실행한다.

- 작업 트리에 커밋되지 않은 변경이 있다.
- V0~V3 중 하나만 다른 인스턴스 사양이나 풀 설정을 사용했다.
- 초기 쿠폰·이력·재고가 공통 시드와 다르다.
- 앱이 여러 대인데 일부 인스턴스의 Git SHA 또는 환경변수가 다르다.
- `TEST_STRATEGY`와 실제 애플리케이션 전략이 다르다.
- V3가 lag 0·DLT 0에 도달하기 전에 검증 배치를 실행했다.
- 결과 폴더에 환경 정보, k6 JSON 또는 DB 검증 결과가 없다.

## 10. 실행 명령

애플리케이션을 모두 중지한 뒤, MySQL·Redis·Kafka 컨테이너가 있는 데이터·관측 EC2에서 준비한다. `BASE_URL`은 중지 여부를 검사할 원격 앱 주소다.

```bash
BASE_URL=http://<앱-사설-IP>:8081 ./scripts/load-test/prepare-level2-run.sh V2 --seed
BASE_URL=http://<앱-사설-IP>:8081 ./scripts/load-test/prepare-level2-run.sh V2
```

준비 스크립트가 출력한 환경변수로 애플리케이션을 실행한 뒤 부하와 사후 검증을 수행한다.

```bash
./scripts/load-test/run-level2.sh V2 L2-V2-01
```

AWS에서 k6 인스턴스를 분리한다면 다음처럼 단계별로 실행한다.

```bash
# k6 인스턴스
LEVEL2_PHASE=load BASE_URL=http://<앱-사설-IP>:8081 \
  ./scripts/load-test/run-level2.sh V2 L2-V2-01

# 결과 폴더 전체를 데이터·관측 EC2의 같은 위치로 복사한 뒤
LEVEL2_PHASE=finalize BASE_URL=http://<앱-사설-IP>:8081 \
  ./scripts/load-test/run-level2.sh V2 L2-V2-01
```

## 11. 공식 회차 시작 전 확인 체크리스트

- [ ] 계정의 `c7i`·`m7i` On-Demand vCPU 한도가 필요한 인스턴스 수를 허용하는지 확인
- [ ] `ap-northeast-2a`에서 지정 인스턴스 유형을 실제 생성할 수 있는지 확인
- [ ] 예상 비용 확인 및 테스트 종료 후 인스턴스 중지·삭제 담당자 지정
- [ ] 모든 EC2가 같은 VPC에 있고 Level별 AZ 배치가 고정표와 같은지 확인
- [ ] Level 3 ALB health check와 두 앱의 동일 SHA·환경변수 확인
- [ ] 모든 EC2에 `CloudWatchAgentServerPolicy`가 포함된 IAM Role 연결
- [ ] `timedatectl show -p NTPSynchronized`가 모든 EC2에서 `yes`인지 확인
- [ ] Kafka advertised host가 데이터·관측 EC2 사설 IP인지 확인
- [ ] Prometheus target과 CloudWatch Agent 상태 확인
- [ ] Grafana 공통 대시보드 확인
- [ ] 결과 파일 회수 위치와 실행자 기록

AWS 계정의 vCPU 한도, 해당 시점 AZ의 인스턴스 재고, 실제 과금액은 저장소에서 확정할 수 없다. 이 세 항목만 콘솔에서 실행 직전에 확인하며, 사양 변경이 필요하면 모든 전략에 동일하게 적용하고 환경 ID를 새로 부여한다.
