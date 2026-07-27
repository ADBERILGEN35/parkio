# v1.0.0-rc6 critical remediation evidence

Date: 2026-07-27

Scope: I11 web bootstrap white-screen, I12 PostgreSQL stale-session scheduler failure

Runtime policy: preparation only; no hosted-beta deployment or runtime mutation

This document deliberately contains no MapTiler key value, account identifier, password,
access token, refresh token, or authorization header. Presence checks report only
`PRESENT`, `EMPTY`, or `MISSING`.

## Release status

`v1.0.0-rc5` remains a failed release candidate and must not be moved or reused.
Hosted-beta remains on the operator-recorded mixed rollback state. The web baseline digest,
already-applied Flyway V17/V18 migrations, customer sessions, lifecycle timings, rollback
artifacts, registry authentication, and running hosted-beta containers were not changed by
this remediation.

The rc6 commit, tag, workflow run, and registry digests must be recorded only after every
mandatory local and CI gate has passed. Until those fields exist, rc6 is not deployable.

## I11 - root cause and complete build-time path

The browser-side validation in `frontend/apps/web/src/config/env.ts` is correct and is
unchanged from the working baseline. For `hosted-beta` or `production`,
`createFrontendConfig` requires both `VITE_API_BASE_URL` and `VITE_MAPTILER_KEY` during
module evaluation. An empty value therefore throws before React mounts.

The rc5 release workflow passed `VITE_MAPTILER_KEY` only when
`secrets.WEB_MAPTILER_KEY` was non-empty. When the repository secret was absent or empty,
the complete failure path was:

1. `.github/workflows/release.yml` omitted the Docker build argument.
2. `frontend/apps/web/Dockerfile` applied its empty `ARG VITE_MAPTILER_KEY` default.
3. The build-stage `ENV` exposed the empty value to `pnpm --filter @parkio/web build`.
4. Vite replaced `import.meta.env` in the emitted JavaScript with that empty value.
5. nginx served the otherwise-valid static artifact.
6. `src/config/env.ts` threw while the env module evaluated.
7. React never mounted, leaving every route white despite successful HTTP responses.

Hosted-beta Compose files pass the same values strictly as image *build* arguments:

- `docker/docker-compose.hosted-beta.yml` for local/hosted-beta-shaped builds;
- `docker/docker-compose.images.yml` for versioned image builds;
- `docker/docker-compose.azure-hosted-beta.yml` inherits the web service configuration.

They do not inject `VITE_*` values into a prebuilt running container. Changing Compose
runtime environment values cannot repair an already-built Vite bundle.

## I11 - remediation and prevention gates

The release workflow now:

- checks the hosted-beta/production value presence before invoking the image build;
- always passes the web build argument, so an empty value cannot silently fall back;
- reports variable names and presence only;
- runs the release-gate regression matrix;
- smoke-tests the actual produced image and requires a mounted login page with no
  uncaught bootstrap error.

The web Dockerfile now:

- runs `validate-build-env.mjs` before Vite;
- fails production-shaped builds whose required values are empty;
- runs `verify-bundle-env.mjs` after Vite against `dist`;
- refuses to export an artifact with a missing/empty value or wrong app environment.

The pre-build validator reads values inside Node. The Docker `RUN` instruction does not
expand them, preventing BuildKit progress output from echoing the value. The post-build
validator and image smoke report statuses only.

`smoke-image.mjs` starts the exact image in a disposable container, recursively inspects
the JavaScript chunks served by nginx, and launches Chromium against `/login`. It requires:

- the expected `VITE_APP_ENV`;
- non-empty required public configuration;
- non-empty `#root`;
- rendered login content and form controls;
- zero uncaught `pageerror` events.

Its fixture matrix proves detection of a missing key, an empty key, wrong build-argument
wiring, and a pre-React white-screen.

## Public MapTiler key security contract

`VITE_MAPTILER_KEY` is intentionally public client configuration, not a server secret.
Vite embeds every `VITE_*` value in JavaScript downloaded by the browser. GitHub Actions
secret storage keeps the value out of source and routine logs but cannot make the bundled
value confidential.

Before hosted-beta redeployment, the operator must confirm provider-side restrictions for
the approved Parkio hosted-beta/production domains and any intentional preview origins,
plus suitable usage limits. Rotate the key if the origin policy or exposure history
warrants it. No real key is committed to source or test fixtures.

## I12 - reproduction and exact cause

The rc5 JPQL reminder query combined an optional `Instant` with:

```text
(:startedAtOrBefore IS NULL OR parkingSession.startedAt <= :startedAtOrBefore)
```

Hibernate expands the two logical appearances into distinct PostgreSQL bind placeholders.
The placeholder used only as the operand of `IS NULL` has no type context. PostgreSQL must
type every placeholder while parsing and fails before JDBC binding can rescue it, producing
SQLState `42P18` (`could not determine data type of parameter`). H2 accepted the query and
therefore did not expose the production defect.

A disposable PostgreSQL 16/PostGIS 3.4 reproduction prepared the equivalent four-bind
predicate and returned SQLState `42P18` for the untyped `IS NULL` placeholder. The
container and data were discarded afterward.

A source audit of repository and scheduler queries found no other scheduler repository
query using a nullable named-parameter guard. SQL `IS NULL OR` occurrences elsewhere in
the repository are schema constraints or column-null checks, not nullable scheduler bind
parameters.

## I12 - query fix and regression coverage

The persistence adapter now selects one of two portable JPQL methods:

- without a start ceiling, the query omits that predicate entirely;
- with a start ceiling, the query includes
  `parkingSession.startedAt <= :startedAtOrBefore`.

Every parameter is consequently used in a typed comparison. No PostgreSQL-specific cast,
scheduler disablement, or lifecycle timing change is involved.

Adapter unit tests prove correct routing for absent and present thresholds. The established
PostgreSQL/PostGIS integration suite covers:

- reminder selection with the optional threshold absent and present;
- exact timestamp boundaries;
- no-candidate results;
- ordering and batch limits;
- stale completion selection;
- repeated scheduler ticks;
- first and second reminder transitions;
- stale auto-completion;
- unchanged scheduler-failure counter;
- idempotent outbox results on the repeated tick;
- existing manual/scheduler and confirm/scheduler concurrency races;
- Flyway application and V18 schema assertions.

## Observability verification

`ParkingSessionStaleCompletionJob` increments the Micrometer counter
`parking.sessions.scheduler.failed` only when a tick throws. Prometheus exports it as
`parking_sessions_scheduler_failed_total`.

`docker/prometheus/alerts.yml` contains `ParkingSessionSchedulerFailures` with:

```promql
increase(parking_sessions_scheduler_failed_total{service="parking-service"}[30m]) > 0
```

`docker/prometheus/prometheus.yml` attaches the static
`service="parking-service"` target label and includes
`/etc/prometheus/alerts.yml` in `rule_files`. The hosted-beta Compose configuration
single-file bind-mounts both files. Disposable `promtool` validation must pass for both the
configuration and rule file before rc6 approval.

No Alertmanager delivery claim is made: Alertmanager is disabled for the hosted-beta
rollout. After a later approved application rollout, the operator must narrowly recreate
only Prometheus so its single-file bind mount observes the new inode/content, then verify
rule loading. That operator action is deliberately not performed by this remediation.

## E2E locale correction and white-screen guard

The application defaults to Turkish and resolves the product locale from
`localStorage['parkio.locale']`; Playwright's browser `locale` setting affects `Intl` but
does not select the UI language. The real-stack tests make their English-copy contract
explicit by installing the product-supported English locale before app bootstrap in every
context, including contexts created directly with `browser.newContext()`.

The first real-stack scenario is now a mandatory bootstrap guard. It requires a non-empty
root, visible email and sign-in controls, and zero uncaught page errors. Assertions remain
exact; no selector was weakened to accept either language.

## Disposable real-stack E2E remediation (2026-07-27)

### Exact failed assertion (rc6 release-completion run)

The prior rc6 disposable run produced no surviving Playwright artifacts. Reproduction on a
fresh disposable stack identified the first authenticated map failure as the login helper
in `frontend/apps/web/e2e-real/real-stack.real.spec.ts`:

```text
await expect(page).toHaveURL(/\/map$/);
await expect(page.getByLabel('Search location')).toBeVisible();
```

Observed on the wrong production-shaped web image:

| Field | Value |
|---|---|
| Test | `logs in, restores from the HttpOnly refresh cookie, and logs out` |
| Expected | URL `/map`, then accessible name `Search location` |
| Actual | URL remained `/login` with alert `Login failed. Please try again.` |
| Browser network | `POST` to `https://api.fixture.invalid/api/v1/auth/login` → `net::ERR_NAME_NOT_RESOLVED` |
| Map assertion | Never reached |

A secondary defect blocked the upload scenario after login was fixed: the create-spot wizard
exposes `Continue`, but the suite clicked `/next/i`, timing out on step 1 of 4.

### Failure classification

| Stage | Classification |
|---|---|
| Login stuck on `/login` with unresolved API host | `TEST_SETUP_DEFECT` |
| Upload wizard `/next/i` timeout | `TEST_EXPECTATION_DEFECT` |

### Root cause

1. **Wrong disposable web image.** `parkio/web:rc6-remediation-smoke` baked
   `VITE_API_BASE_URL=https://api.fixture.invalid/api/v1` from the smoke-image fixture
   matrix. That image passes nginx/Chromium mount smoke but cannot authenticate against the
   local gateway at `http://localhost:8080/api/v1`.
2. **Stale wizard selector.** Upload step navigation uses the accessible name `Continue`
   (`UploadPage.tsx` / `smoke.spec.ts` contract). The real-stack helper still targeted
   `/next/i`.

English locale pinning (`localStorage['parkio.locale'] = 'en'`) was already correct and is
retained; it was not the primary blocker once the API host was fixed.

### Remediation

| Change | Purpose |
|---|---|
| Build `parkio/web:rc6-disposable-e2e` with `VITE_API_BASE_URL=http://localhost:8080/api/v1`, `VITE_APP_ENV=hosted-beta`, non-empty `VITE_MAPTILER_KEY` | Production-shaped disposable web that talks to the local gateway |
| Preflight test `browser can reach PARKIO_REAL_API_BASE_URL from the web origin` | Fails fast when the served bundle cannot reach the configured gateway (JWKS fetch + zero `/api/v1/` request failures) |
| Replace `/next/i` with `Continue` in `fillCreateSpotWizard` | Align with product copy and mocked E2E |
| `fileURLToPath` in `smoke-image.test.mjs` / `verify-bundle-env.test.mjs` | Cross-platform release-gate execution on Windows workstations |

No assertion was weakened. No hosted-beta data or runtime was touched.

### Files changed (E2E scope)

- `frontend/apps/web/e2e-real/real-stack.real.spec.ts`
- `frontend/apps/web/scripts/smoke-image.test.mjs`
- `frontend/apps/web/scripts/verify-bundle-env.test.mjs`

Disposable image evidence (local only, not a release digest):

| Image | Role |
|---|---|
| `parkio/web:rc6-disposable-e2e` | Correct API host for local disposable E2E |
| `parkio/web:rc6-remediation-smoke` | Smoke-fixture API host — must not be used for real-stack E2E |

### Disposable stack evidence

| Check | Result |
|---|---|
| Gateway health | `http://localhost:8080` stack healthy (PostgreSQL/PostGIS, Redis, Kafka, ClamAV, MinIO, all services) |
| Web container | `parkio-web-rc6-e2e` on port 5173 from `parkio/web:rc6-disposable-e2e` |
| Seed accounts | `scripts/seed-real-e2e.sh --update-passwords` (USER + MODERATOR + ADMIN) |
| API login (PowerShell) | `POST /api/v1/auth/login` → 200 |
| CORS preflight | `Origin: http://localhost:5173` allowed with credentials |
| Image smoke | `smoke-image.mjs` OK — `VITE_API_BASE_URL`/`VITE_MAPTILER_KEY` PRESENT, `#root` mounted, zero page errors |
| ACTIVE parking sessions (USER + MODERATOR) | 0 rows in `parking_sessions` after cleanup |
| Post-run cleanup | `scripts/cleanup-real-e2e.sh` removed 1 spot, 1 media file, related rows; disposable web container removed |

### Full `e2e:real` results (final pass)

Command: `PARKIO_REAL_E2E=true` + seeded `PARKIO_REAL_*` vars, Playwright `playwright.real.config.ts`,
base URL `http://localhost:5173`, API `http://localhost:8080/api/v1`.

| Result | Count |
|---|---|
| Passed | 13 |
| Failed | 0 |
| Skipped | 1 (`verifies email when a real verification token is supplied` — `PARKIO_REAL_E2E_VERIFICATION_TOKEN` unset) |
| Not run | 0 |
| Duration | 21.9s |

Passed scenarios include bootstrap guard, API-base preflight, auth routes, registration, login
+ refresh cookie + logout, stale-token reload, logout-all, media upload READY + spot create,
map search/detail, USER moderator denial, MODERATOR queue, ADMIN analytics.

No unexpected page errors or `/api/v1/` network failures were observed on the final pass.

### Remaining risks

- Operators must build or select a disposable web image whose baked `VITE_API_BASE_URL`
  matches `PARKIO_REAL_API_BASE_URL`. Smoke-fixture images remain valid for I11 gates only.
- `PARKIO_REAL_E2E_START_WEB=true` is unreliable on Windows when `pnpm` is not on the
  Playwright `webServer` PATH; prefer the production nginx image on port 5173.
- Verification-token email test remains intentionally skipped without operator-supplied token.
- Repository-root Docker context size remains a release-duration risk (documented above).

## Verification record

The final verification table and immutable metadata are populated only from completed
commands and registry evidence:

| Gate | Result |
|---|---|
| Web typecheck | PASS |
| Web lint | PASS (zero errors; five existing warnings) |
| Web full unit suite | PASS (85 files, 625 tests) |
| Pre/post-build release-gate unit tests | PASS |
| Missing and explicit-empty Docker builds | PASS (both rejected before artifact export) |
| Hosted-beta-shaped local image build | PASS |
| Built-image bundle inspection | PASS (required statuses `PRESENT`) |
| Built-image Chromium mount/login smoke | PASS (root mounted, zero page errors) |
| Smoke failure-mode fixture matrix | PASS |
| Playwright real-stack bootstrap/locale guard | PASS against disposable image |
| Playwright real-stack full suite (`e2e:real`) | PASS (13 passed, 1 skipped verification-token, 21.9s) |
| Release-gate script tests (Windows) | PASS (14/14 after `fileURLToPath` fix) |
| Parking scheduler persistence regression (affected) | PASS |
| Parking unit suite | PASS (34 suites, 289 tests) |
| Parking PostgreSQL/PostGIS focused scheduler regression | PASS |
| Parking complete PostgreSQL/PostGIS integration suite | PASS (6 suites, 45 tests; WSL JVM dynamic attachment enabled) |
| Repeated scheduler tick/failure-counter check | PASS |
| Flyway V18 schema validation | PASS |
| Local parking image build (`linux/amd64`) | PASS |
| Local parking image + fresh PostgreSQL/PostGIS smoke | PASS (V18, 43 ticks, failure counter 0) |
| Prometheus config and alert rule `promtool` parse | PASS |
| Release workflow syntax | PASS (`actionlint`; only pre-existing SC2129 style suppressed) |
| Affected frontend architecture guardrails | PASS |
| Full release workflow | PASS — run `30248167192` (`workflow_dispatch`, `publish_images=true`, tag `v1.0.0-rc6`) |

## Phase R3 — release cut gate (2026-07-27)

| Gate | Result |
|---|---|
| GitHub CLI authentication | PASS — account `ADBERILGEN35`, scopes include `repo` and `workflow` |
| Repository Actions secrets | `total_count: 0` |
| Environment `release` secrets | `WEB_MAPTILER_KEY` **PRESENT** (updated 2026-07-27T07:55:20Z) |
| Images job secret wiring | `environment: release` on the `images` job so `secrets.WEB_MAPTILER_KEY` resolves |

Initial R3 probe found the secret missing and stopped with `MAPTILER_CONFIGURATION_BLOCKED`.
Operator added `WEB_MAPTILER_KEY` to the release environment. The images job previously did not
use that environment, so the secret would still have been empty at web build time; the
minimal wiring fix attaches `environment: release` to the images matrix.

## Phase R4 — cut, publish, verify (complete)

| Gate | Result |
|---|---|
| MapTiler precondition | PASS — release-environment secret PRESENT; images job binds `environment: release` |
| Focused remediation commit | PASS — `220a25cbfe4b4131557385f710fcf8f0c3790bd5` |
| Annotated tag `v1.0.0-rc6` | PASS — peels to `220a25cbfe4b4131557385f710fcf8f0c3790bd5` |
| Push commit + tag | PASS — `origin/master` and `refs/tags/v1.0.0-rc6` |
| Release workflow (publish) | PASS — https://github.com/ADBERILGEN35/parkio/actions/runs/30248167192 |
| Web MapTiler gate in CI | PASS — `Required web build configuration present for VITE_APP_ENV=hosted-beta` |
| Web image Chromium smoke in CI | PASS — `smoke-image: OK` against digest-pulled image |
| Registry digest + OCI label verification | PASS — every image job’s `Verify published image in registry` step succeeded (version tag digest == sha tag digest; `org.opencontainers.image.revision` == release commit; `org.opencontainers.image.version` == `v1.0.0-rc6`; platform `linux/amd64`) |
| Draft GitHub Release | PASS — draft `Parkio v1.0.0-rc6` with SBOM assets |
| Hosted-beta deploy | NOT PERFORMED (by design) |

Workstation-side `docker buildx imagetools inspect` against GHCR returned HTTP 403 because the
active `gh` token lacks `read:packages`. Digests below were taken from the successful CI
registry-verify / smoke steps that already pulled those digests from GHCR on the runner.

## rc6 immutable metadata

Release commit / revision: `220a25cbfe4b4131557385f710fcf8f0c3790bd5`  
Version: `v1.0.0-rc6`  
Platform: `linux/amd64`  
Registry: `ghcr.io/adberilgen35/parkio/<service>`  
Tags per service: `v1.0.0-rc6` and `sha-220a25cbfe4b4131557385f710fcf8f0c3790bd5`

| Service | Immutable digest |
|---|---|
| ai-validation-service | `sha256:c16bfe790998d2f4c70a392435fd311e21e1c1cbd9af43e7b198452066f4cd8d` |
| analytics-service | `sha256:381929e7480ecc5a9f095129c58bb3e18f7b8431dfcef5a526fae079d8357a04` |
| auth-service | `sha256:5979de9996f89df3eaba22ce4e5e1c14163b3ee7f5552390661a091671678eda` |
| gamification-service | `sha256:354ee7cb148ca8872c2301e6619624a6be2e652e391e8f5273e4d5db4fdf220d` |
| gateway-service | `sha256:57d660b5f60390c0d61dff9c30f0bbabee33cd7c6e12835eac8730df7e9038ec` |
| media-service | `sha256:1e7e88729bfffe0f28e3793960cc495804b19fe6fe6beecd183d8b07a510c0bf` |
| moderation-service | `sha256:23a9c1398bb212e929a167de9bf1f61537b678d2c05a953f3a3afc76f950bcd1` |
| notification-service | `sha256:7cfa20d3d0905b8ec03ae704a5c274f3e53c2cf209e62eb8f91f6812bac25ce6` |
| parking-service | `sha256:c84ae5fb3692f779b4b43751828dc0ac5a5d618ef10e4097bab1cc362ba55881` |
| user-service | `sha256:324b480fb96ae227c7e45ff6c33e4ffae5e8ab8dac835d78c5c04bb5009d462b` |
| web | `sha256:b1d72118506a195330dffe5d6f769f4bcf6b49ba808423edb981fe787410bb66` |

Decision: `RC6_READY_FOR_HOSTED_BETA`

The local smoke image is disposable evidence only. Its local image identifier is not a
release digest and must not be used for deployment.

The first parking image build from the repository-root context was canceled after Docker
spent approximately ten minutes transferring 1.98 GB. A backend-only disposable copy of
the exact working sources reduced the context to 8.58 MB, and the unchanged Dockerfile
built successfully. This exposes a release-duration/context-hygiene risk outside I11/I12;
rc6 does not redesign the shared root build context.

## Rollout and rollback implications

The later hosted-beta rollout must be a separate, explicitly approved operator action.
Before rollout, verify the recorded rc6 digest for every image, confirm OCI version,
revision, and `linux/amd64`, independently smoke the web digest, and run the parking digest
against disposable PostgreSQL through Flyway V18 and repeated scheduler ticks.

During a later canary, preserve the foreign customer ACTIVE session, normal lifecycle
timings, existing rollback artifacts, and exact baseline digests. Do not use temporary
`PARKIO_PARKING_SESSION_*` overrides. Recreate only services selected by the approved
manifest, then narrowly recreate Prometheus for the single-file mount and verify rules.

Flyway V17/V18 are already applied and this remediation adds no migration. A web rollback
can return directly to the recorded baseline immutable digest. Parking-service rollback
must use the recorded compatible baseline/rc5 digest without undoing V17/V18. Database
downgrade, customer data modification, tag movement, and use of mutable tags are forbidden.
