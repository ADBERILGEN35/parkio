/**
 * W01F phase B/C: admissions enabled lifecycle, then expect operator to
 * re-disable and re-run checks. Synthetic PENDING/CONFIRMED/WITHDRAWN preserved.
 */
import { writeFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

const ev = process.env.W01F_EVDIR;
if (!ev) throw new Error('W01F_EVDIR required');
mkdirSync(ev, { recursive: true });

const GATEWAY = process.env.GATEWAY_BASE || 'http://127.0.0.1:18091';
const MOCK = process.env.MOCK_BASE || 'http://127.0.0.1:18081';
const PG = process.env.W01F_PG_CONTAINER || 'w01f-pg';
const REDIS = process.env.W01F_REDIS_CONTAINER || 'w01f-redis';
const DB = process.env.W01F_DB || 'parkio_gateway';
const results = [];

function note(name, ok, detail = '') {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}: ${name}${detail ? ' — ' + detail : ''}`);
}

async function json(method, path, body, headers = {}) {
  const res = await fetch(`${GATEWAY}${path}`, {
    method,
    headers: {
      Accept: 'application/json',
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...headers,
    },
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

async function flushRedis() {
  const { spawnSync } = await import('node:child_process');
  spawnSync('docker', ['exec', REDIS, 'redis-cli', 'FLUSHALL'], { encoding: 'utf8' });
}

async function mockMode(mode) {
  await fetch(`${MOCK}/__mock/mode`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mode }),
  });
}

async function mockState() {
  return (await fetch(`${MOCK}/__mock/state`)).json();
}

async function mockTokens() {
  return (await fetch(`${MOCK}/__mock/tokens`)).json();
}

function payload(email, locale = 'tr') {
  return {
    email,
    consentTimestamp: new Date().toISOString(),
    source: 'parkio.dev-landing',
    locale,
  };
}

await flushRedis();
await mockMode('ok');

const pendingEmail = 'w01f-pending@example.test';
const confirmEmail = 'w01f-confirm@example.test';
const withdrawEmail = 'w01f-withdraw@example.test';

{
  const r = await json('POST', '/api/v1/waitlist', payload(pendingEmail));
  const status = await sql(`SELECT status FROM waitlist_interest WHERE email='${pendingEmail}'`);
  const tokens = await mockTokens();
  writeFileSync(
    resolve(ev, 'pending-withdraw-token.txt'),
    tokens.withdrawToken || '',
    'utf8',
  );
  note('enabled_registration_pending', r.status === 202 && status === 'PENDING' && Boolean(tokens.withdrawToken), `http=${r.status} db=${status}`);
}

{
  const r = await json('POST', '/api/v1/waitlist', payload(confirmEmail));
  const tokens = await mockTokens();
  const c = await json('POST', '/api/v1/waitlist/confirm', { token: tokens.confirmToken });
  const status = await sql(`SELECT status FROM waitlist_interest WHERE email='${confirmEmail}'`);
  note(
    'enabled_confirm_works',
    r.status === 202 && c.status === 202 && status === 'CONFIRMED',
    `reg=${r.status} confirm=${c.status} db=${status}`,
  );
}

{
  const r = await json('POST', '/api/v1/waitlist', payload(withdrawEmail));
  const tokens = await mockTokens();
  const w = await json('POST', '/api/v1/waitlist/withdraw', { token: tokens.withdrawToken });
  const status = await sql(
    `SELECT status FROM waitlist_interest WHERE email LIKE 'withdrawn-%@invalid.local' ORDER BY created_at DESC LIMIT 1`,
  );
  note('enabled_withdraw_works', r.status === 202 && w.status === 202 && status === 'WITHDRAWN', `http=${w.status} db=${status}`);
}

const countsBefore = {
  pending: await sql(`SELECT COUNT(*) FROM waitlist_interest WHERE status='PENDING'`),
  confirmed: await sql(`SELECT COUNT(*) FROM waitlist_interest WHERE status='CONFIRMED'`),
  withdrawn: await sql(`SELECT COUNT(*) FROM waitlist_interest WHERE status='WITHDRAWN'`),
  flyway: await sql(`SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1`),
};

writeFileSync(resolve(ev, 'pre-redisable-counts.json'), JSON.stringify(countsBefore, null, 2));
writeFileSync(resolve(ev, 'http-phase-b-enabled.json'), JSON.stringify({ results, countsBefore }, null, 2));
console.log('PHASE_B_DONE counts=', JSON.stringify(countsBefore));
console.log('Restart gateway with admissions DISABLED, then run http-phase-c-redisable.mjs');
