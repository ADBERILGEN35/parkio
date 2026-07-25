# Parkio Live API — Integration Reference & Client

> Verified live on 2026-07-18 against the deployed hosted-beta gateway.
> Purpose: everything needed to generate a frontend (or any client) that connects to the
> **real** Parkio API.

## 1. The live environment (verified by probing, not assumed)

- **Base URL:** `https://api.parkio.dev/api/v1`
- `GET https://api.parkio.dev/actuator/health` → `200 {"status":"UP"}` — gateway is live.
- `GET /api/v1/auth/.well-known/jwks.json` → `200` public — RS256 JWKS published.
- `POST /api/v1/auth/login` with wrong credentials → `401 {"code":"INVALID_CREDENTIALS", ...}` — the full stack (gateway → auth-service → DB) is running.
- Every response carries `x-correlation-id`; errors use a stable JSON envelope (§5).
- HTTP/2 + HSTS enabled.

## 2. CORS — the critical constraint for browser clients

The gateway uses a **strict origin allow-list with credentialed CORS**. Verified live:

| Origin | Preflight result |
|---|---|
| `https://app.parkio.dev` | ✅ allowed (`access-control-allow-origin` echoed, credentials allowed, all methods) |
| `https://parkio.dev` | ❌ 403 |
| anything else (e.g. localhost, sandboxes) | ❌ 403 |

Consequences:

- A **browser** app can call this API **only if served from `https://app.parkio.dev`** (today's allow-list).
- To develop locally against the real API, the operator must add the dev origin to the gateway env: `PARKIO_CORS_ALLOWED_ORIGINS=https://app.parkio.dev,http://localhost:5173` (comma-separated; the deploy uses this env var) — or you use a local reverse proxy (e.g. Vite `server.proxy` → `https://api.parkio.dev`, so the browser sees same-origin).
- **Non-browser clients are unaffected**: native mobile apps, curl, server-side code all work from anywhere (CORS is a browser-only mechanism).
- Credentialed mode is ON: browser clients that use the cookie refresh flow must send `credentials: 'include'` on `/auth/**` calls.

## 3. Auth model

- **Access token:** RS256 JWT, **~15 min TTL**, sent as `Authorization: Bearer <token>` on every non-public call. Validated at the edge against the JWKS; claims include `roles` and a `session_epoch`.
- **Refresh token:** opaque, rotated on every refresh (family revocation on reuse). Two transports, selected by the `X-Parkio-Client` header:
  - **Web transport (default):** HttpOnly refresh cookie set on login/refresh; the SPA calls the auth endpoints with `credentials: 'include'` and never sees the refresh token. `AuthResponse.refreshToken` is omitted.
  - **Token transport (`X-Parkio-Client: mobile`):** `AuthResponse` includes the rotated `refreshToken`; the client stores it securely and sends it in the body of `/auth/refresh-token` and `/auth/logout`. Works from any runtime (native, server, scripts) — this is the practical mode for prototypes.
- **Revocation is live, not just expiry:** after JWT validation the gateway checks (cached ~30 s) the account's **current status** (suspended/banned → `403 ACCOUNT_NOT_ACTIVE`) and the **session epoch** (logout-all / reuse detection / suspension bumps it → old access tokens die within ~30 s). Both checks fail closed with `503` if the backing service is down.
- **Registration flow:** `register` → account is `PENDING_VERIFICATION` → user clicks emailed link → `verify-email` → `login`. Unverified accounts cannot log in. No OAuth/social login exists.

### Public endpoints (no token required) — exact allow-list from the gateway

```
POST /api/v1/auth/register            POST /api/v1/auth/login
POST /api/v1/auth/verify-email        POST /api/v1/auth/resend-verification
POST /api/v1/auth/forgot-password     POST /api/v1/auth/reset-password
POST /api/v1/auth/refresh-token       POST /api/v1/auth/logout
GET  /api/v1/auth/.well-known/jwks.json
POST /api/v1/waitlist
```

Everything else requires a valid Bearer token.

### Edge role gating (first match wins)

| Path | Access |
|---|---|
| `POST /moderation/reports`, `GET /moderation/reports/me`, `POST /moderation/appeals` | any authenticated user |
| `/moderation/**` (queue, cases, resolution) | MODERATOR / ADMIN / SUPER_ADMIN |
| `GET /analytics/users/**` (own stats; ownership enforced downstream) | any authenticated user |
| `/analytics/**` (platform) | ADMIN / SUPER_ADMIN |
| `/admin/**` (user/session/role administration) | ADMIN / SUPER_ADMIN |
| `/ai-validations/**` | MODERATOR / ADMIN / SUPER_ADMIN |
| `GET /waitlist/export` | ADMIN / SUPER_ADMIN |
| everything else | any authenticated user |

## 4. Rate limits (Redis token bucket, keyed by userId, else client IP)

| Tier | Paths | Sustained/s | Burst |
|---|---|---|---|
| Auth | `/auth/**` | 5 | 10 |
| Media upload | `/media/**` | 2 | 5 |
| Parking | `/parking/**` | 10 | 20 |
| Geocoding | `/geocoding/**` | 5 | 10 |
| Admin | `/admin/**` | 20 | 40 |
| Default (users, gamification, notifications, moderation, analytics) | rest | 30 | 60 |
| Waitlist (extra) | `POST /waitlist` | 10/hour per IP, 3/hour per email | — |

Handle `429` with backoff; debounce typeahead against the geocoding tier.

## 5. Error envelope & headers

Every gateway/domain error returns:

```json
{ "code": "MACHINE_READABLE_CODE", "message": "Human message.", "traceId": "uuid", "timestamp": "ISO-8601",
  "fieldErrors": [ { "field": "email", "message": "..." } ] }
```

`fieldErrors` is optional (validation failures only). Codes to handle: `MISSING_TOKEN`, `INVALID_TOKEN`, `INVALID_CREDENTIALS`, `ACCOUNT_NOT_VERIFIED` (403), `ACCOUNT_NOT_ACTIVE` (403 — suspended/banned), `FORBIDDEN`, `USER_STATUS_UNAVAILABLE` (503), `MEDIA_NOT_READY` / `MEDIA_INFECTED` / `MEDIA_SCAN_UNAVAILABLE`, `ILLEGAL_SPOT_REJECTED` (422 when creating a spot with `legalStatus: 'ILLEGAL_OR_RISKY'`), `DUPLICATE_REPORT` / `DUPLICATE_APPEAL` / `INVALID_CASE_STATE` / `INVALID_APPEAL_STATE` (409), plus 429 rate-limit responses.

Headers:

- `x-correlation-id` — echoed on every response; clients may send their own for tracing; **always show/log it on errors** (the UI surfaces it as "trace id").
- `Idempotency-Key` — required convention on high-risk writes (spot create, verify, claim, media upload): send a UUID per logical attempt; retries reuse the same key so the server deduplicates.

## 6. Endpoint catalog

All paths are relative to the base URL (which already contains `/api/v1`). 🔑 = requires `Idempotency-Key` header. 🍪 = send credentials in web-cookie mode.

### Auth
| Call | Endpoint | Body → Response |
|---|---|---|
| Register | `POST /auth/register` 🍪 | `{email, password, locale?: 'tr'\|'en'}` → `AuthResponse` (account starts `PENDING_VERIFICATION`) |
| Login | `POST /auth/login` 🍪 | `{email, password}` → `AuthResponse` |
| Refresh | `POST /auth/refresh-token` 🍪 | web: no body (cookie) / token mode: `{refreshToken}` → `AuthResponse` (rotated) |
| Logout | `POST /auth/logout` 🍪 | web: none / token mode: `{refreshToken}` → void |
| Logout everywhere | `POST /auth/logout-all` 🍪 | none → void (bumps session epoch) |
| Verify email | `POST /auth/verify-email` | `{token}` → `User` |
| Resend verification | `POST /auth/resend-verification` | `{email, locale?}` → void |
| Forgot password | `POST /auth/forgot-password` | `{email, locale?}` → void |
| Reset password | `POST /auth/reset-password` 🍪 | `{token, newPassword}` → void |
| Change password | `POST /auth/change-password` 🍪 | `{currentPassword, newPassword}` → void |
| Current identity | `GET /auth/me` | → `User` |

### Parking
| Call | Endpoint | Notes |
|---|---|---|
| Nearby search | `GET /parking/spots/nearby?lat&lng&radius&limit` | radius ≤ 50000 (default 1000 m), limit ≤ 50 (default 10); results also capped by the caller's level access policy → `PublicSpot[]` |
| Spot detail | `GET /parking/spots/{spotId}` | → `PublicSpot` |
| Spot photo URL | `GET /parking/spots/{spotId}/media-access-url` | → `{spotId, mediaId, accessUrl, expiresAt}` — short-lived signed URL (~5 min); re-fetch when expired |
| Create spot 🔑 | `POST /parking/spots` | `CreateSpotRequest` → `Spot`; 422 `MEDIA_NOT_READY` if photo not uploaded/clean yet; 422 `ILLEGAL_SPOT_REJECTED` for illegal legalStatus |
| Verify spot 🔑 | `POST /parking/spots/{spotId}/verify` | `{result: 'AVAILABLE'\|'FILLED'\|'INVALID'\|'ILLEGAL_OR_RISKY'\|'WRONG_VEHICLE_SIZE'}` → `PublicSpot` (owners cannot verify own spot; once per user) |
| Claim spot 🔑 | `POST /parking/spots/{spotId}/claim` | body `null` → `PublicSpot` (terminal `FILLED`) |
| My spots | `GET /parking/my-spots` · `GET /parking/my-spots/{spotId}` | → `Spot[]` / `Spot` (owner view with confidence/counters) |

### Media
| Call | Endpoint | Notes |
|---|---|---|
| Upload photo 🔑 | `POST /media/upload` | `multipart/form-data`, field name **`file`** → `{mediaId, status, contentType, fileSize}`. 120 s timeout budget; 422 `MEDIA_INFECTED`, 503 `MEDIA_SCAN_UNAVAILABLE` |

### Users & Smart Return
| Call | Endpoint |
|---|---|
| Profile | `GET /users/me` · `PATCH /users/me` `{displayName?, phoneNumber?, city?}` |
| Preferences | `GET /users/me/preferences` · `PATCH /users/me/preferences` `{preferredRadiusMeters?, notificationsEnabled?, preferredLocale?}` |
| Stats | `GET /users/me/stats` → `{trustScore, trustBand, totalPoints, currentLevel}` |
| Vehicle | `GET /users/me/vehicle` · `PUT /users/me/vehicle` `{vehicleType?, plate?}` |
| Public profile | `GET /users/{userId}/public-profile` |
| Smart Return settings | `GET /users/me/smart-return` · `PUT /users/me/smart-return/settings` |
| Smart Return today | `POST .../today/left-by-car` `{expectedReturnAt}` · `POST .../today/not-by-car` · `PUT .../today/return-time` `{expectedReturnAt}` · `POST .../today/cancel` (all under `/users/me/smart-return`) |

### Notifications
| Call | Endpoint |
|---|---|
| Inbox | `GET /notifications/me` → `AppNotification[]` (server caps at 50, no pagination) |
| Mark read | `PATCH /notifications/{id}/read` → `AppNotification` |
| Device tokens | `POST /notifications/device-token` `{token, platform: 'IOS'\|'ANDROID'\|'WEB'}` · `DELETE /notifications/device-token/{tokenId}` |

### Gamification
| Call | Endpoint |
|---|---|
| Progress | `GET /gamification/me/progress` → `{totalPoints, currentLevel, updatedAt}` |
| Points ledger | `GET /gamification/me/points` → totals + 50 recent transactions |
| Level standing | `GET /gamification/me/level` → current/next thresholds, points to next |
| Access policy | `GET /gamification/me/access-policy` → `{searchRadiusMeters, resultLimit, dailyViewLimit, verifiedSpotPriority, notificationPriority}` |
| Level table | `GET /gamification/levels` → `LevelRule[]` |
| Leaderboard | `GET /gamification/leaderboard?limit` (1–100, default 20) → `[{rank, userId, totalPoints, currentLevel}]` |

### Geocoding
| Call | Endpoint |
|---|---|
| Place search | `GET /geocoding/search?q=<3–256 chars>&limit=<1–10>` → `{results: [{id, displayName, primary, secondary, lat, lng}]}` (debounce — tight rate tier) |

### Moderation (user-facing)
| Call | Endpoint |
|---|---|
| Report | `POST /moderation/reports` `{targetType: 'PARKING_SPOT'\|'USER'\|'MEDIA', targetId, reason, description?}` (409 `DUPLICATE_REPORT`) |
| My reports | `GET /moderation/reports/me` |
| Appeal | `POST /moderation/appeals` `{caseId, note?}` |

### Moderation (MODERATOR/ADMIN)
`GET /moderation/cases?status` · `GET /moderation/cases/{id}` · `POST /moderation/cases/{id}/assign` · `POST /moderation/cases/{id}/resolve` `{action, note?}` · `GET /moderation/appeals` · `POST /moderation/appeals/{id}/resolve` `{accepted, note?}`

### Analytics (own stats: any user · platform: ADMIN)
`GET /analytics/users/{userId}` (self only) · `GET /analytics/overview` · `/daily` · `/parking` · `/metrics`

### Admin (ADMIN/SUPER_ADMIN, Spring-style pagination `page`/`size`/`sort`, responses `{content, page, size, totalElements, totalPages}`)
`GET /admin/dashboard` · `GET /admin/users` (+filters) · `GET /admin/users/{id}` · `POST /admin/users/{id}/suspend|reactivate|revoke-sessions|resend-verification` `{reason}` · `GET /admin/users/{id}/sessions` · `DELETE /admin/users/{id}/sessions/{sessionId}` · `POST /admin/users/{id}/roles` `{role, action: 'GRANT'\|'REVOKE', reason}` · `GET /admin/audit-events` (+filters) · `GET /admin/security/summary`

### Waitlist (public)
`POST /waitlist` `{email, city?, role?: 'driver'|'tester'|'partner', consentTimestamp, source: 'parkio.dev-landing'}` → `{status: 'accepted'}`

## 7. Key DTO shapes (exact field names)

```ts
interface User { id: string; email: string; status: string; roles: string[] }

interface AuthResponse {
  accessToken: string | null;            // null until email is verified
  tokenType: string;                     // "Bearer"
  accessTokenExpiresAt: string | null;   // ISO
  refreshTokenExpiresAt: string | null;
  refreshToken?: string | null;          // ONLY in token mode (X-Parkio-Client: mobile)
  user: User;
}

type ParkingStatus = 'PENDING_VALIDATION'|'PENDING_REVIEW'|'ACTIVE'|'VERIFIED'|'SUSPICIOUS'|'FILLED'|'EXPIRED'|'REJECTED'|'REVIEW_FAILED';
type SpotVehicleType = 'SEDAN'|'HATCHBACK'|'SUV'|'VAN'|'MOTORCYCLE'|'ANY';
type LegalStatus = 'LEGAL'|'UNCERTAIN'|'ILLEGAL_OR_RISKY';
type ParkingContext = 'STREET_PARKING'|'OPEN_PARKING_LOT'|'INDOOR_PARKING'|'MALL_PARKING'|'RESIDENTIAL_AREA'|'OFFICE_AREA'|'UNKNOWN';
type ViolationReason = 'NO_PARKING_SIGN'|'GARAGE_ENTRANCE'|'BUS_STOP'|'PEDESTRIAN_CROSSING'|'FIRE_HYDRANT'|'SIDEWALK'|'TRAFFIC_FLOW_BLOCKING'|'PRIVATE_PROPERTY'|'OTHER';

interface PublicSpot {
  id: string; mediaId: string;
  latitude: number; longitude: number;
  addressText: string | null; description: string | null;
  manualLocationEdited: boolean;
  suitableVehicleTypes: SpotVehicleType[];
  parkingContext: ParkingContext;
  legalStatus: LegalStatus;
  violationReasons: string[];
  status: ParkingStatus;
  expiresAt: string; createdAt: string; updatedAt: string;   // expiresAt drives countdowns
}
interface Spot extends PublicSpot {      // owner-only extras
  ownerUserId: string; confidenceScore: number;
  verificationCount: number; filledReportCount: number;
}
interface CreateSpotRequest {
  mediaId: string; latitude: number; longitude: number;
  addressText?: string; description?: string; manualLocationEdited?: boolean;
  suitableVehicleTypes: SpotVehicleType[];
  parkingContext: ParkingContext;
  legalStatus: LegalStatus;
  violationReasons?: ViolationReason[];
}

interface UserStats { trustScore: number; trustBand: string; totalPoints: number; currentLevel: number }

interface SmartReturnSettings {
  enabled: boolean;
  homeLatitude: number | null; homeLongitude: number | null; homeLabel: string | null;
  defaultReturnTime: string | null; reminderLeadMinutes: number;
  lastPromptDate: string | null;
  todayStatus: 'UNKNOWN'|'LEFT_BY_CAR'|'RETURN_CHECK_IN_PROGRESS'|'NOT_BY_CAR'|'CANCELLED';
  todayExpectedReturnAt: string | null;
  todayReturnCheckCompletedAt: string | null; todayNotificationSentAt: string | null;
}

interface AppNotification {
  id: string;
  type: 'NEARBY_PARKING'|'LEVEL_UP'|'POINT_EARNED'|'WARNING'|'SYSTEM'|'SMART_RETURN_PROMPT'|'SMART_RETURN_AVAILABLE';
  channel: 'PUSH'|'EMAIL'|'IN_APP';
  title: string; body: string; metadata?: Record<string,string>;
  status: 'PENDING'|'SENT'|'FAILED'|'READ';
  createdAt: string; readAt: string | null;
}

interface GamificationAccessPolicy {
  userId: string; currentLevel: number; searchRadiusMeters: number; resultLimit: number;
  dailyViewLimit: number; verifiedSpotPriority: boolean; notificationPriority: boolean;
}
interface LeaderboardEntry { rank: number; userId: string; totalPoints: number; currentLevel: number }
```

(Vehicle profile note: the user-service vehicle enum is `MOTORCYCLE|SMALL_CAR|SEDAN|SUV|VAN|TRUCK` — it is a different enum from the spot's `suitableVehicleTypes`.)

## 8. Ready-to-use standalone client (zero dependencies, fetch-based)

Works in Node 18+, React Native, and browsers (browser origin must be CORS-allow-listed — §2). Uses the **token transport** (`X-Parkio-Client: mobile`) so refresh works from any runtime. Trade-off vs the cookie flow: the refresh token is held in client memory — acceptable for a beta prototype, not for the production SPA.

```ts
// parkio-api.ts — standalone client for the live Parkio API.
export const API_BASE = 'https://api.parkio.dev/api/v1';

export interface ApiErrorBody {
  code: string; message: string; traceId: string; timestamp: string;
  fieldErrors?: { field: string; message: string }[];
}
export class ParkioApiError extends Error {
  constructor(public status: number, public body: ApiErrorBody) { super(body.message); }
  get code() { return this.body.code; }
  get traceId() { return this.body.traceId; }
}

const uuid = () => globalThis.crypto?.randomUUID?.() ??
  `${Date.now().toString(16)}-${Math.random().toString(16).slice(2)}`;
const REFRESH_EXEMPT = ['/auth/login', '/auth/register', '/auth/refresh-token', '/auth/logout'];

export class ParkioClient {
  private accessToken: string | null = null;
  private refreshToken: string | null = null;
  private refreshing: Promise<void> | null = null;

  constructor(private base: string = API_BASE) {}

  // ---------- core ----------
  private async send<T>(method: string, path: string, opts: {
    body?: unknown; form?: FormData; credentials?: boolean; idemKey?: string; retried?: boolean;
  } = {}): Promise<T> {
    const headers: Record<string, string> = {
      'X-Correlation-Id': uuid(),
      'X-Parkio-Client': 'mobile',                       // token transport
    };
    if (this.accessToken) headers.Authorization = `Bearer ${this.accessToken}`;
    if (opts.idemKey) headers['Idempotency-Key'] = opts.idemKey;
    if (!opts.form && opts.body !== undefined) headers['Content-Type'] = 'application/json';

    const res = await fetch(this.base + path, {
      method, headers,
      body: opts.form ?? (opts.body !== undefined ? JSON.stringify(opts.body) : undefined),
    });

    if (res.status === 401 && !opts.retried && !REFRESH_EXEMPT.some(p => path.startsWith(p))) {
      await this.refresh();                              // single-flight
      return this.send(method, path, { ...opts, retried: true }); // same idemKey on retry
    }
    if (!res.ok) {
      let body: ApiErrorBody;
      try { body = await res.json(); }
      catch { body = { code: 'UNKNOWN_ERROR', message: res.statusText, traceId: '', timestamp: new Date().toISOString() }; }
      throw new ParkioApiError(res.status, body);
    }
    return res.status === 204 ? (undefined as T) : ((await res.json().catch(() => undefined)) as T);
  }

  private refresh(): Promise<void> {
    this.refreshing ??= (async () => {
      if (!this.refreshToken) throw new ParkioApiError(401,
        { code: 'MISSING_TOKEN', message: 'No refresh token.', traceId: '', timestamp: new Date().toISOString() });
      const res = await fetch(`${this.base}/auth/refresh-token`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Correlation-Id': uuid(), 'X-Parkio-Client': 'mobile' },
        body: JSON.stringify({ refreshToken: this.refreshToken }),
      });
      if (!res.ok) {
        this.accessToken = this.refreshToken = null;     // session dead → re-login
        throw new ParkioApiError(res.status, await res.json().catch(() => ({
          code: 'INVALID_TOKEN', message: 'Refresh failed.', traceId: '', timestamp: new Date().toISOString() })));
      }
      this.storeAuth(await res.json());
    })().finally(() => { this.refreshing = null; });
    return this.refreshing;
  }

  private storeAuth(a: any) {
    this.accessToken = a.accessToken ?? null;
    if (a.refreshToken) this.refreshToken = a.refreshToken;
    return a;
  }
  get isAuthenticated() { return this.accessToken !== null; }

  // ---------- auth ----------
  register = (email: string, password: string, locale: 'tr' | 'en' = 'tr') =>
    this.send<any>('POST', '/auth/register', { body: { email, password, locale } }).then(a => this.storeAuth(a));
  login = (email: string, password: string) =>
    this.send<any>('POST', '/auth/login', { body: { email, password } }).then(a => this.storeAuth(a));
  me = () => this.send<any>('GET', '/auth/me');
  verifyEmail = (token: string) => this.send<any>('POST', '/auth/verify-email', { body: { token } });
  resendVerification = (email: string, locale: 'tr' | 'en' = 'tr') =>
    this.send<void>('POST', '/auth/resend-verification', { body: { email, locale } });
  forgotPassword = (email: string, locale: 'tr' | 'en' = 'tr') =>
    this.send<void>('POST', '/auth/forgot-password', { body: { email, locale } });
  resetPassword = (token: string, newPassword: string) =>
    this.send<void>('POST', '/auth/reset-password', { body: { token, newPassword } });
  changePassword = (currentPassword: string, newPassword: string) =>
    this.send<void>('POST', '/auth/change-password', { body: { currentPassword, newPassword } });
  logout = async () => {
    try { await this.send<void>('POST', '/auth/logout', { body: { refreshToken: this.refreshToken } }); }
    finally { this.accessToken = this.refreshToken = null; }
  };
  logoutAll = () => this.send<void>('POST', '/auth/logout-all');

  // ---------- parking ----------
  nearbySpots = (p: { lat: number; lng: number; radius?: number; limit?: number }) => {
    const q = new URLSearchParams({ lat: String(p.lat), lng: String(p.lng) });
    if (p.radius != null) q.set('radius', String(p.radius));
    if (p.limit != null) q.set('limit', String(p.limit));
    return this.send<any[]>('GET', `/parking/spots/nearby?${q}`);
  };
  getSpot = (spotId: string) => this.send<any>('GET', `/parking/spots/${spotId}`);
  getSpotPhotoUrl = (spotId: string) =>
    this.send<{ accessUrl: string; expiresAt: string }>('GET', `/parking/spots/${spotId}/media-access-url`);
  createSpot = (req: object) => this.send<any>('POST', '/parking/spots', { body: req, idemKey: uuid() });
  verifySpot = (spotId: string, result: 'AVAILABLE'|'FILLED'|'INVALID'|'ILLEGAL_OR_RISKY'|'WRONG_VEHICLE_SIZE') =>
    this.send<any>('POST', `/parking/spots/${spotId}/verify`, { body: { result }, idemKey: uuid() });
  claimSpot = (spotId: string) =>
    this.send<any>('POST', `/parking/spots/${spotId}/claim`, { body: null, idemKey: uuid() });
  mySpots = () => this.send<any[]>('GET', '/parking/my-spots');
  mySpot = (spotId: string) => this.send<any>('GET', `/parking/my-spots/${spotId}`);

  // ---------- media ----------
  /** file: browser File/Blob, or RN { uri, name, type } */
  uploadPhoto = (file: any) => {
    const form = new FormData();
    form.append('file', file);
    return this.send<{ mediaId: string; status: string }>('POST', '/media/upload', { form, idemKey: uuid() });
  };

  // ---------- users & smart return ----------
  myProfile = () => this.send<any>('GET', '/users/me');
  updateMyProfile = (b: { displayName?: string; phoneNumber?: string; city?: string }) =>
    this.send<any>('PATCH', '/users/me', { body: b });
  myStats = () => this.send<any>('GET', '/users/me/stats');
  myPreferences = () => this.send<any>('GET', '/users/me/preferences');
  updateMyPreferences = (b: object) => this.send<any>('PATCH', '/users/me/preferences', { body: b });
  myVehicle = () => this.send<any>('GET', '/users/me/vehicle');
  upsertMyVehicle = (b: { vehicleType?: string | null; plate?: string | null }) =>
    this.send<any>('PUT', '/users/me/vehicle', { body: b });
  publicProfile = (userId: string) => this.send<any>('GET', `/users/${userId}/public-profile`);
  smartReturn = () => this.send<any>('GET', '/users/me/smart-return');
  updateSmartReturnSettings = (b: object) => this.send<any>('PUT', '/users/me/smart-return/settings', { body: b });
  smartReturnLeftByCar = (expectedReturnAt: string) =>
    this.send<any>('POST', '/users/me/smart-return/today/left-by-car', { body: { expectedReturnAt } });
  smartReturnNotByCar = () => this.send<any>('POST', '/users/me/smart-return/today/not-by-car');
  smartReturnCancelToday = () => this.send<any>('POST', '/users/me/smart-return/today/cancel');

  // ---------- notifications ----------
  myNotifications = () => this.send<any[]>('GET', '/notifications/me');
  markNotificationRead = (id: string) => this.send<any>('PATCH', `/notifications/${id}/read`);

  // ---------- gamification ----------
  myProgress = () => this.send<any>('GET', '/gamification/me/progress');
  myPoints = () => this.send<any>('GET', '/gamification/me/points');
  myLevel = () => this.send<any>('GET', '/gamification/me/level');
  myAccessPolicy = () => this.send<any>('GET', '/gamification/me/access-policy');
  levels = () => this.send<any[]>('GET', '/gamification/levels');
  leaderboard = (limit = 20) => this.send<any[]>('GET', `/gamification/leaderboard?limit=${limit}`);

  // ---------- geocoding ----------
  searchPlaces = (q: string, limit = 5) =>
    this.send<{ results: any[] }>('GET', `/geocoding/search?${new URLSearchParams({ q, limit: String(limit) })}`)
      .then(r => r.results);

  // ---------- moderation (user-facing) ----------
  report = (b: { targetType: 'PARKING_SPOT'|'USER'|'MEDIA'; targetId: string; reason: string; description?: string }) =>
    this.send<any>('POST', '/moderation/reports', { body: b });
  myReports = () => this.send<any[]>('GET', '/moderation/reports/me');
  appeal = (caseId: string, note?: string) => this.send<any>('POST', '/moderation/appeals', { body: { caseId, note } });

  // ---------- waitlist (public) ----------
  joinWaitlist = (email: string, city?: string, role?: 'driver'|'tester'|'partner') =>
    this.send<{ status: string }>('POST', '/waitlist', {
      body: { email, city, role, consentTimestamp: new Date().toISOString(), source: 'parkio.dev-landing' },
    });
}

// ---------------- usage ----------------
// const api = new ParkioClient();
// await api.login('you@example.com', 'YourPassword1!');
// const spots = await api.nearbySpots({ lat: 38.4192, lng: 27.1287, radius: 1500, limit: 10 }); // İzmir
// const { mediaId } = await api.uploadPhoto(file);
// const spot = await api.createSpot({
//   mediaId, latitude: 38.4192, longitude: 27.1287,
//   suitableVehicleTypes: ['ANY'], parkingContext: 'STREET_PARKING', legalStatus: 'LEGAL',
// });
// // spot.status === 'PENDING_VALIDATION' → poll api.mySpot(spot.id) until
// // ACTIVE / PENDING_REVIEW / REJECTED / REVIEW_FAILED
```

## 9. Pointing the EXISTING apps at the live API

- **Web** (`frontend/apps/web`): set `VITE_API_BASE_URL=https://api.parkio.dev/api/v1` (env or `.env`). Production builds fail fast if it's unset. Remember §2: the deployed CORS allow-list only admits `https://app.parkio.dev`, so for local dev either get the origin added or proxy `/api` through Vite:
  ```ts
  // vite.config.ts — dev-only workaround for CORS
  server: { proxy: { '/api': { target: 'https://api.parkio.dev', changeOrigin: true } } }
  // then VITE_API_BASE_URL=/api/v1
  ```
  (Note: the cookie-based refresh flow still expects the gateway's cookie domain; for pure prototyping the token-mode client in §8 is simpler.)
- **Mobile** (`frontend/apps/mobile`): `EXPO_PUBLIC_API_BASE_URL=https://api.parkio.dev/api/v1` — this is already the production value in `eas.json`, and the release-artifact check asserts exactly this URL. Native apps have no CORS constraint.

## 10. Practical integration notes

1. **No public demo account exists.** To get data flowing you must register a real account and complete email verification — which depends on the deployed email provider actually sending. If verification mail doesn't arrive in the hosted env, that's an operator/provider configuration issue (admins can resend or verify via the admin API).
2. **A fresh account starts at Level 1:** nearby search is capped to 300 m radius / 3 results / 20 views per day until points accrue (§ access policy). Don't mistake the cap for "no data."
3. **The İzmir default center** (`lat 38.4192, lng 27.1287`) is where beta spots will cluster; searching elsewhere will legitimately return empty.
4. **Spot creation is asynchronous:** `POST /parking/spots` returns `PENDING_VALIDATION`; the Gemini gate flips it to `ACTIVE` / `PENDING_REVIEW` / `REJECTED` seconds later. Poll `GET /parking/my-spots/{id}` and design for every outcome, including the terminal `REVIEW_FAILED` (moderation never reached a verdict within its deadline, retries were exhausted, or the approval arrived after `maxPublishableAge` — show a retry/failure message, never an indefinite "pending").
   **`expiresAt` is null while pending and is only a countdown once the spot is published.** Do not render a remaining-time value for `PENDING_VALIDATION` / `PENDING_REVIEW` or a null `expiresAt`. On a still-fresh approval the spot receives its full advertised window measured from the approval instant. Approvals past `max-publishable-age` (default 30m from creation) become `REVIEW_FAILED` rather than publishing a stale availability report.
   **Compatibility note:** `REVIEW_FAILED` is a new public enum value. Clients that deserialize `status` with a closed enum will need an update (or a tolerant fallback). First-party web/mobile clients and Zod contracts include it; external strict clients should treat unknown statuses as non-discoverable.
5. **Photos expire:** `accessUrl` from `media-access-url` is short-lived (~5 min). Re-request instead of caching; non-owners can fetch it only while the spot is publicly visible.
6. **Idempotency:** generate one `Idempotency-Key` per logical attempt of create/verify/claim/upload and REUSE it on retries of that same attempt (the §8 client does this on its internal 401-retry; do the same for your own network-failure retries).
7. **Show `traceId`** from error bodies (and `x-correlation-id`) in error UI — it's how the operator debugs reports.
8. **Respect the rate tiers** (§4): debounce geocoding typeahead ≥300 ms, throttle media upload retries, and back off on 429.
9. **Locale:** pass `locale: 'tr' | 'en'` on register/resend/forgot so emails arrive in the right language; set `preferredLocale` in preferences for notification language. There is no `Accept-Language` mechanism.
10. **Never send `legalStatus: 'ILLEGAL_OR_RISKY'`** on create — the API rejects it (422); that value only appears via community verification results.
