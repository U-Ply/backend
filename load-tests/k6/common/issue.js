import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

export const issued = new Counter('coupon_issued');
export const outOfStock = new Counter('coupon_out_of_stock');
export const alreadyIssued = new Counter('coupon_already_issued');
export const campaignNotOpen = new Counter('coupon_campaign_not_open');
export const campaignExpired = new Counter('coupon_campaign_expired');
export const clientErrors = new Counter('coupon_other_4xx');
export const lockTimeout = new Counter('coupon_lock_timeout');
export const concurrencyConflict = new Counter('coupon_concurrency_conflict');
export const connectionUnavailable = new Counter('coupon_connection_unavailable');
export const serverErrors = new Counter('coupon_5xx');
export const unexpectedResponses = new Counter('coupon_unexpected_response');

// 게이트(레이트 리미터)가 켜졌을 때(coupon.gate.enabled=true) 429는 "아직 처리 안 됨"이다.
// 컨트롤러에 닿지 않았으므로 같은 Idempotency-Key로 백오프 후 재시도하고, 최종 응답만 분류한다.
export const gateRejected = new Counter('coupon_gate_rejected');
export const gateRetryExhausted = new Counter('coupon_gate_retry_exhausted');

// positiveIntEnv 는 함수 선언이라 호이스팅되어 여기서 호출해도 된다.
const MAX_GATE_RETRIES = positiveIntEnv('MAX_GATE_RETRIES', 50);
const GATE_BACKOFF_MS = positiveIntEnv('GATE_BACKOFF_MS', 200);

// 재고 소진과 중복 발급은 선착순 테스트에서 예상 가능한 비즈니스 응답이다.
// 429는 게이트가 되돌린 재시도 대상이므로 http_req_failed 에서 제외한다.
http.setResponseCallback(http.expectedStatuses(200, 409, 429));

export function issueCoupon(baseUrl, request, idempotencyKey, tags = {}) {
  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
    tags: {
      api: 'coupon-issue',
      route: request.routeId,
      fareClass: request.fareClass,
      ...tags,
    },
  };
  const payload = JSON.stringify(request);

  let response;
  let attempts = 0;
  do {
    response = http.post(`${baseUrl}/api/coupons/issue`, payload, params);
    if (response.status !== 429) {
      break;
    }
    // 게이트가 되돌린 429 — 같은 키로 백오프 후 재시도한다.
    gateRejected.add(1);
    attempts += 1;
    // 지터를 섞은 백오프. 모든 VU가 같은 간격으로 재시도해 다음 틱에 또 뭉치는 것을 막는다.
    sleep((GATE_BACKOFF_MS + Math.random() * GATE_BACKOFF_MS) / 1000);
  } while (attempts < MAX_GATE_RETRIES);

  if (response.status === 429) {
    gateRetryExhausted.add(1);
  }
  return response;
}

export function recordIssueResponse(response, testStrategy = 'V3') {
  const body = parseJson(response.body);
  const outcome = classifyIssueResponse(response, body);

  switch (outcome) {
    case 'ISSUED':
      issued.add(1);
      break;
    case 'OUT_OF_STOCK':
      outOfStock.add(1);
      break;
    case 'ALREADY_ISSUED':
      alreadyIssued.add(1);
      break;
    case 'CAMPAIGN_NOT_OPEN':
      campaignNotOpen.add(1);
      break;
    case 'CAMPAIGN_EXPIRED':
      campaignExpired.add(1);
      break;
    case 'LOCK_TIMEOUT':
      lockTimeout.add(1);
      break;
    case 'CONCURRENCY_CONFLICT':
      concurrencyConflict.add(1);
      break;
    case 'CONNECTION_UNAVAILABLE':
      connectionUnavailable.add(1);
      break;
    case 'SERVER_ERROR':
      serverErrors.add(1);
      break;
    case 'CLIENT_ERROR':
      clientErrors.add(1);
      break;
    case 'GATE_REJECTED':
      // 재시도 예산 소진 후에도 429. gateRetryExhausted 가 이미 셌으므로 여기서는 세지 않는다.
      break;
    default:
      unexpectedResponses.add(1);
  }

  const expected =
    ['ISSUED', 'OUT_OF_STOCK', 'ALREADY_ISSUED', 'CAMPAIGN_NOT_OPEN', 'CAMPAIGN_EXPIRED'].includes(
      outcome,
    ) ||
    (testStrategy === 'V0' && outcome === 'CONCURRENCY_CONFLICT');

  check(response, {
    'expected coupon issue response': () => expected,
  });

  return { body, outcome };
}

export function classifyIssueResponse(response, body = parseJson(response.body)) {
  if (response.status === 200 && body?.status === 'ISSUED' && body?.couponId) {
    return 'ISSUED';
  }

  const businessErrors = ['OUT_OF_STOCK', 'ALREADY_ISSUED', 'CAMPAIGN_NOT_OPEN', 'CAMPAIGN_EXPIRED'];
  const operationalErrors = ['LOCK_TIMEOUT', 'CONCURRENCY_CONFLICT', 'CONNECTION_UNAVAILABLE'];

  if (response.status === 409 && businessErrors.includes(body?.errorCode)) {
    return body.errorCode;
  }
  if (response.status === 503 && operationalErrors.includes(body?.errorCode)) {
    return body.errorCode;
  }
  if (response.status === 429) {
    // 재시도 예산을 소진하고도 429 — 기타 4xx로 중복 집계하지 않는다.
    return 'GATE_REJECTED';
  }
  if (response.status >= 500) {
    return 'SERVER_ERROR';
  }
  if (response.status >= 400) {
    return 'CLIENT_ERROR';
  }
  return 'UNEXPECTED';
}

export function baseThresholds(testStrategy = 'V3') {
  const thresholds = {
    checks: ['rate==1'],
    coupon_lock_timeout: ['count==0'],
    coupon_connection_unavailable: ['count==0'],
    coupon_5xx: ['count==0'],
    coupon_other_4xx: ['count==0'],
    coupon_unexpected_response: ['count==0'],
    // 게이트를 켜도 재시도로 최종 성공/409에 도달해야 한다. 소진이 생기면 게이트 튜닝 실패.
    coupon_gate_retry_exhausted: ['count==0'],
  };

  if (testStrategy !== 'V0') {
    thresholds.coupon_concurrency_conflict = ['count==0'];
  }
  return thresholds;
}

export function positiveIntEnv(name, defaultValue) {
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

export function strategyEnv(defaultValue = null) {
  const rawValue = __ENV.TEST_STRATEGY || defaultValue;
  if (!rawValue) {
    throw new Error('TEST_STRATEGY is required and must match the application strategy');
  }
  const value = rawValue.toUpperCase();
  if (!['V0', 'V1', 'V2', 'V3'].includes(value)) {
    throw new Error(`TEST_STRATEGY must be one of V0, V1, V2, V3: ${value}`);
  }
  return value;
}

export function parseJson(body) {
  try {
    return JSON.parse(body);
  } catch (_) {
    return null;
  }
}

// 외부 라이브러리 없이 실행 순번으로 재현 가능한 UUID v4를 만든다.
export function uuidV4FromSequence(sequence, namespace = 0) {
  const prefix = Number(namespace).toString(16).padStart(8, '0').slice(-8);
  const suffix = Number(sequence).toString(16).padStart(12, '0').slice(-12);
  return `${prefix}-0000-4000-8000-${suffix}`;
}
