// k6 load profiles for article 1, one per default. Each step is its own k6 scenario, so the summary
// prints p99, failed requests and dropped iterations step by step.
//
//   docker compose --env-file scenarios/<scenario>.env run --rm --no-deps k6 run -e PROFILE=osiv /scripts/defaults.js
//   ... run -e PROFILE=threads -e STEPS=200,400,600 -e STEP_SECONDS=90 /scripts/defaults.js
//
// Open model (constant-arrival-rate): requests keep arriving when the service falls behind, as real
// users do. A closed model slows down with the service and hides the cliff.
//
// Ids and SKUs match the dataset order-service seeds (Dataset.java).
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PROFILE = __ENV.PROFILE || 'osiv';
const PRODUCTS = Number(__ENV.PRODUCTS || 200000);
const CUSTOMERS = Number(__ENV.CUSTOMERS || 100000);
const ORDERS = Number(__ENV.ORDERS || 200000);
const MAX_VUS = Number(__ENV.MAX_VUS || 1000);
const WARMUP_SECONDS = 60;
const PAUSE_SECONDS = 10;

const CATEGORIES = ['audio', 'books', 'cameras', 'computers', 'garden', 'home',
  'kitchen', 'office', 'phones', 'sports', 'toys', 'travel'];
const COUNTRIES = ['AT', 'BE', 'DE', 'ES', 'FR', 'IE', 'IT', 'LU', 'NL', 'PT'];
const TIERS = ['BRONZE', 'SILVER', 'GOLD'];
const JSON_BODY = { 'Content-Type': 'application/json' };

const between1And = (max) => 1 + Math.floor(Math.random() * max);
const pick = (values) => values[Math.floor(Math.random() * values.length)];
const sku = (id) => `SKU-${String(id).padStart(7, '0')}`;

const getProduct = () =>
  http.get(`${BASE_URL}/api/products/${sku(between1And(PRODUCTS))}`, { tags: { name: 'product' } });

const getOrder = () =>
  http.get(`${BASE_URL}/api/orders/${between1And(ORDERS)}`, { tags: { name: 'order' } });

// A 20-line cart: about 1.7 ms of pricing CPU with the default 10,000 rules.
function postQuote() {
  const lines = Array.from({ length: 20 }, () => ({
    sku: sku(between1And(PRODUCTS)),
    category: pick(CATEGORIES),
    unitPrice: (5 + Math.random() * 300).toFixed(2),
    quantity: between1And(8),
  }));
  const body = JSON.stringify({ tier: pick(TIERS), country: pick(COUNTRIES), lines });
  return http.post(`${BASE_URL}/api/quotes`, body, { headers: JSON_BODY, tags: { name: 'quote' } });
}

// 1 to 3 lines: about a 15 KB confirmation e-mail on average.
function postOrder() {
  const lines = Array.from({ length: between1And(3) }, () => ({ sku: sku(between1And(PRODUCTS)), quantity: between1And(3) }));
  const body = JSON.stringify({ customerId: between1And(CUSTOMERS), country: pick(COUNTRIES), lines });
  return http.post(`${BASE_URL}/api/orders`, body, { headers: JSON_BODY, tags: { name: 'place-order' } });
}

const PROFILES = {
  // Default 1: the catalog fills the cache. 90% product reads, 10% quotes.
  heap: { request: () => (Math.random() < 0.9 ? getProduct() : postQuote()), expected: 200, warmup: [100], steps: [200, 400, 600, 800], stepSeconds: 120 },
  // Default 2: pure CPU.
  threads: { request: postQuote, expected: 200, warmup: [50, 100], steps: [200, 300, 400, 500, 600], stepSeconds: 60 },
  // Default 3: a database read, then a 20 ms carrier call.
  osiv: { request: getOrder, expected: 200, warmup: [50, 150], steps: [200, 300, 400, 500, 600], stepSeconds: 60 },
  // Default 4: one long step, orders arriving faster than confirmations drain (~40/s).
  async: { request: postOrder, expected: 201, warmup: [20], steps: [100], stepSeconds: 600 },
  // Default 5: every product, sooner or later.
  cache: { request: getProduct, expected: 200, warmup: [100], steps: [500], stepSeconds: 900 },
};

const profile = PROFILES[PROFILE];
if (!profile) {
  throw new Error(`PROFILE must be one of: ${Object.keys(PROFILES).join(', ')}`);
}
const steps = __ENV.STEPS ? __ENV.STEPS.split(',').map(Number) : profile.steps;
const stepSeconds = Number(__ENV.STEP_SECONDS || profile.stepSeconds);

const scenarios = {};
const thresholds = {};
let startSeconds = 0;

function stage(name, rate, seconds) {
  scenarios[name] = {
    executor: 'constant-arrival-rate',
    exec: 'hit',
    rate,
    timeUnit: '1s',
    duration: `${seconds}s`,
    startTime: `${startSeconds}s`,
    preAllocatedVUs: 50,
    maxVUs: MAX_VUS,
  };
  startSeconds += seconds + PAUSE_SECONDS;
}

profile.warmup.forEach((rate, i) => stage(`warmup_${i + 1}_${rate}rps`, rate, WARMUP_SECONDS));
steps.forEach((rate) => {
  const name = `step_${rate}rps`;
  stage(name, rate, stepSeconds);
  // Thresholds that always pass: they only make k6 print one summary line per step.
  thresholds[`http_req_duration{scenario:${name}}`] = ['p(99)>=0'];
  thresholds[`http_req_failed{scenario:${name}}`] = ['rate>=0'];
  thresholds[`dropped_iterations{scenario:${name}}`] = ['count>=0'];
});

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['med', 'p(90)', 'p(99)', 'max'],
  scenarios,
  thresholds,
};

export function hit() {
  const response = profile.request();
  check(response, { [`status is ${profile.expected}`]: (r) => r.status === profile.expected });
}
