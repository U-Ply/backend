import exec from 'k6/execution';
import http from 'k6/http';
import { check } from 'k6';
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

const issued = new Counter('coupon_issued');
const outOfStock = new Counter('coupon_out_of_stock');
const alreadyIssued = new Counter('coupon_already_issued');
const clientErrors = new Counter('coupon_other_4xx');
const lockTimeout = new Counter('coupon_lock_timeout');
const concurrencyConflict = new Counter('coupon_concurrency_conflict');
const connectionUnavailable = new Counter('coupon_connection_unavailable');
const serverErrors = new Counter('coupon_5xx');
const unexpectedResponses = new Counter('coupon_unexpected_response');

// 재고 소진에 따른 409는 예상된 비즈니스 응답이므로 k6의 http_req_failed에서 제외한다.
http.setResponseCallback(http.expectedStatuses(200, 409));

const thresholds = {
  checks: ['rate>0.99'],
  coupon_lock_timeout: ['count==0'],
  coupon_connection_unavailable: ['count==0'],
  coupon_5xx: ['count==0'],
  coupon_other_4xx: ['count==0'],
  coupon_unexpected_response: ['count==0'],
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

  const response = http.post(`${BASE_URL}/api/coupons/issue`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
    tags: {
      api: 'coupon-issue',
    },
  });

  const body = parseJson(response.body);

  if (response.status === 200 && body?.status === 'ISSUED' && body?.couponId) {
    issued.add(1);
  } else if (response.status === 409 && body?.errorCode === 'OUT_OF_STOCK') {
    outOfStock.add(1);
  } else if (response.status === 409 && body?.errorCode === 'ALREADY_ISSUED') {
    alreadyIssued.add(1);
  } else if (response.status === 503 && body?.errorCode === 'LOCK_TIMEOUT') {
    lockTimeout.add(1);
  } else if (response.status === 503 && body?.errorCode === 'CONCURRENCY_CONFLICT') {
    concurrencyConflict.add(1);
  } else if (response.status === 503 && body?.errorCode === 'CONNECTION_UNAVAILABLE') {
    connectionUnavailable.add(1);
  } else if (response.status >= 500) {
    serverErrors.add(1);
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
  const normalBusinessResponse =
    (response.status === 200 && body?.status === 'ISSUED' && Boolean(body?.couponId)) ||
    (response.status === 409 &&
      (body?.errorCode === 'OUT_OF_STOCK' || body?.errorCode === 'ALREADY_ISSUED'));

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
