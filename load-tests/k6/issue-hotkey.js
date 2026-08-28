import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

import {
  baseThresholds,
  issueCoupon,
  positiveIntEnv,
  recordIssueResponse,
  strategyEnv,
  uuidV4FromSequence,
} from './common/issue.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const TOTAL_REQUESTS = positiveIntEnv('TOTAL_REQUESTS', 20000);
const VUS = positiveIntEnv('VUS', 500);
const USER_ID_START = positiveIntEnv('USER_ID_START', 1);
const CAMPAIGN_ID = positiveIntEnv('CAMPAIGN_ID', 1);
const FARE_CLASS = __ENV.FARE_CLASS || 'ECONOMY';
const MAX_DURATION = __ENV.MAX_DURATION || '10m';
const TEST_STRATEGY = strategyEnv('V3');
const HOT_STOCK = positiveIntEnv('HOT_STOCK', 500);
const WARM_STOCK = positiveIntEnv('WARM_STOCK', 300);
const COLD_STOCK = positiveIntEnv('COLD_STOCK', 1000);

if (TOTAL_REQUESTS % 100 !== 0) {
  throw new Error('TOTAL_REQUESTS must be divisible by 100 to preserve the exact 90:7:3 ratio');
}

const routes = [
  { id: __ENV.HOT_ROUTE_ID || 'JEJU', upperBound: 90 },
  { id: __ENV.WARM_ROUTE_ID || 'FUKUOKA', upperBound: 97 },
  { id: __ENV.COLD_ROUTE_ID || 'BANGKOK', upperBound: 100 },
];

const routeRequests = Object.fromEntries(
  routes.map((route) => [route.id, new Counter(`hotkey_${metricName(route.id)}_requests`)]),
);
const routeIssued = Object.fromEntries(
  routes.map((route) => [route.id, new Counter(`hotkey_${metricName(route.id)}_issued`)]),
);
const routeOutOfStock = Object.fromEntries(
  routes.map((route) => [route.id, new Counter(`hotkey_${metricName(route.id)}_out_of_stock`)]),
);

const expectedRequests = [
  (TOTAL_REQUESTS * 90) / 100,
  (TOTAL_REQUESTS * 7) / 100,
  (TOTAL_REQUESTS * 3) / 100,
];
const expectedStocks = [HOT_STOCK, WARM_STOCK, COLD_STOCK];

const hotkeyThresholds = {};
routes.forEach((route, index) => {
  const prefix = `hotkey_${metricName(route.id)}`;
  const expectedIssued = Math.min(expectedRequests[index], expectedStocks[index]);
  hotkeyThresholds[`${prefix}_requests`] = [`count==${expectedRequests[index]}`];
  hotkeyThresholds[`${prefix}_issued`] = [`count==${expectedIssued}`];
  hotkeyThresholds[`${prefix}_out_of_stock`] = [`count==${expectedRequests[index] - expectedIssued}`];
});

export const options = {
  scenarios: {
    hotkey_90_7_3: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: TOTAL_REQUESTS,
      maxDuration: MAX_DURATION,
    },
  },
  thresholds: {
    ...baseThresholds(TEST_STRATEGY),
    http_req_failed: ['rate==0'],
    ...hotkeyThresholds,
  },
};

export default function () {
  const sequence = exec.scenario.iterationInTest;
  const routeId = routeFor(sequence);
  routeRequests[routeId].add(1);

  const response = issueCoupon(
    BASE_URL,
    {
      userId: USER_ID_START + sequence,
      campaignId: CAMPAIGN_ID,
      routeId,
      fareClass: FARE_CLASS,
    },
    uuidV4FromSequence(sequence + 1, 4),
    { scenario: 'hotkey', trafficGroup: routeId },
  );
  const { outcome } = recordIssueResponse(response, TEST_STRATEGY);

  if (outcome === 'ISSUED') {
    routeIssued[routeId].add(1);
  } else if (outcome === 'OUT_OF_STOCK') {
    routeOutOfStock[routeId].add(1);
  }
}

function routeFor(sequence) {
  const percentile = sequence % 100;
  return routes.find((route) => percentile < route.upperBound).id;
}

function metricName(value) {
  return value.toLowerCase().replace(/[^a-z0-9_]/g, '_');
}
