# Parkio Mobile — Internal Beta Release Guide (M5)

How the Android beta artifacts are produced, what is wired, and what still needs
project-owner credentials before Play Console Internal Testing.

## Artifacts & how to rebuild them

Local toolchain (all on Windows): JDK 17, Android SDK (`%LOCALAPPDATA%\Android\Sdk`),
Node 24, pnpm via corepack. The native project is generated — `android/` is
gitignored; regenerate any time with:

```powershell
cd C:\Users\ADBERILGEN\Documents\parkio\frontend\apps\mobile
node node_modules\expo\bin\cli prebuild --platform android --no-install
```

| Artifact | Command (from `apps/mobile/android`) | Env | Notes |
|---|---|---|---|
| Development build (Metro-attached) | `.\gradlew.bat assembleDebug` | `.env.local` | Debug-signed; loads JS from Metro; replaces Expo Go |
| Internal validation APK | `.\gradlew.bat assembleRelease` (`NODE_ENV=production`) with `.env.production` = dev values (`http://10.0.2.2:8080/api/v1`) | local | Requires the temporary `android:usesCleartextTraffic="true"` on the main `<application>` (http). **Never ship this variant.** |
| Preview APK (beta testers) | same, `.env.production` = `EXPO_PUBLIC_APP_ENV=hosted-beta`, `EXPO_PUBLIC_API_BASE_URL=https://beta-api.parkio.dev/api/v1` | hosted-beta | No cleartext patch — https only |
| Release AAB (Play upload) | `.\gradlew.bat bundleRelease` (`NODE_ENV=production`), `.env.production` = production values | production | Output: `android/app/build/outputs/bundle/release/app-release.aab` |

**Env plumbing gotchas (cost a day — do not rediscover):**

1. `EXPO_PUBLIC_*` values must reach the bundle via **dotenv files**, not the
   gradle invocation's shell env — the Gradle daemon keeps the env of its *first*
   launch and passes that to the Metro/expo export child process. With
   `NODE_ENV=production` expo loads `.env.production` (then `.env.local`, which we
   move aside during artifact builds; both are gitignored). Verify what got baked:
   `unzip -p app-release.apk assets/index.android.bundle | grep -ao 'beta-api\|10\.0\.2\.2'`.
2. babel-preset-expo only inlines **static** `process.env.EXPO_PUBLIC_X` member
   expressions. `src/config/env.ts` therefore builds its raw object with explicit
   static accesses — never pass `process.env` around dynamically.
3. When switching env between builds, delete
   `android/app/build/generated/assets/react` (the bundle task's inputs don't
   include env) and, if in doubt, `%TMP%\metro-cache`.
4. Windows MAX_PATH: `frontend/.npmrc` pins `virtual-store-dir-max-length=60`, and
   the (regenerated-after-prebuild) root `android/build.gradle` carries two appended
   blocks: `C:/pkx/<name>` CMake staging redirects, and a `configureCMake*` hook
   that mirrors autolinked codegen/jni trees to `C:/pkg/<shortname>` and rewrites
   `Android-autolinking.cmake` to the mirrors (sidecar `Android-autolinking.mirrors.txt`
   keeps mirrors rebuildable). Never redirect `buildDir` — it breaks autolinking.

EAS cloud builds: `eas.json` defines matching `development` / `preview` /
`production` profiles. Running them needs `eas login` and a real `extra.eas.projectId`
in `app.json` (currently a placeholder) — owner action.

## Signing

Gradle's release config currently signs with the **debug keystore** (Expo prebuild
default) — fine for sideloaded internal validation, **not** for Play upload. Before
the first Play Console upload either:

1. let EAS manage the keystore (recommended: `eas build -p android --profile production`), or
2. generate a local upload key and wire it in `android/gradle.properties` (untracked):
   ```powershell
   keytool -genkeypair -v -keystore parkio-upload.keystore -alias parkio-upload -keyalg RSA -keysize 2048 -validity 10000
   ```
   Back the keystore up outside the repo. Losing it locks you out of app updates
   (unless Play App Signing is enrolled, which it should be).

## Crash reporting & analytics — current state

- **Wired now (no vendor):** React ErrorBoundary → `crashReporting.recordError`;
  global fatal JS errors (`ErrorUtils`); unhandled promise rejections (Hermes
  tracker); typed analytics events (`login`, `sign_up`, `upload_started/completed/
  cancelled`, `spot_created`, `smart_return_opened`, `notification_opened`) — no
  coordinates or PII by design. In release builds both buffer in memory awaiting a
  vendor sink (`setCrashSink` / `setAnalyticsTransport`).
- **Needs owner credentials:** Firebase Crashlytics + Analytics require a Firebase
  project, `google-services.json` in `apps/mobile`, the `@react-native-firebase/app,
  crashlytics, analytics` packages and their config plugins, and a rebuild. Native
  crash capture only exists after that step. The seams above mean this is a
  ~30-line wiring change with zero touch to product code.

## Push notifications — current state

- Device registration against the existing backend endpoint
  (`POST /api/v1/notifications/device-token`), token-refresh re-registration, tap →
  allow-listed in-app route (incl. cold start), foreground banner handling, and
  deactivation on logout are implemented in `src/services/pushNotifications.ts`.
- **Remote delivery needs FCM**: `google-services.json` + a dev/EAS build (Expo Go
  on Android cannot receive remote push). Registration fails soft in Expo Go.
- The backend must send the Expo push token to Expo's push API (or FCM directly)
  — delivery pipeline is a backend feature outside this sprint.

## Environments & secrets

`EXPO_PUBLIC_*` only (no secrets); real `.env*` files are gitignored, committable
templates: `.env.local.example`, `.env.beta.example`, `.env.production.example`.
EAS profiles inject the same values per channel.

## Play Store readiness

See `docs/beta/play-store-listing.md` for the listing text, data-safety matrix and
permission rationale. Remaining owner tasks: Play Console account, app creation,
Firebase project, privacy-policy hosting URL, feature graphic artwork, real device
screenshots (the repo evidence screenshots are emulator captures).
