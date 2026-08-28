import exec from 'k6/execution';
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const TOTAL_REQUESTS = numberEnv('TOTAL_REQUESTS', 20000);
const VUS = numberEnv('VUS', 500);
const USER_ID_START = numberEnv('USER_ID_START', 1);
const CAMPAIGN_ID = numberEnv('CAMPAIGN_ID', 1);
const ROUTE_ID = __ENV.ROUTE_ID || 'JEJU';
const FARE_CLASS = __ENV.FARE_CLASS || 'ECONOMY';
const MAX_DURATION = __ENV.MAX_DURATION || '10m';
const TEST_STRATEGY = strategyEnv('TEST_STRATEGY');

// 게이트(레이트 리미터)가 켜졌을 때(coupon.gate.enabled=true) 429는 "아직 처리 안 됨"이다.
// 컨트롤러에 닿지 않았으므로 같은 Idempotency-Key로 백오프 후 재시도하고,
// 최종(200/409/503 등) 응답만 결과로 분류한다. 게이트가 꺼져 있으면 429가 없어
// 첫 요청이 곧 최종 응답이 된다.
const MAX_GATE_RETRIES = numberEnv('MAX_GATE_RETRIES', 50);
const GATE_BACKOFF_MS = numberEnv('GATE_BACKOFF_MS', 200);

const issued = new Counter('coupon_issued');
const outOfStock = new Counter('coupon_out_of_stock');
const alreadyIssued = new Counter('coupon_already_issued');
const campaignNotOpen = new Counter('coupon_campaign_not_open');
const campaignExpired = new Counter('coupon_campaign_expired');
const clientErrors = new Counter('coupon_other_4xx');
const lockTimeout = new Counter('coupon_lock_timeout');
const concurrencyConflict = new Counter('coupon_concurrency_conflict');
const connectionUnavailable = new Counter('coupon_connection_unavailable');
const serverErrors = new Counter('coupon_5xx');
const unexpectedResponses = new Counter('coupon_unexpected_response');
// 게이트가 되돌린 429의 총합(재시도 유발 수). 게이트가 스파이크를 얼마나 눌렀는지의 참고 지표.
const gateRejected = new Counter('coupon_gate_rejected');
// 재시도 예산을 소진하고도 429 — 게이트 capacity/refill이 (제안 부하 + 재시도)에 비해 낮다는 신호.
const gateRetryExhausted = new Counter('coupon_gate_retry_exhausted');

// 재고 소진에 따른 409, 게이트의 429는 모두 예상된 응답이므로 k6의 http_req_failed에서 제외한다.
http.setResponseCallback(http.expectedStatuses(200, 409, 429));

const thresholds = {
  checks: ['rate>0.99'],
  coupon_lock_timeout: ['count==0'],
  coupon_connection_unavailable: ['count==0'],
  coupon_5xx: ['count==0'],
  coupon_other_4xx: ['count==0'],
  coupon_unexpected_response: ['count==0'],
  // 게이트를 켜도 재시도로 최종 성공/409에 도달해야 한다. 소진이 생기면 게이트 튜닝 실패.
  coupon_gate_retry_exhausted: ['count==0'],
};

// V0은 동시성 제어가 없는 기준선이므로 CONCURRENCY_CONFLICT를 실패 판정에서 제외하고
// 발생량만 기록한다. V1~V3에서는 한 건이라도 발생하면 해당 실행을 실패로 판정한다.
if (TEST_STRATEGY !== 'V0') {
  thresholds.coupon_concurrency_conflict = ['count==0'];
}

export const options = {
  scenarios: {
    issue_coupons: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: TOTAL_REQUESTS,
      maxDuration: MAX_DURATION,
    },
  },
  thresholds,
};

export default function () {
  // shared-iterations 전체에서 유일한 순번이므로 사용자 ID가 VU 사이에서 겹치지 않는다.
  const sequence = exec.scenario.iterationInTest;
  const userId = USER_ID_START + sequence;
  const idempotencyKey = uuidV4FromSequence(sequence + 1);

  const payload = JSON.stringify({
    userId,
    campaignId: CAMPAIGN_ID,
    routeId: ROUTE_ID,
    fareClass: FARE_CLASS,
  });

  const requestParams = {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
    tags: {
      api: 'coupon-issue',
    },
  };

  let response;
  let gateAttempts = 0;
  do {
    response = http.post(`${BASE_URL}/api/coupons/issue`, payload, requestParams);
    if (response.status !== 429) {
      break;
    }
    gateRejected.add(1);
    gateAttempts += 1;
    // 지터를 섞은 백오프. 모든 VU가 같은 간격으로 재시도해 다음 틱에 또 뭉치는 것을 막는다.
    sleep((GATE_BACKOFF_MS + Math.random() * GATE_BACKOFF_MS) / 1000);
  } while (gateAttempts < MAX_GATE_RETRIES);

  if (response.status === 429) {
    gateRetryExhausted.add(1);
  }

  const body = parseJson(response.body);

  if (response.status === 200 && body?.status === 'ISSUED' && body?.couponId) {
    issued.add(1);
  } else if (response.status === 409 && body?.errorCode === 'OUT_OF_STOCK') {
    outOfStock.add(1);
  } else if (response.status === 409 && body?.errorCode === 'ALREADY_ISSUED') {
    alreadyIssued.add(1);
  } else if (response.status === 409 && body?.errorCode === 'CAMPAIGN_NOT_OPEN') {
    campaignNotOpen.add(1);
  } else if (response.status === 409 && body?.errorCode === 'CAMPAIGN_EXPIRED') {
    campaignExpired.add(1);
  } else if (response.status === 503 && body?.errorCode === 'LOCK_TIMEOUT') {
    lockTimeout.add(1);
  } else if (response.status === 503 && body?.errorCode === 'CONCURRENCY_CONFLICT') {
    concurrencyConflict.add(1);
  } else if (response.status === 503 && body?.errorCode === 'CONNECTION_UNAVAILABLE') {
    connectionUnavailable.add(1);
  } else if (response.status >= 500) {
    serverErrors.add(1);
  } else if (response.status === 429) {
    // 재시도 예산 소진 후에도 429 — gateRetryExhausted가 이미 셌다. 4xx로 중복 집계하지 않는다.
  } else if (response.status >= 400 && response.status < 500) {
    clientErrors.add(1);
  } else {
    unexpectedResponses.add(1);
  }

  check(response, {
    'expected response for strategy': () => isExpectedResponse(response, body),
  });
}

function isExpectedResponse(response, body) {
  // 여기서 보는 것은 "기술적 실패가 없었는가"이지 "인수 기준을 만족했는가"가 아니다.
  // 오픈 전·만료 거부도 오류 코드 표에 정의된 정상 비즈니스 응답이므로 통과로 센다.
  // LT-01 에서 이 값이 0인지는 카운터와 결과 문서에서 판정한다(ALREADY_ISSUED 와 같은 취급).
  const businessErrorCodes = [
    'OUT_OF_STOCK',
    'ALREADY_ISSUED',
    'CAMPAIGN_NOT_OPEN',
    'CAMPAIGN_EXPIRED',
  ];

  const normalBusinessResponse =
    (response.status === 200 && body?.status === 'ISSUED' && Boolean(body?.couponId)) ||
    (response.status === 409 && businessErrorCodes.includes(body?.errorCode));

  const expectedNoLockConflict =
    TEST_STRATEGY === 'V0' &&
    response.status === 503 &&
    body?.errorCode === 'CONCURRENCY_CONFLICT';

  return normalBusinessResponse || expectedNoLockConflict;
}

function numberEnv(name, defaultValue) {
  const rawValue = __ENV[name];
  if (rawValue === undefined || rawValue === '') {
    return defaultValue;
  }

  const value = Number(rawValue);
  if (!Number.isInteger(value) || value <= 0) {
    throw new Error(`${name} must be a positive integer: ${rawValue}`);
  }

  return value;
}

function strategyEnv(name) {
  const rawValue = __ENV[name];
  if (rawValue === undefined || rawValue === '') {
    throw new Error(`${name} is required and must match the application strategy`);
  }

  const value = rawValue.toUpperCase();
  if (!['V0', 'V1', 'V2', 'V3'].includes(value)) {
    throw new Error(`${name} must be one of V0, V1, V2, V3: ${value}`);
  }
  return value;
}

function parseJson(body) {
  try {
    return JSON.parse(body);
  } catch (_) {
    return null;
  }
}

// 외부 JS 라이브러리 없이 재현 가능한 UUID v4 형식의 키를 생성한다.
function uuidV4FromSequence(sequence) {
  const suffix = sequence.toString(16).padStart(12, '0').slice(-12);
  return `00000000-0000-4000-8000-${suffix}`;
}
