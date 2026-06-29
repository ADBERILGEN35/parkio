# Parkio Mobile

The production React Native (Expo) app for Parkio. It is a **native** app — not a
WebView wrapper — that reuses the monorepo's shared, framework-agnostic packages
(`@parkio/types`, `@parkio/api-client`, `@parkio/validation`) so DTOs, the HTTP
client, and validation schemas are never duplicated between web and mobile.

> Sprint M0 + M1 scope: app architecture, theming, authentication, secure token
> storage, navigation, error/offline handling, env strategy, CI and EAS config.
> Map, Upload, Camera, Smart Return UI, Push and Location are **placeholders only**.

M1.5 hardens native authentication: mobile requests send
`X-Parkio-Client: mobile`, receive the refresh token in the login/refresh response
body, persist access + refresh tokens only in `expo-secure-store`, rotate on
refresh, and send the current refresh token in the logout body. Web remains on the
HttpOnly refresh-cookie flow.

## Stack

- Expo SDK 56 · React Native · TypeScript
- Expo Router (file-based navigation + deep links)
- TanStack Query (server state) · React Hook Form + Zod (forms)
- expo-secure-store (tokens) · expo-notifications (prepared, not wired)
- Jest + React Native Testing Library

## Getting started

```bash
# from the monorepo frontend root
pnpm install

# copy a local env file (Android emulator reaches the host via 10.0.2.2)
cp apps/mobile/.env.local.example apps/mobile/.env.local

# start Metro (then press a for Android / i for iOS / w for web)
pnpm --filter @parkio/mobile start
```

## Scripts

| Command | What it does |
| --- | --- |
| `pnpm --filter @parkio/mobile start` | Start the Expo dev server |
| `pnpm --filter @parkio/mobile typecheck` | `tsc --noEmit` |
| `pnpm --filter @parkio/mobile lint` | ESLint (eslint-config-expo) |
| `pnpm --filter @parkio/mobile test` | Jest (jest-expo preset) |
| `pnpm --filter @parkio/mobile doctor` | `expo-doctor` config/dependency check |

## Environments

Client config flows through `EXPO_PUBLIC_*` env vars (see `.env.*.example`). Local
dev uses `.env.local`; `hosted-beta` and `production` values are injected by the
matching EAS build profile in `eas.json`. No secrets are committed.

| Env | API base URL source |
| --- | --- |
| development | `.env.local` → `http://10.0.2.2:8080/api/v1` |
| hosted-beta | EAS `preview` profile |
| production | EAS `production` profile |

## Project layout

See [`docs/mobile-architecture.md`](../../../docs/mobile-architecture.md) for the
full architecture: folder structure, navigation, shared packages, the
authentication & token-refresh flow, the environment strategy, and the M2+ roadmap.

## Runtime auth checklist

Do not mark runtime proof complete unless this has been run on an Android emulator,
iOS simulator, or physical device:

- Fresh install opens the login route.
- Login reaches the Home tab and stores tokens in SecureStore.
- Killing and reopening the app restores the session through refresh rotation.
- A stale access token produces one refresh and one retry.
- Logout clears local tokens and reopens to login.
- Logout-all clears local tokens and stale refresh tokens cannot restore.

## Building APKs (EAS)

Configured but not published. Profiles in `eas.json`:

- `development` — debug APK + dev client (`eas build -p android --profile development`)
- `preview` — internal-distribution APK (`eas build -p android --profile preview`)
- `production` — Play Store app bundle (`eas build -p android --profile production`)
