/**
 * Waitlist client for parkio.dev marketing.
 * Modes (meta parkio-waitlist-mode):
 *   api         — POST to gateway (production launch). Ignores ?waitlistMock=1.
 *   unavailable — signup closed; no simulated success (production staged).
 *   mock        — local/test only; in-memory. Must be set explicitly via meta.
 */
(function (global) {
  const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

  function normalizeEmail(value) {
    return String(value || '')
      .trim()
      .toLowerCase();
  }

  function isValidEmail(value) {
    const email = normalizeEmail(value);
    return email.length > 3 && email.length <= 254 && EMAIL_RE.test(email);
  }

  function metaContent(name) {
    const meta = document.querySelector(`meta[name="${name}"]`);
    return meta && meta.content ? String(meta.content).trim() : '';
  }

  function detectMode() {
    const mode = metaContent('parkio-waitlist-mode') || 'api';
    if (mode === 'unavailable' || mode === 'hidden') return 'unavailable';
    if (mode === 'mock') return 'mock';
    // Production/api bundles must never honor ?waitlistMock=1.
    return 'api';
  }

  function apiBase() {
    return metaContent('parkio-waitlist-api') || 'https://api.parkio.dev/api/v1';
  }

  function currentLocale() {
    if (global.ParkioI18n) return global.ParkioI18n.resolveLocale();
    return document.documentElement.lang === 'en' ? 'en' : 'tr';
  }

  function tr(key) {
    if (global.ParkioI18n) return global.ParkioI18n.t(currentLocale(), key);
    return key;
  }

  const mockStore = new Set();

  async function submitApi(payload) {
    const response = await fetch(`${apiBase()}/waitlist`, {
      method: 'POST',
      credentials: 'omit',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(payload),
    });
    if (response.status === 202) {
      return { ok: true, status: 'accepted' };
    }
    if (response.status === 429) {
      return { ok: false, code: 'RATE_LIMITED' };
    }
    if (response.status === 503) {
      const body = await response.json().catch(() => ({}));
      if (body && body.code === 'WAITLIST_ADMISSIONS_DISABLED') {
        return { ok: false, code: 'ADMISSIONS_DISABLED' };
      }
      return { ok: false, code: 'EMAIL_DELIVERY_FAILED' };
    }
    if (response.status >= 400 && response.status < 500) {
      const body = await response.json().catch(() => ({}));
      if (body && body.code === 'WAITLIST_CONSENT_TIMESTAMP_INVALID') {
        return { ok: false, code: 'CONSENT_TIMESTAMP_INVALID' };
      }
      return { ok: false, code: 'VALIDATION_ERROR' };
    }
    if (response.status >= 500) {
      return { ok: false, code: 'SERVER_ERROR' };
    }
    return { ok: false, code: 'NETWORK_ERROR' };
  }

  async function submitMock(payload) {
    // Isolated mock — session memory only. Local/test meta=mock only.
    mockStore.add(payload.email);
    await new Promise((r) => setTimeout(r, 120));
    return { ok: true, status: 'accepted', mock: true };
  }

  async function confirmApi(token) {
    const response = await fetch(`${apiBase()}/waitlist/confirm`, {
      method: 'POST',
      credentials: 'omit',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ token }),
    });
    if (response.status === 202) return { ok: true, status: 'confirmed' };
    return { ok: false, code: 'WAITLIST_TOKEN_INVALID' };
  }

  async function withdrawApi(token) {
    const response = await fetch(`${apiBase()}/waitlist/withdraw`, {
      method: 'POST',
      credentials: 'omit',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ token }),
    });
    if (response.status === 202) return { ok: true, status: 'withdrawn' };
    return { ok: false, code: 'WAITLIST_TOKEN_INVALID' };
  }

  async function confirmMock(token) {
    if (!token) return { ok: false, code: 'WAITLIST_TOKEN_INVALID' };
    return { ok: true, status: 'confirmed', mock: true };
  }

  async function withdrawMock(token) {
    if (!token) return { ok: false, code: 'WAITLIST_TOKEN_INVALID' };
    return { ok: true, status: 'withdrawn', mock: true };
  }

  /**
   * Store a semantic i18n key on the feedback node and render with the
   * currently active locale. Re-rendered on parkio:locale without refetch.
   */
  function setFeedbackKey(el, key, kind) {
    if (!el) return;
    if (!key) {
      delete el.dataset.feedbackKey;
      el.textContent = '';
      el.dataset.kind = '';
      el.hidden = true;
      return;
    }
    el.dataset.feedbackKey = key;
    el.dataset.kind = kind || '';
    el.textContent = tr(key);
    el.hidden = false;
  }

  function refreshFeedback(el) {
    if (!el || !el.dataset.feedbackKey) return;
    el.textContent = tr(el.dataset.feedbackKey);
  }

  function refreshBusyButtons() {
    document.querySelectorAll('[data-waitlist-busy="1"]').forEach((btn) => {
      btn.textContent = tr('waitlist.submitting');
    });
  }

  function refreshLocalizedUi() {
    document.querySelectorAll('[data-waitlist-feedback]').forEach(refreshFeedback);
    const unavailable = document.querySelector('[data-waitlist-unavailable-note]');
    if (unavailable && !unavailable.hidden) {
      unavailable.textContent = tr('waitlist.unavailable');
    }
    refreshBusyButtons();
  }

  function submitFeedbackKey(result) {
    if (result && result.ok) return 'waitlist.success';
    switch (result && result.code) {
      case 'RATE_LIMITED':
        return 'waitlist.error.rate';
      case 'ADMISSIONS_DISABLED':
        return 'waitlist.unavailable';
      case 'EMAIL_DELIVERY_FAILED':
        return 'waitlist.error.delivery';
      case 'CONSENT_TIMESTAMP_INVALID':
        return 'waitlist.error.consentTime';
      case 'VALIDATION_ERROR':
        return 'waitlist.error.invalid';
      case 'SERVER_ERROR':
        return 'waitlist.error.generic';
      default:
        return 'waitlist.error.network';
    }
  }

  function tokenFeedbackKey(kind, result) {
    if (result && result.ok) {
      return kind === 'confirm' ? 'waitlist.page.confirm.success' : 'waitlist.page.withdraw.success';
    }
    return 'waitlist.error.generic';
  }

  function applyUnavailableUi(form) {
    if (!form) return;
    form.setAttribute('aria-disabled', 'true');
    form.dataset.waitlistUnavailable = '1';
    const inputs = form.querySelectorAll('input, button');
    inputs.forEach((el) => {
      el.disabled = true;
    });
    form.hidden = true;
    let notice = document.querySelector('[data-waitlist-unavailable-note]');
    if (!notice) {
      notice = document.createElement('p');
      notice.className = 'waitlist-unavailable';
      notice.setAttribute('data-waitlist-unavailable-note', '');
      notice.setAttribute('role', 'status');
      form.parentNode && form.parentNode.insertBefore(notice, form.nextSibling);
    }
    notice.hidden = false;
    notice.textContent = tr('waitlist.unavailable');
  }

  function bindForm(form) {
    if (!form) return;
    const emailInput = form.querySelector('#waitlist-email');
    const consentInput = form.querySelector('#waitlist-consent');
    const submitBtn = form.querySelector('[type="submit"]');
    const feedback = form.querySelector('[data-waitlist-feedback]');
    const mockNote = form.querySelector('[data-waitlist-isolated-note]');
    const mode = detectMode();
    if (mockNote) {
      mockNote.hidden = mode !== 'mock';
    }
    if (mode === 'unavailable') {
      applyUnavailableUi(form);
      return;
    }

    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      setFeedbackKey(feedback, '', '');
      const email = normalizeEmail(emailInput && emailInput.value);
      if (!isValidEmail(email)) {
        setFeedbackKey(feedback, 'waitlist.error.invalid', 'error');
        emailInput && emailInput.focus();
        return;
      }
      if (!consentInput || !consentInput.checked) {
        setFeedbackKey(feedback, 'waitlist.error.consent', 'error');
        consentInput && consentInput.focus();
        return;
      }

      const payload = {
        email,
        consentTimestamp: new Date().toISOString(),
        source: 'parkio.dev-landing',
        locale: currentLocale(),
      };

      submitBtn.disabled = true;
      submitBtn.dataset.waitlistBusy = '1';
      submitBtn.textContent = tr('waitlist.submitting');
      try {
        const result = mode === 'mock' ? await submitMock(payload) : await submitApi(payload);
        // Render with whatever locale is active when the response is shown.
        const key = submitFeedbackKey(result);
        setFeedbackKey(feedback, key, result.ok ? 'success' : 'error');
        if (result.ok) {
          form.reset();
        }
      } catch (_) {
        setFeedbackKey(feedback, 'waitlist.error.network', 'error');
      } finally {
        submitBtn.disabled = false;
        delete submitBtn.dataset.waitlistBusy;
        submitBtn.textContent = tr('waitlist.submit');
      }
    });
  }

  function bindTokenAction(form, kind) {
    if (!form) return;
    const feedback = form.querySelector('[data-waitlist-feedback]');
    const button = form.querySelector('[type="submit"]');
    const params = new URLSearchParams(global.location.search);
    const token = params.get('token') || '';
    const mode = detectMode();
    const idleKey = kind === 'confirm' ? 'waitlist.page.confirm.cta' : 'waitlist.page.withdraw.cta';
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      if (!token) {
        setFeedbackKey(feedback, 'waitlist.error.generic', 'error');
        return;
      }
      button.disabled = true;
      button.dataset.waitlistBusy = '1';
      button.textContent = tr('waitlist.submitting');
      try {
        let result;
        if (kind === 'confirm') {
          result = mode === 'mock' ? await confirmMock(token) : await confirmApi(token);
        } else {
          result = mode === 'mock' ? await withdrawMock(token) : await withdrawApi(token);
        }
        const key = tokenFeedbackKey(kind, result);
        setFeedbackKey(feedback, key, result.ok ? 'success' : 'error');
      } catch (_) {
        setFeedbackKey(feedback, 'waitlist.error.network', 'error');
      } finally {
        button.disabled = false;
        delete button.dataset.waitlistBusy;
        button.textContent = tr(idleKey);
      }
    });
  }

  function init() {
    bindForm(document.getElementById('waitlist-form'));
    bindTokenAction(document.getElementById('waitlist-confirm-form'), 'confirm');
    bindTokenAction(document.getElementById('waitlist-withdraw-form'), 'withdraw');
    if (!global.__parkioWaitlistLocaleBound) {
      global.__parkioWaitlistLocaleBound = true;
      document.addEventListener('parkio:locale', refreshLocalizedUi);
    }
  }

  global.ParkioWaitlist = {
    isValidEmail,
    normalizeEmail,
    detectMode,
    submitApi,
    submitMock,
    confirmApi,
    withdrawApi,
    init,
    setFeedbackKey,
    refreshLocalizedUi,
    submitFeedbackKey,
    tokenFeedbackKey,
    _mockStore: mockStore,
  };
})(typeof window !== 'undefined' ? window : globalThis);

if (typeof document !== 'undefined') {
  document.addEventListener('DOMContentLoaded', () => {
    if (window.ParkioI18n) window.ParkioI18n.init();
    if (window.ParkioWaitlist) window.ParkioWaitlist.init();
  });
}
