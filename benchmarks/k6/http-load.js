import http from 'k6/http';
import { check, fail, group, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { criticalAuthThresholds } from './critical-auth-gates.js';

/**
 * Parkio k6 smoke — native mobile auth transport.
 *
 * Auth refresh/logout intentionally use X-Parkio-Client: mobile + JSON body
 * refreshToken. Browser cookie+Origin CSRF path is a different contract and is
 * not exercised here (k6 has no reliable Secure cookie jar for that path).
 *
 * Critical auth lifecycle checks are tagged critical=login|refresh and gated by
 * strict thresholds so aggregate HTTP error budgets cannot hide refresh failures.
 */

const BASE_URL = (__ENV.PARKIO_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const API = `${BASE_URL}/api/v1`;
const EMAIL = __ENV.PARKIO_K6_EMAIL || '';
const PASSWORD = __ENV.PARKIO_K6_PASSWORD || '';
const ENABLE_UPLOAD = (__ENV.PARKIO_K6_ENABLE_UPLOAD || 'false').toLowerCase() === 'true';
const ENABLE_ADMIN = (__ENV.PARKIO_K6_ENABLE_ADMIN || 'false').toLowerCase() === 'true';
const LATITUDE = Number(__ENV.PARKIO_K6_LATITUDE || '41.0082');
const LONGITUDE = Number(__ENV.PARKIO_K6_LONGITUDE || '28.9784');
const RADIUS_METERS = Number(__ENV.PARKIO_K6_RADIUS_METERS || '1500');

const loginLatency = new Trend('parkio_login_latency', true);
const refreshLatency = new Trend('parkio_refresh_latency', true);
const profileLatency = new Trend('parkio_profile_latency', true);
const nearbyLatency = new Trend('parkio_nearby_latency', true);
const geocodingLatency = new Trend('parkio_geocoding_latency', true);
const spotDetailsLatency = new Trend('parkio_spot_details_latency', true);
const uploadLatency = new Trend('parkio_upload_latency', true);
const createSpotLatency = new Trend('parkio_create_spot_latency', true);
const moderationLatency = new Trend('parkio_moderation_latency', true);
const analyticsLatency = new Trend('parkio_analytics_latency', true);
const businessErrorRate = new Rate('parkio_business_error_rate');
const criticalLoginOk = new Rate('parkio_critical_login_ok');
const criticalRefreshOk = new Rate('parkio_critical_refresh_ok');
const criticalLoginSamples = new Counter('parkio_critical_login_samples');
const criticalRefreshSamples = new Counter('parkio_critical_refresh_samples');

const MOBILE_JSON_HEADERS = {
  'Content-Type': 'application/json',
  'X-Parkio-Client': 'mobile',
};

export const options = {
  scenarios: {
    authenticated_read: {
      executor: 'ramping-vus',
      stages: [
        { duration: __ENV.PARKIO_K6_RAMP_UP || '30s', target: Number(__ENV.PARKIO_K6_VUS || '10') },
        { duration: __ENV.PARKIO_K6_DURATION || '2m', target: Number(__ENV.PARKIO_K6_VUS || '10') },
        { duration: __ENV.PARKIO_K6_RAMP_DOWN || '30s', target: 0 },
      ],
      exec: 'authenticatedReadFlow',
    },
    auth_session: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.PARKIO_K6_AUTH_RATE || '2'),
      timeUnit: '1s',
      duration: __ENV.PARKIO_K6_DURATION || '2m',
      preAllocatedVUs: 4,
      maxVUs: 20,
      exec: 'authSessionFlow',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    parkio_business_error_rate: ['rate<0.05'],
    parkio_login_latency: ['p(95)<2000'],
    parkio_refresh_latency: ['p(95)<1000'],
    parkio_nearby_latency: ['p(95)<1500'],
    parkio_geocoding_latency: ['p(95)<2500'],
    parkio_profile_latency: ['p(95)<1000'],
    // Critical auth lifecycle — must execute and succeed; cannot pass vacuously.
    // Rate metrics: rate only. Nonzero execution is gated by Counter *_samples.
    ...criticalAuthThresholds(),
  },
  summaryTrendStats: ['min', 'avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  if (!EMAIL || !PASSWORD) {
    fail('PARKIO_K6_EMAIL and PARKIO_K6_PASSWORD are required. Seed a non-production test account first.');
  }

  const login = loginOnce({ critical: false });
  const userId =
    login.response.json('user.id') ||
    login.response.json('user.userId') ||
    login.response.json('user.authUserId');
  if (!userId) {
    fail('Login succeeded but response did not expose user.id/userId/authUserId for user-scoped probes.');
  }

  return {
    accessToken: login.accessToken,
    userId,
  };
}

export function authenticatedReadFlow(data) {
  const params = authParams(data.accessToken);

  group('profile', () => {
    record(profileLatency, http.get(`${API}/users/me`, params), 200);
    record(profileLatency, http.get(`${API}/users/me/stats`, params), 200);
    record(profileLatency, http.get(`${API}/users/me/vehicle`, params), [200, 404]);
    record(profileLatency, http.get(`${API}/gamification/me/progress`, params), 200);
    record(profileLatency, http.get(`${API}/notifications/me`, params), 200);
  });

  group('discovery', () => {
    const nearby = record(
      nearbyLatency,
      http.get(
        `${API}/parking/spots/nearby?lat=${LATITUDE}&lng=${LONGITUDE}&radius=${RADIUS_METERS}&limit=20`,
        params,
      ),
      200,
    );
    const body = nearby.json();
    const firstSpotId = Array.isArray(body) && body.length > 0 ? body[0].id : null;
    if (firstSpotId) {
      record(spotDetailsLatency, http.get(`${API}/parking/spots/${firstSpotId}`, params), 200);
    }

    record(geocodingLatency, http.get(`${API}/geocoding/search?q=Istanbul&limit=5`, params), 200);
  });

  group('moderation and analytics reads', () => {
    record(moderationLatency, http.get(`${API}/moderation/reports/me`, params), 200);
    record(analyticsLatency, http.get(`${API}/analytics/users/${data.userId}`, params), 200);
    if (ENABLE_ADMIN) {
      record(analyticsLatency, http.get(`${API}/analytics/overview`, params), 200);
      record(analyticsLatency, http.get(`${API}/analytics/parking`, params), 200);
    }
  });

  if (ENABLE_UPLOAD) {
    group('upload and create spot', () => {
      const mediaId = uploadTinyJpeg(data.accessToken);
      if (mediaId) {
        createSpot(data.accessToken, mediaId);
      }
    });
  }

  sleep(Number(__ENV.PARKIO_K6_THINK_TIME_SECONDS || '1'));
}

/**
 * Independent synthetic session per iteration: login → refresh (rotated token) → logout.
 * Never reuses another VU's refresh token after rotation.
 */
export function authSessionFlow() {
  const login = loginOnce({ critical: true });
  const refreshBody = JSON.stringify({ refreshToken: login.refreshToken });
  const refreshResponse = http.post(`${API}/auth/refresh-token`, refreshBody, {
    headers: MOBILE_JSON_HEADERS,
    tags: { endpoint: 'refresh', critical: 'refresh' },
  });
  refreshLatency.add(refreshResponse.timings.duration);
  criticalRefreshSamples.add(1);

  const refreshOk = check(
    refreshResponse,
    {
      'critical refresh status 200': (r) => r.status === 200,
      'critical refresh returns access token': (r) => Boolean(safeJson(r, 'accessToken')),
      'critical refresh returns rotated refresh token': (r) => Boolean(safeJson(r, 'refreshToken')),
    },
    { critical: 'refresh' },
  );
  criticalRefreshOk.add(refreshOk);
  businessErrorRate.add(!refreshOk);

  const rotatedRefresh = safeJson(refreshResponse, 'refreshToken') || login.refreshToken;
  http.post(`${API}/auth/logout`, JSON.stringify({ refreshToken: rotatedRefresh }), {
    headers: MOBILE_JSON_HEADERS,
    tags: { endpoint: 'logout' },
  });
}

function loginOnce({ critical }) {
  const response = http.post(
    `${API}/auth/login`,
    JSON.stringify({ email: EMAIL, password: PASSWORD }),
    {
      headers: MOBILE_JSON_HEADERS,
      tags: critical
        ? { endpoint: 'login', critical: 'login' }
        : { endpoint: 'login' },
    },
  );
  loginLatency.add(response.timings.duration);

  const checks = critical
    ? {
        'critical login status 200': (r) => r.status === 200,
        'critical login returns access token': (r) => Boolean(safeJson(r, 'accessToken')),
        'critical login returns refresh token': (r) => Boolean(safeJson(r, 'refreshToken')),
      }
    : {
        'login status 200': (r) => r.status === 200,
        'login returns access token': (r) => Boolean(safeJson(r, 'accessToken')),
        'login returns refresh token': (r) => Boolean(safeJson(r, 'refreshToken')),
      };

  const ok = check(response, checks, critical ? { critical: 'login' } : undefined);
  if (critical) {
    criticalLoginSamples.add(1);
    criticalLoginOk.add(ok);
  }
  businessErrorRate.add(!ok);
  if (!ok) {
    fail(`Login failed with status ${response.status}`);
  }

  return {
    response,
    accessToken: safeJson(response, 'accessToken'),
    refreshToken: safeJson(response, 'refreshToken'),
  };
}

function uploadTinyJpeg(accessToken) {
  const jpeg = String.fromCharCode(
    0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01,
    0x01, 0x01, 0x00, 0x48, 0x00, 0x48, 0x00, 0x00, 0xff, 0xd9,
  );
  const response = http.post(
    `${API}/media/upload`,
    {
      file: http.file(jpeg, 'parkio-k6-tiny.jpg', 'image/jpeg'),
    },
    {
      headers: {
        ...authHeaders(accessToken),
        'Idempotency-Key': `${__VU}-${__ITER}-upload`,
      },
      tags: { endpoint: 'media_upload' },
    },
  );
  record(uploadLatency, response, [200, 201]);
  return safeJson(response, 'mediaId') || null;
}

function createSpot(accessToken, mediaId) {
  const response = http.post(
    `${API}/parking/spots`,
    JSON.stringify({
      mediaId,
      latitude: LATITUDE,
      longitude: LONGITUDE,
      addressText: 'k6 load-test spot',
      description: 'Synthetic performance test spot',
      manualLocationEdited: false,
      suitableVehicleTypes: ['SEDAN'],
      parkingContext: 'STREET',
      legalStatus: 'LEGAL',
      violationReasons: [],
    }),
    {
      headers: {
        ...authHeaders(accessToken),
        'Content-Type': 'application/json',
        'Idempotency-Key': `${__VU}-${__ITER}-spot`,
      },
      tags: { endpoint: 'create_spot' },
    },
  );
  record(createSpotLatency, response, [200, 201]);
}

function record(trend, response, expectedStatus) {
  trend.add(response.timings.duration);
  const expected = Array.isArray(expectedStatus) ? expectedStatus : [expectedStatus];
  const ok = check(response, {
    [`status is ${expected.join(' or ')}`]: (r) => expected.includes(r.status),
  });
  businessErrorRate.add(!ok);
  return response;
}

function authParams(accessToken) {
  return {
    headers: authHeaders(accessToken),
  };
}

function authHeaders(accessToken) {
  return {
    Authorization: `Bearer ${accessToken}`,
  };
}

function safeJson(response, path) {
  try {
    return response.json(path);
  } catch {
    return null;
  }
}
