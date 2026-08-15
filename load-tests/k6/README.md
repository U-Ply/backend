# k6 부하 테스트

`issue-level2.js`는 NoLock, 비관적 락, Redis Lua + MySQL 동기 저장 전략에 공통으로 사용하는 Level 2 발급 스크립트다. 전략은 애플리케이션의 `COUPON_STRATEGY` 설정으로 변경하며 k6 스크립트는 변경하지 않는다.

## 사전 조건

- 애플리케이션과 필요한 인프라가 실행 중이어야 한다.
- 캠페인, 재고 풀과 서로 다른 테스트 사용자들이 DB에 적재되어 있어야 한다.
- `USER_ID_START`부터 `TOTAL_REQUESTS`만큼의 사용자 ID가 연속해서 존재해야 한다.
- Redis 전략은 `stock:{stockId}`와 `issued:{campaignId}`가 공통 초기 상태로 준비되어 있어야 한다.
- 각 실행 전 쿠폰, 이력, DB 재고, Redis 재고와 멱등성 키를 초기화해야 한다.

## 실행 예시

먼저 재고 100장, 요청 200건의 스모크 테스트를 실행한다.

```bash
mkdir -p load-tests/results

k6 run \
  -e BASE_URL=http://localhost:8081 \
  -e TOTAL_REQUESTS=200 \
  -e VUS=20 \
  -e USER_ID_START=1 \
  -e CAMPAIGN_ID=1 \
  -e ROUTE_ID=JEJU \
  -e FARE_CLASS=ECONOMY \
  --summary-export load-tests/results/smoke.json \
  load-tests/k6/issue-level2.js
```

스모크 테스트가 통과한 후 Level 2 테스트를 실행한다.

```bash
k6 run \
  -e BASE_URL=http://localhost:8081 \
  -e TOTAL_REQUESTS=20000 \
  -e VUS=500 \
  -e USER_ID_START=1 \
  -e CAMPAIGN_ID=1 \
  -e ROUTE_ID=JEJU \
  -e FARE_CLASS=ECONOMY \
  --summary-export load-tests/results/pessimistic-vu500-run1.json \
  load-tests/k6/issue-level2.js
```

AWS의 별도 k6 인스턴스에서는 `BASE_URL`에 애플리케이션 EC2의 사설 IP를 지정한다.

```bash
-e BASE_URL=http://10.0.1.10:8081
```

## 환경변수

| 이름 | 기본값 | 설명 |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:8081` | 테스트 대상 애플리케이션 주소 |
| `TOTAL_REQUESTS` | `20000` | 전체 요청 수 |
| `VUS` | `500` | 동시 가상 사용자 수 |
| `USER_ID_START` | `1` | 첫 번째 테스트 사용자 ID |
| `CAMPAIGN_ID` | `1` | 테스트 캠페인 ID |
| `ROUTE_ID` | `JEJU` | 노선 ID |
| `FARE_CLASS` | `ECONOMY` | 좌석 등급 |
| `MAX_DURATION` | `10m` | 테스트 최대 실행 시간 |

## 결과 확인

콘솔과 `--summary-export` 결과에서 다음 항목을 확인한다.

- `coupon_issued`
- `coupon_out_of_stock`
- `coupon_already_issued`
- `coupon_other_4xx`
- `coupon_5xx`
- `coupon_unexpected_response`
- `http_reqs`, `http_req_duration`, `iterations`

k6 결과만으로 정합성을 판정하지 않는다. 실행이 끝난 후 DB 쿠폰 수, 중복 발급 수, DB 잔여 재고와 Redis 잔여 재고를 별도 검증한다.
