# 검증 규칙 검출력 시험

정합성 검증 규칙이 **실제로 위반을 잡아내는지** 를 확인한 기록.

## 왜 이 시험을 하는가

정상 데이터에서 검증 쿼리가 0을 반환하는 것은 두 가지로 해석된다.

1. 위반이 없어서 0
2. 판정 조건이 틀렸거나 검사 대상이 비어서 0

**출력만 봐서는 이 둘을 구별할 수 없다.** 이 프로젝트에서 두 번 겪었다.

- JVM(KST)과 MySQL(UTC)의 9시간 차 때문에 앱이 만든 행이 시점 필터에서 통째로
  제외된 채 10개 규칙이 전부 0으로 "통과" 한 적이 있다. 검사 대상이 0건이었을 뿐이다.
- 예매 취소 경로가 더미 데이터에 없던 동안, INV-05·INV-07 의 해당 분기가 한 번도
  실행되지 않은 채 0이 나온 적이 있다.

따라서 각 규칙에 위반을 하나씩 주입해, **해당 규칙만 검출되는지**(미탐 없음)
그리고 **다른 규칙은 반응하지 않는지**(오탐 없음)를 확인한다.

## 시험 환경

| 항목 | 값 |
|---|---|
| 대상 DB | `coupon_db_test` (개발용 `coupon_db` 와 분리) |
| 데이터 | 쿠폰 4,800 / 이력 6,180 / 재고 풀 300 / 유저 5,000 |
| 캠페인 | 30개 — 진행 중 15 / 종료 15 |
| 생성 조건 | `--users=5000 --campaigns=30 --coupons=5000 --seed=42 --base-time=2026-06-01T00:00:00 --truncate` |
| 롤백 방법 | 규칙마다 생성기 재실행(`--truncate`). 수동 복구 SQL 을 쓰지 않는다 |
| 시행일 | 2026-08-18 ~ 08-19 |

INV-04 만 `coupon_db`(쿠폰 99,900건)에서 생성기의 `--corrupt=5` 옵션으로 수행했다.

각 회차는 다음 순서로 진행했다.

```
재생성 → 주입 1건 → 검증 → 재생성 → 다음 규칙
```

### 기준 데이터의 이력 분포

```
NULL   -> ISSUED      4,800
ISSUED -> USED        1,055   (순수 USED 958 + 예매 취소 97)
ISSUED -> CANCELLED     110   항공사 일괄 취소
USED   -> CANCELLED      97   예매 취소
ISSUED -> EXPIRED       118
                      ──────
                       6,180
```

취소를 두 경로로 나눠 생성하는 것이 중요하다. 한쪽만 만들면 규칙이 "통과" 해도
그 경로를 한 번도 검사하지 않은 것이 된다.

## 결과

| 규칙 | 이름 | 주입 | 검출 | 판정 |
|---|---|---|---|---|
| INV-01 | 초과 발급 금지 | `total_stock` 을 발급 수 미만으로 | INV-01=1, INV-03=1 | 통과 (주1) |
| INV-02 | 1인 1매 | — | — | 주입 불가 (주2) |
| INV-03 | DB 재고 카운터 | `remaining_stock + 1` | INV-03=1 | 통과 |
| INV-04 | 현재 상태 = 최종 이력 | USED 5건의 상태만 CANCELLED 로 | INV-04=5 | 통과 |
| INV-05 | 상태 전이 유효성 | `EXPIRED->USED` 이력 삽입 | INV-05=1 | 통과 (주3) |
| INV-06 | 시각 순서 | `used_at` 을 `issued_at` 이전으로 | INV-06=1 | 통과 |
| INV-07 | 종료 상태 타임스탬프 | **실제 정책 변경** | INV-07=97 | 통과 (아래) |
| INV-08 | 참조 조합 일치 | 다른 캠페인 재고 풀로 `stock_id` 이동 | INV-08=1 | 통과 |
| INV-09 | 도메인 멱등성 | 동일 전이 이력 중복 삽입 | INV-09=1 | 통과 |
| INV-10 | 고아 행 없음 | 없는 `coupon_id` 로 이력 삽입 | INV-10=1 | 통과 |
| INV-11 | 캠페인 기간 내 발급 | `issued_at` 을 `open_at` 이전으로 | INV-11=1 | 통과 |
| INV-12 | 만료 시각 캠페인 상속 | `expire_at` 을 하루 늘림 | INV-12=1 | 통과 |

**주입한 11개 규칙 모두 해당 규칙만 검출됐고, 다른 규칙의 오탐은 없었다.**
(INV-01 의 INV-03 동반 검출은 주1 참조)

---

## 실제 정책 변경으로 얻은 검출 사례 — INV-07

인공 주입이 아니라 **정책 변경이 만든 위반** 이라 별도로 기록한다.

예매 취소(`ISSUED → USED → CANCELLED`) 정책이 확정되어 더미 데이터에 반영하자,
그 즉시 INV-07 이 위반을 검출했다.

```
USED -> CANCELLED 이력    97건
INV-07 위반               97건      ← 정확히 그것만
나머지 13개 규칙            0건      ← 오탐 없음
```

기존 INV-07 은 `CANCELLED` 상태에서 `used_at IS NULL` 만 정상으로 인정했다.
예매 취소는 `used_at` 을 유지하므로 새 정책과 어긋난다.

**정책과 검증 규칙의 불일치를 사람이 아니라 배치가 먼저 알렸다.**
이후 규칙을 완화하고 재검증해 14개 규칙 전부 0으로 돌아왔다.

---

## 주1 — INV-01 은 단독으로 위반시킬 수 없다

`remaining_stock = total_stock - 발급수` 가 성립하고(INV-03 정상)
`CHECK (remaining_stock >= 0)` 이 걸려 있으면, **`발급수 <= total_stock` 이
자동으로 보장된다.** 따라서 INV-01 위반은 INV-03 이 이미 깨진 상태에서만
도달 가능하며, 두 규칙이 함께 검출되는 것이 정상 동작이다.

INV-01 의 실질적 의미는 "`remaining_stock` 갱신 자체가 누락된 발급 경로가
있는가" 를 잡는 것이다.

## 주2 — INV-02 는 DB 제약이 주입을 차단한다

`coupons` 의 `UNIQUE KEY uk_campaign_user (campaign_id, user_id)` 가
INSERT/UPDATE 단계에서 중복을 거부한다. 시험 중 실제로 다음 에러가 발생했다.

```
ERROR 1062 (23000): Duplicate entry '2-1' for key 'coupons.uk_campaign_user'
```

즉 **제약이 실제로 작동함이 증명됐고**, 그 때문에 격리 주입이 불가능하다.
INV-02 는 제약이 제거되거나 우회된 경우를 대비한 이중 방어선으로 남긴다.

## 주3 — INV-05 주입 대상이 바뀌었다

예매 취소 정책 반영 전에는 `USED->CANCELLED` 를 주입해 검출을 확인했다.
그 전이가 정식 허용되면서 더 이상 위반이 아니므로, **`EXPIRED->USED`** 로 바꿨다.

`CANCELLED->ISSUED` 도 후보였으나 `to_status=ISSUED` 가 기존 발급 이력과 겹쳐
INV-09 까지 함께 검출된다. 규칙 하나만 겨냥해야 오탐 없음을 함께 증명할 수 있어
`EXPIRED->USED` 를 골랐다.

허용 범위를 넓힌 뒤에도 INV-05 는 **가능한 20가지 전이 중 15가지를 거부한다.**
특히 종료 상태(CANCELLED/EXPIRED)에서의 전이와 `ISSUED` 로의 복귀를 전부 막으므로,
재고 영구 소진 정책의 전제는 지켜진다.

---

## 정책 확장의 대가

예매 취소 정책을 반영하며 INV-05 에 `USED→CANCELLED` 를, INV-07 에 `used_at` 이
있는 `CANCELLED` 를 허용했다. 그 결과 **"의도치 않게 사용된 쿠폰이 취소된 경우" 는
어떤 규칙도 잡지 못한다.**

이는 규칙이 부족해서가 아니다. 정상 예매 취소와 잘못된 취소가 **DB에 완전히 같은
행을 남기기 때문** 이며, 데이터만으로는 구별할 수 없다.

되찾으려면 정보를 더 넣어야 한다.

| 방법 | 비용 |
|---|---|
| 취소 사유 컬럼 (`booking_cancel` / `airline_bulk`) | 스키마 + 엔티티 + 취소 API 2곳 + 생성기 + 규칙 |
| `bookings` 테이블 연계 | 테이블 미구현 |
| 멱등키 출처 접두어 (`booking-cancel-%`) | 규칙 1개. 취소 API 가 키를 직접 만드는 경우에만 가능 |

**불변식은 상태 머신이 허용하는 만큼만 강할 수 있다.** 정책이 넓어지면 검증은
그만큼 약해진다. 이번 범위에서는 비용 대비 우선순위가 낮다고 판단해 한계로 남긴다.

---

## 주입 SQL (재현용)

각 블록은 **한 번의 세션 안에서** 실행해야 한다.
`SET @변수` 와 `SET FOREIGN_KEY_CHECKS` 는 세션 범위다.

### INV-01 (INV-03 동반 검출)

```sql
SET @s = 1;
SET @issued = (SELECT COUNT(*) FROM coupons WHERE stock_id = @s);
UPDATE campaign_stocks SET total_stock = @issued - 1, remaining_stock = 0
WHERE stock_id = @s;
```

### INV-03

```sql
UPDATE campaign_stocks SET remaining_stock = remaining_stock + 1 WHERE stock_id = 1;
```

### INV-04

생성기 옵션으로 수행. USED 쿠폰 N건의 `status` 만 CANCELLED 로 바꾸고
`cancelled_at = used_at`, `used_at = NULL` 로 정리해 INV-06/07 은 정상으로 둔다.

```
--corrupt=5
```

### INV-05

```sql
SET @c = (SELECT coupon_id FROM coupons WHERE status='ISSUED' ORDER BY coupon_id LIMIT 1);
INSERT INTO coupon_history (coupon_id, from_status, to_status, idempotency_key, event_at)
VALUES (@c, 'EXPIRED', 'USED', 'x-inv05', '2026-01-01 00:00:00.000');
```

`event_at` 을 base-time 이전으로 두는 것이 핵심이다. 최신 이력이 되면
`ROW_NUMBER()` 의 1등이 바뀌어 INV-04 가 함께 터진다.

### INV-06

```sql
SET @c = (SELECT coupon_id FROM coupons WHERE status='USED' ORDER BY coupon_id LIMIT 1);
UPDATE coupons SET used_at = issued_at - INTERVAL 1 HOUR WHERE coupon_id = @c;
```

**이미 USED 인** 쿠폰을 골라 시각만 옮긴다. 상태를 바꾸면 마지막 이력과
어긋나 INV-04 가 함께 터진다.

### INV-07

```sql
SET @c = (SELECT coupon_id FROM coupons WHERE status='ISSUED' ORDER BY coupon_id LIMIT 1);
UPDATE coupons SET used_at = NOW(3) WHERE coupon_id = @c;
```

`NOW(3)` 은 `issued_at` 보다 뒤이므로 INV-06 은 반응하지 않는다.

### INV-08

```sql
SET @c         = (SELECT coupon_id   FROM coupons WHERE status='ISSUED' ORDER BY coupon_id LIMIT 1);
SET @old_stock = (SELECT stock_id    FROM coupons WHERE coupon_id = @c);
SET @old_camp  = (SELECT campaign_id FROM coupons WHERE coupon_id = @c);
SET @new_stock = (SELECT stock_id FROM campaign_stocks
                  WHERE campaign_id <> @old_camp AND remaining_stock > 0
                  ORDER BY stock_id LIMIT 1);

UPDATE coupons         SET stock_id        = @new_stock         WHERE coupon_id = @c;
UPDATE campaign_stocks SET remaining_stock = remaining_stock + 1 WHERE stock_id = @old_stock;
UPDATE campaign_stocks SET remaining_stock = remaining_stock - 1 WHERE stock_id = @new_stock;
```

`campaign_id` 는 `uk_campaign_user` 에 묶여 옮길 수 없어 `stock_id` 쪽을 옮겼다.
양쪽 `remaining_stock` 을 함께 보정해 INV-01/INV-03 을 정상으로 유지한다.

### INV-09

```sql
SET @c = (SELECT coupon_id FROM coupons WHERE status='ISSUED' ORDER BY coupon_id LIMIT 1);
INSERT INTO coupon_history (coupon_id, from_status, to_status, idempotency_key, event_at)
VALUES (@c, NULL, 'ISSUED', 'x-inv09', '2026-01-01 00:00:00.000');
```

### INV-10

```sql
SET FOREIGN_KEY_CHECKS = 0;
INSERT INTO coupon_history (coupon_id, from_status, to_status, idempotency_key, event_at)
VALUES (999999999999, NULL, 'ISSUED', 'x-inv10', NOW(3));
SET FOREIGN_KEY_CHECKS = 1;
```

### INV-11

```sql
SET @c = (SELECT coupon_id FROM coupons ORDER BY coupon_id LIMIT 1);
SET @g = (SELECT campaign_id FROM coupons WHERE coupon_id = @c);
UPDATE coupons
SET issued_at = (SELECT open_at FROM campaigns WHERE campaign_id = @g) - INTERVAL 1 DAY
WHERE coupon_id = @c;
```

### INV-12

```sql
SET @c = (SELECT coupon_id FROM coupons WHERE status='ISSUED' ORDER BY coupon_id LIMIT 1);
UPDATE coupons SET expire_at = expire_at + INTERVAL 1 DAY WHERE coupon_id = @c;
```

만료 시각을 **늘리는** 방향이라 `expired_at < expire_at`(INV-06)에 걸리지 않는다.
ISSUED 쿠폰을 고르는 것도 같은 이유다.

---

## 함께 확인된 것

### 재현성

동일 데이터에 회차를 달리해 두 번 실행한 결과가 규칙별로 완전히 일치했다
(`all-clean-01`, `all-clean-02`). 소요 시간만 ±3% 편차.

### Redis 없이도 검증이 성립한다

Redis 컨테이너를 정지한 상태에서 실행하면 CLOCK-02 가 `Redis 연결 실패 (N/A)` 로
기록되고 위반 0, 나머지 규칙은 정상 수행되며 Job 이 COMPLETED 로 끝난다.

검증 배치는 MySQL 만으로 성립해야 한다. Redis 를 쓰지 않는 V0(NoLock)/V1(비관적 락)
회차에서 없는 문제를 만들면 안 되기 때문이다.

### 만료 배치 이후에도 정합성이 유지된다

만료 배치로 54,579건을 EXPIRED 로 전이시킨 뒤 검증을 실행해 전 규칙 0을 확인했다.
만료 배치가 만든 이력을 INV-04·05·06·09 가 검사한다.

### 시점 고정 방식 전환

`created_at <= @snapshot_at` 필터를 InnoDB MVCC(REPEATABLE READ) 스냅샷으로 바꿨다.
필터 방식은 추가만 되는 테이블에서만 통하고, `remaining_stock` 같은 가변 카운터는
현재값으로 읽혀 스냅샷 이후 발급 한 건에도 INV-03 에 없는 위반이 잡힌다.

부수 효과로 "미래 타임스탬프 때문에 행이 조용히 제외되는" 실패 모드가
구조적으로 사라졌다.

---

## 남은 것

- **INV-01/03/06/08/09/10 의 주입 시험은 생성기 재작성 이전에 수행한 것이다.**
  재작성 후 기준선(14개 규칙 0)은 확인했으나 개별 주입은 다시 돌리지 않았다.
  셸 함수 기준 약 15분이면 재확인할 수 있다.
- 자동화된 회귀 테스트가 없다. 현재 전부 수동 확인이라, 규칙을 잘못 수정해도
  즉시 드러나지 않는다. 위 주입 SQL 을 `@ParameterizedTest` 로 옮기는 것이
  가장 값어치 있는 후속 작업이다.
- 300만 건 규모에서의 검출력·소요 시간은 미측정. INV-04 가 전체의 약 32~39% 를
  차지하므로 확장 전 `EXPLAIN` 확인이 필요하다.

## 결론

- 주입 가능한 11개 규칙을 시험해 **전부 통과**했다(INV-02 는 DB 제약으로 주입 불가)
- 오탐은 INV-01/INV-03 의 구조적 종속 1건뿐이며, 이는 정상이다
- 미탐(주입했는데 못 잡음)은 없었다
- 인공 주입 외에 **실제 정책 변경(예매 취소)이 만든 위반 97건을 검출** 했다
- 이로써 검증 배치는 "항상 0을 반환하는 쿼리" 가 아니라
  **실제로 검사하는 검증기** 임이 확인됐다
