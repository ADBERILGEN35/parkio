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
pnpm test        # Vitest across packages that define it
```

## Design system

The visual foundation implements the **Parkio V2** tokens documented in
[`DESIGN_SYSTEM.md`](./DESIGN_SYSTEM.md) (extracted from the Stitch mockups).

- **V2 tokens are canonical** (Electric Blue primary `#0050cb`, emerald
  secondary/success, amber tertiary/warning, M3-style surface scale). The
  Stitch "Parkio v1" auth-screen tokens (indigo secondary, h1–h4 type scale)
  are **deprecated and no longer used** — the auth screens now render with V2
  primitives; do not reintroduce v1 values.
- **Tailwind CSS** is set up in `apps/web` (`tailwind.config.js` mirrors
  DESIGN_SYSTEM.md §3). Colors resolve through CSS variables defined in
  `apps/web/src/styles/index.css` (RGB triplets, so alpha modifiers like
  `bg-secondary/10` work). Variable naming is dark-mode-ready (`darkMode:
  'class'`), but no dark screens exist yet.
- **UI primitives live in `packages/ui`** (`@parkio/ui`): `Button`, `Input`,
  `Card`, `Surface`, `PageShell`, `SectionHeader`, `Icon`, `IconButton`,
  `Badge`, `SoftBadge`, `StatusBadge`, `MetricCard`, `EmptyState`,
  `LoadingState`, `ErrorMessage`, plus the `cn` class helper. They emit
  Tailwind classes; the app's Tailwind `content` globs include
  `packages/ui/src` so the classes are generated.
- **Status visuals are centralized** in `packages/ui/src/status.ts`:
  `getSpotStatusVisual()` maps `ACTIVE`/`VERIFIED`/`SUSPICIOUS`/`FILLED`/
  `EXPIRED`/`REJECTED` to label/icon/classes (soft-badge recipe), and
  `trustFreshnessFromMinutes()` / `getTrustFreshnessVisual()` bucket
  verification age (fresh 0–10 min, recent 10–30, aging 30–60, stale 1 h+).
  These return labels/classes only — no backend coupling.
- **Icons** are Material Symbols Outlined (variable font, loaded in
  `index.html` together with Inter). Use the `Icon` component (decorative by
  default).
- The legacy `colors`/`spacing`/`radius` exports from `@parkio/ui` are
  **deprecated** and no longer referenced by any page; they remain exported only
  for backwards compatibility. All app pages use Tailwind classes — new code
  should too.

### Design-system coverage

Every authenticated and public route now uses the V2 design system end to end —
there is no remaining "legacy inline-styled" page:

- **Auth:** `/login`, `/register` (branded centered card, `Surface` + `Input` +
  `Button` + `ErrorMessage`, `traceId` preserved) and the **Account suspended**
  screen.
- **Core:** Map, Spot detail, Upload/Create, Profile/Impact Hub.
- **Engagement:** Notifications, Gamification, Leaderboard.
- **Lists:** My spots (`StatusBadge` + `SoftBadge` + `EmptyState`), My reports
  (cards + appeal form + `EmptyState`).
- **Staff:** Moderation, Analytics.
- **Shell:** `AppNav` collapses to a hamburger/overflow menu below `md`
  (keyboard-accessible toggle, role-gated links and the unread badge preserved).

### Reduced motion (accessibility)

The UI honors the OS **"reduce motion"** preference:

- A global fallback in `apps/web/src/styles/index.css`
  (`@media (prefers-reduced-motion: reduce)`) drops transition/animation
  durations to near-zero, stops looping effects (`pulse-glow`, `float`,
  slide/fade-in), and disables the `hover-lift` translate. The loading spinner
  is intentionally preserved (at a calmer speed) so it still reads as busy.
- Button/IconButton press-scale uses Tailwind `motion-safe:` so it only animates
  for users who haven't opted out.
- Users **without** the preference keep the normal motion design unchanged.

## Tests

Stack: **Vitest** + **React Testing Library** + `@testing-library/user-event` +
`@testing-library/jest-dom` + **MSW** for unit/component tests (gateway responses are
mocked at the network layer — tests never hit a real backend), plus a single
**Playwright** browser smoke test (see [E2E smoke test](#e2e-smoke-test)).

### Installing test dependencies (Windows pnpm only)

The `node_modules` tree is installed by **Windows** pnpm; never run
`pnpm install`/`pnpm add` from WSL (store mismatch — `ERR_PNPM_UNEXPECTED_STORE`).
The test dependencies are already declared in the relevant `package.json` files,
so from a **Windows** shell in `frontend/` simply run:

```powershell
pnpm install
```

### Running

```bash
pnpm test                                  # whole workspace (root)
pnpm --filter @parkio/api-client test      # one package
pnpm --filter @parkio/web test:watch       # watch mode
```

### E2E smoke test

A single **Playwright** smoke test (`apps/web/e2e/smoke.spec.ts`) exercises the
core happy path end to end in a real browser:

> `/login` → mocked login → `/map` → mocked nearby search (one spot) →
> `/upload` → mocked media upload + create spot → redirect to `/spots/:id` →
> spot detail renders from its mocked endpoints.

- **The backend is fully mocked** with Playwright `page.route` — no real services
  are contacted. `VITE_API_BASE_URL` is pointed at the dev-server origin so calls
  stay same-origin; map tiles and web fonts are aborted to keep the run offline
  and deterministic.
- It runs against the Vite **dev server** (started automatically by Playwright's
  `webServer`), Chromium only.
- It is **not** part of `pnpm test` (so unit/CI runs need no browser binaries).
  Vitest is scoped to `src/**`, so it never picks up the `.spec.ts` in `e2e/`.

Run it explicitly (one-time browser install on a fresh machine):

```bash
pnpm exec playwright install chromium      # once, in apps/web (or via --filter)
pnpm e2e                                    # headless smoke run (root)
pnpm e2e:ui                                 # interactive UI mode (debugging)
```

Full backend-integrated E2E (real gateway + services, more flows) remains future
work — this is a deliberately small, deterministic safety net for the main flow.

### What is covered

| Area | Files | Focus |
|------|-------|-------|
| `packages/api-client` | `src/*.test.ts` | Authorization/`X-Correlation-Id` interceptors, 401 → single shared refresh → retry → hard logout, refresh-exempt auth paths, `toParkioError` mapping (401/403/`ACCOUNT_NOT_ACTIVE`/429/503/unknown), `Idempotency-Key` on create/verify/claim/upload |
| `packages/validation` | `src/*.test.ts` | login/register, nearby-search boundaries (lat ±90, lng ±180, radius ≤ 50 000, limit 1–50), media type/size limits, create-spot vehicle/violation rules, profile/preferences/vehicle constraints |
| `apps/web` | `src/**/*.test.tsx` | Login success (session + redirect) and 401 (friendly message + traceId), `ProtectedRoute`/`RoleRoute` guards, notifications list/empty/mark-as-read refetch, spot detail 404 / 409 `ALREADY_VERIFIED` / claim success, upload validation/media-reuse/create, profile stats + section-tab switching + vehicle empty/current + profile/preferences save + logout, gamification "Your Impact" header + level hero + recent activity/benefits + activity empty state + roadmap current-level highlight, leaderboard podium + public-profile enrichment/fallback + your-standing highlight + not-in-top-N + show-more + empty, moderation case queue/detail-assign/appeal-resolve controls, analytics overview KPIs + daily empty state + own-id-only 403 message, register extended-form validation (display name / password match / terms) + sends only email+password + captured name/phone PATCHed after provisioning + profile-save failure is non-fatal, register success → preparing → /map and register → preparing (not suspended), post-register provisioning grace (retry on `ACCOUNT_NOT_ACTIVE`, timeout → retry/sign-out) + store guard (suspended only outside the grace window), my-spots empty/list, reports list + appeal form, AppNav mobile menu toggle + role-gated links |
| `apps/web` (E2E) | `e2e/smoke.spec.ts` | Playwright browser smoke: login → map nearby search → upload & create spot → redirect to spot detail (backend mocked via `page.route`, run with `pnpm e2e`) |

Notes:

- Test files (`src/**/*.test.*`, `apps/web/src/test/`) are **excluded from `tsc`**
  (`typecheck`/`build`) so those commands stay green even before the test
  dependencies are installed; Vitest transpiles tests itself. Editors still
  type-check them once dependencies are present.
- Tests are behavior-focused: MSW fakes the gateway contract; backend business
  rules are not re-implemented in tests.

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
`POST /auth/verify-email`, `POST /auth/resend-verification`,
`POST /auth/refresh-token`, `POST /auth/logout`, `GET /auth/me`.

### Auth flow

- Login for verified accounts returns `accessToken` + expiry timestamps + `user`
  (`id`, `email`, `roles`, `status`). The refresh token is set by auth-service as
  an HttpOnly `Secure` `SameSite=Strict` cookie and is not exposed to JavaScript.
- Registration creates a `PENDING_VERIFICATION` account and returns the same
  response envelope with `null` token fields. It does not authenticate the SPA or
  set a refresh cookie. The user lands on `/check-email` and signs in only after
  email verification succeeds.
- The SPA keeps the access token in memory only via `MemoryOnlyTokenStorage`.
  No access or refresh token is persisted in `localStorage`; `clearSession()` also
  removes legacy `parkio.accessToken` / `parkio.refreshToken` keys from older builds.
- The API client attaches `Authorization: Bearer <accessToken>` and a fresh `X-Correlation-Id` per request.
- Cookie-backed auth calls use browser credentials (`withCredentials: true`). CORS
  must allow credentials only for the trusted frontend origin; wildcard origins are
  invalid for this flow.
- On app start (`AuthBootstrap`): the app calls `POST /auth/refresh-token` with the
  HttpOnly cookie. Success restores access-token memory state and the user profile;
  failure clears local auth state.
- Logout calls `POST /auth/logout` with the HttpOnly refresh cookie, then **always**
  clears local auth state — even if the backend call fails — and lands on `/login`.
- Roles/status come from the backend `user` object only; the JWT is never decoded client-side.
- Registration shows live password guidance that mirrors auth-service policy:
  12+ characters, lowercase, uppercase, digit and not an obvious common password.
  Login failures and account lockouts use the same generic invalid-credentials
  copy; the frontend does not expose lock state details. Login for an unverified
  account shows the friendly `Please verify your email before signing in.` copy
  and still does not store any token.

### Email verification UX

- `/check-email` is shown after registration and supports a resend action through
  `POST /auth/resend-verification`. The response is intentionally generic so the
  UI cannot reveal whether an email exists, is verified, or is rate-limited.
- `/verify-email?token=...` submits the token to `POST /auth/verify-email` and
  displays success or failure. A successful verification does not create a
  browser session; the user signs in normally so auth-service can issue the
  access token and HttpOnly refresh cookie.
- Local development uses auth-service's guarded logging sender
  (`parkio.security.email-verification.log-token=true`) to print verification
  links. Hosted beta and production must use a real email provider or keep
  registration closed to tester-controlled accounts.

### Refresh behavior

- On a `401` from a protected request, the interceptor calls `POST /auth/refresh-token`
  once (single in-flight refresh shared across concurrent 401s), stores the new
  access token in memory and retries the original request once. The rotated refresh
  token is delivered only as a replacement HttpOnly cookie.
- 401s from `/auth/login`, `/auth/register`, `/auth/refresh-token` and `/auth/logout`
  never trigger a refresh (invalid credentials / invalid refresh token — retrying would loop).

### Hard logout conditions

The session is cleared (tokens removed, state reset) when:

- the silent refresh fails for any reason — the backend rotates refresh tokens and
  revokes the whole family on reuse, so any refresh failure is unrecoverable;
- cookie-backed bootstrap refresh fails;
- the user signs out.

### Local dev cookie behavior

Production uses `Secure` refresh cookies. The auth-service `dev` profile sets
`PARKIO_REFRESH_COOKIE_SECURE=false` by default so the local Vite app can exercise
the cookie flow over `http://localhost:5173`. Hosted beta and production must keep
`PARKIO_REFRESH_COOKIE_SECURE=true`.

### Suspended accounts

- Any API response of `403 ACCOUNT_NOT_ACTIVE` flips the auth store to `suspended` and
  the app renders a dedicated **Account suspended** screen instead of the router.
- No automatic retries are made; the only available action is sign out (which clears
  the suspended state and returns to `/login`).

### Post-verification provisioning grace

The backend emits the user-service provisioning event when `POST /auth/register`
creates the pending auth user, but the SPA has no session until the user verifies
email and signs in. On the first successful login after a registration started in
this browser, the app may still need to wait for user-service profile/status
projection. For a few seconds a protected call can return `403 ACCOUNT_NOT_ACTIVE`
even though the account is not actually suspended.

To avoid flashing the **Account suspended** screen at newly verified users, the
first verified login after local registration hands off to a short-lived
**"Preparing your account"** screen (`/preparing`) instead of going straight to
`/map`:

- The auth store enters a scoped `provisioning` grace window (`beginProvisioning()`), during
  which `markSuspended()` is a **no-op** — so `403 ACCOUNT_NOT_ACTIVE` does not flip the
  global suspended state.
- `AccountPreparingPage` polls `/auth/me` once per second for ~12 s. On success it persists
  any registration-captured profile fields (see below) and forwards to `/map`, ending the
  grace window; on timeout it offers **Try again** and **Sign out**.
- The grace is **strictly scoped** to this flow: it is cleared on success, on sign-out, and
  on any `setSession`/`clearSession`. `ACCOUNT_NOT_ACTIVE` is **not** globally ignored — a
  genuinely suspended account hitting login/session still flips `suspended` and sees the
  **Account suspended** screen as before. `ACCOUNT_NOT_VERIFIED` is handled separately as
  a sign-in guidance message, not as suspension.

### Extended registration profile capture (beta)

`POST /auth/register` accepts **only** `email` + `password`, so that is all `RegisterPage`
sends. To improve onboarding, the register form additionally collects **Full name**,
**Phone number (optional)**, **Confirm password** and a **Terms** checkbox
(`registerProfileSchema`: displayName 2–50, phone ≤32, passwords must match, terms required).

- The extra fields are **never** sent to `/auth/register`. After registration succeeds they
  are stashed in `sessionStorage` (`auth/pendingProfile.ts`) through the
  `/check-email` and post-verification login handoff.
- Once provisioning completes, `AccountPreparingPage` shows **"Saving your profile details…"**
  and persists them via `PATCH /users/me` (`displayName`, `phoneNumber`).
- A failed profile save is **non-fatal**: the pending data is cleared and a soft
  *"Your account is ready, but we couldn't save some profile details…"* notice is shown with a
  **Continue to Parkio** button — the account still works and details can be edited later from
  Profile.
- Pending profile data is cleared on success, on the failure path, and on any
  `clearSession()` (sign-out / session reset).
- **Phone is captured only — it is NOT SMS-verified.** The helper copy ("We'll use this later
  for account recovery and verification") does not imply verification. SMS verification is
  future backend/provider work. The **Continue with Google** button remains visual-only.

### Other error statuses

- `403 FORBIDDEN` — insufficient role (e.g. moderation/analytics without MODERATOR/ADMIN).
- `429` — gateway rate limit; surfaced as a friendly "too many attempts" message.
- `503 USER_STATUS_UNAVAILABLE` — transient; surfaced as "service unavailable, try again".

## Profile — Settings & Preferences

`/profile` uses the V2 design system (see `DESIGN_SYSTEM.md`) as a **Stitch-style
Settings & Preferences** experience. A persistent **impact summary** (initials avatar,
display name / email, status, roles + the four trust/level/points metrics) sits above a
**section selector** that toggles between four areas. The selector is a sticky vertical
rail on desktop (`col-span-3`) and a horizontal scrollable tab strip on mobile — it is
**frontend-only** (`useState`, `role="tablist"`/`tab`/`tabpanel`); there are **no route
changes**. Each section is backed by its own TanStack Query key and gateway endpoint (all
via `usersApi` in `@parkio/api-client`); data and mutations are unchanged — only the
presentation and section layout are new.

| Section (tab) | Endpoints | Notes |
|---------------|-----------|-------|
| Impact summary (always visible) | `GET /users/me`, `GET /users/me/stats`, auth store | initials avatar, display name (falls back to email prefix), city, status, roles + four stat metrics |
| Profile & Account | `GET /users/me`, `PATCH /users/me`, auth store | editable `displayName` (2–50), `phoneNumber` (≤32), `city` (≤100); read-only email/status/roles; `authUserId` technical detail; sign out |
| Vehicle | `GET /users/me/vehicle`, `PUT /users/me/vehicle` | `vehicleType` (MOTORCYCLE/SMALL_CAR/SEDAN/SUV/VAN/TRUCK) selection cards + `plate` (≤16, private); "None" + empty plate clears the vehicle (PUT replaces wholesale) |
| Notifications | `GET /users/me/preferences`, `PATCH /users/me/preferences` | `preferredRadiusMeters` (100–50000) via slider + number, `notificationsEnabled` toggle |
| Trust & Progress | `GET /users/me/stats` | read-only points/level/trust score/trust band + link to `/gamification` |

`GET /users/{userId}/public-profile` is also available as `usersApi.getPublicProfile`
(privacy-safe view, used by the leaderboard, not on `/profile` itself).

**Backend limitations (intentionally not shown — nothing invented):** no profile photo /
avatar image upload, no multiple vehicles, no privacy/security settings, no email editing or
first/last-name split, and no streaks / achievements / activity heatmaps (an honest note is
shown in Trust & Progress instead). Notifications is a single boolean — no push/email split is
faked.

### Available stats (read-only)

From `GET /users/me/stats` only, rendered as `MetricCard`s with `SoftBadge`/`StatusBadge`
accents: **total points**, **current level**, **trust score**, and **trust band** (the band
string is shown verbatim with a heuristic colour — no invented thresholds). While the query
is pending a `LoadingState` shows; on error a `FriendlyApiErrorMessage` shows.

### Unavailable gamification / community data

The hub shows **no streaks, achievements, contribution heatmap, or "helped drivers"**
counts — the backend exposes none of these, so they are intentionally omitted (no invented
trust scores or activity). Deeper points/level history lives on `/gamification`, linked from
the page; `/profile` does not duplicate those calls.

### Behavior notes

- **PATCH semantics (profile/preferences):** empty form fields are omitted from the
  request body — the backend treats absent/null as "leave unchanged". A value can
  therefore not be cleared from this UI once set (helper text states this).
- **PUT semantics (vehicle):** the vehicle is replaced wholesale; selecting the **None**
  type and clearing the plate removes the vehicle. An empty state is shown when no vehicle
  is configured (`GET /users/me/vehicle` returns `{ vehicleType: null, plate: null }`).
- Successful mutations invalidate the section's query and show a "Saved." confirmation;
  errors render the backend `ApiError` message with its `code` and `traceId`.
- Auth concerns are not duplicated here: 401s go through the silent-refresh interceptor and
  `403 ACCOUNT_NOT_ACTIVE` flips the global suspended state. Sign-out reads identity from the
  auth session, so it never depends on a network call succeeding.

## Parking browsing flow

Core flow: **nearby search → spot list → spot detail → signed photo URL.** All via the
gateway through `parkingApi` in `@parkio/api-client`:

| Page | Endpoints | Notes |
|------|-----------|-------|
| `/map` | `GET /parking/spots/nearby?lat=&lng=&radius=&limit=` | interactive map + manual lat/lng inputs; radius ≤ 50 000 m (backend default 1000), limit ≤ 50 (default 10) |
| `/spots/:spotId` | `GET /parking/spots/{spotId}` + `GET /parking/spots/{spotId}/media-access-url` | photo URL fetched on demand after the spot loads; read-only map centered on the spot |
| `/my-spots` | `GET /parking/my-spots` | owner view (`SpotResponse` incl. confidence/verification counts); items link to `/spots/:spotId` |

Behavior notes:

- `/map` shows an **interactive map** for choosing the search center (browser
  geolocation, a map click, or the manual lat/lng inputs) and renders nearby spots as
  markers — see [Map integration](#map-integration). The manual coordinate inputs are
  always available; the search API contract is unchanged.
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

## Map integration

The browsing flow uses an interactive map built on **Leaflet** + **React Leaflet**.

### Map Experience Beta (`/map` layout)

`/map` implements the Stitch **Production Beta** map design (data-first, solid
fills + 1px borders — no glassmorphism):

- **Desktop:** map-dominant layout — a floating glass search overlay
  (address/place search, *Use my location*, and an "Advanced coordinates"
  disclosure with manual lat/lng + radius/limit) and a results panel
  (result count, spot cards) over the map, which fills the viewport.
- **Mobile:** the map renders first (≈45vh), with the search/results panel
  stacked below it; the manual coordinate fallback is always reachable.
- **Result cards** show public fields only: status badge, trust freshness,
  remaining validity/expiry, description snippet, vehicle-type/context chips
  and a color-coded legal-status badge, linking to `/spots/:spotId`.
  `PublicSpotResponse` exposes **no price, confidenceScore or
  verificationCount** — those are owner-only (`SpotResponse`) or nonexistent,
  so the cards/markers deliberately render none of them (no invented data).
- **Trust freshness limitation:** freshness (fresh 0–10 min / recent 10–30 /
  aging 30–60 / stale 1 h+) is derived from the record's **`updatedAt`** — the
  backend does not expose a `lastVerifiedAt` yet, and the UI says so next to
  the result count. Aging/stale markers are dimmed on the map.
- **Markers** are status-colored dots via the central `getSpotStatusVisual()`
  mapping; popups show status, expiry, vehicle types and a detail link.

- **Tile provider** — defaults to **OpenStreetMap**, which is fine for local/dev. The
  provider is configurable via env so a production provider can be swapped in later
  without code changes (see below). No paid provider or API key is hardcoded.
- **Reusable components** live in `apps/web/src/components/map/`:
  - `NearbySpotsMap` — `/map`: search-center marker, click-to-set-center, spot markers
    with popups (status, expiry, vehicle types, link to the spot).
  - `MapPicker` — `/upload`: click the map to drop a pin and set latitude/longitude.
  - `SpotMap` — `/spots/:spotId`: read-only map with a single marker.
  - `mapConfig.ts` (tile URL/attribution, default center/zoom) and `leafletSetup.ts`
    (CSS import + bundler marker-icon fix) are shared by all three.
- **Geolocation** — on mount `/map` attempts browser geolocation **once**. If the
  user allows it, the map centers on their location and runs a nearby search; if it
  is denied/unavailable/times out, the map falls back to the **İzmir** beta center
  (`38.4237, 27.1428`, zoom 12) with a friendly inline message and **does not**
  auto-search. A *Use my location* button repeats the lookup on demand (it only
  fills the center; the user presses Search). It is **never required**.
- **Location search (typeahead geocoding)** — the primary search box on **both
  `/map` and `/upload`** accepts an **address, street, neighborhood, or place name**
  (e.g. `155 Sokak`, `Bostanlı`, `Konak Pier`, `İzmir Katip Çelebi Üniversitesi`). As
  the user types it shows a Google-Maps-style **autocomplete dropdown** of up to 5
  candidates. The search control is a single shared component —
  `apps/web/src/components/map/PlaceSearch.tsx` — wired to a callback so each page
  reacts differently:
  - **`/map`:** selecting a suggestion forward-geocodes to coordinates and runs the
    existing `GET /parking/spots/nearby` call with the result — **no backend or
    parking API change**. The resolved center is surfaced as *"Searching near Konak,
    İzmir"* (or *"Searching near selected map point"* for coordinate/map-click
    centers).
  - **`/upload` (Step 2 — Location):** selecting a suggestion **fills the
    latitude/longitude fields, centers the map picker, sets `manualLocationEdited =
    true`, and fills the optional address only when it is empty** (the user's own
    text is never overwritten). It surfaces *"Selected location: Konak, İzmir"* and
    **does not create the spot** — publishing still happens from the Review step.
    There is **no reverse geocoding**: clicking the map updates coordinates only and
    shows *"Selected map point"*.
  - **Typeahead behavior** (`apps/web/src/lib/usePlaceAutocomplete.ts`): suggestions
    fire only at **≥ 3 characters** and are **debounced ~350 ms** so Nominatim is not
    hit on every keystroke. Each row shows a primary label (short name) and a
    secondary label (district/city, or the full display name). The dropdown is
    keyboard accessible — **↑/↓** move the highlight, **Enter** selects the
    highlighted item, **Esc** closes it, and mouse click selects. Dropdown states:
    *Searching…* (loading), *No places found* (empty), *Could not load suggestions*
    (error).
  - **Stale-response safety:** a monotonic request-id guard ignores out-of-order
    responses, so a slow earlier query can never overwrite a newer one.
  - **Submit fallback:** pressing **Enter with nothing highlighted** (or the Search
    button) runs an immediate geocode of the typed text; a single unambiguous match
    is auto-selected.
  - **Provider:** OpenStreetMap **Nominatim**, called directly from the browser via
    `apps/web/src/lib/geocoding.ts`. Requests are biased to Turkey
    (`countrycodes=tr`, `accept-language=tr`, `limit=5`). No API key is stored and no
    paid provider is hardcoded.
  - **Local-beta only:** Nominatim's public endpoint has a strict usage policy and
    no SLA — the debounce + min-length gating keeps usage polite, but typeahead still
    multiplies request volume. **For production, move geocoding behind the backend or
    a provider with an SLA/key** and point `VITE_GEOCODING_BASE_URL` at it.
  - **Errors are isolated:** a geocoding network/HTTP failure shows a friendly
    suggestion error and never breaks the parking search, which keeps its own
    `FriendlyApiErrorMessage` handling.
- **Manual coordinate fallback** — kept on both `/map` and `/upload`, behind an
  **"Advanced coordinates"** disclosure so address search is the primary UI. On
  `/map` the raw **latitude/longitude (+ radius/limit)** inputs sit there;
  click-to-set-center and *Use my location* still update the same center. On
  `/upload`, the raw **latitude/longitude** inputs (and the *"I adjusted the location
  manually"* checkbox) sit there too; editing a coordinate field *or* clicking the
  map sets `manualLocationEdited = true`. The upload picker defaults to the İzmir beta
  center (`38.4237, 27.1428`) so the map never opens on empty ocean; `/upload` does
  **not** auto-prompt for browser geolocation.
- **Bundle** — Leaflet is loaded in its own lazy chunk (the `/map` route is eager, so
  the map component is `React.lazy`-loaded) and is **not** part of the initial entry
  bundle. Leaflet's CSS is imported by the map components (browser build only — no SSR
  assumptions).

### Map environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `VITE_MAP_TILE_URL` | OpenStreetMap tile URL | Tile template URL (`{s}/{z}/{x}/{y}`). |
| `VITE_MAP_TILE_ATTRIBUTION` | OpenStreetMap attribution | Attribution HTML shown on the map. |
| `VITE_GEOCODING_BASE_URL` | `https://nominatim.openstreetmap.org` | Forward-geocoding base URL for `/map` location search. Defaults to public Nominatim (local-beta only); point at the backend or an SLA provider for production. |

Both are optional and default to OSM. **Future production consideration:** point these
at a provider with an appropriate usage policy/key (e.g. MapTiler, Stadia, Mapbox) —
OSM's public tile servers are not intended for production traffic. Inject the values at
build/deploy time; do not commit production keys.

### Spot Detail Beta (`/spots/:spotId` layout)

`/spots/:spotId` implements the Stitch **Production Beta** spot-detail design
(trust-first, image-forward, solid fills + 1px borders — no glassmorphism):

- **Layout:** a trust/status summary header above the fold (status badge,
  freshness, remaining validity, legal-status badge, address), then a
  two-column desktop layout — main column with the photo hero, Overview,
  Parking attributes (+ vehicle suitability), Validity window and Location
  map sections; a sticky 360px right sidebar groups the actions. On mobile
  everything stacks (photo → summary sections → actions); verify/claim/report
  are never hidden.
- **Action grouping:** the sidebar has **Community actions** ("Verify
  availability" form + "Claim as filled" button) and **Report issue** (reason
  + optional description). Behavior is unchanged: per-click `Idempotency-Key`,
  the same query invalidations, friendly 404/409 messages, and actions
  disabled for terminal statuses (`FILLED`/`EXPIRED`/`REJECTED`). Owner
  restrictions stay backend-enforced.
- **Signed photo URL:** unchanged flow via
  `GET /parking/spots/{id}/media-access-url` only (never media-service
  directly, no constructed storage URLs). The section shows a loading state,
  the signed-URL expiry caption, a **Refresh photo URL** button, and — if
  media access fails (e.g. 503 `MEDIA_ACCESS_UNAVAILABLE`) — a
  "photo unavailable" state with retry **without hiding the spot details**.
- **Trust/freshness limitation:** same as `/map` — freshness derives from
  `updatedAt` (no `lastVerifiedAt` from the backend), and the validity section
  says so. `confidenceScore`/`verificationCount`/`filledReportCount` exist
  only on the owner view (`SpotResponse`), so the public detail page renders
  none of them — no invented trust data, no verification timeline, no price.

## Upload & Create Spot Beta

`/upload` implements: **choose photo → upload media → create spot → confirm → redirect to
`/spots/:spotId`.** The page uses the V2 design system (see `DESIGN_SYSTEM.md`); the flow,
endpoints, validation, media reuse and idempotency are unchanged from earlier — only the
presentation is the "Beta" treatment.

**Layout** — two columns on desktop, single column on mobile (no hidden actions):

- Left: the upload flow (photo hero → location → spot details → error/retry → submit), or
  the success confirmation once the spot is created.
- Right: a **live progress** panel (the stepper below) plus a backend-independent
  **contribution & trust** panel (why verification matters, community trust, freshness,
  contribution impact — no invented user scores).

**Upload hero** — drag & drop or click to select; once chosen it shows an image preview,
file name and file size, plus inline validation. Replacing the file clears the kept
`mediaId`.

**Progress experience** — a five-state stepper using `LoadingState` / `StatusBadge` /
`SoftBadge`, with no fake percentages:
`Select photo → Uploading photo → Photo uploaded → Creating spot → Spot created`.

### Calls

1. `POST /media/upload` (multipart, field `file`, fresh `Idempotency-Key`) → returns
   `UploadMediaResponse` (`mediaId`, `status`, `contentType`, `fileSize`).
2. `POST /parking/spots` (JSON body incl. the returned `mediaId`, a **separate** fresh
   `Idempotency-Key`) → returns the owner `SpotResponse`.

### Validation constraints

Media upload (mirrors media-service config), checked client-side before upload and enforced
by the backend:

- JPEG, PNG or WebP only (`image/jpeg`, `image/png`, `image/webp`)
- max **10MB**

Create-spot fields: `mediaId` (UUID, from step 1), `latitude` (−90..90), `longitude`
(−180..180), `addressText` (≤512, optional), `description` (≤1000, optional),
`manualLocationEdited`, `suitableVehicleTypes` (≥1 of the parking `SpotVehicleType` enum),
`parkingContext`, `legalStatus`, `violationReasons` (required by the form when legal
status is ILLEGAL_OR_RISKY). Vehicle types render as multi-select selection cards, legal
status as single-choice cards, violation reasons as selectable chips — the underlying
validation rules are unchanged.

### Media reuse behavior

- If the upload succeeds but create-spot fails, the `mediaId` is kept in page state —
  resubmitting retries only the create step without re-uploading the photo (the error
  panel says so).
- Choosing a different file clears the kept `mediaId` (a new upload will run).

### Idempotency strategy

- Each step uses its **own** fresh `Idempotency-Key` (UUID via `createIdempotencyKey()`):
  one for the media upload, a separate one for create-spot.
- A retried create after a failure generates a new key; an idempotent replay returns the
  normal response shape and is handled like any success.

### Other behavior

- **Success state**: on create the page shows a success confirmation (with the new spot's
  `StatusBadge`) and then redirects to `/spots/:spotId` (a "View your spot now" link is also
  shown).
- **Illegal/risky spots cannot be created**: the backend rejects
  `legalStatus: ILLEGAL_OR_RISKY` with `422 ILLEGAL_SPOT_REJECTED`. The form warns about
  this before submit.
- The signed photo viewing URL is not part of this flow — it is generated on demand on the
  spot detail page.

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

## Notifications Beta

`/notifications` lists the current user's in-app notifications via `notificationsApi`,
using the V2 design system (see `DESIGN_SYSTEM.md`). Data and behavior are unchanged — only
the presentation is the "Beta" treatment.

| Action | Endpoint | Notes |
|--------|----------|-------|
| List | `GET /notifications/me` | returns the most recent **50** notifications — the backend has **no pagination params** |
| Mark read | `PATCH /notifications/{notificationId}/read` | idempotent; returns the updated notification |

Design:

- **Grouped-looking list** derived purely from existing data: unread items appear under a
  "New" header, read items under "Earlier" — no new fields or backend calls.
- **Type-aware icon/badge** from the backend `NotificationType` (`NEARBY_PARKING`,
  `LEVEL_UP`, `POINT_EARNED`, `WARNING`, `SYSTEM`) — no invented categories.
- **Unread/read visual states**: unread rows get a left primary accent + dot and an
  "Unread" `SoftBadge`; read rows are tonal/dimmed with a "Read" badge.
- A per-item **Mark as read** button; `EmptyState` / `LoadingState` /
  `FriendlyApiErrorMessage` for the empty/loading/error states.

Behavior preserved:

- Mark-as-read invalidates only the `['notifications']` query; the list and the nav badge
  refresh from the same cache entry.
- **Unread badge in the nav**: there is no unread-count endpoint, so the badge derives the
  count from the cached `['notifications']` list (`select`), sharing one fetch with the
  page. **No polling/websocket** — the count refreshes on navigation/reload and after
  mark-as-read. **No pagination, no push** (push delivery is a backend placeholder).

## Gamification — Your Impact

`/gamification` (route unchanged; labelled **"Impact"** in the nav, previously "Progress")
is a user-facing impact/rewards view: a **"Your Impact"** header, a level/points hero, a
two-column **Recent activity** + **Your current benefits** grid, and a full **level roadmap**
below. On mobile it stacks single-column (hero → activity → benefits → roadmap). Composed of
independent read-only queries via `gamificationApi`, one query key per endpoint:

| Section | Endpoint | Query key |
|---------|----------|-----------|
| Hero (level + points + progress bar + metric tiles) | `GET /gamification/me/level` | `['level']` |
| Recent activity (point history) | `GET /gamification/me/points` | `['points']` |
| Your current benefits (access policy, re-labelled) | `GET /gamification/me/access-policy` | `['access-policy']` |
| Level roadmap | `GET /gamification/levels` | `['levels']` |

- The hero shows current level, total points, points-to-next metric tiles and a progress bar
  computed from existing fields only (`totalPoints`, `currentLevelMinPoints`,
  `nextLevelMinPoints`); at the top level (`nextLevelMinPoints`/`pointsToNextLevel` are `null`)
  it shows a "Max level reached" / "Top level" state.
- **Your current benefits** presents the access policy in plain language (search radius,
  results per search, daily views, verified-spot / notification priority) — no technical config
  wording.
- Recent activity lists the most recent 50 ledger entries (`sourceType`, `direction`
  `EARNED`/`DEDUCTED`, points, optional related spot link, timestamp) with an `EmptyState`
  when there are none.
- The level roadmap highlights the current level (matched via `['level']`), shows completed
  levels with a tick and mutes/locks future levels. Point ranges and per-level perks come from
  `GET /gamification/levels` only.
- **No streaks, achievements, heatmaps, rewards or level names** are shown — the backend
  exposes none of these, so they are intentionally omitted (nothing invented).
  `GET /gamification/me/progress` remains available (`['progress']`) and is reused by the
  leaderboard to find the caller's rank.
- The profile hub reuses the `GET /users/me/stats` projection for points/level/trust and
  links here — no duplicate gamification calls on `/profile`.

## Leaderboard (Top Contributors — P1)

`/leaderboard` calls `GET /gamification/leaderboard` (query key `['leaderboard', limit]`,
backend default limit 20, max 100). The **Top Contributors** P1 pass renders:

- A **podium** for the top 3 ranks (gold/silver/bronze medal discs, 1st centred/largest) and a
  **ranking table** for ranks 4+.
- **Public-profile enrichment** — each visible row is enriched with
  `GET /users/{userId}/public-profile` (query key `['public-profile', userId]`, cached 5 min via
  `useQueries`). When a `displayName` is present it is shown; otherwise the row falls back to a
  shortened user id. `trustBand` (when present) renders as a `SoftBadge`. Profile fetches that
  fail are tolerated per-row — the page keeps rendering with the id fallback.
- An **initials avatar** derived from the resolved label (no avatar images — the backend exposes
  none).
- A **"Your standing"** card (rank / points / level) when the caller's `['progress']` `userId`
  matches a visible row (the leaderboard response has no "is me" flag); their row is also
  highlighted. When absent, an honest *"You are not in the current Top N yet"* note is shown — no
  global rank is invented outside the fetched Top N.
- A **"Show more"** button stepping the existing `limit` param `10 → 20 → 50 → 100` (not real
  pagination), with a *"Showing top N"* caption.
- `EmptyState` / `LoadingState` / `FriendlyApiErrorMessage` for the respective states.

**Backend limitations (intentionally not shown — nothing invented):**

- **No weekly / monthly / all-time periods** — the endpoint has no `period` param, so no
  functional time-range tabs are offered. A note states lifetime points are used.
- **No spots count, rank movement (▲/▼) or streaks** — not in any response.
- **No community/leaderboard statistics** — not exposed.
- **No real pagination** — only the `limit` param is used.
- **No avatar images or trust scores beyond `trustBand`** — not exposed; initials/band only.

**Backend limitation:** each row is `{ rank, userId, totalPoints, currentLevel }` — the
response exposes **user ids only, no display names**, so the UI shows a shortened id (first 8
chars). No names are invented.

## Moderation flow (reports & appeals)

User-facing moderation endpoints (the moderator/admin dashboard is documented in its
own section below):

| Action | Endpoint | Notes |
|--------|----------|-------|
| Report a target | `POST /moderation/reports` | `{ targetType, targetId, reason, description? }` |
| List my reports | `GET /moderation/reports/me` | Query key `['reports']` |
| Appeal a case | `POST /moderation/appeals` | `{ caseId, note? }` |

### Report flow

- "Report this spot" lives on `/spots/:spotId` with `targetType` fixed to
  `PARKING_SPOT` and `targetId` taken from the page; the form only asks for a
  `reason` (backend `ModerationReason`: `FAKE_PHOTO`, `DUPLICATE_PHOTO`, `OLD_PHOTO`,
  `WRONG_LOCATION`, `NOT_A_PARKING_SPOT`, `ILLEGAL_OR_RISKY`, `WRONG_VEHICLE_SIZE`,
  `PRIVATE_PROPERTY`, `SPAM_BEHAVIOR`, `ABUSE_REPORT`) and an optional description
  (max 2000 chars).
- Reporting the same target twice for the same reason returns
  `409 DUPLICATE_REPORT` — shown with a friendly message.
- A successful report invalidates `['reports']` and links to `/reports`.

### My reports

`/reports` lists the caller's reports (`['reports']`). A `ReportResponse` has **no
status field**; "serious" reasons (`ILLEGAL_OR_RISKY`, `FAKE_PHOTO`,
`PRIVATE_PROPERTY`, `ABUSE_REPORT`) open a moderation case immediately and the
report then carries a `caseId` — the UI shows "Case opened: <id>" vs "Recorded".
Spot targets link back to `/spots/:id`.

### Appeal limitations

The backend accepts an appeal only for a **RESOLVED case whose target is the
appealing user** (`targetType = USER`, `targetId = you`). Two consequences:

- The `caseId` on your own reports cannot be appealed by you — that case targets the
  *reported* spot/user, not you (`404 CASE_NOT_FOUND`).
- There is **no user-facing endpoint to list cases against your own account**, so the
  appeal form on `/reports` takes a manually entered case id (e.g. from a warning
  notification). This is a documented backend gap, not worked around client-side.

Expected errors: `404 CASE_NOT_FOUND`, `409 CASE_NOT_RESOLVED`,
`409 DUPLICATE_APPEAL` — all mapped to friendly messages with `code`/`traceId`.

## Moderator dashboard (Beta)

`/moderation` requires a `MODERATOR` or `ADMIN` role — the gateway and
moderation-service both enforce it (403 `FORBIDDEN`); the app's `RoleRoute` and the
role-conditional nav link only mirror that for UX. Endpoints (all via gateway):

| Action | Endpoint | Notes |
|--------|----------|-------|
| List cases | `GET /moderation/cases?status=` | Key `['moderation','cases',status\|'all']`; `status` (`OPEN`/`IN_REVIEW`/`RESOLVED`/`REJECTED`) is the only supported filter |
| Case detail | `GET /moderation/cases/{caseId}` | Key `['moderation','case',caseId]` |
| Assign case | `POST /moderation/cases/{caseId}/assign` | No body — assigns to the caller, status → `IN_REVIEW` |
| Resolve case | `POST /moderation/cases/{caseId}/resolve` | `{ action, note? }` — `ModerationAction`: `APPROVE` (dismisses, status → `REJECTED`), `REJECT`, `MARK_FILLED`, `MARK_RISKY`, `REDUCE_TRUST`, `DEDUCT_POINTS`, `SUSPEND_USER`, `RESTORE_USER` (others → `RESOLVED`) |
| List appeals | `GET /moderation/appeals` | Key `['moderation','appeals']`; recent appeals in **every** status — no filter params |
| Resolve appeal | `POST /moderation/appeals/{appealId}/resolve` | `{ accepted, note? }`; only `OPEN` appeals |

Beta design (DS V2): a **queue-first master/detail** layout — the case queue (with a
status filter, `SoftBadge`s for severity + status, and unassigned/assigned hints) sits
on the left; selecting a case opens a detail panel on the right with a structured
**context grid** (target — a deep link to `/spots/{id}` for `PARKING_SPOT` targets —
reason, severity, status, report count, assigned moderator, opened/updated). The panel
hosts the **Assign to me** action and the **resolve form** (action + optional note);
the **appeals** section below lists recent appeals with inline accept/reject + note
forms on `OPEN` ones. Severity/status use tone-mapped `SoftBadge`s, and
`EmptyState`/`LoadingState`/`FriendlyApiErrorMessage` cover the no-cases, no-appeals,
loading and error states. A `CaseResponse` carries `targetType`, `targetId`, `reason`,
`severity`, `status`, `assignedModeratorId`, `reportCount`, resolution fields and
`openedAt`/`updatedAt`/`resolvedAt` timestamps (there is no `createdAt` — `openedAt`
is shown). Role guard, query keys, assign/resolve/appeal-resolve behaviour and
invalidation of the affected `['moderation', …]` keys are unchanged.

Expected errors: `403 FORBIDDEN`, `404 CASE_NOT_FOUND`/`APPEAL_NOT_FOUND`,
`409 INVALID_CASE_STATE` (case already closed), `409 INVALID_APPEAL_STATE`
(appeal already resolved) — mapped to friendly messages with `code`/`traceId`. Closed
(`RESOLVED`/`REJECTED`) cases hide the assign/resolve controls and show the resolution.

Limitations (backend): **no evidence/media comparison** — `CaseResponse` exposes no
report photos, media diffs or evidence attachments, so the detail panel renders only
the textual fields above (no invented evidence UI). No pagination on cases/appeals
(recent lists only), no assignee/severity filters, no per-case report listing, no
appeal status filter, and assignment always targets the calling moderator (no
assigning to someone else).

## Analytics dashboard (Beta)

`/analytics` requires a `MODERATOR` or `ADMIN` role (gateway + `RoleRoute`, same as
the moderator dashboard). KPI cards plus plain tables — **no chart library yet**.
Endpoints (all via gateway, all parameterless GETs — the backend has no date-range,
granularity or metric filters):

| Section | Endpoint | Query key |
|---------|----------|-----------|
| Overview (lifetime KPIs) | `GET /analytics/overview` | `['analytics','overview']` |
| Daily snapshots table | `GET /analytics/daily` | `['analytics','daily']` |
| Parking funnel table | `GET /analytics/parking` | `['analytics','parking']` |
| All metrics table | `GET /analytics/metrics` | `['analytics','metrics']` |
| User analytics lookup | `GET /analytics/users/{userId}` | `['analytics','user',userId]` |

Beta design (DS V2): the overview renders as a grid of `MetricCard` KPI tiles (each
with a metric icon), the daily/parking/metrics/user sections as styled hairline tables
with icon-labelled metric names, and the user-analytics lookup as a form with the
"Use my id" shortcut. `EmptyState` covers empty tables, `LoadingState` the pending
state, and `FriendlyApiErrorMessage` the error state. The overview returns lifetime
totals (`totalParkingCreated`, `totalParkingVerified`, `totalParkingClaimed`,
`totalParkingRejected`, `totalPointsEarned`, `totalLevelUps`,
`totalNotificationsCreated`). Snapshot/metric rows carry a `metricType` string that
mirrors the backend `AnalyticsMetricType` enum (`PARKING_CREATED`, `PARKING_VERIFIED`,
`PARKING_CLAIMED`, `PARKING_REJECTED`, `POINTS_EARNED`, `LEVEL_UP`,
`NOTIFICATION_CREATED`). `TimeGranularity` exists in the backend domain but is never
returned or accepted, so it is not typed in the frontend. Role guard and the existing
`['analytics', …]` query keys are unchanged.

Limitations (backend): the analytics endpoints are **parameterless except `userId`** —
there are no date filters, metric filters or granularity controls to expose, and **no
charting library** is used (tables only). `GET /analytics/users/{userId}` only allows
the **caller's own id** — any other id returns `403 FORBIDDEN` even for
moderators/admins. The lookup form validates the UUID, offers a "Use my id" shortcut,
and maps the 403 to a friendly message with `code`/`traceId`.

## Role-aware routing

| Route | Guard |
|-------|-------|
| `/login`, `/register` | Public |
| `/map`, `/spots/:id`, `/my-spots`, `/upload`, `/profile`, `/reports`, `/notifications`, `/gamification`, `/leaderboard` | Authenticated |
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

- Production-grade geocoding — both `/map` and `/upload` location search use the
  shared browser-side Nominatim typeahead (local-beta only); production should move it
  behind the backend or an SLA provider
- Reverse geocoding — clicking the map (or entering coordinates) never resolves back
  to an address; the address field is only ever filled from a chosen search suggestion
- Production tile provider (defaults to OpenStreetMap; configurable via env)
- Charting on the analytics dashboard (plain tables only)
- Mobile app
- Real push notifications (backend uses a placeholder; in-app only)
- Full backend-integrated E2E — a single mocked Playwright smoke test exists
  (`pnpm e2e`); broader flows against a real gateway/services are future work
- Dark mode (tokens/`darkMode: 'class'` are wired, but no dark screens exist yet)
