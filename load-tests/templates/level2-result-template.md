# Level 2 테스트 결과

## 1. 실행 정보

| 항목 | 값 |
| --- | --- |
| 실행 일시 | YYYY-MM-DD HH:mm:ss KST |
| 실행자 |  |
| Git commit SHA |  |
| 환경 ID | AWS-EC2-01 |
| 전략 | NO_LOCK / PESSIMISTIC_LOCK / LUA_SCRIPT_SYNC_DB |
| 실행 회차 | 1 / 2 / 3 |
| 애플리케이션 인스턴스 수 | 1 |
| 대상 EC2 사양 |  |
| k6 EC2 사양 |  |
| JVM 옵션 |  |
| HikariCP maximumPoolSize |  |
| Tomcat max threads |  |

## 2. 부하 조건

| 항목 | 값 |
| --- | ---: |
| 전체 사용자 | 20,000 |
| 전체 요청 | 20,000 |
| VU | 500 / 1,000 / 5,000 |
| 초기 재고 | 10,000 |
| campaignId | 1 |
| stockId | 1 |
| routeId | JEJU |
| fareClass | ECONOMY |

## 3. k6 결과

| 항목 | 결과 |
| --- | ---: |
| 전체 요청 (`http_reqs`) |  |
| 성공 (`coupon_issued`) |  |
| 재고 소진 (`coupon_out_of_stock`) |  |
| 중복 발급 (`coupon_already_issued`) |  |
| 락 대기 시간 초과 (`coupon_lock_timeout`) |  |
| 기타 4xx (`coupon_other_4xx`) |  |
| 기타 5xx (`coupon_5xx`) |  |
| 예상하지 못한 응답 (`coupon_unexpected_response`) |  |
| TPS |  |
| 평균 응답 시간 |  |
| p95 |  |
| p99 |  |
| 최대 응답 시간 |  |

응답 건수 검산:

```text
전체 요청
= 성공
+ 재고 소진
+ 중복 발급
+ LOCK_TIMEOUT
+ 기타 4xx
+ 기타 5xx
+ 예상하지 못한 응답
```

원본 요약 JSON:

```text
load-tests/results/<strategy>-vu<vus>-run<run>.json
```

## 4. 최종 정합성

| 판정 항목 | 기대값 | 실제값 | 통과 여부 |
| --- | ---: | ---: | --- |
| DB 쿠폰 수 | 10,000 |  |  |
| DB 잔여 재고 | 0 |  |  |
| 초과 발급 재고 풀 | 0 |  |  |
| 중복 발급 사용자 | 0 |  |  |
| `LOCK_TIMEOUT` | 0 |  |  |
| DB 재고 차이 (`stock_diff`) | 0 |  |  |
| Redis 잔여 재고 | N/A 또는 0 |  |  |
| Redis 발급 사용자 수 | N/A 또는 10,000 |  |  |

검증 명령:

```bash
docker exec -i coupon-mysql mysql -uroot -proot1234 < load-tests/sql/verify-level2.sql
docker exec coupon-redis redis-cli GET stock:1
docker exec coupon-redis redis-cli SCARD issued:1
```

## 5. 관측 지표

| 항목 | 결과 또는 Grafana 캡처 링크 |
| --- | --- |
| DB lock wait |  |
| HikariCP pending |  |
| MySQL CPU·커넥션 |  |
| Redis latency |  |
| 애플리케이션 CPU·메모리 |  |

## 6. 판정 및 특이사항

- 최종 판정: PASS / FAIL
- 실패한 조건:
- 병목으로 추정되는 구간:
- 실행 중 발생한 오류:
- 이전 회차와 다른 점:
- 후속 조치:
