import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const root = resolve(dirname(fileURLToPath(import.meta.url)));
const waitlistSource = readFileSync(resolve(root, 'waitlist.js'), 'utf8');
const i18nSource = readFileSync(resolve(root, 'i18n.js'), 'utf8');

function createSandbox({ search = '', fetchImpl, locale = 'tr', mode = 'api' } = {}) {
  const listeners = {};
  const feedback = {
    textContent: '',
    dataset: {},
    hidden: true,
  };
  const button = {
    textContent: locale === 'en' ? 'Remove me' : 'Listeden çıkar',
    disabled: false,
    dataset: {},
  };
  const form = {
    querySelector(sel) {
      if (sel === '[data-waitlist-feedback]') return feedback;
      if (sel === '[type="submit"]') return button;
      return null;
    },
    addEventListener(type, fn) {
      listeners[`form:${type}`] = fn;
    },
  };
  const docListeners = {};
  const sandbox = {
    window: {},
    document: {
      documentElement: { lang: locale },
      querySelector(sel) {
        if (sel.includes('parkio-waitlist-mode')) return { content: mode };
        if (sel.includes('parkio-waitlist-api')) return { content: 'https://api.parkio.dev/api/v1' };
        if (sel === '[data-waitlist-unavailable-note]') return null;
        return null;
      },
      querySelectorAll(sel) {
        if (sel === '[data-waitlist-feedback]') return [feedback];
        if (sel === '[data-waitlist-busy="1"]') {
          return button.dataset.waitlistBusy === '1' ? [button] : [];
        }
        if (sel === '[data-i18n]') return [];
        if (sel === '[data-lang-option]') return [];
        return [];
      },
      getElementById(id) {
        if (id === 'waitlist-withdraw-form') return form;
        return null;
      },
      addEventListener(type, fn) {
        docListeners[type] = docListeners[type] || [];
        docListeners[type].push(fn);
      },
      dispatchEvent(event) {
        (docListeners[event.type] || []).forEach((fn) => fn(event));
        return true;
      },
    },
    location: { search },
    URLSearchParams,
    fetch: fetchImpl || (async () => ({ status: 202, json: async () => ({}) })),
    setTimeout,
    CustomEvent: class CustomEvent {
      constructor(type, init = {}) {
        this.type = type;
        this.detail = init.detail;
      }
    },
    localStorage: {
      store: {},
      getItem(k) {
        return Object.prototype.hasOwnProperty.call(this.store, k) ? this.store[k] : null;
      },
      setItem(k, v) {
        this.store[k] = String(v);
      },
    },
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  vm.runInNewContext(i18nSource + '\n' + waitlistSource, sandbox);
  // Seed like a real page: link lang then init waitlist.
  const link = sandbox.ParkioI18n.linkLocale();
  sandbox.ParkioI18n.applyLocale(link || locale);
  sandbox.ParkioWaitlist.init();
  return { sandbox, feedback, button, form, listeners };
}

test('withdraw success renders TR and EN from semantic keys', async () => {
  const trEnv = createSandbox({ search: '?token=fixture&lang=tr', locale: 'tr' });
  await trEnv.listeners['form:submit']({ preventDefault() {} });
  assert.equal(trEnv.feedback.dataset.feedbackKey, 'waitlist.page.withdraw.success');
  assert.equal(trEnv.feedback.textContent, 'E-posta adresiniz bildirim listesinden silindi.');

  const enEnv = createSandbox({ search: '?token=fixture&lang=en', locale: 'en' });
  await enEnv.listeners['form:submit']({ preventDefault() {} });
  assert.equal(enEnv.feedback.textContent, 'Your email was removed from the notification list.');
});

test('TR→EN and EN→TR re-render visible feedback without new fetch', async () => {
  let fetches = 0;
  const env = createSandbox({
    search: '?token=fixture&lang=tr',
    locale: 'tr',
    mode: 'api',
    fetchImpl: async () => {
      fetches += 1;
      return { status: 202, json: async () => ({}) };
    },
  });
  await env.listeners['form:submit']({ preventDefault() {} });
  assert.equal(fetches, 1);
  assert.match(env.feedback.textContent, /silindi/);

  env.sandbox.ParkioI18n.applyLocale('en');
  assert.equal(env.feedback.textContent, 'Your email was removed from the notification list.');
  assert.equal(fetches, 1);

  env.sandbox.ParkioI18n.applyLocale('tr');
  assert.equal(env.feedback.textContent, 'E-posta adresiniz bildirim listesinden silindi.');
  assert.equal(fetches, 1);
});

test('locale switch while request pending uses display-time locale', async () => {
  let resolveFetch;
  const pending = new Promise((resolve) => {
    resolveFetch = resolve;
  });
  let fetches = 0;
  const env = createSandbox({
    search: '?token=fixture&lang=tr',
    locale: 'tr',
    mode: 'api',
    fetchImpl: async () => {
      fetches += 1;
      await pending;
      return { status: 202, json: async () => ({}) };
    },
  });
  const submitPromise = env.listeners['form:submit']({ preventDefault() {} });
  assert.equal(env.button.dataset.waitlistBusy, '1');
  assert.equal(env.button.textContent, 'Gönderiliyor…');

  env.sandbox.ParkioI18n.applyLocale('en');
  assert.equal(env.button.textContent, 'Submitting…');
  assert.equal(fetches, 1);

  resolveFetch();
  await submitPromise;
  assert.equal(env.feedback.textContent, 'Your email was removed from the notification list.');
  assert.equal(fetches, 1);
});

test('submitFeedbackKey never exposes raw backend messages', () => {
  const env = createSandbox({ locale: 'en', search: '?token=x&lang=en' });
  assert.equal(env.sandbox.ParkioWaitlist.submitFeedbackKey({ ok: true }), 'waitlist.success');
  assert.equal(
    env.sandbox.ParkioWaitlist.submitFeedbackKey({ ok: false, code: 'RATE_LIMITED' }),
    'waitlist.error.rate',
  );
});

test('email link lang seeds initial locale then switcher wins', () => {
  const env = createSandbox({ search: '?token=x&lang=tr', locale: 'en' });
  assert.equal(env.sandbox.ParkioI18n.resolveLocale(), 'tr');
  env.sandbox.ParkioI18n.applyLocale('en');
  assert.equal(env.sandbox.ParkioI18n.resolveLocale(), 'en');
  assert.equal(env.sandbox.ParkioI18n.linkLocale(), 'tr');
});
