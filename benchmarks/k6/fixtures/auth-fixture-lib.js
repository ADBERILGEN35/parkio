/**
 * Shared mobile auth helpers for isolated k6 engine fixtures.
 * Mirrors http-load.js transport: X-Parkio-Client: mobile + JSON refreshToken.
 */
import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate } from 'k6/metrics';

export const criticalLoginOk = new Rate('parkio_critical_login_ok');
export const criticalRefreshOk = new Rate('parkio_critical_refresh_ok');
export const criticalLoginSamples = new Counter('parkio_critical_login_samples');
export const criticalRefreshSamples = new Counter('parkio_critical_refresh_samples');

const MOBILE_JSON_HEADERS = {
  'Content-Type': 'application/json',
  'X-Parkio-Client': 'mobile',
};

export function apiBase() {
  return (__ENV.PARKIO_BASE_URL || 'http://127.0.0.1:18080').replace(/\/$/, '');
}

export function criticalLogin() {
  const email = __ENV.PARKIO_K6_EMAIL || 'fixture@parkio.local';
  const password = __ENV.PARKIO_K6_PASSWORD || 'fixture-password';
  const response = http.post(
    `${apiBase()}/api/v1/auth/login`,
    JSON.stringify({ email, password }),
    {
      headers: MOBILE_JSON_HEADERS,
      tags: { endpoint: 'login', critical: 'login' },
    },
  );
  criticalLoginSamples.add(1);
  const ok = check(
    response,
    {
      'critical login status 200': (r) => r.status === 200,
      'critical login returns access token': (r) => Boolean(safeJson(r, 'accessToken')),
      'critical login returns refresh token': (r) => Boolean(safeJson(r, 'refreshToken')),
    },
    { critical: 'login' },
  );
  criticalLoginOk.add(ok);
  if (!ok) {
    fail(`critical login failed status=${response.status}`);
  }
  return {
    accessToken: safeJson(response, 'accessToken'),
    refreshToken: safeJson(response, 'refreshToken'),
  };
}

export function criticalRefresh(refreshToken) {
  const response = http.post(
    `${apiBase()}/api/v1/auth/refresh-token`,
    JSON.stringify({ refreshToken }),
    {
      headers: MOBILE_JSON_HEADERS,
      tags: { endpoint: 'refresh', critical: 'refresh' },
    },
  );
  criticalRefreshSamples.add(1);
  const ok = check(
    response,
    {
      'critical refresh status 200': (r) => r.status === 200,
      'critical refresh returns access token': (r) => Boolean(safeJson(r, 'accessToken')),
      'critical refresh returns rotated refresh token': (r) => Boolean(safeJson(r, 'refreshToken')),
    },
    { critical: 'refresh' },
  );
  criticalRefreshOk.add(ok);
  return { ok, response, refreshToken: safeJson(response, 'refreshToken') };
}

function safeJson(response, path) {
  try {
    return response.json(path);
  } catch {
    return null;
  }
}
