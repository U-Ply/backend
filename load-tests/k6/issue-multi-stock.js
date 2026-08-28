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
const ECONOMY_REQUESTS = positiveIntEnv('ECONOMY_REQUESTS', 16000);
const BUSINESS_REQUESTS = positiveIntEnv('BUSINESS_REQUESTS', 4000);
const ECONOMY_STOCK = positiveIntEnv('ECONOMY_STOCK', 8000);
const BUSINESS_STOCK = positiveIntEnv('BUSINESS_STOCK', 2000);
const TOTAL_REQUESTS = ECONOMY_REQUESTS + BUSINESS_REQUESTS;
const VUS = positiveIntEnv('VUS', 500);
const USER_ID_START = positiveIntEnv('USER_ID_START', 1);
const CAMPAIGN_ID = positiveIntEnv('CAMPAIGN_ID', 1);
const ROUTE_ID = __ENV.ROUTE_ID || 'JEJU';
const MAX_DURATION = __ENV.MAX_DURATION || '10m';
const TEST_STRATEGY = strategyEnv('V3');

if (ECONOMY_STOCK > ECONOMY_REQUESTS || BUSINESS_STOCK > BUSINESS_REQUESTS) {
  throw new Error('Each stock value must be less than or equal to its request count');
}

const economyIssued = new Counter('multi_stock_economy_issued');
const economyOutOfStock = new Counter('multi_stock_economy_out_of_stock');
const businessIssued = new Counter('multi_stock_business_issued');
const businessOutOfStock = new Counter('multi_stock_business_out_of_stock');

export const options = {
  scenarios: {
    economy_and_business: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: TOTAL_REQUESTS,
      maxDuration: MAX_DURATION,
    },
  },
  thresholds: {
    ...baseThresholds(TEST_STRATEGY),
    http_req_failed: ['rate==0'],
    multi_stock_economy_issued: [`count==${ECONOMY_STOCK}`],
    multi_stock_economy_out_of_stock: [`count==${ECONOMY_REQUESTS - ECONOMY_STOCK}`],
    multi_stock_business_issued: [`count==${BUSINESS_STOCK}`],
    multi_stock_business_out_of_stock: [`count==${BUSINESS_REQUESTS - BUSINESS_STOCK}`],
  },
};

export default function () {
  const sequence = exec.scenario.iterationInTest;
  // 누적 비율을 비교해 BUSINESS 요청을 전 구간에 고르게 배치한다.
  // 기본 16,000:4,000은 4:1이지만, 환경변수로 비율을 바꿔도 요청 건수가 정확히 맞는다.
  const businessBefore = Math.floor((sequence * BUSINESS_REQUESTS) / TOTAL_REQUESTS);
  const businessThroughCurrent = Math.floor(((sequence + 1) * BUSINESS_REQUESTS) / TOTAL_REQUESTS);
  const fareClass = businessThroughCurrent > businessBefore ? 'BUSINESS' : 'ECONOMY';
  const response = issueCoupon(
    BASE_URL,
    {
      userId: USER_ID_START + sequence,
      campaignId: CAMPAIGN_ID,
      routeId: ROUTE_ID,
      fareClass,
    },
    uuidV4FromSequence(sequence + 1, 5),
    { scenario: 'multi-stock', trafficGroup: fareClass },
  );
  const { outcome } = recordIssueResponse(response, TEST_STRATEGY);

  if (fareClass === 'ECONOMY' && outcome === 'ISSUED') economyIssued.add(1);
  if (fareClass === 'ECONOMY' && outcome === 'OUT_OF_STOCK') economyOutOfStock.add(1);
  if (fareClass === 'BUSINESS' && outcome === 'ISSUED') businessIssued.add(1);
  if (fareClass === 'BUSINESS' && outcome === 'OUT_OF_STOCK') businessOutOfStock.add(1);
}
