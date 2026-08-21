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

// 재고 소진에 따른 409는 예상된 비즈니스 응답이므로 k6의 http_req_failed에서 제외한다.
http.setResponseCallback(http.expectedStatuses(200, 409));

export const options = {
  scenarios: {
    issue_coupons: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: TOTAL_REQUESTS,
      maxDuration: MAX_DURATION,
    },
  },
  thresholds: {
    checks: ['rate>0.99'],
    coupon_lock_timeout: ['count==0'],
    // 커넥션 획득 실패는 어느 전략에서도 정상이 아니므로 0건을 요구한다.
    // coupon_concurrency_conflict 는 V0(NoLock)에서 정상 관측값이라 threshold 를 두지 않고
    // 수치만 기록한다. V1 에서 0이어야 한다는 판정은 결과 문서에서 수행한다.
    //
    // coupon_campaign_not_open / coupon_campaign_expired 도 같은 이유로 threshold 를 두지 않는다.
    // LT-01 에서는 0이어야 하지만, 인수 기준 E-2(만료 정각)·E-3(만료 1초 후) 경계 시나리오에서는
    // 이 값이 나오는 것이 정답이다. 같은 스크립트를 두 용도로 쓰므로 판정은 결과 문서에서 한다.
    coupon_connection_unavailable: ['count==0'],
    coupon_5xx: ['count==0'],
    coupon_other_4xx: ['count==0'],
    coupon_unexpected_response: ['count==0'],
  },
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
  } else if (response.status >= 400 && response.status < 500) {
    clientErrors.add(1);
  } else {
    unexpectedResponses.add(1);
  }

  // 여기서 보는 것은 "기술적 실패가 없었는가"이지 "인수 기준을 만족했는가"가 아니다.
  // 오픈 전·만료 거부는 오류 코드 표에 정의된 정상 비즈니스 응답이므로 통과로 센다.
  // LT-01 에서 이 값이 0인지는 위 카운터와 결과 문서에서 판정한다(ALREADY_ISSUED 와 같은 취급).
  const businessErrorCodes = [
    'OUT_OF_STOCK',
    'ALREADY_ISSUED',
    'CAMPAIGN_NOT_OPEN',
    'CAMPAIGN_EXPIRED',
  ];

  check(response, {
    'expected business response': () =>
      (response.status === 200 && body?.status === 'ISSUED' && Boolean(body?.couponId)) ||
      (response.status === 409 && businessErrorCodes.includes(body?.errorCode)),
  });
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
