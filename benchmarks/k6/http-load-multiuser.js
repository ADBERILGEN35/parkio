import http from 'k6/http';
import { sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// Multi-user read probe. Each VU authenticates as a distinct seeded user
// (PREFIX + __VU + @DOMAIN) so the gateway's per-user RequestRateLimiter token
// bucket is NOT shared across VUs. This isolates real backend read capacity from
// the single-user rate-limit ceiling measured by http-load.js. Measurement only;
// it changes no application behavior. Seed users with loadtest+<n>@<domain>.

const BASE = (__ENV.PARKIO_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const API = `${BASE}/api/v1`;
const PW = __ENV.PARKIO_K6_PASSWORD || 'StrongParkio123';
const DOMAIN = __ENV.PARKIO_K6_USER_DOMAIN || 'real-e2e.parkio.local';
const PREFIX = __ENV.PARKIO_K6_USER_PREFIX || 'loadtest+';
const LAT = Number(__ENV.PARKIO_K6_LATITUDE || '41.0082');
const LNG = Number(__ENV.PARKIO_K6_LONGITUDE || '28.9784');
const RAD = Number(__ENV.PARKIO_K6_RADIUS_METERS || '1500');

const reqFail = new Rate('mu_req_fail');
const rl429 = new Rate('mu_rate_limited');
const T = {
  profile: new Trend('mu_profile', true),
  gamification: new Trend('mu_gamification', true),
  notifications: new Trend('mu_notifications', true),
  nearby: new Trend('mu_nearby', true),
  geocoding: new Trend('mu_geocoding', true),
  moderation: new Trend('mu_moderation', true),
  analytics: new Trend('mu_analytics', true),
};

export const options = {
  scenarios: {
    reads: {
      executor: 'ramping-vus',
      exec: 'flow',
      stages: [
        { duration: __ENV.RAMP || '20s', target: Number(__ENV.VUS || '40') },
        { duration: __ENV.DUR || '90s', target: Number(__ENV.VUS || '40') },
        { duration: __ENV.RAMPDOWN || '10s', target: 0 },
      ],
    },
  },
  summaryTrendStats: ['min', 'avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: { http_req_failed: ['rate<0.05'] },
};

let token = null;
let uid = null;

function ensureLogin() {
  if (token) return true;
  const email = `${PREFIX}${__VU}@${DOMAIN}`;
  const r = http.post(`${API}/auth/login`, JSON.stringify({ email, password: PW }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { endpoint: 'login' },
  });
  if (r.status === 200) {
    token = r.json('accessToken');
    uid = r.json('user.id') || r.json('user.userId') || r.json('user.authUserId');
    return true;
  }
  rl429.add(r.status === 429);
  return false;
}

function rec(trend, r) {
  trend.add(r.timings.duration);
  reqFail.add(r.status !== 200);
  rl429.add(r.status === 429);
  return r;
}

export function flow() {
  if (!ensureLogin()) {
    sleep(1);
    return;
  }
  const p = { headers: { Authorization: `Bearer ${token}` } };
  rec(T.profile, http.get(`${API}/users/me`, p));
  rec(T.gamification, http.get(`${API}/gamification/me/progress`, p));
  rec(T.notifications, http.get(`${API}/notifications/me`, p));
  rec(T.nearby, http.get(`${API}/parking/spots/nearby?lat=${LAT}&lng=${LNG}&radius=${RAD}&limit=20`, p));
  rec(T.geocoding, http.get(`${API}/geocoding/search?q=Istanbul&limit=5`, p));
  rec(T.moderation, http.get(`${API}/moderation/reports/me`, p));
  if (uid) rec(T.analytics, http.get(`${API}/analytics/users/${uid}`, p));
  sleep(Number(__ENV.THINK || '1'));
}
