# Sprint 3 WP-07 — Mobile Application Foundation and Core Flows

## 1. Scope and non-goals

WP-07 hardens the production-shaped foundation of the canonical Mobile app at
`frontend/apps/mobile-v2` and aligns representative core flows with accepted
Web contracts (WP-04/05/06) **without** redesigning frozen Web ownership.

**In scope:** composition root, env, SDK ownership, SecureStore session,
Expo Router auth authority, Mobile QueryClient/keys/query-options, cancel-safe
retries, AbortSignal forwarding, nearby/detail/create/reports/Smart Return
wiring, location/privacy, upload abort cleanup, lifecycle/connectivity,
typed errors, guardrails, focused tests, CI, documentation.

**Non-goals:** formal WP-07 closure, legacy `frontend/apps/mobile` migration,
Web redesign, backend/infra changes, store publication, background location,
offline mutation queues, WP-08 work.

This document does not declare formal WP-07 closure.

## 2. Canonical directory

| Path | Role |
|------|------|
| `frontend/apps/mobile-v2` | **Only** production Mobile owner |
| `frontend/apps/mobile` | Legacy; not the WP-07 target; not imported |

## 3. Stack (actual)

Expo ~56 / React Native 0.85 / expo-router / TypeScript strict / TanStack Query
/ zustand / expo-secure-store / expo-location / expo-image-picker /
@react-native-community/netinfo / shared `@parkio/api-client|types|validation|geo`.

## 4. Ownership map

| Concern | Owner |
|---------|--------|
| Composition root | `app/_layout.tsx` → `AppProviders` |
| SDK / api-client | `src/services/api.ts` (single `createApiClient`) |
| QueryClient | `src/providers/query-client.ts` + `QueryProvider.tsx` |
| Session cache sync | `src/data/SessionQueryCacheSync.tsx` |
| Canonical keys | `src/data/keys.ts` |
| Query options | `src/data/query-options/*` |
| Auth/session | `src/services/{auth,tokenStorage,secureStore}.ts` + `authStore` |
| Navigation auth | Expo Router `(auth)` / `(main)` / `index` layouts |
| Tokens | SecureStore only (no AsyncStorage secrets) |

## 5. Production risks addressed

| Risk | Before | After |
|------|--------|-------|
| Ad-hoc query keys | Inline string keys | Canonical `keys.ts` + query-options |
| Missing AbortSignal on reads | Only nearby/places | me/parking/reports/gamification/notifications options forward signal |
| Cancel retried as transient | Status-only retry | `CancellationError` never retries |
| Logout cache leakage | No clear | `clearUserSessionQueries` on identity change |
| Upload continues after leave | Manual cancel only | AbortController aborted on unmount |
| Cross-app coupling | Possible | Guardrails ban imports from Web src / legacy mobile |

## 6. Guardrails and CI

- Module: `scripts/architecture/wp07-mobile-foundation.mjs`
- Focused: `pnpm guardrails:wp07`, `pnpm --filter @parkio/mobile-v2 test:wp07`
- Wired into full `pnpm guardrails` via `check-boundaries.mjs`
- CI job: WP-07 mobile foundation (retries disabled via jest defaults / `--runInBand`)

## 7. Tradeoffs

- Moderation/analytics page queries keep inline `queryFn` where SDK methods lack
  signal options; keys are still canonical.
- Module-level SDK facades remain (no React context) — single owner, screens import
  facades rather than constructing clients.
- Nearby uses `lat:0,lng:0` placeholder key only while `enabled: false`.

## 8. Remaining production debt

- Broaden signal support for moderation/admin SDK methods if added later
- Dedicated upload abort unit test beyond draft-upload cleanup
- Legacy `apps/mobile` retirement (out of WP-07)
- Store signing / push production builds (out of scope)

## 9. Formal Closure — WP-07.1 Mobile Application Foundation

**Closed:** 2026-07-29
**Sub-package:** WP-07.1

All mandatory section 8 production-debt items governed by WP-07.1 have been
verified against the repository:

| Debt Item | Status | Evidence |
|-----------|--------|----------|
| Broaden signal support for moderation/admin SDK methods | SATISFIED | PR-1 investigation: 21/21 mobile-v2-consumed read methods already forward `AbortSignal`. Admin methods not consumed by mobile-v2 are outside the WP-07.1 condition. No code change required. |
| Dedicated upload abort unit test | SATISFIED | PR-2: `useDraftUpload.abort.test.ts` — 3 focused tests exercising the production `useDraftUpload` hook's `AbortController` lifecycle (abort on unmount, no abort after completion, idempotent cancel+unmount). |
| Legacy `apps/mobile` retirement | OUT OF SCOPE | Explicitly excluded from WP-07 per section 1. |
| Store signing / push production builds | OUT OF SCOPE | Governed by WP-07.4 (operator-gated), not WP-07.1. |

**Not closed by this entry:**
- WP-07.2 (Web ParkingSession Parity)
- WP-07.3 (ParkingSession Deletion Analytics Event)
- WP-07.4 (Mobile Release Signing & Crash Verification — operator-gated)
- WP-07.5 (Hosted Beta Deploy Gate — operator-gated)