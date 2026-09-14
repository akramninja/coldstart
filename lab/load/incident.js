// Production traffic for one incident, and a one-line dashboard every 5 seconds.
//
//   docker compose logs -f traffic
//
// The dashboard reads order-service's own metrics (/actuator/prometheus), the same numbers an
// on-call engineer sees in Grafana. It prints RESOLVED once the symptom has been gone for a full
// minute under the same traffic.
//
// Open model (constant-arrival-rate): users keep arriving when the service falls behind, as real
// users do. Ids match the dataset order-service seeds (Dataset.java).
import http from 'k6/http';
import { sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MANAGEMENT_URL = __ENV.MANAGEMENT_URL || 'http://localhost:9090';
const ORDERS = 200000;
const SAMPLE_SECONDS = 5;
const RESOLVED_AFTER_SECONDS = 60;

const between1And = (max) => 1 + Math.floor(Math.random() * max);

// Clients give up after 10 s, as a browser or an upstream gateway would.
const CLIENT = { timeout: '10s', tags: { name: 'user' } };

const INCIDENTS = {
  3: {
    title: 'Incident 3: lunch peak, requests time out',
    rate: 150,
    uri: '/api/orders/{id}',
    user: () => http.get(`${BASE_URL}/api/orders/${between1And(ORDERS)}`, CLIENT),
    line: (s) => [
      `answered ${fixed(s.rate, 0).padStart(3)}/s of ${RATE}`,
      `errors ${percent(s.errorRatio).padStart(4)}`,
      `p99 ${seconds(s.p99).padStart(7)}`,
      `CPU ${fixed(s.cpu, 2)} of ${fixed(s.cpuLimit, 0)} cores`,
      `DB pool ${s.poolActive}/${s.poolMax} in use, ${String(s.poolPending).padStart(3)} waiting`,
    ],
    healthy: (s) => s.rate >= 0.95 * RATE && s.errorRatio === 0 && s.poolPending === 0 && s.p99 < 0.5,
  },
};

const incident = INCIDENTS[__ENV.INCIDENT];
if (!incident) {
  throw new Error(`INCIDENT must be one of: ${Object.keys(INCIDENTS).join(', ')}`);
}
const RATE = Number(__ENV.RATE || incident.rate);

export const options = {
  discardResponseBodies: true,
  // Timeouts and refused connections throw instead of logging one warning per request, which would
  // bury the dashboard. user() swallows them: the dashboard shows their effect, served < offered.
  throw: true,
  scenarios: {
    users: {
      executor: 'constant-arrival-rate',
      exec: 'user',
      rate: RATE,
      timeUnit: '1s',
      duration: '4h',
      preAllocatedVUs: 100,
      maxVUs: 3000,
    },
    dashboard: { executor: 'constant-vus', exec: 'dashboard', vus: 1, duration: '4h' },
  },
};

export function user() {
  try {
    incident.user();
  } catch (timeoutOrRefused) {
    // Counted where it matters: this user was not served.
  }
}

// Dashboard state lives in its single VU.
let previous = null;
let healthySince = null;
let sawIncident = false;
let resolved = false;
let lastUptime = null;
let started = false;

export function dashboard() {
  if (!started) {
    started = true;
    print(`${incident.title}. Traffic: ${RATE} users/s. One line every ${SAMPLE_SECONDS} s.`);
  }
  sleep(SAMPLE_SECONDS);
  let response;
  try {
    response = http.get(`${MANAGEMENT_URL}/actuator/prometheus`, { responseType: 'text', timeout: '4s', tags: { name: 'dashboard' } });
  } catch (unreachable) {
    response = { status: 0 };
  }
  if (response.status !== 200) {
    print(`${clock()}  order-service is not answering (starting or restarting)`);
    previous = previous && { ...previous, unreachable: true };
    healthySince = null;
    return;
  }
  const current = sample(parse(response.body));
  if (lastUptime !== null && current.uptime < lastUptime) {
    print(`${clock()}  ── order-service restarted: new pod ──`);
    previous = null;
  }
  lastUptime = current.uptime;
  if (previous === null || previous.unreachable) {
    previous = current;
    return;
  }
  const s = delta(previous, current);
  previous = current;

  const healthy = s.rate > 0 && incident.healthy(s);
  if (!healthy) {
    sawIncident = sawIncident || s.rate > 0;
    healthySince = null;
    resolved = false;
  } else if (healthySince === null) {
    healthySince = Date.now();
  }
  let status = '';
  if (healthy && sawIncident && !resolved && Date.now() - healthySince >= RESOLVED_AFTER_SECONDS * 1000) {
    resolved = true;
    status = '  RESOLVED: healthy for 60 s under the same traffic';
  }
  print(`${clock()}  ${incident.line(s).join('  │ ')}${status}`);
}

// ── Prometheus text format ────────────────────────────────────────────────────────────────────

function parse(text) {
  const series = [];
  for (const row of text.split('\n')) {
    if (row === '' || row[0] === '#') continue;
    const match = row.match(/^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{(.*)\})?\s+(\S+)/);
    if (!match) continue;
    const labels = {};
    for (const pair of (match[3] || '').matchAll(/([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"/g)) {
      labels[pair[1]] = pair[2];
    }
    series.push({ name: match[1], labels, value: Number(match[4]) });
  }
  return series;
}

function total(series, name, filter = () => true) {
  return series.filter((s) => s.name === name && filter(s.labels)).reduce((sum, s) => sum + s.value, 0);
}

function sample(series) {
  const onUri = (labels) => labels.uri === incident.uri;
  const buckets = {};
  series
    .filter((s) => s.name === 'http_server_requests_seconds_bucket' && onUri(s.labels))
    .forEach((s) => { buckets[s.labels.le] = (buckets[s.labels.le] || 0) + s.value; });
  return {
    at: Date.now(),
    requests: total(series, 'http_server_requests_seconds_count', onUri),
    errors: total(series, 'http_server_requests_seconds_count', (l) => onUri(l) && l.status.startsWith('5')),
    buckets,
    cpuSeconds: total(series, 'cgroup_cpu_usage_seconds_total'),
    cpuLimit: total(series, 'cgroup_cpu_limit_cores'),
    poolActive: total(series, 'hikaricp_connections_active'),
    poolMax: total(series, 'hikaricp_connections_max'),
    poolPending: total(series, 'hikaricp_connections_pending'),
    uptime: total(series, 'process_uptime_seconds'),
  };
}

function delta(before, after) {
  const elapsed = (after.at - before.at) / 1000;
  const requests = after.requests - before.requests;
  return {
    rate: requests / elapsed,
    errorRatio: requests > 0 ? (after.errors - before.errors) / requests : 0,
    p99: quantile(0.99, before.buckets, after.buckets),
    cpu: (after.cpuSeconds - before.cpuSeconds) / elapsed,
    cpuLimit: after.cpuLimit,
    poolActive: after.poolActive,
    poolMax: after.poolMax,
    poolPending: after.poolPending,
  };
}

// Upper bound of the bucket holding the 99th percentile of requests finished during the interval.
function quantile(q, before, after) {
  const bounds = Object.keys(after).filter((le) => le !== '+Inf').map(Number).sort((a, b) => a - b);
  const count = (after['+Inf'] || 0) - (before['+Inf'] || 0);
  if (count <= 0) return 0;
  for (const bound of bounds) {
    const key = Object.keys(after).find((le) => Number(le) === bound);
    if ((after[key] || 0) - (before[key] || 0) >= q * count) return bound;
  }
  return Infinity;
}

// ── Formatting ────────────────────────────────────────────────────────────────────────────────

function print(message) {
  console.log(message);
}

function clock() {
  return new Date().toISOString().substring(11, 19);
}

function fixed(value, digits) {
  return Number.isFinite(value) ? value.toFixed(digits) : '?';
}

function percent(ratio) {
  return `${Math.round(ratio * 100)}%`;
}

function seconds(value) {
  if (!Number.isFinite(value)) return '> 30 s';
  return value < 1 ? `${Math.round(value * 1000)} ms` : `${value.toFixed(1)} s`;
}
