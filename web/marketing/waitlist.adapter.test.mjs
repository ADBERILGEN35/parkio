import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const root = resolve(dirname(fileURLToPath(import.meta.url)));
const source = readFileSync(resolve(root, 'waitlist.js'), 'utf8');

function loadWaitlist({ search = '', modeMeta = null } = {}) {
  const metas = {};
  if (modeMeta != null) metas['parkio-waitlist-mode'] = { content: modeMeta };
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
    fetch: async () => {
      throw new Error('fetch should not be called in isolated unit checks');
    },
    setTimeout,
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  vm.runInNewContext(source, sandbox);
  return sandbox.ParkioWaitlist;
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
