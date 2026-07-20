#!/usr/bin/env node
/**
 * Contract-faithful mock of the Parkio gateway for local mobile development
 * and end-to-end UI verification without the Java/docker stack.
 *
 *   node scripts/mock-gateway.mjs        # listens on :8080, base path /api/v1
 *
 * Covers every endpoint the mobile app calls, with in-memory state seeded from
 * the İzmir sample content pack (PARKIO-DESIGN-BRIEF §13). Error responses use
 * the real envelope: { code, message, traceId, timestamp }.
 *
 * Conveniences (mock-only, clearly marked):
 *  - every registration is auto-verified with token "demo" (or tap resend)
 *  - a seeded account exists: demo@parkio.dev / Parkio-Demo-1234
 *  - seeded spots re-arm their expiry on restart so the map is never empty
 */
import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';

const PORT = Number(process.env.PORT ?? 8080);
const BASE = '/api/v1';

// ── State ────────────────────────────────────────────────────────────────────
const users = new Map(); // email -> user record
const sessions = new Map(); // accessToken -> userId
const refreshTokens = new Map(); // refreshToken -> userId
const media = new Map(); // mediaId -> { status }
const spots = new Map(); // spotId -> spot record
const verificationsBySpot = new Map(); // spotId -> Set<userId>
const filledReports = new Map(); // spotId -> count
const notifications = new Map(); // userId -> []
const reports = [];
const appeals = [];
const cases = [];
const pointLedgers = new Map(); // userId -> []

const LEVELS = [
  { level: 1, minPoints: 0, maxPoints: 99, searchRadiusMeters: 300, resultLimit: 3, dailyViewLimit: 20, verifiedSpotPriority: false, notificationPriority: false },
  { level: 2, minPoints: 100, maxPoints: 249, searchRadiusMeters: 600, resultLimit: 6, dailyViewLimit: 50, verifiedSpotPriority: false, notificationPriority: false },
  { level: 3, minPoints: 250, maxPoints: 499, searchRadiusMeters: 1200, resultLimit: 12, dailyViewLimit: 120, verifiedSpotPriority: false, notificationPriority: false },
  { level: 4, minPoints: 500, maxPoints: 999, searchRadiusMeters: 1800, resultLimit: 18, dailyViewLimit: 200, verifiedSpotPriority: true, notificationPriority: false },
  { level: 5, minPoints: 1000, maxPoints: null, searchRadiusMeters: 2500, resultLimit: 25, dailyViewLimit: 300, verifiedSpotPriority: true, notificationPriority: true },
];

function levelFor(points) {
  return [...LEVELS].reverse().find((rule) => points >= rule.minPoints) ?? LEVELS[0];
}

function nowIso() {
  return new Date().toISOString();
}

function minutesFromNow(minutes) {
  return new Date(Date.now() + minutes * 60_000).toISOString();
}

function makeUser(email, password, extras = {}) {
  const id = randomUUID();
  const user = {
    id,
    email,
    password,
    status: 'ACTIVE',
    roles: extras.roles ?? ['USER'],
    displayName: extras.displayName ?? null,
    phoneNumber: null,
    city: null,
    createdAt: nowIso(),
    verified: extras.verified ?? false,
    points: extras.points ?? 0,
    trustScore: 100,
    preferences: { preferredRadiusMeters: 1200, notificationsEnabled: true, preferredLocale: 'tr' },
    vehicle: { vehicleType: null, plate: null },
    smartReturn: {
      enabled: false,
      homeLatitude: null,
      homeLongitude: null,
      homeLabel: null,
      defaultReturnTime: '18:30:00',
      reminderLeadMinutes: 25,
      lastPromptDate: null,
      todayStatus: 'UNKNOWN',
      todayExpectedReturnAt: null,
      todayReturnCheckCompletedAt: null,
      todayNotificationSentAt: null,
    },
  };
  users.set(email.toLowerCase(), user);
  notifications.set(id, []);
  pointLedgers.set(id, []);
  return user;
}

function addPoints(user, sourceType, points, relatedSpotId = null) {
  const earned = points >= 0;
  user.points = Math.max(0, user.points + points);
  pointLedgers.get(user.id)?.unshift({
    sourceType,
    direction: earned ? 'EARNED' : 'DEDUCTED',
    points: Math.abs(points),
    relatedSpotId,
    createdAt: nowIso(),
  });
}

function notify(userId, type, title, body, metadata = {}) {
  notifications.get(userId)?.unshift({
    id: randomUUID(),
    type,
    channel: 'IN_APP',
    title,
    body,
    metadata,
    status: 'SENT',
    createdAt: nowIso(),
    readAt: null,
  });
}

const seededSpotIds = new Set();

function makeSpot(owner, fields) {
  const id = randomUUID();
  const mediaId = randomUUID();
  media.set(mediaId, { status: 'READY' });
  const createdAt = fields.createdAtMinutesAgo
    ? new Date(Date.now() - fields.createdAtMinutesAgo * 60_000).toISOString()
    : nowIso();
  const spot = {
    id,
    ownerUserId: owner.id,
    mediaId,
    latitude: fields.lat,
    longitude: fields.lng,
    addressText: fields.address ?? null,
    description: fields.description ?? null,
    manualLocationEdited: false,
    suitableVehicleTypes: fields.vehicles ?? ['ANY'],
    parkingContext: fields.context ?? 'STREET_PARKING',
    legalStatus: fields.legal ?? 'LEGAL',
    violationReasons: [],
    status: fields.status ?? 'ACTIVE',
    confidenceScore: fields.confidence ?? 60,
    verificationCount: fields.verifications ?? 0,
    filledReportCount: 0,
    expiresAt: fields.expiresAt ?? minutesFromNow(10),
    createdAt,
    updatedAt: nowIso(),
  };
  spots.set(id, spot);
  verificationsBySpot.set(id, new Set());
  filledReports.set(id, 0);
  return spot;
}

// Seed: demo user + community + İzmir spots from the sample content pack.
const demo = makeUser('demo@parkio.dev', 'Parkio-Demo-1234', {
  displayName: 'Demo Sürücü',
  verified: true,
  points: 260,
});
const mert = makeUser('mert@parkio.dev', 'Parkio-Demo-1234', { displayName: 'Mert K.', verified: true, points: 640 });
const elif = makeUser('elif@parkio.dev', 'Parkio-Demo-1234', { displayName: 'Elif A.', verified: true, points: 180 });
const baran = makeUser('baran@parkio.dev', 'Parkio-Demo-1234', { displayName: 'Baran T.', verified: true, points: 1120 });
const moderator = makeUser('mod@parkio.dev', 'Parkio-Demo-1234', {
  displayName: 'Moderatör',
  verified: true,
  roles: ['USER', 'MODERATOR', 'ADMIN'],
  points: 300,
});

makeSpot(mert, {
  lat: 38.4382, lng: 27.1421,
  address: 'Kıbrıs Şehitleri yan sokağı, Alsancak',
  description: 'Eczanenin önü az önce boşaldı, sedan rahat sığar. Gölgede.',
  vehicles: ['SEDAN'], context: 'STREET_PARKING', legal: 'LEGAL',
  status: 'VERIFIED', verifications: 2, confidence: 82,
  expiresAt: minutesFromNow(8), createdAtMinutesAgo: 3,
});
makeSpot(elif, {
  lat: 38.4551, lng: 27.1113,
  address: '1720 Sk., Karşıyaka Çarşı',
  description: 'Fırının karşısı, dar ama hatchback girer.',
  vehicles: ['HATCHBACK'], context: 'STREET_PARKING', legal: 'UNCERTAIN',
  status: 'ACTIVE', verifications: 1, confidence: 55,
  expiresAt: minutesFromNow(3), createdAtMinutesAgo: 7,
});
makeSpot(baran, {
  lat: 38.4664, lng: 27.0996,
  address: 'Bostanlı Sahil',
  description: 'Sahil otoparkı girişine 50 m, geniş alan.',
  vehicles: ['ANY'], context: 'OPEN_PARKING_LOT', legal: 'LEGAL',
  status: 'VERIFIED', verifications: 3, confidence: 90,
  expiresAt: minutesFromNow(13), createdAtMinutesAgo: 2,
});
makeSpot(baran, {
  lat: 38.4285, lng: 27.1277,
  address: 'Konak Pier açık otopark',
  description: 'Deniz tarafında birkaç boş yer var.',
  vehicles: ['SUV'], context: 'OPEN_PARKING_LOT', legal: 'LEGAL',
  status: 'ACTIVE', confidence: 62,
  expiresAt: minutesFromNow(9), createdAtMinutesAgo: 1,
});
makeSpot(elif, {
  lat: 38.4322, lng: 27.1406,
  address: 'Folkart arka cadde, Bayraklı',
  description: 'Ofis çıkışı boşaldı.',
  vehicles: ['SEDAN'], context: 'OFFICE_AREA', legal: 'UNCERTAIN',
  status: 'SUSPICIOUS', confidence: 30,
  expiresAt: minutesFromNow(6), createdAtMinutesAgo: 5,
});

for (const spot of spots.values()) seededSpotIds.add(spot.id);

notify(demo.id, 'POINT_EARNED', '+20 puan', "Alsancak'taki yerin doğrulandı.", { deeplink: '/impact' });
notify(demo.id, 'LEVEL_UP', 'Seviye 3', 'Arama yarıçapın artık 1200 m.', { deeplink: '/impact' });

// ── HTTP plumbing ────────────────────────────────────────────────────────────
function send(res, status, body) {
  const payload = body === undefined ? '' : JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': '*',
    'Access-Control-Allow-Methods': '*',
  });
  res.end(payload);
}

function fail(res, status, code, message) {
  send(res, status, { code, message, traceId: `trc_${randomUUID().slice(0, 8)}`, timestamp: nowIso() });
}

function authUser(req) {
  const header = req.headers.authorization ?? '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : null;
  const userId = token ? sessions.get(token) : null;
  if (userId) {
    return [...users.values()].find((candidate) => candidate.id === userId) ?? null;
  }
  // Demo convenience: tokens minted before a mock restart keep working as the
  // demo account, so restarting the mock never signs the app out.
  if (token && token.startsWith('at_')) {
    const fallback = users.get('demo@parkio.dev');
    if (fallback) sessions.set(token, fallback.id);
    return fallback ?? null;
  }
  return null;
}

function issueTokens(user) {
  const accessToken = `at_${randomUUID()}`;
  const refreshToken = `rt_${randomUUID()}`;
  sessions.set(accessToken, user.id);
  refreshTokens.set(refreshToken, user.id);
  return {
    accessToken,
    tokenType: 'Bearer',
    accessTokenExpiresAt: minutesFromNow(15),
    refreshTokenExpiresAt: minutesFromNow(60 * 24 * 14),
    refreshToken,
    user: { id: user.id, email: user.email, status: user.status, roles: user.roles },
  };
}

function publicSpot(spot) {
  const { ownerUserId, confidenceScore, verificationCount, filledReportCount, ...rest } = spot;
  return rest;
}

function haversine(lat1, lng1, lat2, lng2) {
  const R = 6371000;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLng = ((lng2 - lng1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(a));
}

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  const raw = Buffer.concat(chunks);
  const type = req.headers['content-type'] ?? '';
  if (type.includes('application/json') && raw.length > 0) {
    try {
      return JSON.parse(raw.toString('utf8'));
    } catch {
      return {};
    }
  }
  return raw;
}

// A tiny 1x1 JPEG so <Image> loads something real for photos.
const JPEG_PIXEL = Buffer.from(
  '/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==',
  'base64',
);

const server = createServer(async (req, res) => {
  if (req.method === 'OPTIONS') {
    send(res, 204);
    return;
  }
  const url = new URL(req.url ?? '/', `http://localhost:${PORT}`);
  let path = url.pathname;

  // Photo bytes endpoint (signed-url stand-in).
  if (path.startsWith('/mock-photo/')) {
    res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Access-Control-Allow-Origin': '*' });
    res.end(JPEG_PIXEL);
    return;
  }

  if (!path.startsWith(BASE)) {
    fail(res, 404, 'NOT_FOUND', 'Unknown path');
    return;
  }
  path = path.slice(BASE.length);
  const body = await readBody(req);
  const method = req.method ?? 'GET';
  console.log(`[mock] ${method} ${path}`);

  try {
    route(req, res, method, path, url, body);
  } catch (error) {
    console.error('[mock] handler error', error);
    fail(res, 500, 'INTERNAL', 'Mock failure');
  }
});

function route(req, res, method, path, url, body) {
  // ── Auth ──
  if (method === 'POST' && path === '/auth/register') {
    const email = String(body.email ?? '').toLowerCase();
    if (users.has(email)) {
      fail(res, 409, 'EMAIL_ALREADY_EXISTS', 'An account with this email already exists.');
      return;
    }
    const user = makeUser(email, body.password ?? '', { verified: false });
    console.log(`[mock] registered ${email} — verify with token "demo"`);
    send(res, 201, {
      accessToken: null,
      tokenType: 'Bearer',
      accessTokenExpiresAt: null,
      refreshTokenExpiresAt: null,
      user: { id: user.id, email: user.email, status: 'PENDING_VERIFICATION', roles: user.roles },
    });
    return;
  }
  if (method === 'POST' && path === '/auth/login') {
    const user = users.get(String(body.email ?? '').toLowerCase());
    if (!user || user.password !== body.password) {
      fail(res, 401, 'INVALID_CREDENTIALS', 'E-posta veya şifre hatalı.');
      return;
    }
    if (!user.verified) {
      fail(res, 403, 'ACCOUNT_NOT_VERIFIED', 'Verify your email first.');
      return;
    }
    send(res, 200, issueTokens(user));
    return;
  }
  if (method === 'POST' && path === '/auth/refresh-token') {
    const token = body?.refreshToken;
    let userId = token ? refreshTokens.get(token) : null;
    // Demo convenience: pre-restart refresh tokens rotate into the demo account.
    if (!userId && typeof token === 'string' && token.startsWith('rt_')) {
      userId = users.get('demo@parkio.dev')?.id ?? null;
    }
    if (!userId) {
      fail(res, 401, 'INVALID_TOKEN', 'Refresh token invalid.');
      return;
    }
    if (token) refreshTokens.delete(token);
    const user = [...users.values()].find((candidate) => candidate.id === userId);
    send(res, 200, issueTokens(user));
    return;
  }
  if (method === 'POST' && path === '/auth/logout') {
    if (body?.refreshToken) refreshTokens.delete(body.refreshToken);
    send(res, 204);
    return;
  }
  if (method === 'POST' && path === '/auth/logout-all') {
    const user = authUser(req);
    if (user) {
      for (const [token, id] of refreshTokens) if (id === user.id) refreshTokens.delete(token);
      for (const [token, id] of sessions) if (id === user.id) sessions.delete(token);
    }
    send(res, 204);
    return;
  }
  if (method === 'POST' && path === '/auth/verify-email') {
    const token = String(body.token ?? '');
    // Mock rule: "demo" verifies the most recent unverified account.
    const target = [...users.values()].reverse().find((candidate) => !candidate.verified);
    if (token !== 'demo' || !target) {
      fail(res, 400, 'INVALID_TOKEN', 'Bağlantı geçersiz veya süresi dolmuş.');
      return;
    }
    target.verified = true;
    send(res, 200, { id: target.id, email: target.email, status: 'ACTIVE', roles: target.roles });
    return;
  }
  if (method === 'POST' && (path === '/auth/resend-verification' || path === '/auth/forgot-password')) {
    console.log(`[mock] ${path} → use token "demo"`);
    send(res, 204);
    return;
  }
  if (method === 'POST' && path === '/auth/reset-password') {
    const user = [...users.values()].reverse().find((candidate) => candidate.verified);
    if (String(body.token ?? '') !== 'demo' || !user) {
      fail(res, 400, 'INVALID_TOKEN', 'Bağlantı geçersiz veya süresi dolmuş.');
      return;
    }
    user.password = body.newPassword;
    send(res, 204);
    return;
  }

  // Everything below requires auth.
  const user = authUser(req);
  if (!user) {
    fail(res, 401, 'UNAUTHORIZED', 'Sign in required.');
    return;
  }

  if (method === 'POST' && path === '/auth/change-password') {
    if (user.password !== body.currentPassword) {
      fail(res, 400, 'INVALID_CURRENT_PASSWORD', 'Mevcut şifre hatalı.');
      return;
    }
    user.password = body.newPassword;
    send(res, 204);
    return;
  }
  if (method === 'GET' && path === '/auth/me') {
    send(res, 200, { id: user.id, email: user.email, status: user.status, roles: user.roles });
    return;
  }

  // ── Users ──
  if (path === '/users/me' && method === 'GET') {
    send(res, 200, {
      id: user.id, authUserId: user.id, email: user.email, displayName: user.displayName,
      phoneNumber: user.phoneNumber, city: user.city, status: user.status, createdAt: user.createdAt,
    });
    return;
  }
  if (path === '/users/me' && method === 'PATCH') {
    if (body.displayName !== undefined) user.displayName = body.displayName;
    if (body.phoneNumber !== undefined) user.phoneNumber = body.phoneNumber;
    if (body.city !== undefined) user.city = body.city;
    send(res, 200, {
      id: user.id, authUserId: user.id, email: user.email, displayName: user.displayName,
      phoneNumber: user.phoneNumber, city: user.city, status: user.status, createdAt: user.createdAt,
    });
    return;
  }
  if (path === '/users/me/preferences' && method === 'GET') {
    send(res, 200, user.preferences);
    return;
  }
  if (path === '/users/me/preferences' && method === 'PATCH') {
    Object.assign(user.preferences, body);
    send(res, 200, user.preferences);
    return;
  }
  if (path === '/users/me/vehicle' && method === 'GET') {
    send(res, 200, user.vehicle);
    return;
  }
  if (path === '/users/me/vehicle' && method === 'PUT') {
    user.vehicle = { vehicleType: body.vehicleType ?? null, plate: body.plate ?? null };
    send(res, 200, user.vehicle);
    return;
  }
  if (path === '/users/me/stats' && method === 'GET') {
    const rule = levelFor(user.points);
    const band =
      user.trustScore < 25 ? 'UNTRUSTED' : user.trustScore < 50 ? 'LOW_TRUST' : user.trustScore < 75 ? 'MEDIUM_TRUST' : 'HIGH_TRUST';
    send(res, 200, { trustScore: user.trustScore, trustBand: band, totalPoints: user.points, currentLevel: rule.level });
    return;
  }
  if (path === '/users/me/smart-return' && method === 'GET') {
    send(res, 200, user.smartReturn);
    return;
  }
  if (path === '/users/me/smart-return/settings' && method === 'PUT') {
    Object.assign(user.smartReturn, body);
    send(res, 200, user.smartReturn);
    return;
  }
  if (path === '/users/me/smart-return/today/left-by-car' && method === 'POST') {
    user.smartReturn.todayStatus = 'LEFT_BY_CAR';
    user.smartReturn.todayExpectedReturnAt = body.expectedReturnAt;
    send(res, 200, user.smartReturn);
    return;
  }
  if (path === '/users/me/smart-return/today/not-by-car' && method === 'POST') {
    user.smartReturn.todayStatus = 'NOT_BY_CAR';
    send(res, 200, user.smartReturn);
    return;
  }
  if (path === '/users/me/smart-return/today/return-time' && method === 'PUT') {
    user.smartReturn.todayExpectedReturnAt = body.expectedReturnAt;
    send(res, 200, user.smartReturn);
    return;
  }
  if (path === '/users/me/smart-return/today/cancel' && method === 'POST') {
    user.smartReturn.todayStatus = 'CANCELLED';
    user.smartReturn.todayExpectedReturnAt = null;
    send(res, 200, user.smartReturn);
    return;
  }

  // ── Media ──
  if (path === '/media/upload' && method === 'POST') {
    const mediaId = randomUUID();
    media.set(mediaId, { status: 'READY' });
    send(res, 201, { mediaId, status: 'READY', contentType: 'image/jpeg', fileSize: 123456 });
    return;
  }

  // ── Parking ──
  if (path === '/parking/spots/nearby' && method === 'GET') {
    // Demo convenience: seeded spots re-arm after expiry so the map never
    // stays empty (real spots created via the app expire for real).
    let rearmIndex = 0;
    for (const spot of spots.values()) {
      if (seededSpotIds.has(spot.id) && Date.parse(spot.expiresAt) <= Date.now()) {
        spot.expiresAt = minutesFromNow(4 + (rearmIndex % 4) * 3);
        spot.createdAt = new Date(Date.now() - (1 + (rearmIndex % 3)) * 60_000).toISOString();
        spot.updatedAt = nowIso();
        if (spot.status === 'FILLED' || spot.status === 'EXPIRED') spot.status = 'ACTIVE';
        rearmIndex += 1;
      }
    }
    const lat = Number(url.searchParams.get('lat'));
    const lng = Number(url.searchParams.get('lng'));
    const radius = Number(url.searchParams.get('radius') ?? 1000);
    const limit = Number(url.searchParams.get('limit') ?? 10);
    const results = [...spots.values()]
      .filter((spot) => ['ACTIVE', 'VERIFIED', 'SUSPICIOUS'].includes(spot.status))
      .filter((spot) => Date.parse(spot.expiresAt) > Date.now())
      .filter((spot) => haversine(lat, lng, spot.latitude, spot.longitude) <= radius)
      .slice(0, limit)
      .map(publicSpot);
    send(res, 200, results);
    return;
  }
  if (path === '/parking/spots' && method === 'POST') {
    const mediaRecord = media.get(body.mediaId);
    if (!mediaRecord) {
      fail(res, 404, 'MEDIA_NOT_FOUND', 'Media not found.');
      return;
    }
    if (mediaRecord.status !== 'READY') {
      fail(res, 409, 'MEDIA_NOT_READY', 'Media is still scanning.');
      return;
    }
    if (body.legalStatus === 'ILLEGAL_OR_RISKY') {
      fail(res, 422, 'ILLEGAL_SPOT_REJECTED', 'Riskli yerler paylaşılamaz.');
      return;
    }
    const spot = makeSpot(user, {
      lat: body.latitude, lng: body.longitude,
      address: body.addressText ?? null, description: body.description ?? null,
      vehicles: body.suitableVehicleTypes, context: body.parkingContext, legal: body.legalStatus,
      status: 'PENDING_VALIDATION', confidence: 50, expiresAt: minutesFromNow(10),
    });
    spot.manualLocationEdited = Boolean(body.manualLocationEdited);
    spot.violationReasons = body.violationReasons ?? [];
    addPoints(user, 'PARKING_UPLOAD', 5, spot.id);
    // Mock AI gate: publish after 8 seconds.
    setTimeout(() => {
      const target = spots.get(spot.id);
      if (target && target.status === 'PENDING_VALIDATION') {
        target.status = 'ACTIVE';
        target.updatedAt = nowIso();
        notify(user.id, 'SYSTEM', 'Yerin yayında', 'Fotoğrafın doğrulandı — yerin haritada.', {});
      }
    }, 8000);
    send(res, 201, spot);
    return;
  }
  if (path === '/parking/my-spots' && method === 'GET') {
    send(res, 200, [...spots.values()].filter((spot) => spot.ownerUserId === user.id));
    return;
  }
  const mySpotMatch = path.match(/^\/parking\/my-spots\/([^/]+)$/);
  if (mySpotMatch && method === 'GET') {
    const spot = spots.get(mySpotMatch[1]);
    if (!spot || spot.ownerUserId !== user.id) {
      fail(res, 404, 'SPOT_NOT_FOUND', 'Spot not found.');
      return;
    }
    send(res, 200, spot);
    return;
  }
  const mediaUrlMatch = path.match(/^\/parking\/spots\/([^/]+)\/media-access-url$/);
  if (mediaUrlMatch && method === 'GET') {
    const spot = spots.get(mediaUrlMatch[1]);
    if (!spot) {
      fail(res, 404, 'SPOT_NOT_FOUND', 'Spot not found.');
      return;
    }
    send(res, 200, {
      spotId: spot.id,
      mediaId: spot.mediaId,
      accessUrl: `http://${req.headers.host}/mock-photo/${spot.mediaId}.jpg`,
      expiresAt: minutesFromNow(5),
    });
    return;
  }
  const verifyMatch = path.match(/^\/parking\/spots\/([^/]+)\/verify$/);
  if (verifyMatch && method === 'POST') {
    const spot = spots.get(verifyMatch[1]);
    if (!spot) {
      fail(res, 404, 'SPOT_NOT_FOUND', 'Spot not found.');
      return;
    }
    if (spot.ownerUserId === user.id) {
      fail(res, 409, 'OWNER_CANNOT_VERIFY', 'Kendi paylaşımını doğrulayamazsın.');
      return;
    }
    const seen = verificationsBySpot.get(spot.id);
    if (seen.has(user.id)) {
      fail(res, 409, 'ALREADY_VERIFIED', 'Bu yeri zaten doğruladın.');
      return;
    }
    seen.add(user.id);
    const result = body.result;
    if (result === 'AVAILABLE') {
      spot.status = 'VERIFIED';
      spot.verificationCount += 1;
      spot.expiresAt = minutesFromNow(spot.verificationCount === 1 ? 15 : 20);
      const owner = [...users.values()].find((candidate) => candidate.id === spot.ownerUserId);
      if (owner) {
        addPoints(owner, 'PARKING_VERIFIED', 20, spot.id);
        notify(owner.id, 'POINT_EARNED', '+20 puan', 'Yerin doğrulandı.', { deeplink: '/impact' });
      }
      addPoints(user, 'PARKING_VERIFIED', 5, spot.id);
    } else if (result === 'FILLED') {
      const count = (filledReports.get(spot.id) ?? 0) + 1;
      filledReports.set(spot.id, count);
      spot.filledReportCount = count;
      if (count >= 2) spot.status = 'FILLED';
    } else {
      spot.confidenceScore = Math.max(0, spot.confidenceScore - 20);
      if (result === 'ILLEGAL_OR_RISKY') {
        cases.push({
          id: randomUUID(), targetType: 'PARKING_SPOT', targetId: spot.id, reason: 'ILLEGAL_OR_RISKY',
          severity: 'HIGH', status: 'OPEN', assignedModeratorId: null, reportCount: 1,
          resolutionAction: null, resolutionNote: null, openedAt: nowIso(), updatedAt: nowIso(), resolvedAt: null,
        });
      }
    }
    spot.updatedAt = nowIso();
    send(res, 200, publicSpot(spot));
    return;
  }
  const claimMatch = path.match(/^\/parking\/spots\/([^/]+)\/claim$/);
  if (claimMatch && method === 'POST') {
    const spot = spots.get(claimMatch[1]);
    if (!spot) {
      fail(res, 404, 'SPOT_NOT_FOUND', 'Spot not found.');
      return;
    }
    if (spot.ownerUserId === user.id) {
      fail(res, 409, 'OWNER_CANNOT_CLAIM', 'Kendi paylaşımını alamazsın.');
      return;
    }
    if (!['ACTIVE', 'VERIFIED', 'SUSPICIOUS'].includes(spot.status)) {
      fail(res, 409, 'SPOT_NOT_CLAIMABLE', 'Bu yer alınamaz.');
      return;
    }
    spot.status = 'FILLED';
    spot.updatedAt = nowIso();
    const owner = [...users.values()].find((candidate) => candidate.id === spot.ownerUserId);
    if (owner) {
      addPoints(owner, 'PARKING_CLAIMED', 30, spot.id);
      notify(owner.id, 'POINT_EARNED', '+30 puan', 'Paylaştığın yer alındı.', { deeplink: '/impact' });
    }
    addPoints(user, 'PARKING_FILLED_BY_USER', 10, spot.id);
    send(res, 200, publicSpot(spot));
    return;
  }
  const spotMatch = path.match(/^\/parking\/spots\/([^/]+)$/);
  if (spotMatch && method === 'GET') {
    const spot = spots.get(spotMatch[1]);
    if (!spot || ['PENDING_VALIDATION', 'PENDING_REVIEW', 'REJECTED'].includes(spot.status)) {
      if (spot && spot.ownerUserId === user.id) {
        send(res, 200, publicSpot(spot));
        return;
      }
      fail(res, 404, 'SPOT_NOT_FOUND', 'Spot not found.');
      return;
    }
    send(res, 200, publicSpot(spot));
    return;
  }

  // ── Gamification ──
  if (path === '/gamification/me/progress' && method === 'GET') {
    send(res, 200, { userId: user.id, totalPoints: user.points, currentLevel: levelFor(user.points).level, updatedAt: nowIso() });
    return;
  }
  if (path === '/gamification/me/points' && method === 'GET') {
    send(res, 200, { userId: user.id, totalPoints: user.points, recentTransactions: (pointLedgers.get(user.id) ?? []).slice(0, 50) });
    return;
  }
  if (path === '/gamification/me/level' && method === 'GET') {
    const rule = levelFor(user.points);
    const next = LEVELS.find((candidate) => candidate.level === rule.level + 1) ?? null;
    send(res, 200, {
      userId: user.id, currentLevel: rule.level, totalPoints: user.points,
      currentLevelMinPoints: rule.minPoints,
      nextLevelMinPoints: next?.minPoints ?? null,
      pointsToNextLevel: next ? Math.max(0, next.minPoints - user.points) : null,
    });
    return;
  }
  if (path === '/gamification/me/access-policy' && method === 'GET') {
    const rule = levelFor(user.points);
    send(res, 200, {
      userId: user.id, currentLevel: rule.level,
      searchRadiusMeters: rule.searchRadiusMeters, resultLimit: rule.resultLimit,
      dailyViewLimit: rule.dailyViewLimit,
      verifiedSpotPriority: rule.verifiedSpotPriority, notificationPriority: rule.notificationPriority,
    });
    return;
  }
  if (path === '/gamification/levels' && method === 'GET') {
    send(res, 200, LEVELS);
    return;
  }
  if (path === '/gamification/leaderboard' && method === 'GET') {
    const ranked = [...users.values()]
      .filter((candidate) => candidate.points > 0)
      .sort((a, b) => b.points - a.points)
      .map((candidate, index) => ({
        rank: index + 1, userId: candidate.id, totalPoints: candidate.points,
        currentLevel: levelFor(candidate.points).level,
      }));
    send(res, 200, ranked);
    return;
  }

  // ── Notifications ──
  if (path === '/notifications/me' && method === 'GET') {
    send(res, 200, (notifications.get(user.id) ?? []).slice(0, 50));
    return;
  }
  const readMatch = path.match(/^\/notifications\/([^/]+)\/read$/);
  if (readMatch && method === 'PATCH') {
    const list = notifications.get(user.id) ?? [];
    const item = list.find((candidate) => candidate.id === readMatch[1]);
    if (!item) {
      fail(res, 404, 'NOT_FOUND', 'Notification not found.');
      return;
    }
    item.status = 'READ';
    item.readAt = nowIso();
    send(res, 200, item);
    return;
  }
  if (path === '/notifications/device-token' && method === 'POST') {
    send(res, 201, { id: randomUUID(), platform: body.platform, active: true, createdAt: nowIso() });
    return;
  }
  const tokenMatch = path.match(/^\/notifications\/device-token\/([^/]+)$/);
  if (tokenMatch && method === 'DELETE') {
    send(res, 204);
    return;
  }

  // ── Geocoding ──
  if (path === '/geocoding/search' && method === 'GET') {
    const query = (url.searchParams.get('q') ?? '').toLowerCase();
    const places = [
      { id: 'p1', displayName: 'Alsancak, Konak, İzmir', primary: 'Alsancak', secondary: 'Konak, İzmir', lat: 38.4382, lng: 27.1421 },
      { id: 'p2', displayName: 'Karşıyaka Çarşı, İzmir', primary: 'Karşıyaka Çarşı', secondary: 'Karşıyaka, İzmir', lat: 38.4551, lng: 27.1113 },
      { id: 'p3', displayName: 'Bostanlı Sahil, İzmir', primary: 'Bostanlı Sahil', secondary: 'Karşıyaka, İzmir', lat: 38.4664, lng: 27.0996 },
      { id: 'p4', displayName: 'Konak Pier, İzmir', primary: 'Konak Pier', secondary: 'Konak, İzmir', lat: 38.4285, lng: 27.1277 },
      { id: 'p5', displayName: 'Bornova Merkez, İzmir', primary: 'Bornova', secondary: 'İzmir', lat: 38.4622, lng: 27.2163 },
    ];
    send(res, 200, {
      results: places.filter((place) => place.displayName.toLowerCase().includes(query) || query.length < 3),
    });
    return;
  }

  // ── Moderation ──
  if (path === '/moderation/reports' && method === 'POST') {
    const duplicate = reports.find(
      (candidate) =>
        candidate.reporterUserId === user.id &&
        candidate.targetId === body.targetId &&
        candidate.reason === body.reason,
    );
    if (duplicate) {
      fail(res, 409, 'DUPLICATE_REPORT', 'Bu yeri aynı nedenle zaten bildirdin.');
      return;
    }
    const serious = ['ILLEGAL_OR_RISKY', 'ABUSE_REPORT', 'FAKE_PHOTO'].includes(body.reason);
    let caseId = null;
    if (serious) {
      caseId = randomUUID();
      cases.push({
        id: caseId, targetType: body.targetType, targetId: body.targetId, reason: body.reason,
        severity: body.reason === 'ABUSE_REPORT' ? 'HIGH' : 'MEDIUM', status: 'OPEN',
        assignedModeratorId: null, reportCount: 1, resolutionAction: null, resolutionNote: null,
        openedAt: nowIso(), updatedAt: nowIso(), resolvedAt: null,
      });
    }
    const report = {
      id: randomUUID(), reporterUserId: user.id, targetType: body.targetType, targetId: body.targetId,
      reason: body.reason, description: body.description ?? null, caseId, createdAt: nowIso(),
    };
    reports.push(report);
    send(res, 201, report);
    return;
  }
  if (path === '/moderation/reports/me' && method === 'GET') {
    send(res, 200, reports.filter((report) => report.reporterUserId === user.id));
    return;
  }
  if (path === '/moderation/appeals' && method === 'POST') {
    const target = cases.find((candidate) => candidate.id === body.caseId);
    if (!target) {
      fail(res, 404, 'CASE_NOT_FOUND', 'Vaka bulunamadı.');
      return;
    }
    if (target.status !== 'RESOLVED') {
      fail(res, 409, 'CASE_NOT_RESOLVED', 'Bu vaka henüz sonuçlanmadı.');
      return;
    }
    const appeal = {
      id: randomUUID(), appealUserId: user.id, caseId: body.caseId, note: body.note ?? null,
      status: 'OPEN', resolverModeratorId: null, resolutionNote: null, createdAt: nowIso(), resolvedAt: null,
    };
    appeals.push(appeal);
    send(res, 201, appeal);
    return;
  }
  const staff = user.roles.includes('MODERATOR') || user.roles.includes('ADMIN');
  if (path === '/moderation/cases' && method === 'GET' && staff) {
    const status = url.searchParams.get('status');
    send(res, 200, status ? cases.filter((candidate) => candidate.status === status) : cases);
    return;
  }
  const caseMatch = path.match(/^\/moderation\/cases\/([^/]+)$/);
  if (caseMatch && method === 'GET' && staff) {
    const target = cases.find((candidate) => candidate.id === caseMatch[1]);
    if (!target) {
      fail(res, 404, 'CASE_NOT_FOUND', 'Case not found.');
      return;
    }
    send(res, 200, target);
    return;
  }
  const assignMatch = path.match(/^\/moderation\/cases\/([^/]+)\/assign$/);
  if (assignMatch && method === 'POST' && staff) {
    const target = cases.find((candidate) => candidate.id === assignMatch[1]);
    if (!target) {
      fail(res, 404, 'CASE_NOT_FOUND', 'Case not found.');
      return;
    }
    target.assignedModeratorId = user.id;
    target.status = 'IN_REVIEW';
    target.updatedAt = nowIso();
    send(res, 200, target);
    return;
  }
  const resolveMatch = path.match(/^\/moderation\/cases\/([^/]+)\/resolve$/);
  if (resolveMatch && method === 'POST' && staff) {
    const target = cases.find((candidate) => candidate.id === resolveMatch[1]);
    if (!target) {
      fail(res, 404, 'CASE_NOT_FOUND', 'Case not found.');
      return;
    }
    target.status = body.action === 'APPROVE' ? 'REJECTED' : 'RESOLVED';
    target.resolutionAction = body.action;
    target.resolutionNote = body.note ?? null;
    target.resolvedAt = nowIso();
    target.updatedAt = nowIso();
    send(res, 200, target);
    return;
  }
  if (path === '/moderation/appeals' && method === 'GET' && staff) {
    send(res, 200, appeals);
    return;
  }
  const appealResolveMatch = path.match(/^\/moderation\/appeals\/([^/]+)\/resolve$/);
  if (appealResolveMatch && method === 'POST' && staff) {
    const target = appeals.find((candidate) => candidate.id === appealResolveMatch[1]);
    if (!target) {
      fail(res, 404, 'NOT_FOUND', 'Appeal not found.');
      return;
    }
    target.status = body.accepted ? 'ACCEPTED' : 'REJECTED';
    target.resolverModeratorId = user.id;
    target.resolutionNote = body.note ?? null;
    target.resolvedAt = nowIso();
    send(res, 200, target);
    return;
  }

  // ── Analytics (admin) ──
  if (path === '/analytics/overview' && method === 'GET') {
    send(res, 200, {
      totalParkingCreated: spots.size + 104,
      totalParkingVerified: 58,
      totalParkingClaimed: 41,
      totalParkingRejected: 9,
      totalPointsEarned: 4820,
      totalLevelUps: 36,
      totalNotificationsCreated: 412,
    });
    return;
  }

  fail(res, 404, 'NOT_FOUND', `No mock for ${method} ${path}`);
}

server.listen(PORT, () => {
  console.log(`Parkio mock gateway on http://localhost:${PORT}${BASE}`);
  console.log('Demo account: demo@parkio.dev / Parkio-Demo-1234');
  console.log('Moderator:    mod@parkio.dev  / Parkio-Demo-1234');
  console.log('Email verification / password reset token: "demo"');
});
