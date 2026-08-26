# Level 2·3 AWS 부하 테스트 환경 구성 가이드

이 문서는 [`load-test-environment.md`](load-test-environment.md)의 고정값을 AWS에 실제로 구성하는 절차다. 공식 결과는 환경 고정표가 우선하며, 회차 중 설정을 바꾸면 새 환경 ID로 다시 실행한다.

## 1. 배치 구조

### Level 2

```text
k6 EC2 (c7i.2xlarge, 2a)
  └─ private HTTP ─> App EC2 (c7i.xlarge, 2a)
                         └─ private TCP ─> Data/Observability EC2 (m7i.2xlarge, 2a)
                                              ├─ MySQL / Redis / Kafka
                                              └─ Prometheus / Grafana / exporter
```

### Level 3

```text
k6 EC2
  └─ internal ALB
       ├─ App EC2 #1 (2a)
       └─ App EC2 #2 (2b)
              └─ Data/Observability EC2 (2a)
```

ALB는 서로 다른 AZ의 서브넷을 최소 2개 요구하므로 Level 3만 `2a`, `2b`를 사용한다. Level 2와 Level 3 결과는 직접 비교하지 않는다.

## 2. AWS 사전 확인

1. 리전을 서울 `ap-northeast-2`로 선택한다.
2. Service Quotas에서 Running On-Demand Standard instances 한도를 확인한다.
   - Level 2 필요량: 총 20 vCPU
   - Level 3 필요량: 총 24 vCPU
3. `c7i.xlarge`, `c7i.2xlarge`, `m7i.2xlarge`를 지정 AZ에서 만들 수 있는지 확인한다.
4. 비용과 종료 후 인스턴스 정리 담당자를 정한다.
5. EC2용 IAM Role에 `CloudWatchAgentServerPolicy`를 연결한다. SSM을 사용하면 `AmazonSSMManagedInstanceCore`도 연결한다.

할당량이나 인스턴스 재고 때문에 사양을 바꾸면 일부 서버만 바꾸지 않는다. 모든 전략에 같은 변경을 적용하고 환경 ID를 새로 부여한다.

## 3. 네트워크와 보안그룹

모든 EC2는 같은 VPC의 사설 IP로 통신한다.

| 대상 SG | 포트 | 출처 |
| --- | ---: | --- |
| App | 8081 | k6 SG |
| App | 8081 | Data/Observability SG (Prometheus) |
| App | 8081 | ALB SG (Level 3) |
| App | 22 | 운영자 IP |
| Data/Observability | 3306, 6379, 9092 | App SG |
| Data/Observability | 22 | 운영자 IP |
| k6 | 22 | 운영자 IP |
| ALB | 80 | k6 SG 또는 k6 사설 IP |

Grafana와 Prometheus는 외부 공개 대신 SSH 터널 사용을 권장한다.

```bash
ssh -L 3000:localhost:3000 -L 9090:localhost:9090 ubuntu@<인프라-EC2>
```

## 4. 공통 설치와 시계 확인

모든 EC2:

```bash
sudo apt update
sudo apt install -y git curl unzip
timedatectl
timedatectl show -p NTPSynchronized
date -u
```

`NTPSynchronized=yes`여야 한다. 앱 EC2에는 JDK 17, 데이터·관측 EC2에는 Docker/Compose, k6 EC2에는 k6를 설치한다. CloudWatch Agent는 IAM Role을 연결한 뒤 CPU·메모리·디스크·네트워크·swap을 수집하도록 설정한다.

공식 회차는 PR이 병합된 동일 merge commit SHA를 모든 호스트에서 checkout한다.

```bash
git clone https://github.com/U-Ply/backend.git
cd backend
git checkout <merge-commit-sha>
git status --short
```

마지막 명령은 출력이 없어야 한다.

## 5. 데이터·관측 EC2

Kafka가 원격 앱에게 접근 가능한 주소를 알려주도록 사설 IP를 전달한다.

```bash
export KAFKA_ADVERTISED_HOST="<데이터-관측-EC2-사설-IP>"
```

Prometheus AWS 설정은 Git 추적 파일을 직접 수정하지 않고 `/tmp` 등에 생성한다.

```yaml
# /tmp/uply-prometheus.yml
global:
  scrape_interval: 5s

scrape_configs:
  - job_name: coupon-app
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - <앱1-사설-IP>:8081
          # Level 3에서만 앱2 추가
          # - <앱2-사설-IP>:8081
```

```bash
export PROMETHEUS_CONFIG_FILE=/tmp/uply-prometheus.yml
docker compose up -d
docker compose ps
```

MySQL·Redis·Kafka 시계와 연결을 확인한다.

```bash
docker exec coupon-mysql mysql -uroot -proot1234 \
  -e "SELECT NOW(3), UTC_TIMESTAMP(3), @@session.time_zone, @@global.time_zone;"
docker exec coupon-redis redis-cli TIME
docker exec coupon-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list
```

`cAdvisor`, `mysqld_exporter`, `redis_exporter`, `kafka_exporter` 및 해당 Prometheus scrape 설정은 `docker-compose.yml`/`prometheus/prometheus.yml`에 추가되어 있다(`feat/monitoring-visualization`). `docker compose up -d`로 함께 기동되며, 공식 성능 회차 전에 `docker compose ps`로 4개 exporter 컨테이너가 모두 healthy인지 확인한다. exporter 컨테이너가 하나라도 없는 상태로 진행한 회차는 API 결과 확인용 리허설로만 사용한다.

## 6. 애플리케이션 배포

JAR는 한 번만 빌드하고 SHA-256이 같은 파일을 모든 앱 EC2에 배포한다.

```bash
./gradlew clean test spotlessCheck bootJar
sha256sum build/libs/*.jar
```

앱 환경변수는 기존 `application.yml`의 JDBC 옵션을 유지하도록 프로젝트 전용 키를 사용한다.

```bash
export DB_HOST="<데이터-관측-EC2-사설-IP>"
export DB_NAME=coupon_db
export DB_USERNAME=coupon
export DB_PASSWORD=coupon1234
export REDIS_HOST="<데이터-관측-EC2-사설-IP>"
export KAFKA_HOST="<데이터-관측-EC2-사설-IP>"

export SERVER_PORT=8081
export SERVER_TOMCAT_THREADS_MAX=200
export SERVER_TOMCAT_ACCEPT_COUNT=100
export SERVER_TOMCAT_MAX_CONNECTIONS=8192
export DB_POOL_SIZE=10
export RECONCILIATION_SCHEDULER_ENABLED=false
export LOGGING_LEVEL_ROOT=WARN
```

회차별 `COUPON_*` 값은 준비 스크립트가 출력한 값을 그대로 적용한다.

```bash
nohup java -Xms2g -Xmx2g -XX:+UseG1GC -Duser.timezone=UTC \
  -jar coupon-service.jar > coupon-service.log 2>&1 &
```

```bash
curl --fail http://localhost:8081/actuator/health
curl --fail http://localhost:8081/actuator/prometheus >/dev/null
```

## 7. Level 2 회차 실행

### 7.1 준비 — 데이터·관측 EC2

모든 앱을 먼저 중지한다. 최초 한 번만 `--seed`를 사용한다.

```bash
BASE_URL=http://<앱-사설-IP>:8081 \
  ./scripts/load-test/prepare-level2-run.sh V2 --seed
```

반복 회차:

```bash
BASE_URL=http://<앱-사설-IP>:8081 \
  ./scripts/load-test/prepare-level2-run.sh V2
```

출력된 전략 환경변수로 앱을 다시 시작한다.

### 7.2 부하 — k6 EC2

```bash
LEVEL2_PHASE=load \
BASE_URL=http://<앱-사설-IP>:8081 \
./scripts/load-test/run-level2.sh V2 L2-V2-01
```

### 7.3 결과 전달

결과 폴더 전체를 데이터·관측 EC2의 같은 경로로 복사한다.

```bash
scp -r load-tests/results/L2-V2-01 \
  ubuntu@<인프라-EC2>:~/backend/load-tests/results/
```

### 7.4 사후 검증 — 데이터·관측 EC2

```bash
LEVEL2_PHASE=finalize \
BASE_URL=http://<앱-사설-IP>:8081 \
./scripts/load-test/run-level2.sh V2 L2-V2-01
```

전략 전환은 `앱 중지 → prepare → 전략 환경변수 적용 → 앱 시작 → load → 폴더 복사 → finalize` 순서로 반복한다.

## 8. Level 3 추가 조건

- 내부 ALB에 서로 다른 AZ 서브넷 2개를 연결한다.
- 각 AZ의 앱 한 대를 대상 그룹 8081 포트에 등록한다.
- health check는 `/actuator/health`를 사용한다.
- 두 앱의 Git SHA, JAR SHA-256, JVM 옵션과 환경변수가 같아야 한다.
- k6 `BASE_URL`은 ALB DNS 이름을 사용한다.
- Level 3용 60초 유입 k6 스크립트는 아직 없으므로 착수 전 별도 구현한다.

## 9. 공식 회차 체크리스트

- [ ] Git 작업 트리 clean, 모든 호스트 Git SHA 동일
- [ ] Level 3 두 앱의 JAR SHA-256 동일
- [ ] 인스턴스 유형·AZ·EBS가 환경 고정표와 일치
- [ ] 모든 EC2 NTP 동기화 및 UTC 확인
- [ ] Kafka advertised host가 인프라 사설 IP
- [ ] Prometheus의 앱 target이 UP
- [ ] CloudWatch Agent가 모든 EC2에서 지표 전송
- [ ] DB 쿠폰·이력 0건, DB·Redis 재고 10,000
- [ ] V3 Kafka topic·offset 초기화
- [ ] 결과 폴더에 load/finalize 환경 기록과 k6 원본 결과 존재
- [ ] 종료 후 인스턴스 중지·삭제 및 결과 회수
