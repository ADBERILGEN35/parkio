# Sprint 2.3 WP-01 Baseline

This file records the verification contract for WP-01. It does not define SDK behavior and does not supersede the Sprint 2.3 Architecture Freeze Document.

## Guarded boundaries

- Web (`apps/web/src`) and Mobile-v2 (`apps/mobile-v2/src` and `apps/mobile-v2/app`) MUST use the `@parkio/api-client` package entrypoint for Parkio HTTP integration. Direct HTTP dependencies, `fetch`, `XMLHttpRequest`, and deep api-client imports fail the guardrail.
- `packages/api-client/src` MUST remain platform-neutral. It MUST NOT import application code, React, React Native, Expo, React Query, Zustand, or Node-only modules.
- `packages/types` and `packages/validation` MUST NOT depend on `@parkio/api-client`.
- The current api-client package entrypoint and public symbols are inventoried in `public-exports.baseline.json`. Unreviewed drift fails verification; an approved later work package that intentionally changes the frozen SDK surface MUST update the inventory in the same change.
- Backend source, build topology, Docker definitions, and the OpenAPI architecture declaration are protected by the backend-scope check for this frontend-only sprint.

The application scan intentionally covers production application source. Generated output, dependencies, test reports, and the Web service worker are outside the direct-HTTP rule because they are not Web or Mobile-v2 application integration code.

## Commands

Run from `frontend/`:

- `pnpm guardrails` — scans dependency boundaries, direct HTTP usage, and the public export inventory.
- `pnpm guardrails:test` — certifies the detection rules with deterministic Node tests.
- `pnpm guardrails:lint` — applies the repository ESLint baseline to the WP-01 tooling.
- `pnpm guardrails:backend` — rejects protected backend changes in the current worktree.
- `pnpm guardrails:backend -- --base <sprint-base-ref>` — rejects protected backend changes across the complete sprint diff.
- `pnpm bundle:measure` — measures raw and gzip JavaScript bytes in `apps/web/dist` after a production build.
- `pnpm bundle:measure -- <relative-path>` — applies the same measurement to another generated JavaScript artifact.

Frontend pull requests fetch complete Git history before running the backend-scope check against the pull request base SHA. This ensures the base commit is available and prevents a Sprint 2.3 client change from silently carrying a protected backend change while keeping backend-only workflows independent.

## Bundle-size baseline methodology

Bundle measurements MUST be taken from a clean production build using the pinned lockfile and supported Node/pnpm versions. Record both raw and gzip bytes from `pnpm bundle:measure`; compare like-for-like build targets and build configuration. WP-01 establishes measurement, not a size budget. A size threshold requires the frozen change-control process rather than an undocumented CI limit.

Reference measurement from the 2026-07-22 WP-01 production build (Node 22.18.0, pnpm 9.15.0): 48 JavaScript files, 1,739,292 raw bytes, and 511,282 gzip bytes. The measurement is diagnostic; hashed filenames are expected to change between builds.

## Pre-existing repository failures

The following failures were observed on 2026-07-22 before WP-01 hardening. They are outside WP-01, were not introduced by its architecture tooling, and remain visible rather than being suppressed:

- Workspace typecheck fails in Mobile-v2 at `claimedRegionRace.test.ts` lines 14-16 (`TS2556`) and `ClaimedRegionAnnotator.tsx` line 185 (`StyleSheet.absoluteFillObject`).
- Workspace lint fails in Mobile-v2 at `ClaimedRegionAnnotator.tsx` lines 57 and 98 because React refs are read during render; existing warnings are also reported.
- Workspace tests include existing failures in the legacy Mobile `home.session`, `notifications`, and `staffRoutes` suites and a Mobile-v2 leaderboard timeout.
- The worktree-only form of `pnpm guardrails:backend` reports protected backend changes already present in the shared repository worktree. CI uses the sprint base comparison to isolate committed Sprint 2.3 scope.

These failures MUST be resolved by their owning work, not hidden or changed by WP-01.

## Baseline verification

WP-01 verification requires `pnpm guardrails`, `pnpm guardrails:test`, `pnpm guardrails:lint`, a base-referenced `pnpm guardrails:backend`, `pnpm build`, and `pnpm bundle:measure` to pass from `frontend/`. Repository-level `git diff --check` MUST also pass. Workspace typecheck, lint, and tests remain required repository gates; any pre-existing failures MUST be reported separately from failures introduced by WP-01.
