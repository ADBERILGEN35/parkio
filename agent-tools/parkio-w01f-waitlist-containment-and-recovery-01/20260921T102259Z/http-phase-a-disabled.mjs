/**
 * W01F isolated containment + recovery HTTP exercise.
 * Requires: gateway on GATEWAY_BASE, email mock on MOCK_BASE,
 * docker containers w01f-pg / w01f-redis.
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

async function sql(q, database = DB) {
  const { spawnSync } = await import('node:child_process');
  const r = spawnSync(
    'docker',
    ['exec', PG, 'psql', '-U', 'w01f', '-d', database, '-t', '-A', '-c', q],
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
  const res = await fetch(`${MOCK}/__mock/state`);
  return res.json();
}

async function mockTokens() {
  const res = await fetch(`${MOCK}/__mock/tokens`);
  if (!res.ok) return null;
  return res.json();
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
const sendsBefore = (await mockState()).sends;

// Phase A: admissions disabled (default process config for this phase)
{
  const email = 'w01f-blocked@example.test';
  const r = await json('POST', '/api/v1/waitlist', payload(email));
  const count = await sql(`SELECT COUNT(*) FROM waitlist_interest WHERE email='${email}'`);
  const sends = (await mockState()).sends;
  note(
    'disabled_submit_no_write_no_email',
    r.status === 503 && r.body?.code === 'WAITLIST_ADMISSIONS_DISABLED' && count === '0' && sends === sendsBefore,
    `http=${r.status} code=${r.body?.code} count=${count} sends=${sends}`,
  );

  const resend = await json('POST', '/api/v1/waitlist/resend', { email });
  const sends2 = (await mockState()).sends;
  note(
    'disabled_resend_no_email',
    resend.status === 503 && resend.body?.code === 'WAITLIST_ADMISSIONS_DISABLED' && sends2 === sendsBefore,
    `http=${resend.status} sends=${sends2}`,
  );
}

// Health / non-waitlist surface still up
{
  const health = await fetch(`${GATEWAY}/actuator/health`);
  note('health_up_during_containment', health.status === 200, `http=${health.status}`);
}

writeFileSync(resolve(ev, 'http-containment-phase-a.json'), JSON.stringify({ results, note: 'phase A before admissions enable restart' }, null, 2));
console.log('PHASE_A_DONE — restart gateway with admissions enabled, then re-run phase B script or continue via W01F_PHASE=b');
