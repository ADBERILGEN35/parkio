# Task 8 — End-to-End Verification Gate

Project: Parkio

This document describes the **final production verification gate** before release.
It is independently mergeable verification work. It does **not** declare the
project complete and does **not** close any work package.

## Verification scope

Task 8 proves already-accepted behavior across:

| Layer | Focus |
|-------|--------|
| Backend | PostgreSQL/PostGIS lifecycle integration |
| Shared client | Contract tests for `@parkio/api-client` |
| Web | Remount / reconnect / cache restoration |
| Mobile-v2 | Provider remount / reconnect / restoration |
| CI | Dedicated Mobile CI for Mobile-v2 + Expo Doctor |

Frozen architecture (Backend, Shared Client, Web, Mobile-v2 ownership, routing,
auth, query/cache/SDK ownership, public APIs, business rules) is not redesigned.

## PostgreSQL integration coverage

Representative parking lifecycle against **real PostGIS** (not H2):

- Class: `services/parking-service/.../Task08ParkingLifecyclePostgisIT.java`
- Tag: `@Tag("integration")` + Testcontainers `postgis/postgis:16-3.4`
- Covers:
  - successful create → AI pass → verify → claim persistence
  - invalid transition (`OWNER_CANNOT_CLAIM` → HTTP 403) without corrupting spot
  - idempotent create replay (no duplicate rows/events)
  - duplicate submission with different body → `IDEMPOTENCY_KEY_CONFLICT`
  - concurrent two-claimer race → exactly one active community session

Run:

```bash
./gradlew :services:parking-service:integrationTest --tests Task08ParkingLifecyclePostgisIT
# or full Docker-required gate:
./gradlew integrationTest -Pparkio.integrationTest.requireDocker=true
```

Existing PostGIS ITs (`ParkingSessionPostgisIntegrationTest`,
`ParkingPostgisIntegrationTest`, etc.) remain the broader concurrency/history
suite; Task 8 adds an explicit lifecycle gate for release verification.

## Shared client contract coverage

File: `frontend/packages/api-client/src/contract.task08.test.ts`

Verifies:

- stable public exports (factories + typed errors)
- nearby request serialization + response mapping
- Authorization + Idempotency-Key propagation on create/verify/claim
- typed HTTP error mapping (`NotFound` / `Conflict` / `Validation`)
- distinct transport classes (`CancellationError` / `TimeoutError` / `NetworkError`)

Run:

```bash
pnpm --filter @parkio/api-client test
```

Does not duplicate page/screen tests. Public export freeze continues via
`pnpm guardrails` (`check-public-exports.mjs`).

## Web restoration coverage

File: `frontend/apps/web/src/providers/query-restoration.task08.test.tsx`

Verifies:

- documented reconnect/remount refetch policy
- remount with a fresh QueryClient does not share stale cache
- invalidate + refetch restores canonical server state
- concurrent same-key fetches are deduplicated
- logout cache clear is not resurrected by online/offline transitions

Run:

```bash
pnpm --filter @parkio/web test src/providers/query-restoration.task08.test.tsx
# included in full:
pnpm --filter @parkio/web test
```

Does not redesign QueryClient, routing, or query keys.

## Mobile-v2 remount / reconnect coverage

File: `frontend/apps/mobile-v2/src/providers/__tests__/restoration.task08.test.tsx`

Verifies:

- documented refetch policy
- provider remount creates a new client; AppState listeners clean up
- NetInfo → `onlineManager` reconnect wiring
- AppState → `focusManager` wiring
- concurrent fetch dedupe
- invalidate + refetch restores server state
- logout/identity clear is not resurrected by reconnect

Run:

```bash
pnpm --filter @parkio/mobile-v2 test -- restoration.task08
# full + WP-07 focused:
pnpm --filter @parkio/mobile-v2 test
pnpm --filter @parkio/mobile-v2 test:wp07
```

Does not redesign Mobile state ownership or navigation.

## CI changes

Workflow: `.github/workflows/mobile-ci.yml`

- Path filters include `frontend/apps/mobile-v2/**`
- New job `mobile-v2-checks`:
  - typecheck / lint
  - focused `test:wp07`
  - full Mobile-v2 unit tests
  - `expo-doctor` for `@parkio/mobile-v2`
- Legacy job `mobile-legacy-checks` preserves previous `@parkio/mobile` coverage
- Retries remain disabled; no unrelated repository jobs duplicated
- Mobile-v2 `expo-doctor` executes with `continue-on-error` because pre-existing
  Expo config / dependency drift warnings are documented debt (not Task 8 scope)

Frontend CI (`frontend-ci.yml`) continues to run WP-07 focused gates and
recursive workspace tests. Backend integration remains
`.github/workflows/backend-integration.yml`.

## Remaining verification gaps

Intentionally out of this gate / still debt:

- Full cross-service multi-container E2E (gateway + all services) beyond existing
  real-stack Playwright workflows
- Simulator / device UI automation for Mobile-v2
- Store signing / EAS release binary CI
- Broadening AbortSignal on moderation/analytics SDK methods (WP-07 debt)
- Replacing Jest `--forceExit` open-handle workaround on Mobile-v2
- Expo doctor warnings: `.expo` gitignore, app.json splash schema, native folder
  vs Prebuild sync fields, patch-level Expo package drift

## Non-claims

This document does **not**:

- claim Task 8 closes WP-03…WP-07
- claim the Parkio project is complete
- claim store publication readiness