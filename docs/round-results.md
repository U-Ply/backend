# 소규모 실전 회차 결과 (L1)

검증 배치가 픽스처가 아니라 **실제 발급 경로가 만든 데이터**를 검사한 첫 기록.
어제까지의 검출력 시험(주입한 위반을 잡는가)과 달리, 이 회차는 남의 코드가 만든
산출물이 12개 불변식을 지키는가를 본다.

- 환경 ID: `LOCAL-DOCKER-01`
- gitCommit: `bd7d513`
- 일시: 2026-08-21 01:2x ~ 01:4x (UTC)
- 공통 조건: 캠페인 31 / 재고풀 301 (JEJU·ECONOMY) / 초기 재고 30 / 요청 100 / 사용자 100명 고유
- 회차마다 쿠폰·이력 삭제 후 `remaining_stock=30` 으로 되돌리고 시작

---

## L1-BASE-01 — 기준선 (회차 전 더미데이터)

회차 결과에서 이 값을 빼야 코드 결함만 남는다. 검증 배치는 캠페인별로 거르지 않고
테이블 전체를 훑기 때문에, 더미데이터의 위반이 회차 결과에 섞여 들어온다.

| 항목 | 값 |
| --- | ---: |
| coupons | 99,900 |
| coupon_history | 127,605 |
| campaign_stocks | 300 |
| INV-01~12 위반 | 0 |
| CLOCK-01 | 통과 (drift 0.002s) |
| CLOCK-02 | N/A |
| 총 소요 | 약 1.2초 |

규칙별 최대 비용: INV-04 409ms, INV-09 267ms. 300만 건 적재 시 이 둘이 먼저 늘어난다.

---

## 회차별 결과

| 항목 | V1 (PESSIMISTIC_LOCK + sync-db) | V2 (LUA_SCRIPT + sync-db) | V3 (LUA_SCRIPT + kafka) |
| --- | ---: | ---: | ---: |
| runId | L1-V1-01 | L1-V2-01 | L1-V3-01 |
| 200 OK | 30 | 30 | 30 |
| 409 OUT_OF_STOCK | 70 | 70 | 70 |
| 409 ALREADY_ISSUED | 0 | 0 | 0 |
| 5xx | 0 | 0 | 0 |
| 응답 합계 | 100 | 100 | 100 |
| DB 쿠폰 | 30 | 30 | 30 |
| DB 이력 | 30 | 30 | 30 |
| DB remaining_stock | 0 | 0 | 0 |
| Redis stock:301 | N/A | 0 | 0 |
| Redis issued:31 | N/A | 30 | 30 |
| Kafka 발행 | N/A | N/A | 30 (offset 14+8+8) |
| 최종 Consumer lag | N/A | N/A | 0 |
| DLT | N/A | N/A | 0 (토픽 미생성) |
| INV-01~12 위반 | 0 | 0 | 0 |
| CLOCK-01 | 통과 (0.000s) | 통과 (0.000s) | 통과 (0.000s) |
| CLOCK-02 | N/A | N/A | N/A |
| REC-01 | N/A | COMPLETED | COMPLETED |

### 검사 범위 확인

"위반 0건"이 의미를 가지려면 검사 대상이 실제로 있었어야 한다. BASE 대비 증가분:

| 규칙 | BASE | 회차 | 증가 |
| --- | ---: | ---: | ---: |
| INV-02/04/06/07/08/11/12 | 99,900 | 99,930 | +30 |
| INV-05/09 | 127,605 | 127,635 | +30 |
| INV-01/03 | 300 | 301 | +1 |

회차가 만든 쿠폰 30건·이력 30건·재고풀 1개가 검사 범위 안에 들어갔음을 확인했다.
검사 범위가 늘지 않았다면 "0건"은 아무것도 보지 않았다는 뜻이므로 함께 기록한다.

---

## 발견 사항

### 1. V0/V1이 Redis에 의존한다 — 실험 통제가 깨진다

V1 첫 시도에서 100건 전부 `404 CAMPAIGN_NOT_FOUND` 로 실패했다.

```
{"errorCode":"CAMPAIGN_NOT_FOUND","message":"Campaign stock not found: campaignId=31, stockId=31"}
```

`CouponServiceImpl` 이 **전략을 고르기 전에** Redis를 읽는다.

```java
Instant openAt = campaignCacheRepository.getOpenAt(request.campaignId());
Instant expireAt = campaignCacheRepository.getExpireAt(request.campaignId());
...
CouponIssueStrategy issueStrategy = strategySelector.current();   // 그 뒤
```

`CampaignCacheRepository` 구현체는 `RedisCampaignCacheRepository` 하나뿐이라
전략과 무관하게 Redis 왕복 2회가 요청 경로에 들어간다.
`campaign:31:openAt` · `campaign:31:expireAt` 를 수동 주입한 뒤에야 회차가 진행됐다.

test-plan §4 는 V0/V1 을 "MySQL 동기"로, §11 비교표는 Redis 항목을 N/A 로 규정한다.
현재 구현은 이와 어긋나며, 기준선에 이미 Redis가 포함되므로
"Redis 도입 효과"를 재는 Level 2 실험의 통제가 성립하지 않는다.

**제안**: `StockIdLookupSelector` 와 같은 방식으로 전략별 조회처를 분리한다.
DB 구현체(`DbCampaignCacheRepository`)를 추가하고 selector 를 두면,
전략 선택을 시각 조회보다 앞으로 옮기는 것만으로 해결된다.

### 2. lag 게이트는 이 규모에서 재현되지 않는다

V3 회차 직후(`L1-V3-00-LAGGY`) 고의로 lag 을 남긴 채 검증을 돌려
INV-03 오검출을 재현하려 했으나, 컨슈머가 2초 안에 100건을 모두 소화해
`checked_rows` 가 이미 99,930 이었고 위반은 0 이었다.

즉 **100건 규모에서는 lag 창이 너무 좁아 게이트가 무의미하다.**
test-plan §14.4 의 lag=0 선행 조건은 Level 2/3 규모에서만 실제로 작동한다.
게이트 자체를 검증하려면 부하 테스트 중에 확인해야 한다.

### 3. REC-01 이 검증 회차와 이어지지 않는다

REC-01 결과는 `verification_report` 에 `rule_code='REC-01'` 로 정상 저장된다.
다만 reconcile 배치를 runId 없이 호출하면 서버가 `20260821-013046782` 같은 값을
자동 생성하므로, 같은 회차의 INV 결과와 다른 run_id 로 흩어진다.

`uk_run_rule (run_id, rule_code)` 는 규칙 코드가 달라 충돌하지 않으므로,
**검증과 같은 runId 로 실행하면 한 리포트에 합쳐진다.**

    curl -X POST ".../batch/verification?runId=L1-V3-01&round=V3"
    curl -X POST ".../batch/reconcile?runId=L1-V3-01"

앞으로 회차 실행은 이 방식으로 통일한다.

### 4. REC-01은 전체 재고풀을 대조한다

첫 실행이 `MISMATCH` 로 실패했다.

```
REC-01 Redis-DB 재고 불일치 — checkedStocks=301, mismatches=300
```

회차용 풀 301 은 일치했으나, 더미 풀 300개는 Redis 키가 없어 전부 불일치로 잡혔다.
소규모 회차에서 REC-01 을 의미 있게 돌리려면 전체 풀을 먼저 웜업해야 한다.
`checkedStocks` 를 남기고 있어 무엇을 검사했는지는 드러난다 — 이 부분은 잘 되어 있다.

### 5. 캐시 웜업을 호출하는 운영 코드가 없다

`CampaignCacheWarmupService.warmupCampaign` 은 테스트에서만 호출된다.
현재 시스템은 셸 스크립트를 사람이 손으로 돌려야만 Lua 경로 발급이 가능하다.
`scripts/load-test/initialize-level2-redis.sh` 는 `campaign_id=1 / stock_id=1` 로
하드코딩되어 있고 `FLUSHDB` 까지 수행하므로 다른 캠페인에는 쓸 수 없다.

관리자 API 에 웜업 엔드포인트를 추가하는 것이 적절하다. (담당: 3번)

---

## 확인된 것

- **INV-12 (만료 시각 캠페인 상속)** — Lua 경로가 `expire_at` 을 캠페인 값 그대로 전달한다.
  `CampaignCacheWarmupService` 가 epoch millis 로 캐싱하고 `LuaScriptIssueStrategy.getExpireAt`
  이 되읽는데, 스키마가 `DATETIME(3)` 이라 왕복이 무손실이다. 양쪽 다 `ZoneOffset.UTC` 로
  고정되어 있어 JVM 시계에 걸리지 않는다. 단건 응답에서도 확인:
  `expireAt=2026-08-28T01:00:52.345Z` = DB `expire_at`.
- **CLOCK-01** — 세 회차 모두 `session_tz=+00:00`, drift 0.000~0.001s.
  8/18 시점의 9시간 어긋남이 완전히 해소됐다.
- **V3 메시지 유실 없음** — 파티션별 오프셋 14+8+8 = 30 으로 성공 응답 수와 일치.

## 검증하지 못한 것

- **`DataIntegrityViolationException` → `DB_SAVE_FAILED` 변경의 영향**.
  회차마다 새 캠페인에 고유 사용자를 썼기 때문에 `uk_campaign_user` 에 걸리는 경로가
  한 번도 실행되지 않았다. 이 변경으로 중복 발급 시도가 409 대신 500 이 되는지는
  아직 실측되지 않았다. Redis `issued` Set 과 DB 가 어긋난 상태를 만들어야 확인 가능하다.
- **CLOCK-02** — 세 회차 모두 N/A. Redis 시계로 `issued_at` 을 기록하는 경로가 아직 없다.
