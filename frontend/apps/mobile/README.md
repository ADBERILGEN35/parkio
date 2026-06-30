# Parkio Mobile

The production React Native (Expo) app for Parkio. It is a **native** app — not a
WebView wrapper — that reuses the monorepo's shared, framework-agnostic packages
(`@parkio/types`, `@parkio/api-client`, `@parkio/validation`) so DTOs, the HTTP
client, and validation schemas are never duplicated between web and mobile.

> Sprint M0 + M1 scope: app architecture, theming, authentication, secure token
> storage, navigation, error/offline handling, env strategy, CI and EAS config.
> M2 adds Map & Discovery. M3.1 adds native camera/gallery media acquisition and
> media upload preparation. Smart Return UI, Push, and Spot Creation remain later
> mobile work.

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
corepack pnpm install

# copy a local env file (Android emulator reaches the host via 10.0.2.2)
cp apps/mobile/.env.local.example apps/mobile/.env.local

# start Metro (then press a for Android / i for iOS / w for web)
corepack pnpm --filter @parkio/mobile start
```

Use the frontend workspace's pinned pnpm (`corepack pnpm -v` should be `9.15.0`).
On Windows, if Jest reports `charRegex is not a function`, Vitest cannot find
`@rollup/rollup-win32-x64-msvc`, or ESLint cannot load `unrs-resolver`, rebuild
the install from `frontend/` with:

```powershell
$env:CI = 'true'
corepack pnpm install --frozen-lockfile
```

## Scripts

| Command | What it does |
| --- | --- |
| `corepack pnpm --filter @parkio/mobile start` | Start the Expo dev server |
| `corepack pnpm --filter @parkio/mobile typecheck` | `tsc --noEmit` |
| `corepack pnpm --filter @parkio/mobile lint` | ESLint (eslint-config-expo) |
| `corepack pnpm --filter @parkio/mobile test` | Jest (jest-expo preset) |
| `corepack pnpm --filter @parkio/mobile run doctor` | `expo-doctor` config/dependency check |

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

### Android / Expo Go setup for runtime proof

Windows runtime proof requires either Android Studio with an emulator or a
physical Android device with Expo Go:

1. Install Android Studio and include **Android SDK Platform-Tools** and
   **Android Emulator**.
2. Set `ANDROID_HOME` to `%LOCALAPPDATA%\Android\Sdk` and add these to `PATH`:
   `%ANDROID_HOME%\platform-tools` and `%ANDROID_HOME%\emulator`.
3. Create a Pixel API emulator in Android Studio Device Manager and start it, or
   connect a physical Android device with USB debugging enabled.
4. Confirm `adb devices` lists exactly one target.
5. Keep `apps/mobile/.env.local` pointed at `http://10.0.2.2:8080/api/v1` for
   the Android emulator. For a physical device, replace `10.0.2.2` with the
   host machine LAN IP.
6. From `frontend/`, run `corepack pnpm --filter @parkio/mobile start`, then
   press `a` for the emulator or scan the QR code with Expo Go on a physical
   device.
7. Run the checklist above while watching backend auth logs. Do not print or
   capture raw access or refresh tokens.

## M3.1 camera/media runtime checklist

Run this on an Android emulator or physical Android device with Expo Go after
Metro is running:

1. Open the authenticated app and tap **Share a spot**.
2. Tap **Take photo** and verify the Android camera permission prompt uses
   Parkio-specific copy.
3. Grant camera access, capture a photo, and verify the preview screen appears.
4. Tap **Retake**, capture again, and verify the previous preview is replaced.
5. Tap **Choose another**, then **Choose from gallery**, and verify the Android
   gallery permission prompt and native picker.
6. Select an image and verify the preview screen renders the selected image.
7. Tap **Use this photo** and verify progress moves through preparation/upload.
8. During an upload, tap **Cancel** and verify the cancelled state offers retry
   and choose-another actions.
9. Disable network during upload and verify the offline message appears and
   retry is disabled until connectivity returns.
10. Re-enable network, tap **Retry upload**, and verify the same prepared file is
    uploaded without picking/capturing a new image.
11. Verify the terminal state says **Photo uploaded** and displays the returned
    media status/file size. Do not proceed to Spot Creation in M3.1.

Implementation notes:

- Images are resized to a 2048 px longest edge, re-encoded to JPEG, and compressed
  under the media-service 10 MB limit before upload.
- JPEG re-encoding removes unnecessary EXIF metadata and normalizes display
  orientation before multipart upload.
- Upload uses the shared `mediaApi.uploadMedia` with the same idempotency key on
  retry. The mobile app does not duplicate DTOs or upload request logic.
- Expo foreground uploads can be cancelled and retried. True OS-managed
  background upload is not available in Expo Go for this multipart flow; M3.1
  keeps prepared bytes available for retry while the screen remains mounted.

## Building APKs (EAS)

Configured but not published. Profiles in `eas.json`:

- `development` — debug APK + dev client (`eas build -p android --profile development`)
- `preview` — internal-distribution APK (`eas build -p android --profile preview`)
- `production` — Play Store app bundle (`eas build -p android --profile production`)
