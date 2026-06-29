# Parkio Mobile — Architecture (M0 + M1)

This document describes the long-term production architecture of the Parkio mobile
app (`frontend/apps/mobile`). The app is a **native** React Native (Expo) client,
not a WebView wrapper, and shares its domain layer with the web app.

---

## 1. Goals & principles

- **Native, production-grade foundation** — not a website in a shell.
- **Reuse, never duplicate** the domain layer: DTOs, the API client, and
  validation schemas come from the existing workspace packages.
- **Single, predictable session lifecycle** with secure token storage.
- **Platform seams are injected, not forked** — the shared api-client is
  configured with mobile implementations of its abstractions.
- Web behavior stays cookie-backed; native mobile opts into a SecureStore refresh
  flow with `X-Parkio-Client: mobile`.

---

## 2. Folder structure

```
frontend/apps/mobile/
├── app/                          # Expo Router routes (file-based)
│   ├── _layout.tsx               # Root: providers + splash gate + bootstrap
│   ├── index.tsx                 # Entry gate → redirect to app or auth
│   ├── +not-found.tsx
│   ├── (auth)/                   # Unauthenticated group (guarded)
│   │   ├── _layout.tsx           # Redirects signed-in users into the app
│   │   ├── login.tsx
│   │   ├── register.tsx
│   │   └── forgot-password.tsx
│   └── (main)/                   # Authenticated group (guarded)
│       ├── _layout.tsx           # Redirects signed-out users to login
│       ├── (tabs)/               # Bottom tab navigator
│       │   ├── _layout.tsx
│       │   ├── home.tsx
│       │   ├── notifications.tsx
│       │   └── profile.tsx
│       ├── map.tsx               # Placeholder (M2)
│       ├── upload.tsx            # Placeholder (M2)
│       └── smart-return.tsx      # Placeholder (M3)
├── src/
│   ├── components/
│   │   ├── ui/                   # AppText, Button, Card, Screen, Skeleton, Badge, StateView
│   │   ├── forms/                # FormTextField (RHF-bound)
│   │   └── feedback/             # ErrorBoundary, OfflineBanner
│   ├── config/                   # env.ts (typed EXPO_PUBLIC_* config, Zod-validated)
│   ├── hooks/                    # useAuth, useOnlineStatus
│   ├── providers/                # AppProviders, QueryProvider, ToastProvider
│   ├── services/                 # api, auth, secureStore, tokenStorage, pushNotifications
│   ├── state/                    # authStore (zustand)
│   ├── test/                     # renderWithProviders
│   ├── theme/                    # tokens, colors, theme, ThemeProvider
│   └── utils/                    # errors (friendly mapper)
├── assets/images/                # icon / splash / adaptive-icon
├── e2e/                          # Detox placeholder
├── app.json / app config         # Expo config
├── eas.json                      # Build profiles (development/preview/production)
├── babel.config.js / metro.config.js
├── tsconfig.json / eslint.config.js / .prettierrc.json
├── jest.config.js / jest.setup.ts
└── .env.*.example                # Per-environment templates
```

---

## 3. Shared package usage

The mobile app depends on three workspace packages via `workspace:*` and consumes
their raw TypeScript directly (Metro transpiles them — no build step):

| Package | Used for | Notes |
| --- | --- | --- |
| `@parkio/types` | All DTOs (`User`, `Profile`, `AppNotification`, `UserStats`, …) | Single source of truth; mirrors backend contracts. |
| `@parkio/api-client` | `createApiClient` + every `create*Api` factory, error classes, `TokenStorage`, single-flight refresh coordinator | The HTTP layer is reused verbatim; no endpoints re-implemented. |
| `@parkio/validation` | Zod schemas (`loginSchema`, `registerSchema`, `forgotPasswordSchema`) + password helpers | Same validation rules as web. |

Metro is configured (`metro.config.js`) to watch the monorepo root and resolve
from both the app and root `node_modules`, which is what makes the symlinked pnpm
workspace packages resolve on device.

**No DTOs or API clients are duplicated.** The only mobile-specific code in the
data layer is the injection of platform implementations (token storage, callbacks).

---

## 4. Authentication flow

State lives in `src/state/authStore.ts` (zustand), mirroring the web app's model
so behaviour is consistent across platforms.

### Cold start (session restore)
```
app/_layout.tsx  ── bootstrapSession()
   1. tokenStorage.hydrate()      # load access + refresh token from keystore
   2. refreshSession()            # single-flight POST /auth/refresh-token
        success -> rotate tokens + setSession (user is authenticated)
        failure -> clear keystore + state (back to login)
   3. endBootstrap()              # splash hides; index/guards decide route
```

### Sign in / sign up
```
signIn(credentials) -> authApi.login with X-Parkio-Client: mobile
   backend returns accessToken + refreshToken in the body
   tokenStorage.setTokens(...)            # memory + SecureStore
   secureStore.saveSession({ userId })
   authStore.setSession(user)             # UI flips to authenticated
```

Registration returns a pending-verification response and does not authenticate the
user. The app clears any local session state and returns the user to login after
account creation.

### 401 handling & auto-refresh (in `@parkio/api-client`)
- A 401 on a non-exempt request triggers **one** refresh via the shared
  single-flight coordinator; concurrent 401s collapse into a single
  `POST /auth/refresh-token`.
- On refresh success the original request is retried **once** (`_retry` guard) —
  **never an infinite retry loop**.
- On refresh failure `onAuthFailure` runs → hard logout (keystore + state cleared).
- A `403 ACCOUNT_NOT_ACTIVE` flips the `suspended` flag instead of logging out.
- A teardown during an in-flight refresh is detected via `sessionEpoch`, so a late
  success can't resurrect a session the user just logged out of.

### Logout / Logout all
`signOut` sends the current SecureStore refresh token in the logout body so the
backend revokes exactly that mobile session. `signOutAll` uses the bearer access
token to revoke every server-side refresh session for the user. Both calls are
best-effort locally: the keystore and in-memory session are always cleared, and
the `(main)` guard redirects to login.

---

## 5. Secure storage & token flow

`expo-secure-store` (iOS Keychain / Android Keystore-backed) is the **only** place
sensitive values are persisted — never AsyncStorage.

| Key | Value | Notes |
| --- | --- | --- |
| `parkio.accessToken` | JWT access token | Mirrored from the in-memory cache on every write. |
| `parkio.refreshToken` | opaque refresh token | Present only for native mobile; rotated on refresh. |
| `parkio.userId` | user id | For optimistic restore / diagnostics. |

**The synchronous/asynchronous bridge.** The shared api-client reads the access
token *synchronously* in its request interceptor, but the keystore is *async*.
`SecureTokenStorage` (`src/services/tokenStorage.ts`) solves this by keeping the
access and refresh tokens in memory as the synchronous source of truth and
mirroring every write to the keystore; `hydrate()` reloads both on cold start.

**Refresh transport.** Web clients continue to receive the raw refresh token only
as an HttpOnly cookie and never see it in JavaScript. Native mobile sends
`X-Parkio-Client: mobile` without browser `Origin`/`Referer` headers, receives
the refresh token in the JSON login/refresh body, stores it only in SecureStore
plus memory, and replays it in the refresh/logout body. Browser-like requests
stay on the cookie flow even if they accidentally send the mobile header. The
backend still rotates refresh tokens on every successful refresh and reuse
detection remains server-side.

---

## 6. Navigation

- **Expo Router** (file-based). Route groups `(auth)` and `(main)` model the two
  session states; `(main)/(tabs)` is the bottom tab navigator (Home, Notifications,
  Profile).
- **Guards** are declarative: `app/index.tsx` redirects from the entry point, and
  each group `_layout.tsx` re-asserts the guard so **deep links** into protected
  routes redirect to login when signed out (and vice-versa).
- **Deep links** use the `parkio://` scheme (`app.json`), resolved by Expo Router's
  file routes — e.g. `parkio:///(main)/(tabs)/notifications`.
- Feature routes for Map / Upload / Smart Return exist as **placeholders** so the
  navigation graph and entry points are real today.

---

## 7. Cross-cutting concerns

| Concern | Implementation |
| --- | --- |
| Providers | `AppProviders` composes ErrorBoundary → GestureHandlerRoot → SafeArea → Theme → Query → Toast. |
| Error boundary | `components/feedback/ErrorBoundary.tsx` — recoverable fallback, crash-sink seam for M2. |
| Offline | `useOnlineStatus` (NetInfo) + `OfflineBanner`; Query `refetchOnReconnect`. |
| Toasts | `ToastProvider` (RN Animated, no worklets) — `useToast()`. |
| Loading | Skeleton / SkeletonCard (no spinners for content; no layout shift). |
| Errors → copy | `utils/errors.toUserMessage` maps `ParkioApiError`/network to human text. |
| Theming | Token-driven (`theme/`), light fully designed, dark **prepared** (palette exists, not finalised). |
| Accessibility | 44pt min targets, roles/labels/`accessibilityState`, `allowFontScaling` (Dynamic Type), focus-friendly order. |

---

## 8. Environment strategy

- All client config is public and travels via `EXPO_PUBLIC_*`, validated by Zod in
  `src/config/env.ts`.
- Local: `.env.local` (copied from `.env.local.example`).
- Beta/Production: injected by EAS build profiles (`eas.json` → `preview` /
  `production`), so no environment-specific values are committed.
- `expo doctor` and the `Mobile CI` workflow gate typecheck, lint, and tests.

### Build profiles (`eas.json`)
| Profile | Output | Channel |
| --- | --- | --- |
| development | debug APK + dev client | development |
| preview | internal APK | preview |
| production | Play Store AAB | production |

No release binary is built or published in this sprint — only configured.

---

## 9. Roadmap (M2+)

- **M2 — Map & discovery:** real map, nearby search (reusing `parkingApi`), result
  list/sheet, geocoding search.
- **M2 — Upload & camera:** spot capture wizard (reusing `mediaApi`/`parkingApi`).
- **M2 — Push notifications:** finish `services/pushNotifications.ts` (permissions,
  Expo push token, register with notification-service, handlers + deep-link routing).
- **M3 — Smart Return mobile UI**, location services, background checks.
- **Detox E2E** (see `e2e/README.md`) and a dedicated device-CI workflow.
- **Dark mode** finalisation + in-app theme override.
- **Crash reporting** wired into the ErrorBoundary sink.
