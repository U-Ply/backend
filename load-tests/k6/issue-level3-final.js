import exec from 'k6/execution';

import {
  baseThresholds,
  issueCoupon,
  positiveIntEnv,
  recordIssueResponse,
  uuidV4FromSequence,
} from './common/issue.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const TOTAL_REQUESTS = positiveIntEnv('TOTAL_REQUESTS', 20000);
const INITIAL_STOCK = positiveIntEnv('INITIAL_STOCK', 10000);
const PRE_ALLOCATED_VUS = positiveIntEnv('PRE_ALLOCATED_VUS', 500);
const MAX_VUS = positiveIntEnv('MAX_VUS', 5000);
const USER_ID_START = positiveIntEnv('USER_ID_START', 1);
const CAMPAIGN_ID = positiveIntEnv('CAMPAIGN_ID', 1);
const ROUTE_ID = __ENV.ROUTE_ID || 'JEJU';
const FARE_CLASS = __ENV.FARE_CLASS || 'ECONOMY';
const ARRIVAL_WINDOW = __ENV.ARRIVAL_WINDOW || '60s';

if (TOTAL_REQUESTS < INITIAL_STOCK) {
  throw new Error('TOTAL_REQUESTS must be greater than or equal to INITIAL_STOCK');
}

const thresholds = {
  ...baseThresholds('V3'),
  http_req_failed: ['rate==0'],
  dropped_iterations: ['count==0'],
  coupon_issued: [`count==${INITIAL_STOCK}`],
  coupon_out_of_stock: [`count==${TOTAL_REQUESTS - INITIAL_STOCK}`],
  coupon_already_issued: ['count==0'],
};

export const options = {
  scenarios: {
    level3_final_acceptance: {
      executor: 'constant-arrival-rate',
      rate: TOTAL_REQUESTS,
      timeUnit: ARRIVAL_WINDOW,
      duration: ARRIVAL_WINDOW,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
      gracefulStop: '30s',
    },
  },
  thresholds,
};

export default function () {
  const sequence = exec.scenario.iterationInTest;
  const response = issueCoupon(
    BASE_URL,
    {
      userId: USER_ID_START + sequence,
      campaignId: CAMPAIGN_ID,
      routeId: ROUTE_ID,
      fareClass: FARE_CLASS,
    },
    uuidV4FromSequence(sequence + 1, 3),
    { scenario: 'level3-final' },
  );

  recordIssueResponse(response, 'V3');
}
