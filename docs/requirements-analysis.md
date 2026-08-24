# 대규모 트래픽 선착순 쿠폰 발급 시스템 요구 분석서

## 1. 문서 목적

본 문서는 항공사 특가 이벤트를 배경으로 한 선착순 쿠폰 발급 시스템의 기능, 데이터 정합성, 동시성, 장애 대응, 검증 및 운영 요구사항을 정의한다.

구현과 테스트의 공통 기준을 제공하는 것이 목적이며, 아직 팀 합의가 끝나지 않은 사항은 `미결정 사항`으로 구분한다.

## 2. 프로젝트 개요

항공권 특가 이벤트가 정시에 오픈되면 다수 사용자가 노선과 좌석 등급별 얼리버드 할인 쿠폰을 선착순으로 발급받는다. 발급된 쿠폰은 항공권 예약에 사용할 수 있으며, 공급자가 미사용 쿠폰을 취소하거나 만료 배치가 유효기간이 지난 쿠폰을 만료시킬 수 있다.

시스템은 다음 핵심 문제를 다룬다.

- 재고 10,000장에 20,000명이 동시에 요청해도 초과 발급이 발생하지 않아야 한다.
- 한 캠페인에서 한 사용자는 최대 한 장만 발급받아야 한다.
- 여러 API 서버가 동시에 요청을 처리해도 동일한 결과를 보장해야 한다.
- 발급, 사용, 취소, 만료 요청은 중복 또는 동시 실행되어도 한 번만 반영되어야 한다.
- Redis, Kafka, MySQL 사이에 장애가 발생해도 불일치를 탐지하고 대응할 수 있어야 한다.
- 100만 사용자와 300만 건의 쿠폰 이력을 대상으로 정합성을 전수 검증할 수 있어야 한다.

## 3. 범위

### 3.1 필수 범위

- 가상 사용자, 캠페인, 노선 및 좌석 등급별 재고 생성
- 선착순 쿠폰 발급
- 쿠폰 사용, 취소, 자동 만료
- 쿠폰 및 캠페인 조회
- Redis Lua Script를 이용한 원자적 재고 차감과 중복 발급 방지
- Kafka를 이용한 발급 결과의 비동기 MySQL 반영
- 멱등성 키를 이용한 중복 요청 제어
- Spring Batch 기반 정합성 검증 및 만료 처리
- k6 기반 동시성 및 부하 테스트
- 개인정보 마스킹
- Docker Compose 기반 로컬 실행 환경

### 3.2 선택 범위

- 캠페인 오픈 시작 예약
- 발급 현황 SSE 조회
- 대기열 및 실시간 순번 조회
- 검증 결과 리포트 자동화

## 4. 기술 구성

| 영역 | 기술 |
| --- | --- |
| 언어 및 프레임워크 | Java 17, Spring Boot |
| 데이터베이스 | MySQL 8.0.16 이상, InnoDB |
| 동시성 제어 | Redis, Lua Script |
| 메시지 브로커 | Apache Kafka |
| 배치 | Spring Batch |
| 부하 테스트 | k6 |
| 관측 | Spring Boot Actuator, Micrometer, Prometheus, Grafana |
| 실행 환경 | Docker Compose |
| 식별자 생성 | TSID (`com.github.f4b6a3:tsid-creator`) |

## 5. 핵심 도메인

### 5.1 사용자

- 모든 사용자는 가상 데이터다.
- 100만 명의 사용자 데이터를 적재한다.
- 인증은 구현하지 않으며 API는 `userId`를 요청 식별자로 사용한다.

### 5.2 캠페인

- 캠페인은 하나의 항공권 특가 이벤트를 의미한다.
- `openAt` 이전에는 발급할 수 없다.
- `expireAt`은 해당 캠페인에서 발급된 쿠폰의 유효기간 기준이다.
- 하나의 캠페인은 여러 노선 및 좌석 등급별 재고 풀을 가질 수 있다.

### 5.3 재고 풀

- 재고 풀의 유일성 기준은 `campaignId + routeId + fareClass`다.
- Redis 재고 키는 `stock:{stockId}`다.
- 인기 노선의 핫키 현상을 관찰할 수 있도록 재고 풀별로 Redis 키를 분리한다.
- 발급에 성공한 순간 재고 한 장은 영구 소진된다.
- 이후 쿠폰이 `USED`, `CANCELLED`, `EXPIRED`가 되어도 재고는 복구되지 않는다.

### 5.4 쿠폰

- 쿠폰 ID는 API 서버가 TSID 형식의 `BIGINT`로 생성한다.
- MySQL의 `coupons.coupon_id`에는 `AUTO_INCREMENT`를 사용하지 않는다.
- 생성한 동일 ID를 API 응답, Kafka 이벤트 및 MySQL 기본 키에 사용한다.
- 한 사용자는 동일 캠페인에서 최대 한 장만 발급받을 수 있다.

## 6. 쿠폰 상태 모델

| 상태 | 의미 | 재고 영향 |
| --- | --- | --- |
| `ISSUED` | 발급되어 사용자가 보유 중 | 발급 시 1 감소 |
| `USED` | 항공권 예약에 사용 완료 | 변경 없음 |
| `CANCELLED` | 사용자가 예약을 취소했거나 공급자가 미사용 쿠폰을 회수 | 변경 없음 |
| `EXPIRED` | 미사용 상태로 유효기간 경과 | 변경 없음 |

허용 상태 전이는 다음과 같다.

| 현재 상태 | 가능한 다음 상태 |
| --- | --- |
| 없음 | `ISSUED` |
| `ISSUED` | `USED`, `CANCELLED`, `EXPIRED` |
| `USED` | `CANCELLED` |
| `CANCELLED` | 없음 |
| `EXPIRED` | 없음 |

모든 상태 변경은 다음 원칙을 따른다.

- `CANCELLED`, `EXPIRED`는 최종 상태다.
- 사용은 `ISSUED -> USED`, 사용자 예약 취소는 `USED -> CANCELLED` 조건부 갱신으로 처리한다.
- 공급자의 미사용 쿠폰 회수와 만료는 각각 `ISSUED -> CANCELLED`, `ISSUED -> EXPIRED` 조건부 갱신으로 처리한다.
- 갱신된 행이 한 건인 작업만 성공한 것으로 판단한다.
- 사용은 `expire_at > NOW(3)` 조건을 추가한다.
- DB 서버의 `NOW(3)`를 시간 판정 기준으로 사용한다.
- 만료 배치가 실행되기 전이라도 유효기간이 지났으면 사용을 거부한다.

## 7. 쿠폰 발급 처리

### 7.1 정상 흐름

1. 요청 형식, 멱등성 키 및 캠페인을 검증한다.
2. API 서버가 TSID 기반 `couponId`를 생성한다.
3. `campaignId + routeId + fareClass`로 `stockId`를 조회한다.
4. Redis Lua Script가 오픈 시각, 중복 발급 및 잔여 재고를 확인한다.
5. 발급 가능하면 재고를 1 감소시키고 사용자를 발급 Set에 등록한다.
6. `CouponIssuedEvent`를 Kafka `coupon-issued` 토픽에 발행한다.
7. 브로커 ACK를 동기 확인한 뒤 `200 ISSUED`와 생성된 `couponId`를 반환한다.
8. Kafka Consumer가 쿠폰 저장, 최초 발급 이력 저장 및 `campaign_stocks.remaining_stock` 1 감소를 하나의 MySQL 트랜잭션으로 처리한다.

### 7.2 동시성 보장

Redis Lua Script는 다음 작업을 하나의 원자적 연산으로 수행해야 한다.

- 캠페인 오픈 여부 확인
- `issued:{campaignId}` Set을 이용한 사용자 중복 확인
- `stock:{stockId}` 잔여 재고 확인 및 감소
- 사용자 발급 Set 등록
- 발급 시도 상태 기록

MySQL은 다음 제약을 최종 방어선으로 사용한다.

- `UNIQUE(campaign_id, user_id)`: 캠페인별 1인 1매
- `CHECK(remaining_stock >= 0)`: 음수 재고 방지
- `PRIMARY KEY(coupon_id)`: 중복 이벤트에 의한 쿠폰 중복 저장 방지
- `UNIQUE(coupon_history.idempotency_key)`: 동일 상태 변경 이력의 중복 저장 방지

### 7.3 Redis–Kafka 실패 복구

- Redis 재고 차감 후 Kafka 발행 결과를 동기 확인한다.
- Kafka 발행이 명확히 실패하면 Redis 재고와 발급 Set을 원복하는 멱등한 보상 처리가 필요하다.
- Kafka timeout처럼 발행 결과가 불명확한 경우에는 이벤트가 저장되고 ACK만 유실됐을 가능성이 있으므로 즉시 보상하지 않는다.
- 발행 결과가 불명확한 요청의 상태 확인, 재발행 및 보상 방식은 상세 설계 단계에서 확정한다.
- REC-01은 잔여 Redis–DB 불일치를 탐지하고 알린다. 기본 정책은 자동 보정이 아니라 원인 확인 후 운영 보정이다.

Redis와 Kafka는 하나의 원자적 트랜잭션으로 처리되지 않으므로, 동기 ACK, 멱등 보상 및 사후 대사를 조합해 장애 위험을 관리한다.

### 7.4 발급 직후 DB 반영 전 요청

- Kafka 저장은 비동기이므로 `200 ISSUED` 응답 직후 MySQL에 쿠폰이 없을 수 있다.
- Kafka 비동기 발급은 이벤트 발행 전에 `coupon:pending:{couponId}`를 24시간 TTL로 저장한다.
- Kafka 발행이 명확하게 실패하면 pending 키를 삭제하고, 결과가 불명확하면 유지한다.
- 단건 조회와 사용·취소 요청 시 DB에 없고 pending 키가 있을 때만 100ms 간격으로 최대 3회 DB를 추가 조회한다.
- 3회 추가 조회 후에도 DB에 없으면 `COUPON_NOT_READY`를 반환한다.
- DB와 Redis pending 키에 모두 없으면 `COUPON_NOT_FOUND`를 반환한다.
- Consumer의 MySQL 저장 트랜잭션이 커밋된 후 pending 키를 삭제한다.
- 실제 상태 변경은 MySQL에서 쿠폰이 확인된 이후에만 수행한다.

## 8. 멱등성 요구사항

현재 구조는 Redis TTL 동안 API 응답 멱등성을 보장하고, MySQL 제약으로 성공한 상태 변경의 중복 저장을 방어한다.

### 8.1 키 규격

- 헤더명: `Idempotency-Key`
- 형식: 클라이언트가 생성한 UUID v4
- 적용: 발급, 사용, 취소 API
- 미적용: 모든 조회 API
- 범위: API 종류와 관계없이 시스템 전체에서 유일한 전역 키
- Redis 키: `idempotency:{idempotencyKey}`
- API 종류별 접두사는 사용하지 않는다.

클라이언트는 재시도에는 동일 키를 사용하고, 서로 다른 비즈니스 작업에는 반드시 새 키를 사용해야 한다.

### 8.2 저장 정보

Redis에는 다음 정보를 저장한다.

- `requestHash`
- 처리 상태 `PROCESSING` 또는 `COMPLETED`
- 최초 HTTP 상태 코드
- 최초 응답 본문

`requestHash`는 HTTP Method, 정규화된 URI 및 정규화된 요청 본문을 SHA-256으로 계산한다. `Idempotency-Key` 자체는 해시에 포함하지 않는다.

### 8.3 처리 규칙

- 새로운 키는 Redis `SET NX` 또는 Lua Script로 한 요청만 선점한다.
- 같은 키와 같은 해시가 `COMPLETED`이면 비즈니스 로직을 재실행하지 않고 최초 응답을 반환한다.
- 같은 키와 다른 해시이면 `409 IDEMPOTENCY_KEY_REUSED`를 반환한다.
- 같은 키와 같은 해시가 `PROCESSING`이면 기다리지 않고 즉시 `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`를 반환한다.
- `PROCESSING` 상태는 선점 시점부터 30초의 TTL을 둔다.
- `COMPLETED` 상태는 전환 시점부터 10분의 TTL을 둔다.
- `PROCESSING`이 만료되면 같은 요청이 다시 선점될 수 있으며, 도메인 제약과 DB UNIQUE 제약으로 데이터의 중복 반영을 방어한다.
- TTL 만료 후에는 최초 응답 재현과 키 재사용 충돌 검사를 보장하지 않으며 신규 요청과 동일하게 처리한다.
- 발급 중복은 Redis 발급 Set과 MySQL의 캠페인별 사용자 UNIQUE 제약이 별도로 방어한다.
- Kafka Consumer의 중복 처리 기준은 이벤트의 `idempotencyKey`이며, 쿠폰 PK와 이력 UNIQUE 제약을 함께 사용한다.

`coupon_history.idempotency_key` UNIQUE는 성공한 상태 변경 이력의 중복 저장을 방지하지만, TTL 이후 최초 응답 재현이나 실패 요청의 멱등성을 보장하지 않는다.

## 9. Kafka 요구사항

### 9.1 토픽

- 토픽명: `coupon-issued`
- 파티션 수: 3
- 파티션 키: `couponId`
- DLT: `coupon-issued.DLT`

### 9.2 이벤트 스키마

```json
{
  "couponId": "long",
  "userId": "long",
  "campaignId": "long",
  "stockId": "long",
  "idempotencyKey": "UUID v4",
  "issuedAt": "2026-08-12T01:00:00.000Z"
}
```

### 9.3 신뢰성 설정

- Producer: `acks=all`
- Producer: `enable.idempotence=true`
- Producer 재시도: 3회
- Consumer 실패 시 1초 간격으로 3회 재시도
- 최종 실패 이벤트는 DLT로 격리
- DLT 자동 재처리는 범위 밖이며 운영 문서에 수동 대응 절차만 기록한다.

발급만 Kafka로 비동기 처리하고 사용과 취소는 MySQL 트랜잭션으로 동기 처리한다.

## 10. 기능 요구사항

### FR-01 쿠폰 발급

- `POST /api/coupons/issue`
- `Idempotency-Key`가 필수다.
- 요청은 `userId`, `campaignId`, `routeId`, `fareClass`를 포함한다.
- 오픈 전 요청, 중복 발급 및 재고 소진을 거부한다.
- 성공 시 `couponId`, `ISSUED`, `issuedAt`, `expireAt`을 반환한다.

### FR-02 쿠폰 사용

- `POST /api/coupons/{couponId}/use`
- `ISSUED`이며 DB 현재 시각이 `expireAt` 이전인 경우에만 성공한다.
- 성공 시 `USED`와 `usedAt`을 저장하고 이력을 한 번 기록한다.
- 재고는 변경하지 않는다.

### FR-03 쿠폰 취소

- `POST /api/coupons/{couponId}/cancel`
- 공급자 취소를 의미하며 `ISSUED`이고 `expireAt` 이전인 경우에만 성공한다.
- 성공 시 `CANCELLED`와 `cancelledAt`을 저장하고 이력을 한 번 기록한다.
- Redis 재고와 발급 Set을 변경하지 않는다.

### FR-04 쿠폰 만료

- Spring Batch가 `ISSUED`이며 `expireAt <= NOW(3)`인 쿠폰을 `EXPIRED`로 변경한다.
- 사용 및 취소와 동일한 조건부 갱신 원칙을 사용한다.
- 성공한 만료만 이력에 기록한다.
- 재고는 변경하지 않는다.
- 관리자 시연 API로 수동 실행할 수 있다.

### FR-05 쿠폰 조회

- 쿠폰 단건 조회와 사용자별 쿠폰 목록 조회를 제공한다.
- 응답에는 이메일과 이름을 포함하지 않는다.
- 비동기 저장 중인 쿠폰은 발급 상태 확인 및 제한된 재시도 정책을 적용한다.

### FR-06 캠페인 및 재고 조회

- 캠페인 기본 정보와 노선·좌석 등급별 재고를 조회한다.
- 발급 현황 API의 `remainingStock`은 DB 집계가 아닌 Redis 값을 사용한다.
- SSE는 서버가 주기적으로 Redis를 조회하는 폴링 방식으로 구현한다.

### FR-07 검증 배치

- 관리자 API로 검증 Job을 실행하고 `runId`를 반환한다.
- 검증 결과를 규칙별로 조회할 수 있다.
- 전수 위반 수는 `verification_report`에 기록한다.
- 위반 상세는 규칙별 최대 1,000건을 `verification_violation`에 샘플로 저장한다.
- 동일 `runId + ruleCode`는 한 번만 기록한다.

## 11. 검증 규칙

실제 발급 수는 쿠폰 상태와 관계없이 해당 재고 풀에서 생성된 전체 쿠폰 수다. 발급 이후 재고는 복구되지 않는다.

| 코드 | 규칙 | 판정 기준 | 위반 대상 |
| --- | --- | --- | --- |
| INV-01 | 초과 발급 금지 | 재고 풀별 전체 쿠폰 수 `<= total_stock` | `campaign_stocks` |
| INV-02 | 1인 1매 | 동일 캠페인과 사용자 조합의 쿠폰 수 `<= 1` | `coupons` |
| INV-03 | DB 재고 카운터 | `remaining_stock = total_stock - 전체 쿠폰 수` | `campaign_stocks` |
| INV-04 | 현재 상태와 최종 이력 일치 | 쿠폰 상태가 마지막 이력의 `to_status`와 같고, 이력이 없는 쿠폰이 없음 | `coupons` |
| INV-05 | 상태 전이 유효성 | 허용된 다섯 종류의 전이만 존재 | `coupon_history` |
| INV-06 | 시각 순서 | 상태 변경 시각이 `issued_at`과 같거나 이후이며, `expired_at`은 `expire_at`과 같거나 이후 | `coupons` |
| INV-07 | 종료 상태 타임스탬프 | 현재 상태와 상태 전이 경로에 맞는 종료 시각 조합인지 확인 | `coupons` |
| INV-08 | 참조 조합 일치 | coupons.campaign_id가 stock_id로 조회한 campaign_stocks.campaign_id와 같음 | `coupons` |
| INV-09 | 도메인 멱등성 | 같은 쿠폰의 동일 도착 상태 이력이 두 번 이상 없음 | `coupon_history` |
| INV-10 | 고아 행 없음 | 존재하지 않는 부모 행을 참조하는 자식 행이 없음 | 각 자식 테이블 |

INV-05의 허용 전이는 다음과 같다.

- `NULL -> ISSUED`
- `ISSUED -> USED`
- `ISSUED -> CANCELLED`
- `ISSUED -> EXPIRED`
- `USED -> CANCELLED`

INV-04의 마지막 이력은 `event_at DESC, history_id DESC` 순서로 결정한다. 이력이 없는 쿠폰도 위반으로 처리한다.

INV-06은 타임스탬프가 존재할 때 다음 조건을 검증한다.

- `used_at >= issued_at`
- `cancelled_at >= issued_at`
- `expired_at >= issued_at`
- `expired_at >= expire_at`

INV-07은 현재 상태에 따라 다음 타임스탬프 조합을 검증한다.

- `ISSUED`: `used_at`, `cancelled_at`, `expired_at` 모두 NULL
- `USED`: `used_at`만 NOT NULL
- `CANCELLED`(공급자 회수): `cancelled_at`만 NOT NULL
- `CANCELLED`(사용자 예약 취소): `used_at`, `cancelled_at`이 NOT NULL
- `EXPIRED`: `expired_at`만 NOT NULL

INV-10은 다음 참조 관계의 고아 행을 검사한다.

- `campaign_stocks.campaign_id -> campaigns.campaign_id`
- `coupons.user_id -> users.user_id`
- `coupons.campaign_id -> campaigns.campaign_id`
- `coupons.stock_id -> campaign_stocks.stock_id`
- `coupon_history.coupon_id -> coupons.coupon_id`

### REC-01 Redis–DB 재고 대사

- INV 규칙과 별도 Job으로 실행한다.
- Redis 장애가 다른 검증 규칙의 실행을 막지 않아야 한다.
- Redis `stock:{stockId}`와 MySQL `campaign_stocks.remaining_stock`을 비교한다.
- 불일치를 탐지하고 보고하되 기본 동작은 자동 수정이 아니다.
- Redis를 사용하지 않는 V0(NoLock), V1(비관적 락) 회차는 `N/A`다.
- V3(Redis Lua + Kafka)는 Consumer lag와 DLT가 모두 0인 뒤에만 최종 비교한다. 처리 중에는 Redis가 DB보다 먼저 차감되는 것이 정상일 수 있으므로, 정착 전 실행은 `SKIPPED_NOT_SETTLED`로 종료한다.
- Redis 키 누락 또는 숫자가 아닌 Redis 값도 불일치로 기록한다. `diff = redis_remaining - db_remaining`이며, 샘플은 `campaign_stocks`의 `stock_id`를 대상으로 남긴다.
- 기본은 부하 테스트 종료 후 수동 실행이며, 주기 실행은 발급이 없는 시간대에 `RECONCILIATION_SCHEDULER_ENABLED=true`인 배치 담당 인스턴스 한 대에서만 활성화한다.

## 12. API 오류 정책

공통 오류 응답 형식은 다음과 같다.

```json
{
  "errorCode": "string",
  "message": "string",
  "timestamp": "2026-08-11T01:00:00.000Z"
}
```

| 오류 코드 | HTTP | 발생 조건 |
| --- | ---: | --- |
| `OUT_OF_STOCK` | 409 | 재고 소진 |
| `ALREADY_ISSUED` | 409 | 동일 캠페인 중복 발급 |
| `CAMPAIGN_NOT_OPEN` | 409 | 캠페인 오픈 전 요청 |
| `CAMPAIGN_EXPIRED` | 409 | 만료 시각이 지난 캠페인에 대한 발급 요청 |
| `INVALID_STATE_TRANSITION` | 409 | 허용되지 않은 상태 변경 |
| `IDEMPOTENCY_KEY_REUSED` | 409 | 같은 키가 다른 요청에 사용됨 |
| `IDEMPOTENCY_REQUEST_IN_PROGRESS` | 409 | 동일 키의 최초 요청 처리 중 |
| `COUPON_NOT_READY` | 409 | 발급 이벤트의 MySQL 반영 대기 중 |
| `COUPON_NOT_FOUND` | 404 | 존재하지 않는 쿠폰 |
| `USER_NOT_FOUND` | 404 | 존재하지 않는 사용자 |
| `CAMPAIGN_NOT_FOUND` | 404 | 존재하지 않는 캠페인 또는 재고 풀 |
| `LOCK_TIMEOUT` | 503 | 비관적 락 대기 시간 초과 (재시도 가능) |
| `CONCURRENCY_CONFLICT` | 503 | DB 교착 등 동시성 경합으로 처리 실패 (재시도 가능) |
| `CONNECTION_UNAVAILABLE` | 503 | DB 커넥션 획득 실패 (재시도 가능) |

503으로 응답하는 세 코드는 **재시도 가능한 일시적 실패**다. 요청 자체는 유효하며 서버 상태가
회복되면 같은 요청이 성공할 수 있다. 4xx와 달리 클라이언트가 요청을 고칠 필요가 없다.

세 코드는 발생 지점이 서로 다르다. `LOCK_TIMEOUT`은 `SELECT ... FOR UPDATE`로 락을 기다리다
한계를 넘긴 경우, `CONCURRENCY_CONFLICT`는 트랜잭션 커밋 단계의 DB 교착,
`CONNECTION_UNAVAILABLE`은 트랜잭션 시작 단계에서 커넥션 풀을 얻지 못한 경우다.
자세한 내용은 `docs/lock-and-transaction-settings.md` 6절에 있다.

## 13. Redis 키

| 용도 | 키 | 자료형 | TTL |
| --- | --- | --- | --- |
| 재고 | `stock:{stockId}` | String 정수 | 없음 |
| 캠페인별 발급 사용자 | `issued:{campaignId}` | Set | 없음 |
| API 멱등성 | `idempotency:{idempotencyKey}` | String JSON | PROCESSING 30초, COMPLETED 10분 |
| 쿠폰 DB 반영 대기 | `coupon:pending:{couponId}` | String (`PENDING`) | 24시간 |
| 대기열 | `waiting:{campaignId}` | Sorted Set | 없음 |

## 14. 개인정보 및 로그 요구사항

- API 응답에는 이메일과 이름을 포함하지 않는다.
- 이름은 로그에 기록하지 않는다.
- 이메일은 로컬 파트 앞 세 글자만 남겨 마스킹한다.
- 예: `kim***@example.com`
- 요청 본문, Kafka 이벤트, 오류 로그 및 관측 태그에 개인정보 원문이 노출되지 않아야 한다.
- 예외 스택과 SQL 바인딩 로그에서도 개인정보가 출력되지 않도록 설정한다.

## 15. 비기능 요구사항

### 15.1 정합성

- 재고 10,000장에 20,000명이 동시에 요청해도 정확히 10,000건만 발급한다.
- 초과 발급은 0건이어야 한다.
- 캠페인별 사용자 중복 발급은 0건이어야 한다.
- 중복 상태 변경 이력은 0건이어야 한다.
- Redis와 MySQL의 불일치를 탐지할 수 있어야 한다.

### 15.2 확장성

- API 서버를 두 대 이상 실행할 수 있어야 한다.
- 애플리케이션 로컬 락에 의존하지 않아야 한다.
- 노선 및 좌석 등급별 재고가 독립적으로 처리되어야 한다.

### 15.3 관측성

다음 항목을 Prometheus가 수집하고 Grafana에서 조회할 수 있어야 한다.

- API TPS, 상태 코드 및 p95/p99 지연 시간
- Tomcat 요청 스레드 사용량
- HikariCP 활성, 유휴 및 대기 커넥션
- Redis 명령 처리 시간 및 오류
- Kafka Producer 성공·실패, Consumer 처리량 및 lag
- MySQL 연결 및 쿼리 관련 지표
- 발급 성공, 재고 소진, 중복 발급 및 보상 발생 건수

### 15.4 재현성

- Docker Compose로 MySQL, Redis, Kafka, Prometheus 및 Grafana를 실행할 수 있어야 한다.
- 동일한 초기 데이터와 테스트 조건으로 부하 테스트를 반복할 수 있어야 한다.
- 동일한 DB 스냅샷을 대상으로 한 검증은 동일한 결과를 반환해야 한다.

## 16. 더미 데이터 요구사항

- 사용자 100만 건을 생성한다.
- 쿠폰 이력 300만 건을 생성한다.
- 모든 사용자와 개인정보는 가상 데이터다.
- 검증 규칙의 정상 및 위반 사례를 재현할 수 있는 별도 시드 구성을 제공한다.
- 대량 데이터 생성 및 적재 시간은 평가 대상이 아니다.

## 17. 부하 테스트 시나리오와 인수 기준

### LT-01 기본 선착순 발급

- 단일 재고 풀 10,000장
- 20,000 VU, 사용자당 한 번 요청
- 성공 10,000건
- `OUT_OF_STOCK` 10,000건
- 5xx 0건
- Kafka 반영 완료 후 MySQL 쿠폰 10,000건
- 초과 발급 및 중복 발급 0건

비관적 락과 Redis Lua 전략에 동일 조건을 적용해 결과와 성능을 비교한다.

### LT-02 노선별 핫키

- 제주 90%, 후쿠오카 7%, 방콕 3% 비율로 20,000 VU를 분배한다.
- 노선별 TPS, p95/p99 지연 및 Redis 처리 시간을 비교한다.

### LT-03 좌석 등급별 경합

- 이코노미 8,000장에 16,000명 요청
- 비즈니스 2,000장에 4,000명 요청
- 각 풀은 정확히 재고만큼 발급되고 서로의 재고에 영향을 주지 않아야 한다.

### LT-04 멀티 인스턴스 비교

- 앱 한 대와 안전한 전략
- 앱 두 대와 의도적으로 안전하지 않은 로컬 락 전략
- 앱 두 대와 Redis Lua 전략

동일 부하에서 초과 발급 여부를 비교한다.

### LT-05 서버 및 커넥션 풀 튜닝

- 기본 HikariCP 및 Tomcat 설정과 튜닝 설정을 동일 조건에서 비교한다.
- TPS, p95/p99 지연 및 커넥션 대기 시간을 기록한다.

### LT-06 멱등성 재시도

- 100개 쿠폰 각각에 같은 `couponId + Idempotency-Key`로 사용 요청을 5회 수행한다.
- 각 쿠폰의 `USED` 이력은 정확히 한 건이어야 한다.
- 같은 키와 다른 요청을 보내면 `IDEMPOTENCY_KEY_REUSED`가 반환되어야 한다.

## 18. 패키지 구성 원칙

```text
com.uply.coupon
├── campaign       캠페인 및 재고 풀
├── coupon         쿠폰, 상태 이력 및 발급 전략
├── messaging      Kafka Producer, Consumer 및 신뢰성 설정
├── operation
│   ├── verification
│   ├── expiration
│   └── reconciliation
└── common         예외, 멱등성, Redis 및 공통 설정
```

- 캠페인과 쿠폰을 별도 Aggregate로 관리한다.
- 발급 동시성 전략은 `CouponIssueStrategy` 인터페이스로 교체 가능하게 한다.
- `NoLock`, `PessimisticLock`, `LuaScript` 전략을 동일 API에서 비교할 수 있게 한다.
- 검증, 만료 및 Redis 대사는 운영 기능으로 분리한다.

## 19. 미결정 사항

### 19.1 검증 스냅샷 구현

검증 회차 내 모든 규칙이 같은 데이터를 읽어야 한다는 요구는 확정됐지만 Spring Batch에서 이를 구현하는 구체적인 트랜잭션 경계는 추후 결정한다.

검토 대상은 다음과 같다.

- 모든 INV 조회를 하나의 읽기 전용 `REPEATABLE READ` 트랜잭션에서 실행
- 검출과 보고서 저장 트랜잭션 분리
- 장시간 트랜잭션이 운영 트래픽에 미치는 영향
- `snapshot_at`을 실행 시작 메타데이터로만 사용할지 여부

### 19.2 영구 멱등성

현재 API 응답 멱등성 보장 기간은 Redis TTL 10분이다. TTL 이후에도 최초 응답 재현과 키 충돌 판별을 보장하려면 별도의 영속 멱등성 저장소 도입을 검토한다.

### 19.3 UNKNOWN 발급 복구 실행 주체

Kafka 발행 결과가 불명확한 요청을 누가, 얼마나 자주, 어떤 종료 기준으로 복구할지 결정해야 한다.

- 스케줄 배치
- 별도 복구 Consumer
- 운영자 수동 실행

## 20. 완료 조건

다음 조건을 모두 만족하면 필수 범위가 완료된 것으로 본다.

- 주요 API와 상태 전이가 요구사항대로 동작한다.
- LT-01에서 성공 10,000건, 재고 소진 10,000건, 5xx 0건을 충족한다.
- 멀티 인스턴스 Lua 전략에서 초과 발급과 1인 중복 발급이 0건이다.
- Kafka 중복 전달에도 쿠폰과 발급 이력이 한 번만 저장된다.
- 사용, 취소 및 만료의 동시 실행에서 하나의 최종 상태만 반영된다.
- 사용자 100만 건 및 쿠폰 이력 300만 건이 적재된다.
- INV 및 REC 검증을 실행하고 결과를 조회할 수 있다.
- 개인정보가 응답과 로그에 노출되지 않는다.
- 부하 테스트 결과와 주요 Grafana 지표를 근거 자료로 제시할 수 있다.
