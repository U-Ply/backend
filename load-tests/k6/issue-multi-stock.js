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
  // 기본 16,000:4,000 비율을 실행 전 구간에 4:1로 섞어 두 재고 풀이 동시에 경합하게 한다.
  const fareClass = sequence % 5 === 4 ? 'BUSINESS' : 'ECONOMY';
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
