# Kafka Consumer MySQL 통합 테스트 결과

이 테스트는 `coupon-issued` 이벤트의 DB 처리 로직이 쿠폰·발급 이력·재고를
정확히 반영하는지 실제 MySQL 환경에서 검증한다.
성능 수치가 아니라 DB 최종 반영, 중복 이벤트 방어, 트랜잭션 롤백의 코드 회귀 여부를 판정한다.

## 1. 실행 메타데이터

| 항목 | 값 |
| --- | --- |
| gitCommit | `a59f850` |
| baseCommit | `c34d986` |
| environmentId | `LOCAL-NATIVE-JAEMIN-01` |
| runNumber | 1 |
| 실행 일시 | 2026-08-16 17:42:32 (KST) |
| 실행 방법 | STS `Run As → Gradle Test` |
| 동일 CLI 명령 | `./gradlew test --tests "*CouponIssuedEventProcessorIntegrationTest"` |

### 1.1 환경

| 항목 | 값 |
| --- | --- |
| 운영체제 | Windows 11 10.0.26200.9168 |
| JDK | Gradle toolchain language version 17 (STS 실행 환경 Java 21) |
| Gradle Wrapper | 8.14 |
| MySQL | 8.0.45 (Windows 네이티브 서비스 `MySQL80`, Docker 아님) |
| 데이터베이스 | `coupon_db` |
| DB 계정 | `coupon` |
| 트랜잭션 격리 수준 | REPEATABLE-READ |
| HikariCP | maximum-pool-size 10, connection-timeout 3000ms |
| JPA | `ddl-auto: validate` |
| Spring Profile | `test` |
| Redis | 미사용 |
| Kafka | 실제 Broker 미사용, 리스너 비활성 (`coupon.kafka.consumer.enabled=false`) |

`LOCAL-DOCKER-01`이 아니다. Windows 네이티브 MySQL 8.0.45 환경이므로
Docker 기반 테스트 결과와 직접 비교하지 않고 별도 환경 ID로 기록한다.

이번 테스트는 `CouponIssuedEventProcessor.process()`를 직접 호출한다.
따라서 Consumer의 MySQL 트랜잭션 처리 로직은 검증하지만 Kafka Broker의 메시지 전달,
offset 커밋, 재시도 및 DLT 동작은 검증 범위에 포함하지 않는다.

## 2. 실행 요약

| 항목 | 값 |
| --- | --- |
| 실행한 테스트 클래스 | `CouponIssuedEventProcessorIntegrationTest` |
| 전체 테스트 수 | 3 |
| 성공 | 3 |
| 실패 | 0 |
| 오류 | 0 |
| 건너뜀 | 0 |
| 실패 시 첫 번째 원인 | 해당 없음 |
| 테스트 실행 시간 | 총 0.571s |
| Gradle 전체 실행 시간 | 23.768s |

공통 테스트 데이터: 사용자 2명, 캠페인 1건, 재고 풀 1건(JEJU / ECONOMY), 초기 재고 10장.

테스트 실행 전에 최신 `develop`의 `docs/schema.sql`을 적용했고,
`coupon_db`의 7개 테이블이 모두 0건인 것을 확인했다.

## 3. 테스트 데이터 초기화

각 테스트 메서드 실행 전에 `@BeforeEach`가 다음 순서로 데이터를 초기화한다.

```text
coupon_history 삭제
→ coupons 삭제
→ campaign_stocks 삭제
→ campaigns 삭제
→ users 삭제
→ 사용자 2명 생성
→ 캠페인 1건 생성
→ 재고 풀 1건 생성
```

| 항목 | 값 |
| --- | --- |
| userId | 1, 2 |
| campaignId | 1 |
| stockId | 1 |
| routeId | JEJU |
| fareClass | ECONOMY |
| totalStock | 10 |
| remainingStock | 10 |
| 초기 쿠폰 수 | 0 |
| 초기 이력 수 | 0 |

테스트 데이터는 Workbench에서 수동으로 입력하지 않고 테스트 코드가 직접 생성한다.
각 테스트는 이전 테스트의 결과에 의존하지 않는다.

## 4. 정상 이벤트 및 동일 이벤트 재처리

실행한 테스트는 `eventIsPersistedExactlyOnce()`다.

동일한 `couponId`와 `idempotencyKey`를 가진 이벤트를 두 번 처리했다.

| 항목 | 결과 |
| --- | --- |
| 최초 처리 반환값 | `true` |
| 동일 이벤트 재처리 반환값 | `false` |
| 최종 쿠폰 수 | 1 |
| 최종 발급 이력 수 | 1 |
| 쿠폰 상태 | `ISSUED` |
| 발급 이력 | `NULL → ISSUED` |
| 최종 잔여 재고 | 9 |
| 총 재고 차감량 | 1 |
| 테스트 실행 시간 | 0.034s |

### 4.1 판정

정상 이벤트 처리 시 쿠폰과 최초 발급 이력이 각각 1건 저장되고 DB 재고가 1 감소했다.
동일 이벤트를 다시 처리해도 쿠폰·이력·재고가 추가 반영되지 않았다.

동일 이벤트의 중복 소비 방어 조건을 충족했다.

## 5. 동일 캠페인·사용자 중복 이벤트

실행한 테스트는 `sameCampaignAndUserWithDifferentCouponIdIsSkipped()`다.

서로 다른 `couponId`와 `idempotencyKey`를 사용하면서
동일한 `campaignId + userId`를 가진 이벤트 두 건을 처리했다.

| 항목 | 결과 |
| --- | --- |
| 최초 처리 반환값 | `true` |
| 중복 이벤트 처리 반환값 | `false` |
| 최종 쿠폰 수 | 1 |
| 최종 발급 이력 수 | 1 |
| 최종 잔여 재고 | 9 |
| 총 재고 차감량 | 1 |
| 테스트 실행 시간 | 0.501s |

### 5.1 판정

동일 캠페인에서 같은 사용자에게 서로 다른 쿠폰 ID를 가진 이벤트가 전달돼도
쿠폰과 발급 이력은 각각 1건만 유지됐다.
중복 이벤트로 인한 DB 재고 추가 감소도 발생하지 않았다.

캠페인별 1인 1매 중복 방어 조건을 충족했다.

## 6. 재고 차감 실패 시 트랜잭션 롤백

실행한 테스트는 `stockDecreaseFailureRollsBackCouponAndHistory()`다.

재고를 0으로 설정한 뒤 이벤트를 처리해 재고 차감 실패를 발생시켰다.

| 항목 | 결과 |
| --- | --- |
| 발생 예외 | `IllegalStateException` |
| 예외 메시지 검증 | `재고 차감` 포함 |
| 최종 쿠폰 수 | 0 |
| 최종 발급 이력 수 | 0 |
| 최종 잔여 재고 | 0 |
| 테스트 실행 시간 | 0.034s |

### 6.1 판정

재고 차감이 실패하면 같은 트랜잭션에서 먼저 수행된 쿠폰 INSERT와
발급 이력 INSERT가 모두 롤백됐다.
부분 반영 없이 기존 재고 0도 유지됐다.

Consumer DB 처리의 원자성 조건을 충족했다.

## 7. 종합

| 검증 항목 | 판정 | 비고 |
| --- | --- | --- |
| 정상 이벤트 DB 반영 | 통과 | 쿠폰 1건, 이력 1건, 재고 1 감소 |
| 동일 이벤트 중복 방어 | 통과 | 추가 저장 및 재고 감소 없음 |
| 동일 캠페인·사용자 중복 방어 | 통과 | 쿠폰·이력 각 1건 유지 |
| 처리 실패 트랜잭션 롤백 | 통과 | 쿠폰·이력 0건, 재고 0 유지 |

Kafka Consumer의 MySQL 최종 반영, 중복 이벤트 방어 및 트랜잭션 롤백 로직이
이번 통합 테스트 범위에서 정상 동작했다.

## 8. 제외 범위

다음 항목은 이번 테스트에서 실행하지 않았다.

- 실제 Kafka Broker 메시지 소비
- Kafka Consumer 재시도
- DLT 이동
- Kafka offset 커밋
- Consumer lag
- Kafka Producer 및 ACK
- Redis 재고 보상
- 동시성 및 성능 부하 테스트

## 9. 결과 해석 시 주의사항

JUnit 테스트 메서드의 실행 순서는 보장되지 않는다.
전체 클래스 실행 후 DB에 남은 데이터는 마지막으로 실행된 테스트의 결과일 수 있으므로,
Workbench에 최종적으로 남은 행만으로 성공 여부를 판정하지 않는다.

각 테스트 내부의 DB assertion과 Gradle 테스트 리포트를 최종 판정 기준으로 사용한다.
