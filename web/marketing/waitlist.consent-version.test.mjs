// CL-F18: the consent text the form shows is registered in the gateway under a version id. These
// pins must equal services/gateway-service/.../WaitlistConsentText.java and
// docs/architecture/waitlist-consent-text-versions.md. Changing the wording without a new version
// fails here and in the gateway tests.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const root = resolve(dirname(fileURLToPath(import.meta.url)));
const waitlistSource = readFileSync(resolve(root, 'waitlist.js'), 'utf8');
const i18nSource = readFileSync(resolve(root, 'i18n.js'), 'utf8');
const indexHtml = readFileSync(resolve(root, 'index.html'), 'utf8');

const REGISTERED = {
  version: 'waitlist-consent-v1',
  tr: '20f3d2355fd83a52b6753c1a86fbfe8afc929e3e916cb0b34e9add4d6294824c',
  en: 'eed8440ccbfa389bd22e915b39ce5de1fbf27ef01b9158368c7fa8b0e2fd0a75',
};

function sha256(text) {
  return createHash('sha256').update(text, 'utf8').digest('hex');
}

function loadI18n() {
  const sandbox = {
    window: {},
    document: { documentElement: { lang: 'tr' }, querySelectorAll: () => [], addEventListener: () => {} },
    localStorage: { getItem: () => null, setItem: () => {} },
    navigator: { language: 'tr' },
    location: { search: '' },
    URLSearchParams,
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  vm.runInNewContext(i18nSource, sandbox);
  return sandbox.ParkioI18n;
}

function loadWaitlist() {
  const sandbox = {
    window: {},
    document: { querySelector: () => null, getElementById: () => null, addEventListener: () => {}, documentElement: { lang: 'tr' } },
    location: { search: '' },
    URLSearchParams,
    fetch: async () => { throw new Error('no network in this test'); },
    setTimeout,
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  vm.runInNewContext(waitlistSource, sandbox);
  return sandbox.ParkioWaitlist;
}

test('the consent checkbox texts are the registered waitlist-consent-v1 texts', () => {
  const i18n = loadI18n();
  assert.equal(sha256(i18n.DICT.tr['waitlist.consent']), REGISTERED.tr, 'TR consent text changed: register a new version');
  assert.equal(sha256(i18n.DICT.en['waitlist.consent']), REGISTERED.en, 'EN consent text changed: register a new version');
  assert.match(indexHtml, /id="waitlist-consent"[^>]*required/);
  assert.match(indexHtml, /data-i18n="waitlist\.consent"/);
});

test('the form sends consent=true and the registered version with every submission', () => {
  const waitlist = loadWaitlist();
  assert.equal(waitlist.CONSENT_TEXT_VERSION, REGISTERED.version);
  const payload = waitlist.buildSubmitPayload({ fullName: 'Ad Soyad', email: 'synthetic@example.com', locale: 'en' });
  assert.equal(payload.consent, true);
  assert.equal(payload.consentTextVersion, REGISTERED.version);
  assert.equal(payload.source, 'parkio.dev-landing');
  assert.equal(payload.locale, 'en');
  assert.equal(payload.email, 'synthetic@example.com');
  assert.match(payload.consentTimestamp, /^\d{4}-\d{2}-\d{2}T/);
  assert.match(waitlistSource, /const payload = buildSubmitPayload\(/, 'the submit handler must use buildSubmitPayload');
});

test('the gateway consent error codes map to the consent feedback', async () => {
  const waitlist = loadWaitlist();
  for (const code of ['WAITLIST_CONSENT_REQUIRED', 'WAITLIST_CONSENT_VERSION_INVALID']) {
    const sandboxFetch = async () => ({ status: 400, json: async () => ({ code }) });
    const module = (() => {
      const sandbox = {
        window: {}, document: { querySelector: () => null, getElementById: () => null, addEventListener: () => {}, documentElement: { lang: 'tr' } },
        location: { search: '' }, URLSearchParams, fetch: sandboxFetch, setTimeout,
      };
      sandbox.window = sandbox; sandbox.globalThis = sandbox;
      vm.runInNewContext(waitlistSource, sandbox);
      return sandbox.ParkioWaitlist;
    })();
    const result = await module.submitApi(module.buildSubmitPayload({ fullName: 'A B', email: 'a@example.com', locale: 'tr' }));
    // Compare fields: the object comes from another vm context, so deepEqual on prototypes would fail.
    assert.equal(result.ok, false);
    assert.equal(result.code, 'CONSENT_REQUIRED');
    assert.equal(waitlist.submitFeedbackKey(result), 'waitlist.error.consent');
  }
});
