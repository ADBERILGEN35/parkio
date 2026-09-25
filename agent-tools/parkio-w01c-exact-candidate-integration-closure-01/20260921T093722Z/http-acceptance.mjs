/**
 * W01C isolated HTTP acceptance against a live gateway + Postgres + Redis + email mock.
 * Tokens/emails are kept in memory only; evidence gets redacted assertions.
 */
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { setTimeout as sleep } from 'node:timers/promises';
import { writeFileSync, mkdirSync, readFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const ev = process.env.W01C_EVDIR;
if (!ev) throw new Error('W01C_EVDIR required');
mkdirSync(ev, { recursive: true });

const GATEWAY = process.env.GATEWAY_BASE || 'http://127.0.0.1:18090';
const MOCK = process.env.MOCK_BASE || 'http://127.0.0.1:18080';
const results = [];

function note(name, ok, detail = '') {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}: ${name}${detail ? ' — ' + detail : ''}`);
}

async function json(method, path, body, headers = {}) {
  const res = await fetch(`${GATEWAY}${path}`, {
    method,
    headers: { Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}), ...headers },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let parsed = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    parsed = { raw: text.slice(0, 200) };
  }
  return { status: res.status, headers: res.headers, body: parsed };
}

async function flushRedis() {
  const { spawnSync } = await import('node:child_process');
  spawnSync('docker', ['exec', 'w01c-redis', 'redis-cli', 'FLUSHALL'], { encoding: 'utf8' });
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
  // Read process-local tokens via a tiny admin endpoint we add to mock using query on state file is insufficient.
  // Instead, call Resend mock internal by GETting /__mock/tokens if present.
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

async function sql(q) {
  // Use docker exec for assertions (redacted)
  const { spawnSync } = await import('node:child_process');
  const r = spawnSync(
    'docker',
    ['exec', 'w01c-pg', 'psql', '-U', 'w01c', '-d', 'parkio_gateway', '-t', '-A', '-c', q],
    { encoding: 'utf8' },
  );
  if (r.status !== 0) throw new Error(r.stderr || r.stdout);
  return r.stdout.trim();
}

await mockMode('ok');

// 1) registration pending
{
  const email = 'w01c-reg-001@example.test';
  const r = await json('POST', '/api/v1/waitlist', payload(email));
  const status = await sql(`SELECT status FROM waitlist_interest WHERE email='${email}'`);
  const count = await sql(`SELECT COUNT(*) FROM waitlist_interest WHERE email='${email}'`);
  note('registration_creates_pending', r.status === 202 && status === 'PENDING' && count === '1', `http=${r.status} db=${status}`);
}

// 2) concurrent duplicates
{
  const email = 'w01c-dup-001@example.test';
  const body = payload(email);
  const [a, b, c] = await Promise.all([
    json('POST', '/api/v1/waitlist', body),
    json('POST', '/api/v1/waitlist', body),
    json('POST', '/api/v1/waitlist', body),
  ]);
  const count = await sql(`SELECT COUNT(*) FROM waitlist_interest WHERE email='${email}'`);
  note(
    'concurrent_duplicates_single_row',
    count === '1' && [a, b, c].every((x) => x.status === 202 || x.status === 429 || x.status === 503),
    `count=${count} statuses=${[a.status, b.status, c.status].join(',')}`,
  );
}

// 3) provider failure leaves retryable pending
{
  const email = 'w01c-fail-001@example.test';
  await mockMode('fail');
  const r1 = await json('POST', '/api/v1/waitlist', payload(email));
  const status = await sql(`SELECT status FROM waitlist_interest WHERE email='${email}'`);
  const sent = await sql(`SELECT verification_sent_at IS NULL FROM waitlist_interest WHERE email='${email}'`);
  note(
    'provider_failure_keeps_retryable_pending',
    r1.status === 503 && r1.body?.code === 'WAITLIST_EMAIL_DELIVERY_FAILED' && status === 'PENDING' && sent === 't',
    `http=${r1.status} code=${r1.body?.code} sent_null=${sent}`,
  );
  await mockMode('ok');
  const r2 = await json('POST', '/api/v1/waitlist', payload(email));
  note('provider_retry_accepted', r2.status === 202, `http=${r2.status}`);
}

// tokens from mock
let confirmToken = null;
let withdrawToken = null;
{
  // Ask mock for last tokens via extended endpoint
  const tok = await fetch(`${MOCK}/__mock/tokens`).then((r) => r.json());
  confirmToken = tok.confirmToken;
  withdrawToken = tok.withdrawToken;
  note('mock_captured_tokens', Boolean(confirmToken) && Boolean(withdrawToken), 'redacted');
}

// 4) GET cannot confirm; POST can
{
  const g = await fetch(`${GATEWAY}/api/v1/waitlist/confirm?token=${encodeURIComponent(confirmToken || 'x')}`);
  note('get_confirm_rejected', g.status === 405, `http=${g.status}`);
  const p = await json('POST', '/api/v1/waitlist/confirm', { token: confirmToken });
  const status = await sql(`SELECT status FROM waitlist_interest WHERE email='w01c-fail-001@example.test'`);
  note('post_confirm_works', p.status === 202 && status === 'CONFIRMED', `http=${p.status} db=${status}`);
  const replay = await json('POST', '/api/v1/waitlist/confirm', { token: confirmToken });
  note('confirm_replay_idempotent', replay.status === 202, `http=${replay.status}`);
}

// 5) expired token
{
  const email = 'w01c-exp-001@example.test';
  await json('POST', '/api/v1/waitlist', payload(email));
  const tok = await fetch(`${MOCK}/__mock/tokens`).then((r) => r.json());
  await sql(`UPDATE waitlist_interest SET verification_expires_at = TIMESTAMPTZ '2020-01-01 00:00:00+00' WHERE email='${email}'`);
  const r = await json('POST', '/api/v1/waitlist/confirm', { token: tok.confirmToken });
  const status = await sql(`SELECT status FROM waitlist_interest WHERE email='${email}'`);
  note('expired_token_no_transition', r.status === 400 && status === 'PENDING', `http=${r.status} db=${status}`);
}

// 6) superseded token after resend
{
  await flushRedis();
  const email = 'w01c-super-001@example.test';
  const sub = await json('POST', '/api/v1/waitlist', payload(email));
  const oldTok = await fetch(`${MOCK}/__mock/tokens`).then((r) => r.json());
  const resend = await json('POST', '/api/v1/waitlist/resend', { email });
  const newTok = await fetch(`${MOCK}/__mock/tokens`).then((r) => r.json());
  const rotated = oldTok.confirmToken && newTok.confirmToken && oldTok.confirmToken !== newTok.confirmToken;
  const oldConfirm = await json('POST', '/api/v1/waitlist/confirm', { token: oldTok.confirmToken });
  const statusAfterOld = await sql(`SELECT status FROM waitlist_interest WHERE email='${email}'`);
  const newConfirm = await json('POST', '/api/v1/waitlist/confirm', { token: newTok.confirmToken });
  const statusAfterNew = await sql(`SELECT status FROM waitlist_interest WHERE email='${email}'`);
  note(
    'superseded_token_rejected_current_works',
    sub.status === 202 &&
      resend.status === 202 &&
      rotated &&
      oldConfirm.status === 400 &&
      statusAfterOld === 'PENDING' &&
      newConfirm.status === 202 &&
      statusAfterNew === 'CONFIRMED',
    `sub=${sub.status} resend=${resend.status} rotated=${rotated} old=${oldConfirm.status} new=${newConfirm.status}`,
  );
}

// 7) withdraw + reregister
{
  await flushRedis();
  const email = 'w01c-wd-001@example.test';
  const sub = await json('POST', '/api/v1/waitlist', payload(email));
  const tok = await fetch(`${MOCK}/__mock/tokens`).then((r) => r.json());
  const w = await json('POST', '/api/v1/waitlist/withdraw', { token: tok.withdrawToken });
  const withdrawn = await sql(`SELECT COUNT(*) FROM waitlist_interest WHERE status='WITHDRAWN' AND email LIKE 'withdrawn-%@invalid.local'`);
  const again = await json('POST', '/api/v1/waitlist', payload(email));
  const pending = await sql(`SELECT COUNT(*) FROM waitlist_interest WHERE email='${email}' AND status='PENDING'`);
  note(
    'withdraw_and_reregister',
    sub.status === 202 && w.status === 202 && Number(withdrawn) >= 1 && again.status === 202 && pending === '1',
    `sub=${sub.status} withdraw=${w.status} again=${again.status} pending=${pending}`,
  );
}

// 8) rate limit
{
  await flushRedis();
  const emailPrefix = 'w01c-rl';
  const statuses = [];
  for (let i = 0; i < 12; i++) {
    const r = await json('POST', '/api/v1/waitlist', payload(`${emailPrefix}-${i}@example.test`));
    statuses.push(r.status);
  }
  note('redis_rate_limit_trips', statuses.includes(429) && statuses.includes(202), `statuses=${statuses.join(',')}`);
}

// 9) CORS
{
  const okOrigin = await fetch(`${GATEWAY}/api/v1/waitlist`, {
    method: 'OPTIONS',
    headers: {
      Origin: 'https://parkio.dev',
      'Access-Control-Request-Method': 'POST',
      'Access-Control-Request-Headers': 'content-type',
    },
  });
  const allow = okOrigin.headers.get('access-control-allow-origin');
  note('cors_allowed_origin', allow === 'https://parkio.dev', `acao=${allow}`);

  const badOrigin = await fetch(`${GATEWAY}/api/v1/waitlist`, {
    method: 'OPTIONS',
    headers: {
      Origin: 'https://evil.example',
      'Access-Control-Request-Method': 'POST',
      'Access-Control-Request-Headers': 'content-type',
    },
  });
  const badAllow = badOrigin.headers.get('access-control-allow-origin');
  note('cors_disallowed_origin', !badAllow || badAllow === 'null', `acao=${badAllow}`);
}

// 10) restart durability — caller restarts gateway externally then we check counts
{
  const before = await sql(`SELECT COUNT(*) FROM waitlist_interest`);
  writeFileSync(resolve(ev, 'pre-restart-count.txt'), `count=${before}\n`);
  note('pre_restart_rows_recorded', Number(before) > 0, `count=${before}`);
}

writeFileSync(resolve(ev, 'http-acceptance.json'), JSON.stringify({ results }, null, 2));
const failed = results.filter((r) => !r.ok);
process.exit(failed.length ? 1 : 0);
