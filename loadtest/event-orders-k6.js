import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:8081';
const tierIds = (__ENV.TIER_IDS || '').split(',').map(Number);
const label = __ENV.LABEL || '';
const mode = __ENV.MODE || 'constant';
const rate = Number(__ENV.RATE || 20);
const spikeRate = Number(__ENV.SPIKE_RATE || 160);
const durationSeconds = Number(__ENV.DURATION_SECONDS || 5);
const allocatedVUs = Number(__ENV.ALLOCATED_VUS || 64);
const sessionCount = Number(__ENV.SESSION_COUNT || 1000);

if (!tierIds.length || tierIds.some(id => !Number.isSafeInteger(id) || id < 1)) {
  throw new Error('TIER_IDS must contain positive comma-separated tier IDs');
}
if (!/^[A-Za-z0-9-]{1,64}$/.test(label)) throw new Error('LABEL is required');
if (!['constant', 'spike'].includes(mode)) throw new Error('MODE must be constant or spike');
if (![rate, spikeRate, durationSeconds, allocatedVUs, sessionCount].every(n => Number.isInteger(n) && n > 0)) {
  throw new Error('Rates, duration, allocated VUs, and session count must be positive integers');
}
if (sessionCount > 5000) {
  throw new Error('SESSION_COUNT is capped at 5000 isolated test users');
}

const accepted = new Counter('event_orders_accepted');
const businessRejected = new Counter('event_orders_business_rejected');
const controlledRejected = new Counter('event_orders_controlled_rejection');
const dependencyUnavailable = new Counter('event_orders_dependency_unavailable');
const technical = new Counter('event_orders_technical_failure');

export const options = {
  discardResponseBodies: false,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
  scenarios: {
    event_orders: mode === 'constant' ? {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration: `${durationSeconds}s`,
      preAllocatedVUs: allocatedVUs,
    } : {
      executor: 'ramping-arrival-rate',
      startRate: rate,
      timeUnit: '1s',
      stages: [
        { target: spikeRate, duration: '2s' },
        { target: spikeRate, duration: '2s' },
        { target: rate, duration: '2s' },
      ],
      preAllocatedVUs: allocatedVUs,
    },
  },
};

export default function () {
  const index = exec.scenario.iterationInTest + 1;
  if (index > sessionCount) throw new Error(`Only ${sessionCount} isolated Redis sessions are seeded`);
  const tierId = tierIds[(index - 1) % tierIds.length];
  const token = (100000 + index).toString(16).padStart(32, '0');
  const response = http.post(
    `${baseUrl}/api/v1/orders`,
    JSON.stringify({ ticketTierId: tierId, quantity: 1 }),
    {
      headers: {
        Authorization: `Bearer ${token}`,
        'Idempotency-Key': `event-orders-${label}-${index}`,
        'Content-Type': 'application/json',
      },
      tags: { endpoint: 'event-order-create' },
      timeout: '15s',
    },
  );
  let payload;
  try { payload = response.json(); } catch (_) { payload = null; }
  if (response.status === 200 && payload?.success === true
      && payload?.data?.status === 'PENDING_PAYMENT') accepted.add(1);
  else if (response.status === 409 && payload?.success === false) businessRejected.add(1);
  else if (response.status === 429 && payload?.success === false) controlledRejected.add(1);
  else if (response.status === 503) dependencyUnavailable.add(1);
  else technical.add(1);
}
