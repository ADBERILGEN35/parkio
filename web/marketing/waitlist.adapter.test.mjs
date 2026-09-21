import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const root = resolve(dirname(fileURLToPath(import.meta.url)));
const source = readFileSync(resolve(root, 'waitlist.js'), 'utf8');
const htaccess = readFileSync(resolve(root, '.htaccess'), 'utf8');
const indexHtml = readFileSync(resolve(root, 'index.html'), 'utf8');
const i18n = readFileSync(resolve(root, 'i18n.js'), 'utf8');

function loadWaitlist({ search = '', modeMeta = null, fetchImpl } = {}) {
  const metas = {};
  if (modeMeta != null) metas['parkio-waitlist-mode'] = { content: modeMeta };
  metas['parkio-waitlist-api'] = { content: 'https://api.parkio.dev/api/v1' };
  const sandbox = {
    window: {},
    document: {
      querySelector: (sel) => {
        const m = /^meta\[name="([^"]+)"\]$/.exec(sel);
        if (m && metas[m[1]]) return metas[m[1]];
        return null;
      },
      getElementById: () => null,
      addEventListener: () => {},
    },
    location: { search },
    URLSearchParams,
    fetch:
      fetchImpl ||
      (async () => {
        throw new Error('fetch should not be called in isolated unit checks');
      }),
    setTimeout,
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  vm.runInNewContext(source, sandbox);
  return sandbox.ParkioWaitlist;
}

function jsonResponse(status, body) {
  return {
    status,
    json: async () => body,
  };
}

test('mock mode requires explicit meta=mock; query param alone is ignored', async () => {
  const apiIgnored = loadWaitlist({ search: '?waitlistMock=1', modeMeta: 'api' });
  assert.equal(apiIgnored.detectMode(), 'api');

  const launchDefault = loadWaitlist({ search: '?waitlistMock=1' });
  assert.equal(launchDefault.detectMode(), 'api');

  const mock = loadWaitlist({ search: '?waitlistMock=1', modeMeta: 'mock' });
  assert.equal(mock.detectMode(), 'mock');
  assert.equal(mock.isValidEmail('synthetic@example.com'), true);
  assert.equal(mock.isValidEmail('not-an-email'), false);
  assert.equal(mock.normalizeEmail('  A@B.COM '), 'a@b.com');

  const accepted = await mock.submitMock({
    email: 'synthetic@example.com',
    consentTimestamp: new Date().toISOString(),
    source: 'parkio.dev-landing',
    locale: 'tr',
  });
  assert.equal(accepted.ok, true);
  assert.equal(accepted.mock, true);
  assert.equal(mock._mockStore.has('synthetic@example.com'), true);
});

test('unavailable mode does not report mock success and rejects query bypass', () => {
  const unavailable = loadWaitlist({ search: '?waitlistMock=1', modeMeta: 'unavailable' });
  assert.equal(unavailable.detectMode(), 'unavailable');
  const hidden = loadWaitlist({ modeMeta: 'hidden' });
  assert.equal(hidden.detectMode(), 'unavailable');
});

test('submitApi maps known gateway outcomes without treating them as network failure', async () => {
  const cases = [
    [202, { status: 'accepted' }, { ok: true, status: 'accepted' }],
    [429, {}, { ok: false, code: 'RATE_LIMITED' }],
    [503, { code: 'WAITLIST_ADMISSIONS_DISABLED' }, { ok: false, code: 'ADMISSIONS_DISABLED' }],
    [503, { code: 'EMAIL_DELIVERY_FAILED' }, { ok: false, code: 'EMAIL_DELIVERY_FAILED' }],
    [400, { code: 'VALIDATION_ERROR' }, { ok: false, code: 'VALIDATION_ERROR' }],
    [500, {}, { ok: false, code: 'NETWORK_ERROR' }],
  ];
  for (const [status, body, expected] of cases) {
    const api = loadWaitlist({
      modeMeta: 'api',
      fetchImpl: async (url, init) => {
        assert.match(String(url), /\/waitlist$/);
        assert.equal(init.method, 'POST');
        assert.equal(init.credentials, 'omit');
        return jsonResponse(status, body);
      },
    });
    const result = await api.submitApi({
      email: 'synthetic@example.com',
      consentTimestamp: '2026-09-21T13:00:00.000Z',
      source: 'parkio.dev-landing',
      locale: 'tr',
    });
    assert.equal(result.ok, expected.ok, `status ${status} ok`);
    assert.equal(result.status, expected.status, `status ${status} status`);
    assert.equal(result.code, expected.code, `status ${status} code`);
  }
});

test('CSP allows connect-src to api.parkio.dev (W01L root cause)', () => {
  assert.match(
    htaccess,
    /Content-Security-Policy "[^"]*connect-src 'self' https:\/\/api\.parkio\.dev;/,
  );
});

test('submit payload uses past consentTimestamp to tolerate clock skew', async () => {
  let seenBody = null;
  const api = loadWaitlist({
    modeMeta: 'api',
    fetchImpl: async (_url, init) => {
      seenBody = JSON.parse(init.body);
      return jsonResponse(202, { status: 'accepted' });
    },
  });
  const before = Date.now();
  await api.submitApi({
    email: 'synthetic@example.com',
    consentTimestamp: new Date(Date.now() - 120_000).toISOString(),
    source: 'parkio.dev-landing',
    locale: 'tr',
  });
  // Unit calls submitApi with explicit payload; assert helper used by form stays skewed in source.
  const sourceJs = readFileSync(resolve(root, 'waitlist.js'), 'utf8');
  assert.match(sourceJs, /Date\.now\(\)\s*-\s*120_000/);
  assert.ok(seenBody);
  assert.ok(Date.parse(seenBody.consentTimestamp) <= before - 60_000);
});

test('hero CTA and nav waitlist copy are present in canonical marketing', () => {
  assert.match(indexHtml, /id="hero-waitlist-cta"/);
  assert.match(indexHtml, /href="#waitlist"[^>]*data-i18n="cta\.waitlist"/);
  assert.match(indexHtml, /i18n\.js\?v=w01l2/);
  assert.match(i18n, /'cta\.waitlist': 'Bekleme listesine katıl'/);
  assert.match(i18n, /'cta\.waitlist': 'Join the waitlist'/);
  assert.match(i18n, /'nav\.waitlist': 'Bekleme listesi'/);
  assert.match(i18n, /'nav\.waitlist': 'Waitlist'/);
});
