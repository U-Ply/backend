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

`BULK-02` 는 `round=V2` 로 잘못 붙여 실행한 회차다. 삭제하지 않고 남긴다 (아래 관찰 참고).

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

## 관찰 1 — 회차 라벨과 실제 전략이 어긋나도 검출되지 않는다

`BULK-02` 는 `round=V2` 로 기록됐지만, 애플리케이션은 `coupon.issue.strategy=PESSIMISTIC_LOCK`
으로 떠 있었다. 그런데도 판정은 `PASSED` 다.

`round` 는 URL 파라미터로 들어오고 실제 전략은 애플리케이션 설정에서 온다. 두 값이 모순돼도
아무도 확인하지 않는다. Level 2/3 공식 회차에서 "V3 회차" 라고 붙였는데 앱이 V1 설정으로 떠
있으면, 리포트에는 `round=V3 PASSED` 가 남고 §11 비교표가 잘못된 라벨로 채워진다.

검증 배치가 `coupon.issue.strategy` 를 읽어 `round` 와 모순되면 거절하거나 최소한 기록해야 한다.

## 관찰 2 — 검증과 대사 사이의 스냅샷 간격

`BULK-01` 에서 검증 규칙의 `snapshot_at` 은 `02:44:55`, REC-01 은 `02:52:07` 이다. 8분 차이다.
대사 배치를 나중에 따로 호출했기 때문이다.

지금은 데이터가 정적이라 문제가 없지만, 실제 회차에서는 그 사이에 데이터가 변하면 한 리포트 안에
서로 다른 시점의 결과가 섞인다. §14.4 는 "lag 0 이후 실행" 만 정하고 있으므로, 검증과 대사 사이
간격에 대한 규정을 추가하는 것이 좋다.

---

## 요구사항 대조

| 요구사항 | 상태 | 근거 |
| --- | --- | --- |
| 가상 사용자 100만 명 적재 | 충족 | `users` 1,000,000 |
| 발급 이력 300만 건 적재 | 충족 | `coupon_history` 3,898,982 |
| 검증은 300만 건 전체 대상 | 충족 | `checked_rows` 3,000,000 / 3,898,982 |
| 같은 데이터 재실행 시 같은 결과 | 충족 | BULK-01 vs BULK-03 비교 0행 |
| 스스로 검증할 수단 | 충족 | INV-01~12 + CLOCK-01·02 + REC-01, 관리자 API |
| (선택) 검증 결과 리포트 자동화 | 충족 | 마크다운 리포트 자동 생성 |
| 검증기 자체의 검출력 | 충족 | 오염 5건 → INV-04 정확히 5건, 오탐 0 |
