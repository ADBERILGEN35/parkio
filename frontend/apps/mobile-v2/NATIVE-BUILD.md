# mobile-v2 native ownership

Canonical Android release builds use the committed `android/` tree
(`scripts/run-android-release.mjs` -> Gradle; EAS `gradleCommand`
`:app:assembleRelease` / `:app:bundleRelease`). This is a
continuous-native project with a retained prebuild recipe, not CNG-on-EAS.

## Why expo-doctor's CNG check cannot pass

`expo-doctor` check
"Check for app config fields that may not be synced in a non-CNG project"
fails when both are true:

1. `android/` is present, so Expo classifies the app as a non-CNG / bare project.
2. `app.json` still contains CNG-managed fields: `orientation`, `icon`,
   `scheme`, `userInterfaceStyle`, `ios`, `android`, `plugins`.

Those `app.json` fields are the **prebuild recipe** for an operator-initiated
`expo prebuild` that regenerates `android/`. Gradle and EAS `gradleCommand`
do not re-apply them while `android/` exists. Deleting `android/` to silence
the checker would abandon the signed release surface (WP-07, local release,
hosted-beta APK). Removing the CNG fields would destroy the recipe needed to
regenerate native files. Waiting for Expo to change the check does not
reconcile ownership.

The doctor check therefore cannot represent the supported setup. CI does not
disable expo-doctor. `scripts/run-expo-doctor.mjs` still fails the job on every
non-CNG doctor failure. The CNG finding is replaced by
`scripts/assert-native-ownership.mjs`, which asserts the live native tree.

## Replacement gate

`assert-native-ownership.mjs` requires:

- committed `android/` with Gradle wrapper and `app` module
- `applicationId` / `namespace` = `app.json` `expo.android.package` =
  `dev.parkio.mobilev2`
- manifest scheme `parkio-v2`, `allowBackup=false`, portrait, and blocked
  `RECORD_AUDIO` / `SYSTEM_ALERT_WINDOW` removed
- `run-android-release.mjs` builds from `android/` via `gradlew`
- EAS `preview` / `hosted-beta` use `:app:assembleRelease`; `production`
  uses `:app:bundleRelease`
- every local `app.json` plugin path exists

Store version (`versionCode` / `versionName` in Gradle) is owned by
`android/`. `app.json` `version` is the JS/Expo identity and may differ
(today `0.1.2-backnav` vs `0.1.0`). Do not "fix" that by deleting native
files.

Do not delete `android/` to silence expo-doctor. Do not set
`continue-on-error` on the Mobile-v2 doctor step.
