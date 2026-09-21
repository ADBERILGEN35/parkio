import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const root = resolve(dirname(fileURLToPath(import.meta.url)));
const source = readFileSync(resolve(root, 'waitlist.js'), 'utf8');

const sandbox = {
  window: {},
  document: {
    querySelector: () => null,
    getElementById: () => null,
    addEventListener: () => {},
  },
  location: { search: '?waitlistMock=1' },
  URLSearchParams,
  fetch: async () => {
    throw new Error('fetch should not be called in isolated unit checks');
  },
  setTimeout,
};
sandbox.window = sandbox;
sandbox.globalThis = sandbox;
vm.runInNewContext(source, sandbox);

const api = sandbox.ParkioWaitlist;
assert.equal(api.detectMode(), 'mock');
assert.equal(api.isValidEmail('synthetic@example.com'), true);
assert.equal(api.isValidEmail('not-an-email'), false);
assert.equal(api.normalizeEmail('  A@B.COM '), 'a@b.com');

const accepted = await api.submitMock({
  email: 'synthetic@example.com',
  consentTimestamp: new Date().toISOString(),
  source: 'parkio.dev-landing',
  locale: 'tr',
});
assert.equal(accepted.ok, true);
assert.equal(accepted.mock, true);
assert.equal(api._mockStore.has('synthetic@example.com'), true);

test('waitlist helpers validate email and mock submit is isolated', () => {
  assert.ok(true);
});
