import exec from 'k6/execution';
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

import { issueCoupon, parseJson, positiveIntEnv, uuidV4FromSequence } from './common/issue.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const USERS = positiveIntEnv('IDEMPOTENCY_USERS', 100);
const REPEATS = positiveIntEnv('IDEMPOTENCY_REPEATS', 5);
const USER_ID_START = positiveIntEnv('USER_ID_START', 1);
const CAMPAIGN_ID = positiveIntEnv('CAMPAIGN_ID', 1);
const ROUTE_ID = __ENV.ROUTE_ID || 'JEJU';
const FARE_CLASS = __ENV.FARE_CLASS || 'ECONOMY';

const firstResponses = new Counter('idempotency_first_response');
const replayedResponses = new Counter('idempotency_replayed_response');
const mismatchedResponses = new Counter('idempotency_response_mismatch');
const inProgress = new Counter('idempotency_in_progress');
const errors = new Counter('idempotency_errors');

http.setResponseCallback(http.expectedStatuses(200, 409));

export const options = {
  scenarios: {
    same_key_same_request: {
      executor: 'per-vu-iterations',
      vus: USERS,
      iterations: REPEATS,
      maxDuration: '5m',
    },
  },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    idempotency_first_response: [`count==${USERS}`],
    idempotency_replayed_response: [`count==${USERS * (REPEATS - 1)}`],
    idempotency_response_mismatch: ['count==0'],
    idempotency_in_progress: ['count==0'],
    idempotency_errors: ['count==0'],
  },
};

let firstResponseBody = null;

export default function () {
  const userSequence = exec.vu.idInTest - 1;
  const iteration = exec.vu.iterationInScenario;
  const idempotencyKey = uuidV4FromSequence(userSequence + 1, 6);
  const response = issueCoupon(
    BASE_URL,
    {
      userId: USER_ID_START + userSequence,
      campaignId: CAMPAIGN_ID,
      routeId: ROUTE_ID,
      fareClass: FARE_CLASS,
    },
    idempotencyKey,
    { scenario: 'idempotency' },
  );
  const body = parseJson(response.body);

  if (response.status === 409 && body?.errorCode === 'IDEMPOTENCY_REQUEST_IN_PROGRESS') {
    inProgress.add(1);
    check(response, { 'completed response is returned': () => false });
    return;
  }
  if (response.status !== 200 || body?.status !== 'ISSUED' || !body?.couponId) {
    errors.add(1);
    check(response, { 'completed response is returned': () => false });
    return;
  }

  if (iteration === 0) {
    firstResponseBody = response.body;
    firstResponses.add(1);
  } else if (response.body === firstResponseBody) {
    replayedResponses.add(1);
  } else {
    mismatchedResponses.add(1);
  }

  check(response, {
    'completed response is returned': () => response.status === 200,
    'same key returns the exact first response': () => iteration === 0 || response.body === firstResponseBody,
  });
}
