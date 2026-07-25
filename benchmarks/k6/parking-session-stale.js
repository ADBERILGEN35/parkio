/**
 * Focused k6 skeleton for parking-session stale lifecycle HTTP paths.
 *
 * Safe defaults: low VUs, no secrets in-repo, skips write flow unless credentials
 * (or a pre-issued bearer token) are supplied via environment variables.
 *
 * Flow (when authenticated):
 *   GET  /api/v1/parking/sessions/lifecycle-config
 *   GET  /api/v1/parking/sessions/active
 *   POST /api/v1/parking/sessions              (optional start)
 *   POST /api/v1/parking/sessions/{id}/confirm-active
 *   POST /api/v1/parking/sessions/{id}/complete
 *
 * Usage (non-production only):
 *   PARKIO_BASE_URL=http://localhost:8080 \
 *   PARKIO_K6_EMAIL=user@example.local \
 *   PARKIO_K6_PASSWORD='...' \
 *   k6 run benchmarks/k6/parking-session-stale.js
 *
 * Or reuse a token:
 *   PARKIO_K6_ACCESS_TOKEN=eyJ... k6 run benchmarks/k6/parking-session-stale.js
 */
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.PARKIO_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const API = `${BASE_URL}/api/v1`;
const EMAIL = __ENV.PARKIO_K6_EMAIL || '';
const PASSWORD = __ENV.PARKIO_K6_PASSWORD || '';
const ACCESS_TOKEN = __ENV.PARKIO_K6_ACCESS_TOKEN || '';
const ENABLE_MUTATIONS = (__ENV.PARKIO_K6_ENABLE_SESSION_MUTATIONS || 'true').toLowerCase() === 'true';
const LATITUDE = Number(__ENV.PARKIO_K6_LATITUDE || '41.0082');
const LONGITUDE = Number(__ENV.PARKIO_K6_LONGITUDE || '28.9784');

const configLatency = new Trend('parkio_session_lifecycle_config_latency', true);
const activeLatency = new Trend('parkio_session_active_latency', true);
const startLatency = new Trend('parkio_session_start_latency', true);
const confirmLatency = new Trend('parkio_session_confirm_latency', true);
const completeLatency = new Trend('parkio_session_complete_latency', true);
const businessErrorRate = new Rate('parkio_session_business_error_rate');

export const options = {
  scenarios: {
    parking_session_stale: {
      executor: 'ramping-vus',
      stages: [
        { duration: __ENV.PARKIO_K6_RAMP_UP || '15s', target: Number(__ENV.PARKIO_K6_VUS || '3') },
        { duration: __ENV.PARKIO_K6_DURATION || '1m', target: Number(__ENV.PARKIO_K6_VUS || '3') },
        { duration: __ENV.PARKIO_K6_RAMP_DOWN || '15s', target: 0 },
      ],
      exec: 'sessionLifecycleFlow',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.1'],
    parkio_session_business_error_rate: ['rate<0.1'],
    parkio_session_active_latency: ['p(95)<2000'],
    parkio_session_lifecycle_config_latency: ['p(95)<2000'],
  },
  summaryTrendStats: ['min', 'avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function authParams(token) {
  return {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      Accept: 'application/json',
      'X-Parkio-Client': 'k6-parking-session-stale',
    },
  };
}

function newIdempotencyKey(prefix) {
  return `${prefix}-${__VU}-${__ITER}-${Date.now()}`;
}

function record(trend, response, okStatuses) {
  const allowed = Array.isArray(okStatuses) ? okStatuses : [okStatuses];
  trend.add(response.timings.duration);
  const ok = allowed.includes(response.status);
  businessErrorRate.add(!ok);
  check(response, {
    [`status in ${allowed.join(',')}`]: () => ok,
  });
  return response;
}

function loginOnce() {
  const res = http.post(
    `${API}/auth/login`,
    JSON.stringify({ email: EMAIL, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json', Accept: 'application/json' } },
  );
  loginLatencySafe(res);
  if (res.status !== 200) {
    return null;
  }
  return res.json('accessToken');
}

function loginLatencySafe(res) {
  // Keep login off primary thresholds; failures are handled by setup skip.
  check(res, { 'login status 200': (r) => r.status === 200 });
}

export function setup() {
  if (ACCESS_TOKEN) {
    return { accessToken: ACCESS_TOKEN, mutations: ENABLE_MUTATIONS };
  }
  if (!EMAIL || !PASSWORD) {
    console.warn(
      'PARKIO_K6_ACCESS_TOKEN or PARKIO_K6_EMAIL+PARKIO_K6_PASSWORD not set; ' +
        'script will only hit public readiness if exposed. Skipping authenticated flow.',
    );
    return { accessToken: '', mutations: false };
  }
  const token = loginOnce();
  if (!token) {
    console.warn('Login failed; authenticated parking-session probes will be skipped.');
    return { accessToken: '', mutations: false };
  }
  return { accessToken: token, mutations: ENABLE_MUTATIONS };
}

export function sessionLifecycleFlow(data) {
  if (!data || !data.accessToken) {
    sleep(1);
    return;
  }
  const params = authParams(data.accessToken);

  group('lifecycle-config', () => {
    record(configLatency, http.get(`${API}/parking/sessions/lifecycle-config`, params), [200, 404]);
  });

  let sessionId = null;
  group('active', () => {
    const active = record(activeLatency, http.get(`${API}/parking/sessions/active`, params), [200, 204]);
    if (active.status === 200) {
      sessionId = active.json('id');
    }
  });

  if (!data.mutations) {
    sleep(0.5);
    return;
  }

  group('start-confirm-complete', () => {
    if (!sessionId) {
      const startParams = {
        headers: {
          ...params.headers,
          'Idempotency-Key': newIdempotencyKey('start'),
        },
      };
      const started = record(
        startLatency,
        http.post(
          `${API}/parking/sessions`,
          JSON.stringify({ latitude: LATITUDE, longitude: LONGITUDE }),
          startParams,
        ),
        [201, 409],
      );
      if (started.status === 201) {
        sessionId = started.json('id');
      } else if (started.status === 409) {
        const active = http.get(`${API}/parking/sessions/active`, params);
        if (active.status === 200) {
          sessionId = active.json('id');
        }
      }
    }

    if (!sessionId) {
      businessErrorRate.add(true);
      return;
    }

    record(
      confirmLatency,
      http.post(`${API}/parking/sessions/${sessionId}/confirm-active`, null, params),
      [200, 404, 409],
    );

    const completeParams = {
      headers: {
        ...params.headers,
        'Idempotency-Key': newIdempotencyKey('complete'),
      },
    };
    record(
      completeLatency,
      http.post(`${API}/parking/sessions/${sessionId}/complete`, null, completeParams),
      [200, 404, 409],
    );
  });

  sleep(0.5);
}