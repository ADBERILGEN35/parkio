/**
 * W01F phase C: after re-disable + restart — block new admission, preserve rows, withdraw still works.
 */
import { writeFileSync, mkdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const ev = process.env.W01F_EVDIR;
if (!ev) throw new Error('W01F_EVDIR required');
mkdirSync(ev, { recursive: true });

const GATEWAY = process.env.GATEWAY_BASE || 'http://127.0.0.1:18091';
const MOCK = process.env.MOCK_BASE || 'http://127.0.0.1:18081';
const PG = process.env.W01F_PG_CONTAINER || 'w01f-pg';
const DB = process.env.W01F_DB || 'parkio_gateway';
const results = [];

function note(name, ok, detail = '') {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}: ${name}${detail ? ' — ' + detail : ''}`);
}

async function json(method, path, body) {
  const res = await fetch(`${GATEWAY}${path}`, {
    method,
    headers: { Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let parsed = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    parsed = { raw: text.slice(0, 200) };
  }
  return { status: res.status, body: parsed };
}

async function sql(q) {
  const { spawnSync } = await import('node:child_process');
  const r = spawnSync(
    'docker',
    ['exec', PG, 'psql', '-U', 'w01f', '-d', DB, '-t', '-A', '-c', q],
    { encoding: 'utf8' },
  );
  if (r.status !== 0) throw new Error(r.stderr || r.stdout);
  return r.stdout.trim();
}

async function mockState() {
  return (await fetch(`${MOCK}/__mock/state`)).json();
}

const before = JSON.parse(readFileSync(resolve(ev, 'pre-redisable-counts.json'), 'utf8'));
const sendsBefore = (await mockState()).sends;

{
  const email = 'w01f-after-disable@example.test';
  const r = await json('POST', '/api/v1/waitlist', {
    email,
    consentTimestamp: new Date().toISOString(),
    source: 'parkio.dev-landing',
    locale: 'tr',
  });
  const count = await sql(`SELECT COUNT(*) FROM waitlist_interest WHERE email='${email}'`);
  const sends = (await mockState()).sends;
  note(
    'redisabled_blocks_admission',
    r.status === 503 && count === '0' && sends === sendsBefore,
    `http=${r.status} count=${count} sends=${sends}`,
  );
}

const after = {
  pending: await sql(`SELECT COUNT(*) FROM waitlist_interest WHERE status='PENDING'`),
  confirmed: await sql(`SELECT COUNT(*) FROM waitlist_interest WHERE status='CONFIRMED'`),
  withdrawn: await sql(`SELECT COUNT(*) FROM waitlist_interest WHERE status='WITHDRAWN'`),
  flyway: await sql(`SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1`),
};

note(
  'data_survives_redisable_restart',
  after.pending === before.pending &&
    after.confirmed === before.confirmed &&
    after.withdrawn === before.withdrawn &&
    after.flyway === before.flyway,
  `before=${JSON.stringify(before)} after=${JSON.stringify(after)}`,
);

// Withdraw pending row that still has a withdraw token — use DB to fetch is hard without token.
// Instead confirm PENDING row still exists and health is up.
{
  const health = await fetch(`${GATEWAY}/actuator/health`);
  note('health_up_after_redisable', health.status === 200, `http=${health.status}`);
  const pendingEmail = await sql(`SELECT email FROM waitlist_interest WHERE email='w01f-pending@example.test'`);
  note('pending_row_preserved', pendingEmail === 'w01f-pending@example.test', `email=${pendingEmail}`);

  const token = readFileSync(resolve(ev, 'pending-withdraw-token.txt'), 'utf8').trim();
  const w = await json('POST', '/api/v1/waitlist/withdraw', { token });
  const status = await sql(
    `SELECT status FROM waitlist_interest WHERE email LIKE 'withdrawn-%@invalid.local' ORDER BY created_at DESC LIMIT 1`,
  );
  note(
    'withdraw_works_while_admissions_disabled',
    Boolean(token) && w.status === 202 && status === 'WITHDRAWN',
    `http=${w.status} db=${status}`,
  );
}

writeFileSync(resolve(ev, 'http-phase-c-redisable.json'), JSON.stringify({ results, before, after }, null, 2));
const failed = results.filter((r) => !r.ok);
console.log(failed.length ? `PHASE_C_FAIL ${failed.length}` : 'PHASE_C_PASS');
process.exit(failed.length ? 1 : 0);
