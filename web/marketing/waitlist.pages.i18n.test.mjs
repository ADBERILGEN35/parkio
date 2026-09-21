import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const root = resolve(dirname(fileURLToPath(import.meta.url)));
const i18nSource = readFileSync(resolve(root, 'i18n.js'), 'utf8');
const confirmHtml = readFileSync(resolve(root, 'waitlist/confirm/index.html'), 'utf8');
const withdrawHtml = readFileSync(resolve(root, 'waitlist/unsubscribe/index.html'), 'utf8');
const waitlistJs = readFileSync(resolve(root, 'waitlist.js'), 'utf8');

function loadI18n(search = '') {
  const sandbox = {
    window: {},
    document: {
      documentElement: { lang: 'tr' },
      querySelectorAll: () => [],
      querySelector: () => null,
      title: '',
      addEventListener: () => {},
      dispatchEvent: () => true,
    },
    localStorage: {
      store: {},
      getItem(k) {
        return this.store[k] || null;
      },
      setItem(k, v) {
        this.store[k] = String(v);
      },
    },
    location: { search },
    URLSearchParams,
    CustomEvent: class CustomEvent {
      constructor(type, init) {
        this.type = type;
        this.detail = init && init.detail;
      }
    },
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  vm.runInNewContext(i18nSource, sandbox);
  return sandbox.ParkioI18n;
}

test('confirm and withdraw pages wire all visible copy through i18n keys', () => {
  for (const html of [confirmHtml, withdrawHtml]) {
    assert.match(html, /data-i18n="brand\.tagline"/);
    assert.match(html, /data-i18n="waitlist\.page\.kicker"/);
    assert.match(html, /i18n\.js\?v=w01l6/);
    assert.match(html, /waitlist\.js\?v=w01l6/);
    assert.doesNotMatch(html, /GET istekleri/);
    assert.doesNotMatch(html, /Alternatif silme talepleri/);
  }
  assert.match(confirmHtml, /data-i18n="waitlist\.page\.confirm\.note"/);
  assert.match(withdrawHtml, /data-i18n="waitlist\.page\.withdraw\.note"/);
});

test('EN dictionary localizes confirm/withdraw chrome without HTTP jargon', () => {
  const i18n = loadI18n('?lang=en');
  assert.equal(i18n.resolveLocale(), 'en');
  assert.equal(i18n.t('en', 'brand.tagline'), 'Parking intelligence');
  assert.equal(i18n.t('en', 'waitlist.page.kicker'), 'Registration notice');
  assert.equal(
    i18n.t('en', 'waitlist.page.confirm.note'),
    'Press Confirm to complete your subscription. Support: info@parkio.dev',
  );
  assert.equal(
    i18n.t('en', 'waitlist.page.withdraw.note'),
    'For support or deletion requests: info@parkio.dev',
  );
  assert.doesNotMatch(i18n.t('en', 'waitlist.page.confirm.note'), /GET/i);
  assert.equal(
    i18n.t('tr', 'waitlist.page.confirm.note'),
    'Kaydınızı tamamlamak için onay düğmesine basın. Destek: info@parkio.dev',
  );
});

test('TR dictionary keeps confirm/withdraw guidance without implementation terms', () => {
  const i18n = loadI18n('?lang=tr');
  assert.equal(i18n.resolveLocale(), 'tr');
  assert.equal(i18n.t('tr', 'brand.tagline'), 'Park zekâsı');
  assert.equal(i18n.t('tr', 'waitlist.page.kicker'), 'Kayıt bildirimi');
  assert.doesNotMatch(i18n.t('tr', 'waitlist.page.confirm.note'), /GET/i);
  assert.doesNotMatch(i18n.t('tr', 'waitlist.page.withdraw.note'), /Alternatif silme talepleri/);
});

test('token action success copy comes from i18n keys', () => {
  assert.match(waitlistJs, /waitlist\.page\.confirm\.success/);
  assert.match(waitlistJs, /waitlist\.page\.withdraw\.success/);
  assert.doesNotMatch(waitlistJs, /Your email is confirmed on the registration notification list/);
});

test('link lang query wins over stored preference', () => {
  const i18n = loadI18n('?lang=en');
  i18n.applyLocale('tr');
  // Fresh load with lang=en should still resolve en from URL before storage
  const again = loadI18n('?token=fixture&lang=en');
  assert.equal(again.resolveLocale(), 'en');
});
