# Parkio mobile-v2

The Parkio mobile app rebuilt on the **Pencil design system** (`untitled.pen`,
"The Living Signal" — see `PARKIO-DESIGN-BRIEF.md` §12). Expo ~56 / React
Native 0.85 / expo-router, Turkish-default bilingual UI, light + dark themes,
wired to the existing gateway API through the shared `@parkio/*` packages.

Lives side by side with the v1 app (`apps/mobile`); architecture notes and
key decisions are in [`ARCHITECTURE.md`](./ARCHITECTURE.md).

## Run it

```bash
# From the repo root, once:
cd frontend && pnpm install

cd apps/mobile-v2
cp .env.example .env.local   # already points at http://localhost:8080/api/v1

# Terminal 1 — a backend. Either the real gateway stack, or the bundled mock:
pnpm mock-gateway            # contract-faithful mock with İzmir sample data

# Terminal 2 — the app:
pnpm start                   # press i for iOS simulator / a for Android
```

Mock-gateway demo accounts (password `Parkio-Demo-1234`):

| Account | Purpose |
|---|---|
| `demo@parkio.dev` | Level-3 driver with points + notifications |
| `mod@parkio.dev` | MODERATOR + ADMIN (staff queue + analytics) |

Email verification / password reset in the mock accept the token `demo`.
Seeded spots re-arm their 10-minute expiry automatically so the map never
stays empty; spots you create expire for real.

- iOS simulator / web reach the mock via `localhost`; Android emulator needs
  `EXPO_PUBLIC_API_BASE_URL=http://10.0.2.2:8080/api/v1`; a physical device
  needs your LAN IP.
- Remote push doesn't run inside Expo Go (SDK 53+) — registration fails soft;
  use a development build for push.

## Quality gates

```bash
pnpm typecheck   # tsc --noEmit (strict)
pnpm lint        # eslint (expo flat config)
pnpm test        # jest — unit + render suites
```

## What's implemented

Full product surface per the brief: onboarding (language → value slides →
permission priming) · email auth suite (login/register/verify/forgot/reset)
· map tab (MapLibre WebView, live Freshness-Ring markers, glass search +
typeahead, search-this-area, radius/level chip, permission & view-limit &
empty cards, Smart Return banner) · spot bottom sheet (peek/expanded) & full
detail with all status variants · verify / claim / report actions · camera-
first share wizard (eager upload with progress/retry/offline queue, GPS gate,
**center-pin map adjust + address entry + place search**, details with the
Riskli hard-block, review, celebration + honest pending state, draft resume)
· my spots · leaderboard · impact (radius diagram, ledger, level roadmap) ·
notifications · reports & appeals · Smart Return (settings, morning prompt,
today banner) · profile hub + subscreens · staff moderation queue/case/
analytics · push deep-link routing (v1-compatible payloads) · dark mode.
