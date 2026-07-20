# Parkio mobile-v2 — architecture notes

New Expo app implementing the **Pencil design system** (`untitled.pen`, brief in
`PARKIO-DESIGN-BRIEF.md` §12) against the existing gateway API. Lives side by side
with the v1 app (`apps/mobile`); shares `@parkio/api-client`, `@parkio/types`,
`@parkio/validation`, `@parkio/geo`.

## Key decisions

- **Stack:** Expo ~56 / RN 0.85 / expo-router / TS strict / TanStack Query /
  zustand. Same dependency versions as v1 (proven in this workspace + Expo Go).
- **Design tokens** (`src/theme/tokens.ts`) mirror the pen file variables 1:1;
  light is brand, dark is calm navy. `AppText` is the only text primitive
  (Inter, type scale, tabular numerals for countdowns).
- **i18n:** TR default, EN mirror — `src/i18n/translations.ts` typed key
  catalog; `en` is `Record<TranslationKey, string>` so parity is compile-checked.
- **Auth:** identical architecture to v1 — keystore-backed `tokenStorage`
  (sync in-memory cache + SecureStore mirror), single-flight refresh via
  `setRefreshHandler`, `X-Parkio-Client: mobile` body-token transport,
  `sessionEpoch` teardown guard. See `src/services/{api,auth,tokenStorage}.ts`.
- **Map:** MapLibre GL JS inside a `react-native-webview` (key-free OSM raster,
  CSS-filtered to the calm pale basemap; dark filter in dark mode). DOM markers
  (≤50 spots by level policy) implement the exact pill+freshness-ring design
  with in-WebView countdown ticks. Bridge: `window.__parkio.*` inbound,
  `postMessage` outbound. One HTML builder serves map tab, location-adjust
  (center-pin mode), Smart Return home picker, and spot-detail mini map.
- **Freshness rings:** `react-native-svg`; remaining-life fraction =
  `(expiresAt − now) / (expiresAt − createdAt)`; color >66% blue, 33–66% amber,
  <33% red (`freshnessColor`).
- **Glass:** `expo-blur` + rgba fill + hairline border (`Glass` component);
  Android uses the translucent fallback automatically.
- **Share flow:** camera-first wizard; draft persisted via `shareDraftStore`
  (expo-file-system JSON) and resumable across cold starts. Photo is prepared
  with expo-image-manipulator (≤1600px JPEG), uploaded eagerly with progress /
  cancel / retry / offline queue; `MEDIA_NOT_READY`-style create failures retry
  with backoff while the ClamAV scan finishes. Address entry + center-pin map
  adjust + place search all set `addressText` / lat/lng / `manualLocationEdited`.
- **Push:** backend `data.route` payloads target v1 route names; `ROUTE_MAP` in
  `src/services/pushNotifications.ts` translates them to v2 routes and role-guards
  staff targets.
- **Persistence:** non-sensitive JSON via `src/services/jsonStore.ts`
  (expo-file-system Files under `parkio-store/`); secrets only in SecureStore.
- **Mock gateway:** `scripts/mock-gateway.mjs` (Node, no deps) implements the
  gateway contract with in-memory İzmir sample data for end-to-end dev/testing:
  `pnpm mock-gateway`, app `.env.local` points at `http://localhost:8080/api/v1`.

## Route map (expo-router)

```
app/
  index.tsx                    entry redirect (onboarding/auth/main/suspended)
  (onboarding)/ language → slides → permissions → welcome
  (auth)/ login register forgot-password check-email verify-email reset-password
  (main)/
    (tabs)/ map my-spots leaderboard profile   (+ share = raised center action)
    share/ (modal wizard: index=steps, camera)
    spots/[id]
    notifications impact reports smart-return suspended
    profile/ edit vehicle preferences change-password about
    moderation/ index [id] analytics
```

Tab bar is custom (`AppTabBar`): 5 slots, center "Paylaş" raised primary circle
opens the share source sheet, matching the pen design.
