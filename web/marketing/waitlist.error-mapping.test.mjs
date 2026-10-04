import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

// CL-F19: only the explicit delivery-failure code says the signup was saved; token pages tell an
// invalid or expired link (no retry suggestion) apart from a rate limit and a server error.

const root = resolve(dirname(fileURLToPath(import.meta.url)));
const waitlistSource = readFileSync(resolve(root, 'waitlist.js'), 'utf8');
const i18nSource = readFileSync(resolve(root, 'i18n.js'), 'utf8');
const RETRY = /try again|tekrar dene/i;

function jsonResponse(status, body) {
  return { status, json: async () => body };
}

function brokenJsonResponse(status) {
  return { status, json: async () => { throw new SyntaxError('not json'); } };
}

function pageSandbox({ kind, search = '?token=fixture', locale = 'en', fetchImpl }) {
  const listeners = {};
  const feedback = { textContent: '', dataset: {}, hidden: true };
  const button = { textContent: '', disabled: false, dataset: {} };
  const form = {
    querySelector(sel) {
      if (sel === '[data-waitlist-feedback]') return feedback;
      if (sel === '[type="submit"]') return button;
      return null;
    },
    addEventListener(type, fn) {
      listeners[type] = fn;
    },
  };
  const sandbox = {
    document: {
      documentElement: { lang: locale },
      querySelector(sel) {
        if (sel.includes('parkio-waitlist-mode')) return { content: 'api' };
        if (sel.includes('parkio-waitlist-api')) return { content: 'https://api.parkio.dev/api/v1' };
        return null;
      },
      querySelectorAll(sel) {
        return sel === '[data-waitlist-feedback]' ? [feedback] : [];
      },
      getElementById(id) {
        return id === `waitlist-${kind}-form` ? form : null;
      },
      addEventListener() {},
      dispatchEvent() {
        return true;
      },
    },
    location: { search },
    URLSearchParams,
    fetch: fetchImpl,
    setTimeout,
    CustomEvent: class CustomEvent {
      constructor(type, init = {}) {
        this.type = type;
        this.detail = init.detail;
      }
    },
    localStorage: { getItem: () => null, setItem() {} },
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  vm.runInNewContext(`${i18nSource}\n${waitlistSource}`, sandbox);
  sandbox.ParkioI18n.applyLocale(locale);
  sandbox.ParkioWaitlist.init();
  return {
    feedback,
    submit: () => listeners.submit({ preventDefault() {} }),
    waitlist: sandbox.ParkioWaitlist,
  };
}

async function submitResult(response) {
  const { waitlist } = pageSandbox({ kind: 'confirm', fetchImpl: async () => response });
  const result = await waitlist.submitApi({ email: 'synthetic@example.com' });
  return { code: result.code, key: waitlist.submitFeedbackKey(result) };
}

test('only WAITLIST_EMAIL_DELIVERY_FAILED reports the signup as saved', async () => {
  const delivery = await submitResult(jsonResponse(503, { code: 'WAITLIST_EMAIL_DELIVERY_FAILED' }));
  assert.equal(delivery.code, 'EMAIL_DELIVERY_FAILED');
  assert.equal(delivery.key, 'waitlist.error.delivery');

  for (const [label, response] of [
    ['503 without a body', brokenJsonResponse(503)],
    ['503 with another code', jsonResponse(503, { code: 'SOMETHING_ELSE' })],
    ['503 with an empty body', jsonResponse(503, {})],
  ]) {
    const result = await submitResult(response);
    assert.equal(result.code, 'SERVER_ERROR', label);
    assert.equal(result.key, 'waitlist.error.generic', label);
  }

  const disabled = await submitResult(jsonResponse(503, { code: 'WAITLIST_ADMISSIONS_DISABLED' }));
  assert.equal(disabled.code, 'ADMISSIONS_DISABLED');
});

for (const kind of ['confirm', 'withdraw']) {
  test(`${kind}: an invalid or expired link says so, in TR and EN, without suggesting a retry`, async () => {
    for (const [locale, response] of [
      ['en', jsonResponse(400, { code: 'WAITLIST_TOKEN_INVALID' })],
      ['tr', jsonResponse(400, { code: 'WAITLIST_TOKEN_INVALID' })],
      ['en', jsonResponse(400, { code: 'VALIDATION_ERROR' })],
    ]) {
      const page = pageSandbox({ kind, locale, fetchImpl: async () => response });
      await page.submit();
      assert.equal(page.feedback.dataset.feedbackKey, `waitlist.page.${kind}.invalid`);
      assert.notEqual(page.feedback.textContent, '');
      assert.doesNotMatch(page.feedback.textContent, RETRY, `${locale}: ${page.feedback.textContent}`);
    }
  });

  test(`${kind}: a missing token is an invalid link, not a retry`, async () => {
    let fetches = 0;
    const page = pageSandbox({
      kind,
      search: '',
      fetchImpl: async () => {
        fetches += 1;
        return jsonResponse(202, {});
      },
    });
    await page.submit();
    assert.equal(fetches, 0);
    assert.equal(page.feedback.dataset.feedbackKey, `waitlist.page.${kind}.invalid`);
    assert.doesNotMatch(page.feedback.textContent, RETRY);
  });

  test(`${kind}: a rate limit and a server error each get their own message`, async () => {
    const limited = pageSandbox({ kind, fetchImpl: async () => jsonResponse(429, { code: 'RATE_LIMITED' }) });
    await limited.submit();
    assert.equal(limited.feedback.dataset.feedbackKey, 'waitlist.error.rate');

    for (const status of [500, 502, 503, 404]) {
      const failed = pageSandbox({ kind, locale: 'tr', fetchImpl: async () => brokenJsonResponse(status) });
      await failed.submit();
      assert.equal(failed.feedback.dataset.feedbackKey, 'waitlist.page.token.serverError', String(status));
      assert.match(failed.feedback.textContent, /tekrar deneyin/);
    }

    const offline = pageSandbox({
      kind,
      fetchImpl: async () => {
        throw new TypeError('network down');
      },
    });
    await offline.submit();
    assert.equal(offline.feedback.dataset.feedbackKey, 'waitlist.error.network');
  });
}

test('the new token-page messages exist in TR and EN', () => {
  for (const key of ['waitlist.page.confirm.invalid', 'waitlist.page.withdraw.invalid', 'waitlist.page.token.serverError']) {
    const occurrences = i18nSource.split(`'${key}'`).length - 1;
    assert.equal(occurrences, 2, `${key} must be defined once per locale`);
  }
});
