// Production traffic for the Liquibase incidents, and a one-line dashboard every 5 seconds.
//
//   docker compose logs -f traffic
//
// The dashboard has two sources, both plain HTTP:
//   - orders-api's own metrics (/actuator/prometheus): what customers get;
//   - pgweb's query API: what the database says about the migration. It keeps working while
//     orders-api is down, which is exactly when you need it.
//
// Open model (constant-arrival-rate): customers keep arriving when the service falls behind.
import http from 'k6/http';
import { sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MANAGEMENT_URL = __ENV.MANAGEMENT_URL || 'http://localhost:9090';
const PGWEB_URL = __ENV.PGWEB_URL || 'http://localhost:8081';
const ORDERS = Number(__ENV.ORDERS || 1000000);
const CUSTOMERS = 200000;
const READS_PER_SECOND = 40;
const WRITES_PER_SECOND = 10;
const TARGET = READS_PER_SECOND + WRITES_PER_SECOND;
const SAMPLE_SECONDS = 5;
const RESOLVED_AFTER_SECONDS = 60;

const between1And = (max) => 1 + Math.floor(Math.random() * max);

// Clients give up after 10 s, as a browser or an upstream gateway would.
const CLIENT = { timeout: '10s', tags: { name: 'user' }, headers: { 'Content-Type': 'application/json' } };

const INCIDENTS = {
  1: {
    title: 'Incident 1: the Friday deploy',
    healthy: (app, db) => app.rate >= 0.95 * TARGET && app.errorRatio === 0 && db.createdAtIndex && !db.locked,
  },
};

const incident = INCIDENTS[__ENV.INCIDENT];
if (!incident) {
  throw new Error(`INCIDENT must be one of: ${Object.keys(INCIDENTS).join(', ')}`);
}

export const options = {
  discardResponseBodies: true,
  // Timeouts and refused connections throw instead of logging one warning per request, which would
  // bury the dashboard. The user functions swallow them: the dashboard shows answered < offered.
  throw: true,
  scenarios: {
    reads: rate('read', READS_PER_SECOND),
    writes: rate('write', WRITES_PER_SECOND),
    dashboard: { executor: 'constant-vus', exec: 'dashboard', vus: 1, duration: '4h' },
  },
};

function rate(exec, perSecond) {
  return { executor: 'constant-arrival-rate', exec, rate: perSecond, timeUnit: '1s', duration: '4h', preAllocatedVUs: 20, maxVUs: 1000 };
}

export function read() {
  attempt(() => http.get(`${BASE_URL}/api/orders/${between1And(ORDERS)}`, CLIENT));
}

export function write() {
  const body = JSON.stringify({ customerId: between1And(CUSTOMERS), total: (5 + Math.random() * 495).toFixed(2) });
  attempt(() => http.post(`${BASE_URL}/api/orders`, body, CLIENT));
}

function attempt(call) {
  try {
    call();
  } catch (timeoutOrRefused) {
    // Counted where it matters: this customer was not served.
  }
}

// ── Dashboard ──────────────────────────────────────────────────────────────────────────────────

const DB_QUERY = `
SELECT
  (SELECT count(*) FROM databasechangelog)                                        AS changesets,
  (SELECT count(*) > 0 FROM pg_indexes WHERE indexname = 'orders_created_at_idx') AS created_at_index,
  (SELECT locked FROM databasechangeloglock WHERE id = 1)                         AS locked,
  (SELECT lockedby FROM databasechangeloglock WHERE id = 1)                       AS locked_by,
  (SELECT extract(epoch FROM localtimestamp - lockgranted)::int
     FROM databasechangeloglock WHERE id = 1)                                     AS locked_seconds,
  (SELECT extract(epoch FROM now() - query_start)::int FROM pg_stat_activity
     WHERE state = 'active' AND query ILIKE 'CREATE INDEX%' LIMIT 1)              AS index_build_seconds,
  (SELECT n_live_tup FROM pg_stat_user_tables WHERE relname = 'orders')           AS orders_rows`;

// Dashboard state lives in its single VU.
let previous = null;
let lastUptime = null;
let healthySince = null;
let sawIncident = false;
let resolved = false;
let started = false;

export function dashboard() {
  if (!started) {
    started = true;
    print(`${incident.title}. Traffic: ${READS_PER_SECOND} reads/s + ${WRITES_PER_SECOND} orders placed/s. One line every ${SAMPLE_SECONDS} s.`);
  }
  sleep(SAMPLE_SECONDS);

  const db = database();
  const current = application();
  const columns = [];

  let app = null;
  if (current === null) {
    columns.push('orders-api NOT ANSWERING');
    previous = null;
  } else {
    if (lastUptime !== null && current.uptime < lastUptime) {
      print(`${clock()}  ── orders-api restarted ──`);
      previous = null;
    }
    lastUptime = current.uptime;
    if (previous !== null) {
      app = delta(previous, current);
      columns.push(
        `answered ${fixed(app.rate, 0).padStart(2)}/s of ${TARGET}`,
        `errors ${percent(app.errorRatio).padStart(4)}`,
        `p99 ${seconds(app.p99).padStart(6)}`,
      );
    } else {
      columns.push('orders-api answering');
    }
    previous = current;
  }
  columns.push(...describe(db));

  const healthy = app !== null && db.ok && incident.healthy(app, db);
  if (!healthy) {
    sawIncident = sawIncident || current === null || db.locked;
    healthySince = null;
    resolved = false;
  } else if (healthySince === null) {
    healthySince = Date.now();
  }
  let status = '';
  if (healthy && sawIncident && !resolved && Date.now() - healthySince >= RESOLVED_AFTER_SECONDS * 1000) {
    resolved = true;
    status = '  RESOLVED: release 1.1 serving for 60 s';
  }
  print(`${clock()}  ${columns.join('  │ ')}${status}`);
}

function application() {
  let response;
  try {
    response = http.get(`${MANAGEMENT_URL}/actuator/prometheus`, { responseType: 'text', timeout: '4s', tags: { name: 'dashboard' } });
  } catch (unreachable) {
    return null;
  }
  return response.status === 200 ? sample(parse(response.body)) : null;
}

function database() {
  let response;
  try {
    response = http.get(`${PGWEB_URL}/api/query?query=${encodeURIComponent(DB_QUERY)}`, { responseType: 'text', timeout: '4s', tags: { name: 'dashboard' } });
  } catch (unreachable) {
    return { ok: false, reason: 'database: pgweb not answering' };
  }
  if (response.status !== 200) {
    return { ok: false, reason: 'database: no Liquibase tables yet' };
  }
  const row = firstRow(JSON.parse(response.body));
  if (row === null) {
    return { ok: false, reason: 'database: no answer' };
  }
  return {
    ok: true,
    changesets: Number(row.changesets),
    createdAtIndex: row.created_at_index === true,
    locked: row.locked === true,
    lockedBy: row.locked_by,
    lockedSeconds: row.locked_seconds,
    indexBuildSeconds: row.index_build_seconds,
    ordersRows: Number(row.orders_rows || 0),
  };
}

// pgweb returns rows as objects keyed by column (or as arrays in older versions).
function firstRow(result) {
  const rows = result.rows || [];
  if (rows.length === 0) return null;
  const row = rows[0];
  if (!Array.isArray(row)) return row;
  const named = {};
  result.columns.forEach((column, i) => { named[column] = row[i]; });
  return named;
}

function describe(db) {
  if (!db.ok) return [db.reason];
  const columns = [
    `orders ${millions(db.ordersRows)}`,
    `changesets ${db.changesets}`,
    `created_at index ${db.createdAtIndex ? 'yes' : 'no '}`,
    db.locked ? `LOCK HELD by ${db.lockedBy} for ${duration(db.lockedSeconds)}` : 'lock free',
  ];
  if (db.indexBuildSeconds !== null && db.indexBuildSeconds !== undefined) {
    columns.push(`CREATE INDEX running for ${duration(db.indexBuildSeconds)}`);
  }
  return columns;
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
  const onOrders = (labels) => (labels.uri || '').startsWith('/api/orders');
  const buckets = {};
  series
    .filter((s) => s.name === 'http_server_requests_seconds_bucket' && onOrders(s.labels))
    .forEach((s) => { buckets[s.labels.le] = (buckets[s.labels.le] || 0) + s.value; });
  return {
    at: Date.now(),
    requests: total(series, 'http_server_requests_seconds_count', onOrders),
    errors: total(series, 'http_server_requests_seconds_count', (l) => onOrders(l) && (l.status || '').startsWith('5')),
    buckets,
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

function duration(totalSeconds) {
  const s = Number(totalSeconds) || 0;
  return s < 60 ? `${s} s` : `${Math.floor(s / 60)} min ${String(s % 60).padStart(2, '0')} s`;
}

function millions(rows) {
  return rows >= 1000000 ? `${(rows / 1000000).toFixed(1)}M` : `${Math.round(rows / 1000)}k`;
}
