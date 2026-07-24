'use strict';

const { describe, it, mock } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');

const {
  resolveSmokeConfig,
  validateCoordinates,
  redactSecrets,
} = require('./parking-session-smoke-config.cjs');
const { SmokeRunner, main } = require('./parking-session-smoke-runner.cjs');

function baseEnv(overrides = {}) {
  return {
    PARKIO_DEPLOYMENT_PROFILE: 'hosted-beta',
    PARKIO_SMOKE_CONFIRM_TARGET: 'beta',
    PARKIO_SMOKE_DISPOSABLE_ACCOUNT: 'I_CONFIRM_DISPOSABLE',
    PARKIO_SMOKE_BASE_URL: 'http://127.0.0.1:8080',
    PARKIO_SMOKE_USER_A_EMAIL: 'smoke-a@example.test',
    PARKIO_SMOKE_USER_A_PASSWORD: 'SecretPass123!',
    ...overrides,
  };
}

function headerValue(headers, name) {
  if (!headers) return undefined;
  if (headers instanceof Headers) return headers.get(name);
  return headers[name] ?? headers[name.toLowerCase()];
}

function jsonResponse(body, status, extraHeaders = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...extraHeaders },
  });
}

describe('resolveSmokeConfig fail-closed', () => {
  it('rejects missing confirm, disposable, credentials, and profile', () => {
    const cfg = resolveSmokeConfig({});
    assert.equal(cfg.ok, false);
    const joined = cfg.errors.join(' ');
    assert.match(joined, /PARKIO_DEPLOYMENT_PROFILE/);
    assert.match(joined, /PARKIO_SMOKE_CONFIRM_TARGET/);
    assert.match(joined, /PARKIO_SMOKE_DISPOSABLE_ACCOUNT/);
    assert.match(joined, /credentials/i);
  });

  it('rejects production-like host api.parkio.com', () => {
    const cfg = resolveSmokeConfig(
      baseEnv({ PARKIO_SMOKE_BASE_URL: 'https://api.parkio.com' }),
    );
    assert.equal(cfg.ok, false);
    assert.ok(cfg.errors.some((e) => e.includes('production-like')));
  });

  it('rejects azure-hosted-beta without https or wrong host', () => {
    const httpCfg = resolveSmokeConfig(
      baseEnv({
        PARKIO_DEPLOYMENT_PROFILE: 'azure-hosted-beta',
        PARKIO_SMOKE_BASE_URL: 'http://api.parkio.dev',
      }),
    );
    assert.equal(httpCfg.ok, false);
    assert.ok(httpCfg.errors.some((e) => e.includes('https')));

    const wrongHost = resolveSmokeConfig(
      baseEnv({
        PARKIO_DEPLOYMENT_PROFILE: 'azure-hosted-beta',
        PARKIO_SMOKE_BASE_URL: 'https://api.beta.parkio.dev',
      }),
    );
    assert.equal(wrongHost.ok, false);
    assert.ok(wrongHost.errors.some((e) => e.includes('api.parkio.dev')));
  });

  it('defaults request pacing to 150 ms and accepts zero', () => {
    assert.equal(resolveSmokeConfig(baseEnv()).requestDelayMs, 150);
    assert.equal(
      resolveSmokeConfig(baseEnv({ PARKIO_SMOKE_REQUEST_DELAY_MS: '0' })).requestDelayMs,
      0,
    );
  });

  it('enables owner isolation only when both User B credentials exist', () => {
    const configured = resolveSmokeConfig(baseEnv({
      PARKIO_SMOKE_USER_B_EMAIL: 'smoke-b@example.test',
      PARKIO_SMOKE_USER_B_PASSWORD: 'SecretPass456!',
    }));
    assert.equal(configured.hasUserB, true);
    assert.equal(
      resolveSmokeConfig(baseEnv({ PARKIO_SMOKE_USER_B_EMAIL: 'smoke-b@example.test' })).hasUserB,
      false,
    );
  });
});


describe('validateCoordinates', () => {
  it('rejects invalid coordinates', () => {
    assert.match(String(validateCoordinates(NaN, 0)), /finite/);
    assert.match(String(validateCoordinates(91, 0)), /range/);
    assert.match(String(validateCoordinates(0, 181)), /range/);
    assert.equal(validateCoordinates(41, 29), null);
  });
});

describe('redactSecrets', () => {
  it('redacts bearer, password, tokens, and lat/lng fields', () => {
    const secrets = ['SuperSecret', 'user@secret.test'];
    const raw =
      'Bearer abc.def token password "password":"SuperSecret" ' +
      '"accessToken":"tok123" "refreshToken":"ref456" ' +
      '"latitude":41.01 "longitude":29.0 user@secret.test';
    const out = redactSecrets(raw, secrets);
    assert.doesNotMatch(out, /SuperSecret/);
    assert.doesNotMatch(out, /user@secret\.test/);
    assert.match(out, /Bearer \[REDACTED\]/);
    assert.match(out, /"password":"\[REDACTED\]"/);
    assert.match(out, /"accessToken":"\[REDACTED\]"/);
    assert.match(out, /"refreshToken":"\[REDACTED\]"/);
    assert.match(out, /"latitude":"\[REDACTED\]"/);
    assert.match(out, /"longitude":"\[REDACTED\]"/);
  });
});

describe('SmokeRunner mocked minimal run', () => {
  it('main returns exit 2 when disposable confirmation missing', async () => {
    const result = await main(
      baseEnv({ PARKIO_SMOKE_DISPOSABLE_ACCOUNT: 'NOPE' }),
      { log: () => {} },
    );
    assert.equal(result.exitCode, 2);
    assert.equal(result.summary, null);
  });

  it('PS-HB-01..11 with delete probe 500 yields exit 1', async () => {
    const env = baseEnv({
      PARKIO_SMOKE_RUN_ID: 'test-run-delete-fail',
      PARKIO_SMOKE_REQUEST_DELAY_MS: '0',
    });
    const config = resolveSmokeConfig(env);
    assert.equal(config.ok, true);

    let sessionId = null;
    let loginCount = 0;
    let completedId = null;
    const idempotentStarts = new Map();

    const fetchImpl = mock.fn(async (url, init = {}) => {
      const u = String(url);
      const method = (init.method || 'GET').toUpperCase();
      const idem = headerValue(init.headers, 'Idempotency-Key');

      if (u.endsWith('/actuator/health') && method === 'GET') {
        return new Response('{"status":"UP"}', { status: 200 });
      }
      if (u.endsWith('/auth/login') && method === 'POST') {
        loginCount += 1;
        return jsonResponse({ accessToken: `token-${loginCount}` }, 200);
      }
      if (u.includes('/parking/sessions/active') && method === 'GET') {
        if (sessionId === null) {
          return new Response(null, { status: 204, headers: { 'Cache-Control': 'no-store' } });
        }
        return jsonResponse({ id: sessionId, status: 'ACTIVE', parkingSource: 'MANUAL' }, 200);
      }
      if (u.endsWith('/parking/sessions') && method === 'POST') {
        if (idem && idempotentStarts.has(idem)) {
          return jsonResponse(
            { id: idempotentStarts.get(idem), status: 'ACTIVE', parkingSource: 'MANUAL' },
            200,
          );
        }
        if (sessionId !== null) {
          return jsonResponse({ code: 'ACTIVE_SESSION_EXISTS' }, 409);
        }
        const id = randomUUID();
        sessionId = id;
        if (idem) idempotentStarts.set(idem, id);
        return jsonResponse({ id, status: 'ACTIVE', parkingSource: 'MANUAL' }, 201);
      }
      if (u.includes('/complete') && method === 'POST') {
        completedId = sessionId;
        sessionId = null;
        return jsonResponse(
          { id: completedId, status: 'COMPLETED', endedAt: new Date().toISOString() },
          200,
        );
      }
      if (u.includes('/parking/sessions/history') && method === 'GET') {
        const items = completedId ? [{ id: completedId, status: 'COMPLETED' }] : [];
        return jsonResponse({ items }, 200);
      }
      if (method === 'DELETE' && u.includes('/parking/sessions/') && !u.endsWith('/history')) {
        return new Response('server error', { status: 500 });
      }
      if (method === 'DELETE' && u.endsWith('/parking/sessions/history')) {
        return new Response(null, { status: 204 });
      }
      if (u.includes('/cancel') && method === 'POST') {
        sessionId = null;
        return jsonResponse({ status: 'CANCELLED' }, 200);
      }
      return new Response('not found', { status: 404 });
    });

    const runner = new SmokeRunner(config, { fetchImpl, log: () => {} });
    const summary = await runner.run();
    assert.equal(summary.exitCode, 1);
    const probe = summary.checks.find((c) => c.id === 'PS-HB-04b');
    assert.ok(probe);
    assert.equal(probe.status, 'FAIL');
    const hb11 = summary.checks.find((c) => c.id === 'PS-HB-11');
    assert.ok(hb11);
    assert.equal(hb11.status, 'PASS');
  });
});

describe('SmokeRunner hosted request policy', () => {
  function runnerFor(fetchImpl, sleep, overrides = {}) {
    const config = resolveSmokeConfig(baseEnv({
      PARKIO_SMOKE_REQUEST_DELAY_MS: '0',
      ...overrides,
    }));
    return new SmokeRunner(config, { fetchImpl, sleep, log: () => {} });
  }

  it('retries only HTTP 429 with 250/500/1000 ms backoff', async () => {
    const statuses = [429, 429, 429, 204];
    const sleeps = [];
    const fetchImpl = mock.fn(async () => new Response(null, { status: statuses.shift() }));
    const runner = runnerFor(fetchImpl, async (ms) => sleeps.push(ms));

    const result = await runner.request('DELETE', '/parking/sessions/history');

    assert.equal(result.status, 204);
    assert.equal(fetchImpl.mock.callCount(), 4);
    assert.deepEqual(sleeps, [250, 500, 1000]);
  });

  it('returns the fourth 429 after retry exhaustion', async () => {
    const sleeps = [];
    const fetchImpl = mock.fn(async () => new Response(null, { status: 429 }));
    const runner = runnerFor(fetchImpl, async (ms) => sleeps.push(ms));

    const result = await runner.request('GET', '/parking/sessions/active');

    assert.equal(result.status, 429);
    assert.equal(fetchImpl.mock.callCount(), 4);
    assert.deepEqual(sleeps, [250, 500, 1000]);
  });

  it('does not retry HTTP 409', async () => {
    const sleeps = [];
    const fetchImpl = mock.fn(async () => jsonResponse(
      { code: 'PARKING_SESSION_NOT_TERMINAL' },
      409,
    ));
    const runner = runnerFor(fetchImpl, async (ms) => sleeps.push(ms));

    const result = await runner.request('DELETE', `/parking/sessions/${randomUUID()}`);

    assert.equal(result.status, 409);
    assert.equal(fetchImpl.mock.callCount(), 1);
    assert.deepEqual(sleeps, []);
  });

  it('paces consecutive logical requests using the configured delay', async () => {
    const sleeps = [];
    const fetchImpl = mock.fn(async () => new Response(null, { status: 204 }));
    const runner = runnerFor(
      fetchImpl,
      async (ms) => sleeps.push(ms),
      { PARKIO_SMOKE_REQUEST_DELAY_MS: '150' },
    );

    await runner.request('GET', '/parking/sessions/active');
    await runner.request('GET', '/parking/sessions/active');

    assert.equal(fetchImpl.mock.callCount(), 2);
    assert.deepEqual(sleeps, [150]);
  });

  it('retries a cleanup 429 and continues through delete and bulk cleanup', async () => {
    const activeId = randomUUID();
    let cancelCalls = 0;
    const requested = [];
    const fetchImpl = mock.fn(async (url, init = {}) => {
      const method = (init.method || 'GET').toUpperCase();
      const path = new URL(String(url)).pathname;
      requested.push(`${method} ${path}`);
      if (method === 'GET' && path.endsWith('/active')) {
        return jsonResponse({ id: activeId, status: 'ACTIVE' }, 200);
      }
      if (method === 'POST' && path.endsWith('/cancel')) {
        cancelCalls += 1;
        return cancelCalls === 1
          ? new Response(null, { status: 429 })
          : jsonResponse({ id: activeId, status: 'CANCELLED' }, 200);
      }
      if (method === 'DELETE') return new Response(null, { status: 204 });
      if (method === 'GET' && path.endsWith('/history')) {
        return jsonResponse({ items: [] }, 200);
      }
      return new Response(null, { status: 404 });
    });
    const runner = runnerFor(fetchImpl, async () => {});
    runner.ownedSessionIds.add(activeId);

    await runner.runCleanup('token-a', null);

    assert.equal(cancelCalls, 2);
    assert.equal(runner.cleanup.status, 'PASS');
    assert.ok(requested.some((request) => request === `DELETE /api/v1/parking/sessions/${activeId}`));
    assert.ok(requested.some((request) => request === 'DELETE /api/v1/parking/sessions/history'));
  });
});