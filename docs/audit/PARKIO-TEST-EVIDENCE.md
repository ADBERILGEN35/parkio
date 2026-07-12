# Parkio Audit — Test & Verification Evidence (Sprint A1)

**Date:** 2026-07-12
**Commit:** `f5efa0ddae1aadbbe3a8d9209f3587c62a392d7a` (master, `deployment-readiness`)
**Environment:** WSL2 (Linux 6.6.87.2) on Windows, repo on `/mnt/c` (slow I/O — durations are inflated vs CI), OpenJDK 21.0.11, Node 22.18.0, pnpm 9.15.0. **Docker daemon unreachable** (Docker Desktop not running) — all container-dependent verification is BLOCKED, not skipped-as-passed.

Pre-existing working-tree state (recorded before any audit action, preserved untouched):
modified `frontend/apps/web/src/{main.tsx, vite-env.d.ts, pages/LandingPage.test.tsx, pages/landing/{WaitlistForm.tsx, waitlistService.ts}}`, staged-new `frontend/apps/web/src/pages/landing/waitlistShared.ts` and `docs/releases/HOSTINGER-LANDING-DEPLOYMENT.md`, untracked `dist/`, `parkio-hostinger-landing.zip`. These are in-flight landing/waitlist changes by the maintainer, **not** produced by this audit, and they postdate the `v1.0.0-rc1` tag.

---

## 1. Commands executed

| # | Command | Working dir | Result | Duration | Notes |
|---|---------|-------------|--------|----------|-------|
| 1 | `git status --short` | repo root | PASS | <1s | Pre-existing state recorded above |
| 2 | `./gradlew build` | repo root | PASS | 19s | 110 tasks, **all UP-TO-DATE (cached)** — not accepted as evidence |
| 3 | `./gradlew cleanTest test` | repo root | PASS | 8s | Tests served **FROM-CACHE** — not accepted as evidence |
| 4 | `./gradlew test --rerun-tasks --no-build-cache` | repo root | **PASS** | **8m23s** | **712 tests, 0 failures, 0 skipped**, genuinely executed (76 tasks executed). Totals parsed from JUnit XML in `*/build/test-results/test/` |
| 5 | `corepack pnpm -r typecheck` | frontend/ | **PASS** | ~4 min | exit 0, all packages |
| 6 | `corepack pnpm -r lint` | frontend/ | **PASS** | ~9 min | exit 0; 5 warnings in `apps/web` (react-refresh/only-export-components), 0 errors |
| 7 | `corepack pnpm --filter @parkio/api-client test` | frontend/ | **PASS** | — | 37/37 tests, 6 files |
| 8 | `corepack pnpm --filter @parkio/web test` | frontend/ | **FAIL** | 272s | **256/257 pass; 1 fail**: `LandingPage > submits the real waitlist flow successfully` (5s test-budget timeout) |
| 9 | `vitest run src/pages/LandingPage.test.tsx` (isolated) | apps/web | **FAIL** | 79s | Same failure reproduces in isolation → **deterministic**, in the uncommitted waitlist rework (finding WEB-001) |
| 10 | `corepack pnpm --filter @parkio/mobile test -- --runInBand` | frontend/ | **FAIL (flake)** | — | 170/171 pass; `SmartReturnScreen` search-flow test failed in full suite (85.6s suite) |
| 11 | `jest SmartReturnScreen.test.tsx --runInBand` (isolated) | apps/mobile | **PASS** | 96s | 10/10 pass → full-suite failure is a **timing flake under slow I/O** (finding TEST-002) |
| 12 | `corepack pnpm --filter @parkio/web build` | frontend/ | **PASS** | 22s | Production Vite build succeeds from the current tree |
| 13 | `bash -n` on all 22 shell scripts (`scripts/*.sh`, `docker/scripts/*.sh`) | repo root | **PASS** | <5s | 22/22 syntax-clean |
| 14 | `corepack pnpm audit --prod --registry=https://registry.npmjs.org/` | frontend/ | **PASS w/ findings** | — | 1 moderate advisory: `uuid` <11.1.1 (GHSA-w5hq-g745-h8pq), 281 paths, all via `@expo/cli → xcode` build tooling (finding OSS-002). First attempt over the machine's default `http://` registry failed with HTTP 426 → finding DX-001 |
| 15 | `git check-ignore docker/.env && git ls-files docker/.env` | repo root | **PASS** | <1s | Confirms local `.env` (which contains a real dev private key) is ignored and untracked (finding SEC-003) |

### 1a. Post-snapshot re-check (audit day, ~22:00)

The maintainer continued working on the tree after evidence collection (additional uncommitted changes to backup/deploy scripts, compose files, `eas.json`, web Dockerfile, and the waitlist test/service files appeared). Re-run against the newer tree:

| # | Command | Result | Notes |
|---|---------|--------|-------|
| 16 | `vitest run src/pages/LandingPage.test.tsx` (current tree) | **PASS** | 7/7 — the WEB-001 failure was fixed by later maintainer changes; fix still uncommitted, and `parkio-hostinger-landing.zip` predates it. Full web suite not re-run after the fix. |

## 2. Commands BLOCKED (must be rerun on a Docker-capable host)

| Command | Why blocked | Rerun as |
|---------|-------------|----------|
| `./gradlew integrationTest -Pparkio.integrationTest.requireDocker=true` | No Docker daemon (Testcontainers needs Postgres/Kafka/MinIO/Redis) | Exactly as shown, repo root |
| `docker compose -f docker-compose.yml -f docker-compose.apps.yml config` | No Docker daemon | `docker/` dir |
| `docker compose -f docker-compose.yml -f docker-compose.hosted-beta.yml config` | No Docker daemon | `docker/` dir |
| `scripts/validate-hosted-beta-compose.sh` | Wraps `docker compose config` | repo root |
| `scripts/preflight-hosted-beta.sh` | Needs hosted env file + docker | repo root, against a real hosted env file |
| `corepack pnpm --filter @parkio/web e2e:real` (Playwright real-stack) | Needs running gateway stack | frontend/ with stack up |
| `scripts/restore-drill.sh` / backup scripts | Need Postgres containers | repo root |
| `scripts/chaos-compose-validation.sh` | Needs compose stack | repo root |
| k6 write-path load probe | Needs full stack; also deliberately out of audit scope (no load tests against shared envs) | see PERF-001 |

**None of the blocked items were converted to PASS.** CI history provides partial coverage: `backend-integration.yml` runs `integrationTest` with `requireDocker=true` on every relevant push, and `backup-restore-drill.yml` runs a real restore drill weekly (cron `23 4 * * 1`). CI results were not re-verified from this environment (no network assumption made about GitHub state).

## 3. Test inventory (what exists, by subsystem)

| Subsystem | Unit | Integration (Testcontainers, `@Tag("integration")`) | E2E | Other |
|-----------|------|-----------------------------------------------------|-----|-------|
| Backend (10 services + platform + dlt-redrive tool) | 712 executed this audit (per-service: gateway 21 files, auth 19, parking 23, notification 19, media 14, user 13, others 6–10) | Present in `src/test` behind tag; **BLOCKED locally**, gated in CI with fail-fast Docker probe | — | Migration-drill via restore workflow; chaos & observability validation workflows |
| Web (`@parkio/web`) | 257 vitest (47 files) incl. accessibility (jest-axe), security headers, PWA/SW policy, SEO/meta, docker-asset encoding | — | Playwright (mock) + `playwright.real.config.ts` real-stack suite (7/7 recorded PASS at FFINAL, not re-run here) | Lighthouse-style checks not present |
| api-client | 37 vitest | — | — | — |
| Mobile (`@parkio/mobile`) | 171 jest (33 suites) incl. token storage, refresh handler, RBAC parity, deep links | — | Device smoke **not executed** for RC1 (HB-01, finding MOB-001) | `verify:artifact` script for release APK |
| Shared packages (types/geo/ui/validation/config) | covered via consuming apps + own tests in workspace run | — | — | — |
| Infra/scripts | `bash -n` 22/22 (this audit); `test-preflight-hosted-beta.sh` fixture tests | — | — | Weekly CI restore drill; perf baseline recorded in `benchmarks/reports/p221` |

### Claim check vs certification docs

- FFINAL claimed "529 frontend unit tests PASS". This audit measured 257 (web) + 37 (api-client) + 171 (mobile) = **465** in the three main packages; the remainder plausibly sits in shared packages not re-run individually here. Order of magnitude confirmed; exact figure not re-verified.
- FINAL-BACKEND-CERTIFICATION's green-suite claim is **confirmed and current**: 712/712 on today's tree, executed without cache.
- The two red tests found today (**WEB-001** deterministic, in uncommitted work; **TEST-002** flake) postdate the certified tag and do not contradict the RC1 certification; they do mean **the current working tree is not release-clean**.

## 4. Highest-risk missing tests

1. **Account-deletion/erasure flow** — cannot be tested because it does not exist (PRIV-001).
2. **Redis-outage behavior of edge rate limiting and login lockout** (SEC-001/SEC-002) — a chaos assertion, scriptable with the existing chaos scaffolding.
3. **Write-path (media upload + spot create) load** under concurrency, including ClamAV saturation (PERF-001).
4. **Restore drill against the live hosted volume layout** (CI drill covers scripts, not a specific host).
5. **Mobile physical-device smoke** — login, camera capture upload, push receipt (MOB-001).
6. **Waitlist submit against a live gateway** from the built landing artifact (WEB-001).
