import exec from 'k6/execution';
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
const ROUTE_ID = __ENV.ROUTE_ID || 'JEJU';
const FARE_CLASS = __ENV.FARE_CLASS || 'ECONOMY';
const MAX_DURATION = __ENV.MAX_DURATION || '10m';
const TEST_STRATEGY = strategyEnv();
const thresholds = baseThresholds(TEST_STRATEGY);
// 기존 Level 2 비교는 1% 미만의 check 실패를 수치로 남기되, 상세 카운터로 판정한다.
thresholds.checks = ['rate>0.99'];

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

  const response = issueCoupon(
    BASE_URL,
    { userId, campaignId: CAMPAIGN_ID, routeId: ROUTE_ID, fareClass: FARE_CLASS },
    idempotencyKey,
    { scenario: 'level2-strategy' },
  );
  recordIssueResponse(response, TEST_STRATEGY);
}
