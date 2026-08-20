# Level 2 테스트 결과

## 1. 실행 정보

| 항목 | 값 |
| --- | --- |
| 실행 일시 | YYYY-MM-DD HH:mm:ss KST |
| 실행자 |  |
| Git commit SHA |  |
| 환경 ID | AWS-EC2-01 |
| 전략 | NO_LOCK / PESSIMISTIC_LOCK / LUA_SCRIPT_SYNC_DB / LUA_SCRIPT_KAFKA |
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
| 동시성 경합 (`coupon_concurrency_conflict`) |  |
| 커넥션 획득 실패 (`coupon_connection_unavailable`) |  |
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
+ CONCURRENCY_CONFLICT
+ CONNECTION_UNAVAILABLE
+ 기타 4xx
+ 기타 5xx
+ 예상하지 못한 응답
```

`CONCURRENCY_CONFLICT`는 V0(NoLock)에서 발생하는 것이 정상이다. V1~V3에서는 0건이어야 한다.

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
| Kafka 종료 시 Consumer lag | N/A 또는 0 |  |  |
| Kafka DLT | N/A 또는 0 |  |  |
| Kafka DB 최종 반영 시간 | N/A |  |  |
| verificationJob 상태 | COMPLETED |  |  |
| INV-01~12 실패 규칙 수 | 0 |  |  |
| CLOCK-01 | PASS |  |  |
| CLOCK-02 | PASS 또는 N/A |  |  |
| REC-01 불일치 | N/A 또는 0 |  |  |

검증 명령:

```bash
docker exec -i coupon-mysql mysql -uroot -proot1234 < load-tests/sql/verify-level2.sql
docker exec coupon-redis redis-cli GET stock:1
docker exec coupon-redis redis-cli SCARD issued:1
```

검증 배치 기록:

| 항목 | 값 |
| --- | --- |
| verification runId |  |
| verification jobExecutionId |  |
| reconcile jobExecutionId |  |
| 검증 결과 조회 또는 캡처 링크 |  |

## 5. 관측 지표

| 영역 | 항목 | 평균 | 최대 | 안정성 판단 또는 Grafana 캡처 링크 |
| --- | --- | ---: | ---: | --- |
| MySQL | DB CPU 사용률 |  |  |  |
| MySQL | DB 메모리 사용량·사용률 |  |  |  |
| MySQL | lock wait |  |  |  |
| MySQL | 활성 커넥션 |  |  |  |
| 애플리케이션 | JVM 프로세스 CPU |  |  |  |
| 애플리케이션 | JVM heap·non-heap |  |  |  |
| 애플리케이션 | HikariCP pending |  |  |  |
| EC2 호스트 | CPU 사용률 |  |  |  |
| EC2 호스트 | 메모리 사용량·사용률 |  |  |  |
| EC2 호스트 | swap·OOM·재시작 |  |  | 0건 여부 |
| Redis | CPU·메모리 |  |  |  |
| Redis | 명령 지연 |  |  |  |
| Kafka | CPU·메모리 |  |  |  |
| Kafka | 최대 Consumer lag |  |  |  |

호스트 CPU·메모리는 애플리케이션 프로세스 지표와 구분한다. CPU가 순간적으로 높아진 것만으로 실패 처리하지 않고, 테스트 구간 내 지속 포화 여부와 응답 지연을 함께 판단한다. OOM, swap 급증 및 컨테이너·프로세스 재시작은 0건이어야 한다.

## 6. V3 Kafka 정착 결과

| 항목 | 값 |
| --- | ---: |
| k6 종료 시각 |  |
| 최대 Consumer lag |  |
| Consumer lag 0 도달 시각 |  |
| DB 쿠폰 10,000건 도달 시각 |  |
| k6 종료 후 DB 최종 반영 소요 시간 |  |
| DLT 메시지 수 | 0 |
| REC-01 결과 | PASS / FAIL / N/A |

## 7. 판정 및 특이사항

- 최종 판정: PASS / FAIL
- 실패한 조건:
- 병목으로 추정되는 구간:
- 실행 중 발생한 오류:
- 이전 회차와 다른 점:
- 후속 조치:
