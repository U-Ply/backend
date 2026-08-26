# 대량 데이터 정합성 검증 (300만 건)

과제 명세의 다음 항목에 대한 실행 기록이다.

> 대량 데이터 적재 → 가상 사용자 100만 명, 발급 이력 300만 건 생성 및 적재
> 검증은 300만 건 전체를 대상으로 하며, 같은 데이터 기준으로 재실행하면 같은 결과가 나와야 한다
> (실행 시간은 평가하지 않음)

- 환경 ID: `LOCAL-DOCKER-01`
- gitCommit: `<git rev-parse --short HEAD>`
- 일시: 2026-08-25
- MySQL 8.0.46 (도커 기본 설정, `innodb_buffer_pool_size` 128MB)
- 애플리케이션: `coupon.issue.strategy=PESSIMISTIC_LOCK`, `coupon.save.strategy=sync-db`

---

## 1. 데이터 생성

난수도 `NOW()` 도 쓰지 않는다. 모든 값이 시드와 인덱스에서 파생되므로 같은 인자면 같은 데이터가 나온다.

```bash
./gradlew bootRun --args="--spring.profiles.active=dummy --spring.main.web-application-type=none --users=1000000 --coupons=3000000 --seed=42 --truncate"
```

| 테이블 | 건수 |
| --- | ---: |
| `users` | 1,000,000 |
| `campaigns` | 30 (진행 15 / 종료 15) |
| `campaign_stocks` | 300 |
| `coupons` | 3,000,000 |
| `coupon_history` | **3,898,982** |

적재 시간 2분 52초 (coupons 구간 150,607ms / 19,919 rows/s).
**적재 속도는 평가 대상이 아니므로 최적화하지 않았다.**

---

## 2. 검증 실행

```
POST /api/admin/batch/verification?runId=BULK-01&round=V1&failOnViolation=false
POST /api/admin/batch/reconcile?runId=BULK-01
```

`round=V1` 인 이유. 이 데이터는 발급 API 를 타지 않고 직접 적재한 정적 데이터라 Redis 재고·Redis
시계와 무관하다. V1 로 두면 CLOCK-02 와 REC-01 이 `NOT_APPLICABLE` 로 기록되고, 실제로 검사할
수 있는 INV-01~12 와 CLOCK-01 만 판정한다. **N/A 는 통과가 아니라 "이 회차에 해당하지 않음"
으로 남는다.**

| 회차 | 데이터 | 판정 | 규칙 | 총 위반 | 총 소요 |
| --- | --- | --- | --- | ---: | ---: |
| `BULK-01` | 정상 | PASSED | 검사 13 / N/A 2 / 미실행 0 | 0 | 55,757 ms |
| `BULK-03` | 정상 (재실행) | PASSED | 검사 13 / N/A 2 / 미실행 0 | 0 | 54,417 ms |
| `BULK-04` | 오염 5건 | FAILED | 검사 13 / N/A 2 / 미실행 0 | **5** | 67,771 ms |
| `BULK-06` | 오염 5건 + 만료 배치 이후 | FAILED | 검사 13 / N/A 2 / 미실행 0 | **5** | 70,954 ms |

`BULK-06` 의 `FAILED` 는 만료 배치의 실패가 아니다. `BULK-04` 의 오염 5건이 데이터에 그대로
남아 있어서 나온 값이며, 위반 대상도 그 5건 그대로다. 7절 참고.

리포트 원본이 파일로 남아 있는 회차는 `BULK-06` 하나다
(`BULK-06-report.md`).
나머지 회차는 이 문서의 표가 기록이다 — `verification_report` 는 시드 스크립트가 지우는
테이블이라 보관 장소로 쓸 수 없다. 부록 참고.

---

## 3. "300만 건 전체 대상" 의 증거 — `checked_rows`

"위반 0건" 만으로는 아무것도 증명되지 않는다. 검사 대상이 실제로 있었는지를 함께 본다.

| 규칙 | 검사 행 | 대상 |
| --- | ---: | --- |
| INV-02 1인 1매 | 3,000,000 | coupons 전수 |
| INV-04 현재 상태 = 최종 이력 | 3,000,000 | coupons 전수 |
| INV-06 시각 순서 | 3,000,000 | coupons 전수 |
| INV-07 종료 상태 타임스탬프 | 3,000,000 | coupons 전수 |
| INV-08 참조 조합 일치 | 3,000,000 | coupons 전수 |
| INV-11 캠페인 기간 내 발급 | 3,000,000 | coupons 전수 |
| INV-12 만료 시각 캠페인 상속 | 3,000,000 | coupons 전수 |
| INV-05 상태 전이 유효성 | 3,898,982 | coupon_history 전수 |
| INV-09 도메인 멱등성 | 3,898,982 | coupon_history 전수 |
| INV-01 초과 발급 금지 | 300 | 재고 풀 전수 |
| INV-03 DB 재고 카운터 | 300 | 재고 풀 전수 |
| INV-10 고아 행 없음 | — | 존재 검사(NOT EXISTS) |

`checked_rows` 가 비어 있는 INV-10 은 전수 스캔이 아니라 `NOT EXISTS` 로 판정하는 규칙이다.

---

## 4. 재실행 동일성

같은 데이터, 같은 프로세스, 같은 설정에서 두 번 실행했다.

```sql
SELECT a.rule_code, a.status, b.status, a.violation_count, b.violation_count,
       a.checked_rows, b.checked_rows
FROM verification_report a JOIN verification_report b USING (rule_code)
WHERE a.run_id='BULK-01' AND b.run_id='BULK-03'
  AND (a.status <> b.status
       OR a.violation_count <> b.violation_count
       OR COALESCE(a.checked_rows,-1) <> COALESCE(b.checked_rows,-1));
```

**결과: 0행.** 15개 규칙 전부에서 `status`·`violation_count`·`checked_rows` 가 일치한다.

`elapsed_ms` 만 실행마다 다르다.

| 규칙 | BULK-01 | BULK-03 |
| --- | ---: | ---: |
| INV-04 | 13,328 ms | 13,667 ms |
| INV-09 | 12,028 ms | 11,766 ms |
| INV-11 | 6,386 ms | 6,110 ms |
| INV-10 | 6,141 ms | 5,270 ms |

판정에 쓰는 값은 결정적이고 시간만 흔들린다. 명세가 "실행 시간은 평가하지 않는다" 고 한 것과 맞는다.

### 4.1 완전 재구축 후 동일성 (`BULK-06`)

`BULK-01` vs `BULK-03` 은 같은 프로세스 안에서 배치를 두 번 돌린 것이다. 데이터가 그대로였으니
"같은 데이터면 같은 결과" 의 절반만 보인다.

2026-08-26, 데이터베이스를 통째로 비우고 **같은 인자로 처음부터 다시 만든 뒤** 같은 절차를
반복했다.

```bash
./gradlew bootRun --args="--spring.profiles.active=dummy --spring.main.web-application-type=none --users=1000000 --coupons=3000000 --seed=42 --corrupt=5 --truncate"
POST /api/admin/batch/expiration
POST /api/admin/batch/verification?runId=BULK-06&round=V1&failOnViolation=false
POST /api/admin/batch/reconcile?runId=BULK-06
```

`BULK-04` 와 `BULK-06` 은 **서로 다른 날, 서로 다른 적재본** 위에서 돌았다. 그 사이에
데이터베이스를 통째로 비우고 300만 건을 다시 만들었다. 두 회차가 공유하는 것은 생성기 인자
(`--seed=42 --corrupt=5`) 뿐이다.

| 항목 | `BULK-04` | `BULK-06` |
| --- | --- | --- |
| 적재본 | 2026-08-25 | 2026-08-26 (재구축) |
| INV-04 위반 | 5 | 5 |
| INV-04 `target_id` | 4 · 19 · 31 · 37 · 40 | 4 · 19 · 31 · 37 · 40 |
| INV-04 검사 행 | 3,000,000 | 3,000,000 |
| 나머지 14개 규칙 위반 | 0 | 0 |
| 규칙 수 | 15 (검사 13 / N/A 2 / 미실행 0) | 15 (검사 13 / N/A 2 / 미실행 0) |

**오염 5건의 `coupon_id` 까지 같다.** 생성기가 난수도 `NOW()` 도 쓰지 않고 모든 값을 시드와
인덱스에서 파생시키기 때문이다. 개수만 같은 것이 아니라 대상까지 같으므로 "우연히 5건" 이
배제된다.

`BULK-06` 은 여기에 만료 배치까지 다시 돌린 상태다. `coupon_history` 가 3,898,982 →
4,949,780 으로 늘었고(차이 1,050,798), INV-05·INV-09 의 `checked_rows` 가 그 값을 그대로
받는다. 재구축 → 만료 → 검증 전체가 같은 결과로 재현된다.

리포트 원본은 `docs/round-results/BULK-06-report.md` 에 있다.

---

## 5. 규칙별 비용 — 10만 건 대비

`L1-BASE-01`(2026-08-21, 같은 `LOCAL-DOCKER-01`) 에서 이렇게 예측했다.

> 규칙별 최대 비용: INV-04 409ms, INV-09 267ms. **300만 건 적재 시 이 둘이 먼저 늘어난다.**

| 규칙 | 10만 건 | 300만 건 | 배수 |
| --- | ---: | ---: | ---: |
| INV-04 현재 상태 = 최종 이력 | 409 ms | **13,328 ms** | 32.6× |
| INV-09 도메인 멱등성 | 267 ms | **12,028 ms** | 45.0× |
| INV-11 캠페인 기간 내 발급 | 56 ms | 6,386 ms | 114× |
| INV-10 고아 행 없음 | 92 ms | 6,141 ms | 66.8× |
| INV-08 참조 조합 일치 | 65 ms | 5,565 ms | 85.6× |
| INV-12 만료 시각 캠페인 상속 | 59 ms | 5,553 ms | 94.1× |
| INV-05 상태 전이 유효성 | 46 ms | 1,889 ms | 41.1× |
| INV-02 1인 1매 | 41 ms | 1,014 ms | 24.7× |

예측대로 **INV-04 와 INV-09 가 1·2위**다. 둘 다 `coupon_history` 390만 행 위에서
윈도우 함수(`ROW_NUMBER`)와 `GROUP BY` 를 돌리는 규칙이다.

같은 환경 ID 에서 측정했으므로 이 비교는 §8.1 을 만족한다.

---

## 6. 검출력 — 오염 주입

위반 0건이 "정말 정합해서 0" 인지 "아무것도 안 봐서 0" 인지는 `checked_rows` 만으로는 완전히
가릴 수 없다. 같은 시드로 데이터를 다시 만들면서 오염 5건을 주입해 확인했다.

```bash
./gradlew bootRun --args="--spring.profiles.active=dummy --spring.main.web-application-type=none --users=1000000 --coupons=3000000 --seed=42 --corrupt=5 --truncate"
```

```
오염 주입 대상 coupon_id = [4, 19, 31, 37, 40]
```

오염 방식은 USED 쿠폰의 `status` 만 `CANCELLED` 로 바꾸고 `cancelled_at = used_at`,
`used_at = NULL` 로 옮기는 것이다. **한 규칙만 정확히 겨냥하도록 설계했다.**

- INV-04 는 걸린다 — 현재 상태 `CANCELLED` 인데 이력의 마지막은 `USED`
- INV-06 은 안 걸린다 — `cancelled_at` 이 원래 `used_at` 값이라 `issued_at` 이후다
- INV-07 은 안 걸린다 — `CANCELLED` + `cancelled_at` 있음 + `expired_at` 없음은 허용 분기다

### 결과 (`BULK-04`)

| 규칙 | 위반 | 검사 행 |
| --- | ---: | ---: |
| **INV-04 현재 상태 = 최종 이력** | **5** | 3,000,000 |
| INV-01 / 02 / 03 / 05 / 06 / 07 / 08 / 09 / 10 / 11 / 12 | 0 | — |
| CLOCK-01 | 0 | — |

검출된 대상은 주입한 그 5건이다.

```
GET /api/admin/batch/verification/runs/BULK-04/violations?ruleCode=INV-04

target_id 4   current=CANCELLED last_history=USED
target_id 19  current=CANCELLED last_history=USED
target_id 31  current=CANCELLED last_history=USED
target_id 37  current=CANCELLED last_history=USED
target_id 40  current=CANCELLED last_history=USED
```

**미탐 0 (주입한 5건을 전부 잡았다) · 오탐 0 (나머지 규칙은 하나도 반응하지 않았다).**
개수만이 아니라 `target_id` 까지 일치하므로, 우연히 다른 5건을 잡은 경우가 배제된다.

INV-04 의 소요가 13,328ms → 25,202ms 로 늘었다. 위반이 나오면 샘플을 실제로 저장하기 때문이다.

---

## 7. 만료 배치 — 105만 건 상태 전이

만료 배치의 지금까지 최대 실적은 10만 건 데이터에서 34,796건이었다 (`round-results.md`,
2026-08-21). 300만 건 데이터는 종료 캠페인 15개에 만료 대상을 105만 건 갖고 있어, 같은
배치를 **30배 규모**에서 돌려볼 수 있었다.

### 기대값을 먼저 적었다

결과를 본 뒤에 해석하면 어떤 숫자가 나와도 그럴듯해 보인다. 실행 전에 스냅샷을 뜨고 기대값을
확정했다.

| 항목 | 배치 전 | 기대 | 실제 |
| --- | ---: | ---: | ---: |
| 만료 대상 (`ISSUED` + 만료일 경과) | 1,050,798 | 0 | **0** |
| `coupons` ISSUED | 2,175,782 | 1,124,984 | **1,124,984** |
| `coupons` EXPIRED | 75,083 | 1,125,881 | **1,125,881** |
| `coupons` USED | 599,553 | 599,553 | **599,553** |
| `coupons` CANCELLED | 149,582 | 149,582 | **149,582** |
| `coupon_history` | 3,898,982 | 4,949,780 | **4,949,780** |
| `to_status='EXPIRED'` 이력 | 75,083 | 1,125,881 | **1,125,881** |

여섯 숫자가 전부 일치한다. `USED`·`CANCELLED` 가 그대로인 것은 만료 배치가 종료 상태를
건드리지 않았다는 뜻이다 (`UPDATE ... WHERE status = 'ISSUED'`).

### 실행

```
POST /api/admin/batch/expiration     ->  jobExecutionId 113
```

```
job_execution_id  job_name        step_name        read_count  write_count  commit_count
113               expirationJob   cutoffStep                0            0             1
113               expirationJob   expirationStep    1,050,798    1,050,798         1,051
```

`cutoffStep` 이 `SELECT NOW(3)` 으로 기준 시각을 한 번 고정해 `JobExecutionContext` 에 넣고,
`expirationStep` 이 그 값으로 `JdbcPagingItemReader`(청크 1,000)를 돌린다. 커밋 1,051회는
1,050,798 / 1,000 과 맞는다.

### 재실행 멱등성

같은 배치를 7분 뒤에 한 번 더 호출했다.

```
114               expirationJob   expirationStep            0            0             1
```

**0건을 읽고 아무것도 바꾸지 않았다.** 이력이 중복으로 쌓이지도 않았다 — 라이터가
`idempotency_key = CONCAT('expire-', coupon_id)` 를 쓰고, `UPDATE` 에 `status = 'ISSUED'`
조건이 걸려 있어 두 번째 회차의 대상 집합이 비었다.

### 만료는 재고를 복원하지 않는다 (§2.8)

배치 전에 `campaign_stocks` 300행 전체를 `stock_before_expire` 로 복제해 두고 비교했다.

```sql
SELECT COUNT(*) AS diff_rows
FROM ((SELECT * FROM campaign_stocks) EXCEPT (SELECT * FROM stock_before_expire)) d;
```

**결과: 0.** 105만 장이 만료됐는데 재고는 한 컬럼도 움직이지 않았다. INV-03 도 위반 0으로
같은 결론을 낸다.

### 새 이력이 실제로 검사됐다는 증거

만료 배치 이후 회차(`BULK-06`)에서 이력 기반 두 규칙의 `checked_rows` 가 늘었다.

| 규칙 | 만료 전 (`BULK-01`~`BULK-04`) | 만료 후 (`BULK-06`) |
| --- | ---: | ---: |
| INV-05 상태 전이 유효성 | 3,898,982 | **4,949,780** |
| INV-09 도메인 멱등성 | 3,898,982 | **4,949,780** |

만료 배치가 새로 넣은 1,050,798행이 검사 대상에 그대로 들어갔다. 규칙이 옛날 행만 훑고
통과한 것이 아니다. INV-05 는 `ISSUED -> EXPIRED` 전이 105만 건을, INV-07 은 EXPIRED 쿠폰이
`expired_at` 만 갖고 `used_at`·`cancelled_at` 은 비어 있는지를 처음으로 이 규모에서 봤다.

INV-04 의 소요는 25,202ms(BULK-04) → 29,026ms 로 늘었다. `coupon_history` 가 27% 커진 것과
같은 방향이다.

### 위반 5건은 만료 배치와 무관하다

```
GET /api/admin/batch/verification/runs/BULK-06/violations?ruleCode=INV-04

target_id 4   current=CANCELLED last_history=USED
target_id 19  current=CANCELLED last_history=USED
target_id 31  current=CANCELLED last_history=USED
target_id 37  current=CANCELLED last_history=USED
target_id 40  current=CANCELLED last_history=USED
```

`BULK-04` 에서 주입한 오염 5건 그대로다. **만료 배치가 건드린 1,050,798건 중 이력이 어긋난
것은 하나도 없다.** 개수만이 아니라 `target_id` 까지 같으므로 "우연히 5건" 이 아니다.

---


## 요구사항 대조

| 요구사항 | 상태 | 근거 |
| --- | --- | --- |
| 가상 사용자 100만 명 적재 | 충족 | `users` 1,000,000 |
| 발급 이력 300만 건 적재 | 충족 | `coupon_history` 3,898,982 |
| 검증은 300만 건 전체 대상 | 충족 | `checked_rows` 3,000,000 / 3,898,982 |
| 같은 데이터 재실행 시 같은 결과 | 충족 | BULK-01 vs BULK-03 비교 0행, BULK-04 vs BULK-06 재구축 후 동일 |
| 스스로 검증할 수단 | 충족 | INV-01~12 + CLOCK-01·02 + REC-01, 관리자 API |
| (선택) 검증 결과 리포트 자동화 | 충족 | 마크다운 리포트 자동 생성 |
| 검증기 자체의 검출력 | 충족 | 오염 5건 → INV-04 정확히 5건, 오탐 0 |
| 발급·사용·취소·만료 전체 상태 관리 | 충족 | 만료 배치 105만 건 전이, 이력 105만 건 (7절) |
| 동일 상태 변경이 반복돼도 한 번만 반영 | 충족 | 만료 배치 재실행 시 0건 처리 (7절) |

---

## 부록 — 원시 데이터 보존 상태

2026-08-25 저녁, 레벨 2 리허설 준비를 위해 `scripts/load-test/seed-level2.sh` 를 실행했다.
이 스크립트가 부르는 `load-tests/sql/seed-level2.sql` 은 `verification_report` 와
`verification_violation` 을 TRUNCATE 한다. 따라서 **그 이전 회차의 규칙별 행은 DB 에 남아
있지 않다.** 이 문서의 표가 그 회차들의 기록이다.

`seed-level2.sql` 이 지우는 테이블은 `verification_violation` · `verification_report` ·
`coupon_history` · `coupons` · `campaign_stocks` · `campaigns` · `users` 일곱 개다.
**Spring Batch 메타데이터는 건드리지 않는다.** 만료 배치의 실행 기록은 그대로 남아 있다.

```sql
SELECT s.job_execution_id, s.step_name, s.read_count, s.write_count, s.commit_count, e.start_time
FROM BATCH_STEP_EXECUTION s
JOIN BATCH_JOB_EXECUTION e ON e.job_execution_id = s.job_execution_id
JOIN BATCH_JOB_INSTANCE  i ON i.job_instance_id  = e.job_instance_id
WHERE i.job_name = 'expirationJob'
ORDER BY s.job_execution_id;
```

| jobExecutionId | 일시 | read | write | commit |
| ---: | --- | ---: | ---: | ---: |
| 6 | 2026-08-19 01:45 | 54,579 | 54,579 | 55 |
| 47 | 2026-08-21 09:15 | 34,806 | 34,806 | 35 |
| **113** | **2026-08-25 07:46** | **1,050,798** | **1,050,798** | **1,051** |
| 114 | 2026-08-25 07:53 | 0 | 0 | 1 |

이전 최대 실적의 **19.2배**다. 재실행(114)이 0건인 것도 여기 남아 있어, 멱등성 근거는 회차
리포트 없이도 성립한다.

### 복원 방법

데이터 생성기가 결정적이므로 같은 인자로 그대로 되살릴 수 있다.

```bash
./gradlew bootRun --args="--spring.profiles.active=dummy --spring.main.web-application-type=none --users=1000000 --coupons=3000000 --seed=42 --corrupt=5 --truncate"
```

이후 만료 배치 → 검증 → 대사를 다시 실행한다. **runId 는 새 값을 써야 한다.**
`seed-level2.sql` 도 `reset-level2.sh` 도 `BATCH_JOB_INSTANCE` 는 지우지 않으므로, 한 번 쓴
runId 로는 배치가 아예 뜨지 않는다 (`A job instance already exists and is complete`).
`BULK-07` 이후를 쓴다. 공식 회차도 마찬가지다 — `L2-V1-01` 을 재시도하려면 `L2-V1-02` 로 간다.

**복원은 2026-08-26 에 실제로 수행했다.** `BULK-06` 이 그 결과이며 4.1절에 비교표가 있다.
그때 리포트를 파일로 떨궈 두었으므로 DB 가 다시 지워져도 남는다.

```bash
curl -s ".../verification/runs/BULK-06/report"                       > docs/round-results/BULK-06-report.md
curl -s ".../verification/runs/BULK-06"                              > docs/round-results/BULK-06-rules.json
curl -s ".../verification/runs/BULK-06/violations?ruleCode=INV-04"    > docs/round-results/BULK-06-violations.json
```

**회차 리포트는 실행 직후 파일로 저장한다.** DB 의 `verification_report` 는 시드 스크립트가
지우는 테이블이라 보관 장소로 쓸 수 없다.

### 회차 재실행 시 주의 — runId 는 전역 유일해야 한다

`reset-level2.sh` 도 `BATCH_*` 테이블은 지우지 않는다. 공식 회차에서 `L2-V1-01` 을 한 번
쓰고 조건을 바꿔 다시 돌리려면 `L2-V1-02` 로 가야 한다. 같은 runId 를 재사용하면 배치가
아예 뜨지 않는다.