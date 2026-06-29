# Mobile E2E (Detox) — placeholder

Detox is **not wired up yet** (M0 scope is foundation, not full device E2E). This
folder reserves the location and documents the intended setup so a later sprint can
add it without re-deciding the approach.

## Planned setup (M2+)

1. Add dev dependencies: `detox`, `jest` config for Detox, and `detox-cli`.
2. Add a `detox` section to `package.json` with iOS/Android debug + release configs
   pointing at the EAS-built binaries.
3. Author specs here (e.g. `login.e2e.ts`, `navigation.e2e.ts`) covering:
   - cold start → login → tabs
   - session restore after relaunch
   - logout returns to the auth stack
   - deep link into a protected route redirects to login when signed out
4. Run on a device/emulator in a dedicated CI workflow (not the fast `mobile-ci`
   gate), mirroring how the web app keeps Playwright separate from unit CI.

Until then, the auth/session/navigation logic is covered by the jest-expo unit
tests under `src/**/__tests__`.
