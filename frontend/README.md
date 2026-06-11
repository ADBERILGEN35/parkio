# Parkio Frontend

pnpm monorepo for the Parkio web client. Sibling to `services/`, `docs/`, `docker/`, and `infra/` — **never** place frontend code under `services/`.

## Structure

```
frontend/
  apps/web/          Vite + React application
  packages/
    api-client/      Axios client (gateway-only)
    types/           Shared TypeScript types
    validation/      Zod schemas
    ui/              Minimal shared components
    config/          Shared TS + ESLint config
  docs/
```

## Prerequisites

- Node.js ≥ 20
- pnpm 9 (`corepack enable` recommended)
- Parkio backend running locally (gateway on port **8080**)

## Install

```bash
cd frontend
pnpm install
```

Copy the web app env template:

```bash
cp apps/web/.env.example apps/web/.env
```

## Development

```bash
pnpm dev
```

Opens the web app at [http://localhost:5173](http://localhost:5173).

## Build & quality

```bash
pnpm build       # all packages + production web bundle
pnpm typecheck   # TypeScript across the workspace
pnpm lint        # ESLint across packages that define it
```

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `VITE_API_BASE_URL` | `http://localhost:8080/api/v1` | **Gateway-only** API base. Never point at service ports (8081–8089). |

Set in `apps/web/.env` (not committed).

## Gateway-only rule

All HTTP calls go through the **gateway** (`VITE_API_BASE_URL`). The gateway validates JWTs, enforces account status, applies rate limits, and stamps internal headers. Direct service URLs are for local debugging and OpenAPI browsing only — see `docs/architecture/openapi.md`.

## CORS

The browser dev origin must be allowed by the gateway. Local docker-apps defaults to:

```
PARKIO_CORS_ALLOWED_ORIGINS=http://localhost:5173
```

If CORS errors appear, confirm this value is set in `docker/.env` and restart the gateway.

## Authentication & refresh

All auth calls go through the gateway (`VITE_API_BASE_URL`, **required** — defaults to
`http://localhost:8080/api/v1`): `POST /auth/register`, `POST /auth/login`,
`POST /auth/refresh-token`, `POST /auth/logout`, `GET /auth/me`.

### Auth flow

- Login/register return `accessToken` + `refreshToken` + expiry timestamps + `user`
  (`id`, `email`, `roles`, `status`). On success the app persists tokens, stores
  user/roles/status in the auth store and redirects to `/map`.
- Tokens persist in `localStorage` via `LocalStorageTokenStorage` (the only token
  storage abstraction — tokens are never stored elsewhere).
- The API client attaches `Authorization: Bearer <accessToken>` and a fresh `X-Correlation-Id` per request.
- On app start (`AuthBootstrap`): if an access token exists, `GET /auth/me` restores the
  profile. A `401` triggers one silent refresh (via the interceptor); if that refresh
  fails, the session is cleared (hard logout). Transient failures (network, 5xx) keep
  the stored tokens for the next attempt.
- Logout calls `POST /auth/logout` with the stored refresh token (if present), then
  **always** clears local auth state — even if the backend call fails — and lands on `/login`.
- Roles/status come from the backend `user` object only; the JWT is never decoded client-side.

### Refresh behavior

- On a `401` from a protected request, the interceptor calls `POST /auth/refresh-token`
  once (single in-flight refresh shared across concurrent 401s), stores the rotated
  token pair and retries the original request once.
- 401s from `/auth/login`, `/auth/register`, `/auth/refresh-token` and `/auth/logout`
  never trigger a refresh (invalid credentials / invalid refresh token — retrying would loop).

### Hard logout conditions

The session is cleared (tokens removed, state reset) when:

- the silent refresh fails for any reason — the backend rotates refresh tokens and
  revokes the whole family on reuse, so any refresh failure is unrecoverable;
- `/auth/me` on app start returns `401` after the refresh attempt;
- the user signs out.

### Suspended accounts

- Any API response of `403 ACCOUNT_NOT_ACTIVE` flips the auth store to `suspended` and
  the app renders a dedicated **Account suspended** screen instead of the router.
- No automatic retries are made; the only available action is sign out (which clears
  the suspended state and returns to `/login`).

### Other error statuses

- `403 FORBIDDEN` — insufficient role (e.g. moderation/analytics without MODERATOR/ADMIN).
- `429` — gateway rate limit; surfaced as a friendly "too many attempts" message.
- `503 USER_STATUS_UNAVAILABLE` — transient; surfaced as "service unavailable, try again".

## Profile flow

`/profile` is a working page composed of independent sections, each backed by its own
TanStack Query key and gateway endpoint (all via `usersApi` in `@parkio/api-client`):

| Section | Endpoints | Notes |
|---------|-----------|-------|
| Account summary | — (auth store) | email/status/roles from the auth session; sign out |
| Profile | `GET /users/me`, `PATCH /users/me` | `displayName` (2–50), `phoneNumber` (≤32), `city` (≤100) |
| Preferences | `GET /users/me/preferences`, `PATCH /users/me/preferences` | `preferredRadiusMeters` (100–50000), `notificationsEnabled` |
| Vehicle | `GET /users/me/vehicle`, `PUT /users/me/vehicle` | `vehicleType` (MOTORCYCLE/SMALL_CAR/SEDAN/SUV/VAN/TRUCK), `plate` (≤16, private) |
| Stats | `GET /users/me/stats` | read-only: total points, level, trust score + band |

`GET /users/{userId}/public-profile` is also available as `usersApi.getPublicProfile`
(privacy-safe view, not used on `/profile` itself).

Behavior notes:

- **PATCH semantics (profile/preferences):** empty form fields are omitted from the
  request body — the backend treats absent/null as "leave unchanged". A value can
  therefore not be cleared from this UI once set.
- **PUT semantics (vehicle):** the vehicle is replaced wholesale; clearing both fields
  removes the vehicle. An empty state is shown when no vehicle is configured
  (`GET /users/me/vehicle` returns `{ vehicleType: null, plate: null }`).
- Successful mutations invalidate the section's query; errors render the backend
  `ApiError` message with its `code` and `traceId`.
- Auth concerns are not duplicated here: 401s go through the silent-refresh
  interceptor and `403 ACCOUNT_NOT_ACTIVE` flips the global suspended state.

## Parking browsing flow

Core flow: **nearby search → spot list → spot detail → signed photo URL.** All via the
gateway through `parkingApi` in `@parkio/api-client`:

| Page | Endpoints | Notes |
|------|-----------|-------|
| `/map` | `GET /parking/spots/nearby?lat=&lng=&radius=&limit=` | manual lat/lng inputs; radius ≤ 50 000 m (backend default 1000), limit ≤ 50 (default 10) |
| `/spots/:spotId` | `GET /parking/spots/{spotId}` + `GET /parking/spots/{spotId}/media-access-url` | photo URL fetched on demand after the spot loads |
| `/my-spots` | `GET /parking/my-spots` | owner view (`SpotResponse` incl. confidence/verification counts); items link to `/spots/:spotId` |

Behavior notes:

- **No map provider yet** — `/map` shows a placeholder "Map area" and the user must
  enter latitude/longitude manually. GPS / a real map library (Leaflet/Mapbox) will
  replace the manual inputs later; the search API contract stays the same.
- Spot detail `404` (expired/filled/removed spots) renders a friendly "spot not found"
  message instead of an error box.
- `503 MEDIA_ACCESS_UNAVAILABLE` on the photo URL keeps the spot details visible and
  shows a "photo temporarily unavailable" state with a retry button.
- The signed photo URL is never cached by TanStack Query (`staleTime: 0, gcTime: 0`);
  a **Refresh photo URL** button re-requests it after expiry (~5 minutes).
- Parking enums used in the UI (`ParkingStatus`, `SpotVehicleType`, `LegalStatus`,
  `ParkingContext`) mirror parking-service values exactly. Note the parking
  `SpotVehicleType` (incl. `HATCHBACK`, `ANY`) is a **different enum** from the
  user-service vehicle profile `VehicleType`.

## Upload & create-spot flow

`/upload` implements: **choose photo → upload media → create spot → redirect to `/spots/:spotId`.**

1. `POST /media/upload` (multipart, field `file`, fresh `Idempotency-Key`) → returns
   `UploadMediaResponse` (`mediaId`, `status`, `contentType`, `fileSize`).
2. `POST /parking/spots` (JSON body incl. the returned `mediaId`, a **separate** fresh
   `Idempotency-Key`) → returns the owner `SpotResponse`; the app redirects to its detail page.

Media upload constraints (mirrors media-service config):

- JPEG, PNG or WebP only (`image/jpeg`, `image/png`, `image/webp`)
- max **10MB** (validated client-side before upload; backend enforces it too)

Create-spot fields: `mediaId` (UUID, from step 1), `latitude` (−90..90), `longitude`
(−180..180), `addressText` (≤512, optional), `description` (≤1000, optional),
`manualLocationEdited`, `suitableVehicleTypes` (≥1 of the parking `SpotVehicleType` enum),
`parkingContext`, `legalStatus`, `violationReasons` (required by the form when legal
status is ILLEGAL_OR_RISKY).

Failure/retry behavior:

- If the upload succeeds but create-spot fails, the `mediaId` is kept in page state —
  resubmitting retries only the create step without re-uploading the photo.
- Choosing a different file clears the kept `mediaId` (a new upload will run).
- Each attempt uses fresh idempotency keys; an idempotent replay returns the normal
  response shape and is handled like any success.
- **Illegal/risky spots cannot be created**: the backend rejects
  `legalStatus: ILLEGAL_OR_RISKY` with `422 ILLEGAL_SPOT_REJECTED`. The form warns
  about this before submit.
- The signed photo viewing URL is not part of this flow — it is generated on demand on
  the spot detail page.

## Verify & claim flow

The spot detail page (`/spots/:spotId`) has an **Actions** card for non-owner users:

| Action | Endpoint | Request body |
|--------|----------|--------------|
| Verify | `POST /parking/spots/{spotId}/verify` | `{ "result": <VerificationResult> }` |
| Claim | `POST /parking/spots/{spotId}/claim` | none |

- **Verify**: the user reports what they observed at the spot. `VerificationResult`
  mirrors parking-service exactly: `AVAILABLE`, `FILLED`, `INVALID`,
  `ILLEGAL_OR_RISKY`, `WRONG_VEHICLE_SIZE`. The request carries **only** the result —
  the backend `VerifySpotRequest` has no note/description or vehicle-mismatch fields.
- **Claim**: the user takes the spot; the backend marks it `FILLED`. On success the UI
  shows "Spot claimed — it is now marked as filled."
- Both calls require a fresh **`Idempotency-Key`** (UUID via `createIdempotencyKey()`),
  generated **per submit/click** — a retry of the same network call replays
  idempotently on the backend, while a new user action gets a new key.
- On success the app invalidates the spot detail, nearby search and my-spots queries so
  status/verification changes appear everywhere.

Business rules are **backend-enforced**, not duplicated client-side:

- Owners cannot verify or claim their own spot (`403 OWNER_CANNOT_VERIFY` /
  `OWNER_CANNOT_CLAIM`). The public spot view doesn't expose the owner, so the UI can't
  pre-detect this — the backend error is surfaced instead.
- Duplicate verification → `409 ALREADY_VERIFIED` ("you already verified this spot").
- Non-actionable states → `409 SPOT_NOT_VERIFIABLE` / `SPOT_NOT_CLAIMABLE` /
  `SPOT_EXPIRED`, surfaced as "no longer available". `404 SPOT_NOT_FOUND` is shown as
  hidden/expired/not found.
- The only client-side gating: actions are disabled while a call is pending and when
  the spot is already in a terminal status (`FILLED`, `EXPIRED`, `REJECTED`).

## Media signed URL flow

Spot photos are **not** served from `/media/{id}/access-url` for regular users. Use the parking-mediated endpoint:

```
GET /parking/spots/{spotId}/media-access-url
```

Response: `{ spotId, mediaId, accessUrl, expiresAt }`.

**Signed URLs expire** (default ~5 minutes). Fetch on demand when rendering a photo; do not cache long. Re-request when `expiresAt` has passed.

Typical upload → spot flow:

1. `POST /media/upload` (multipart `file`, `Idempotency-Key`)
2. `POST /parking/spots` with returned `mediaId` (`Idempotency-Key`)
3. On detail view: `GET /parking/spots/{id}/media-access-url` → load `accessUrl` in `<img>`

## Idempotency

Send `Idempotency-Key` (UUID) on:

- `POST /parking/spots`
- `POST /parking/spots/{id}/verify`
- `POST /parking/spots/{id}/claim`
- `POST /media/upload`

Use `createIdempotencyKey()` from `@parkio/api-client`.

## Notifications flow

`/notifications` lists the current user's in-app notifications via `notificationsApi`:

| Action | Endpoint | Notes |
|--------|----------|-------|
| List | `GET /notifications/me` | returns the most recent **50** notifications — the backend has **no pagination params** |
| Mark read | `PATCH /notifications/{notificationId}/read` | idempotent; returns the updated notification |

- Each item shows title, body, type, `createdAt` and a read/unread badge. A
  notification is unread until its status is `READ` (`readAt` set).
- Enums mirror notification-service exactly: type `NEARBY_PARKING`, `LEVEL_UP`,
  `POINT_EARNED`, `WARNING`, `SYSTEM`; channel `PUSH`, `EMAIL`, `IN_APP`; status
  `PENDING`, `SENT`, `FAILED`, `READ`.
- Mark-as-read invalidates only the `['notifications']` query; the list and the nav
  badge refresh from the same cache entry.
- **Unread badge in the nav**: there is no unread-count endpoint, so the badge derives
  the count from the cached `['notifications']` list (`select`), sharing one fetch with
  the page. No polling/websocket yet — the count refreshes on navigation/reload and
  after mark-as-read.
- Device-token and notification-preferences endpoints exist on the backend but are not
  used by the web app yet (push delivery is a backend placeholder).

## Gamification flow

`/gamification` ("Progress" in the nav) is composed of independent read-only cards via
`gamificationApi`, one query key per endpoint:

| Card | Endpoint | Query key |
|------|----------|-----------|
| Progress | `GET /gamification/me/progress` | `['progress']` |
| Level standing | `GET /gamification/me/level` | `['level']` |
| Access policy | `GET /gamification/me/access-policy` | `['access-policy']` |
| Points history | `GET /gamification/me/points` | `['points']` |
| All levels | `GET /gamification/levels` | `['levels']` |

- Level standing shows points needed for the next level; `nextLevelMinPoints` /
  `pointsToNextLevel` are `null` at the top level ("highest level" state).
- Points history lists the most recent 50 ledger entries (`sourceType`, `direction`
  `EARNED`/`DEDUCTED`, points, optional related spot link, timestamp).
- The profile page's Stats card reuses the existing `GET /users/me/stats` projection
  (`['me','stats']`) for points/level/trust and links to `/gamification` — no duplicate
  gamification calls on `/profile`.

## Leaderboard flow

`/leaderboard` calls `GET /gamification/leaderboard` (query key `['leaderboard']`,
backend default limit 20, max 100). Each row is `{ rank, userId, totalPoints,
currentLevel }` — the response exposes **user ids only** (no display names), so the UI
shows a truncated id. All reads are cached by TanStack Query; nothing invalidates them
besides normal refetch-on-mount.

## Role-aware routing

| Route | Guard |
|-------|-------|
| `/login`, `/register` | Public |
| `/map`, `/spots/:id`, `/my-spots`, `/upload`, `/profile`, `/notifications`, `/gamification`, `/leaderboard` | Authenticated |
| `/moderation`, `/analytics` | Authenticated + `MODERATOR` or `ADMIN` |

The gateway also enforces privileged routes at the edge; the app's `RoleRoute` mirrors this for UX.

## Error shape

All services return a uniform `ApiError`:

```json
{
  "code": "SPOT_NOT_FOUND",
  "message": "Human-readable summary",
  "traceId": "correlation-id",
  "timestamp": "2026-06-09T10:00:00Z",
  "fieldErrors": [{ "field": "email", "message": "must not be blank" }]
}
```

Surface `traceId` in error UI for support.

## Not implemented yet

- Map provider (Leaflet/Mapbox/etc.)
- Mobile app
- Real push notifications (backend uses a placeholder; in-app only)
- Heavy UI / design system
