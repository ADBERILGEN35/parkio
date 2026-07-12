# AZ2 Blocker Resolution Report

**Status:** implementation complete; Docker/Azure runtime proof pending.

## Worktree preservation

The start-of-sprint status was recorded before editing. No unrelated file was reverted, deleted, staged, or deployed. A later status refresh also showed existing/concurrent Hostinger outputs (`dist/hostinger/`, the ZIP and release note), repository audit documents, and landing refactor files outside the AZ2 edit list. Those files were preserved and are not claimed as sprint output. Existing AZ1 Azure documents were edited only where the implemented profile changed their operator instructions.

## Resolved blockers

| Blocker | Resolution | Evidence |
|---|---|---|
| waitlist success timeout | replaced submit-time dynamic import with static service import; retained real MSW/Axios boundary | targeted suite 11/11; success state 197 ms |
| duplicate/static behavior | added duplicate accepted and API-disabled/no-request regressions | waitlist service + landing tests |
| mobile hostname drift | EAS preview, artifact gate, and mobile release guide now use `https://api.parkio.dev/api/v1` | config regression and repository search |
| manual observability shutdown | Azure overlay plus explicit service list excludes four services | static profile test |
| tracing | forced false per JVM and required by Azure preflight | overlay/static test |
| profile lifecycle drift | manifests record profile; rollback/restore reject mismatches | deploy/backup helpers |
| waitlist backup gap | gateway PostgreSQL added to dump, verify, drill, restore, and manifests | ten-database script inventory |

## Waitlist root cause

`WaitlistForm` loaded `waitlistService` dynamically inside `handleSubmit`. The cold Vite/Vitest transform/import remained pending long enough to hit Vitest's five-second test budget before the request/state transition, even though the direct service test was fast. The form is already contained in the route-split landing surface; submit-time code splitting added an asynchronous failure/race boundary without protecting an application boundary.

The fix statically imports the service. It does not mock away the request: MSW still intercepts the shared Axios client call and the test still requires the rendered success state. No timeout or assertion was weakened.

## Isolation guarantees

- `parkio.dev` and the Hostinger packaging procedure were not changed.
- `VITE_WAITLIST_INTAKE_MODE=disabled` still renders no email field/button and makes zero waitlist requests.
- Normal hosted-beta defaults to its existing four-file stack.
- Azure requires the explicit `azure-hosted-beta` selector and fifth overlay.
- Unsupported profiles fail closed.

## Verification boundary

Static profile, shell syntax, preflight fixtures, waitlist tests, and hostname assertions are locally verifiable. Docker Compose rendering, image manifest inspection, deploy/rollback dry-runs, and live backup/restore are not PASS until run with Docker Compose v2.24.4+, `jq`, and a populated non-placeholder Azure env.

Exact target commands are maintained in [AZURE-DEPLOYMENT-PROFILE.md](AZURE-DEPLOYMENT-PROFILE.md) and [AZURE-DEPLOYMENT-RUNBOOK.md](AZURE-DEPLOYMENT-RUNBOOK.md).

## Verification record

| Command | Directory | Result | Duration / evidence |
|---|---|---|---|
| targeted landing + waitlist service Vitest | `frontend/` | PASS | 11/11; success transition 197 ms in focused run |
| full `@parkio/web` test | `frontend/` | PASS | 258/258; 309.81 s wall |
| `@parkio/api-client` test | `frontend/` | PASS | 37/37; 23.77 s |
| web typecheck | `frontend/` | PASS | 19.79 s |
| web lint | `frontend/` | PASS | 0 errors, 5 existing warnings; 49.58 s |
| web build | `frontend/` | PASS | production build; 59.39 s; existing large MapLibre chunk warning |
| web build with Hostinger intake disabled | `frontend/` | PASS | `VITE_WAITLIST_INTAKE_MODE=disabled`; 42.51 s; no package/deploy command run |
| mobile config Jest | `frontend/` | PASS | 3/3; 73.01 s Jest time |
| mobile typecheck | `frontend/` | PASS | 75.31 s |
| mobile lint | `frontend/` | PASS | 0 errors, 6 existing warnings; 220.35 s |
| full mobile Jest, concurrent run | `frontend/` | FAIL | 173/174; Smart Return result timeout; 428.53 s |
| affected Smart Return file, isolated | `frontend/` | PASS | 10/10; failed scenario 947 ms; 126.28 s wall |
| full mobile Jest, isolated rerun | `frontend/` | PASS | 174/174; 250.916 s Jest, 278.96 s wall |
| backend `./gradlew test --no-daemon` | repo | PASS | build successful; 22.87 s; tasks up to date |
| shell syntax (`bash -n`, `sh -n`) | repo | PASS | changed/all operator scripts parse |
| preflight regression | repo | PASS | 38 assertions, including valid Azure profile |
| Azure static profile test | repo | PASS | service count, exclusions, tracing, memory, hostname, backups, fail-closed profile |
| Azure template preflight | repo | EXPECTED FAIL | 20 unreplaced secret/operator placeholders; template correctly cannot deploy |
| Azure five-file Compose render | repo | BLOCKED | Docker WSL integration/daemon unavailable |
| deploy/rollback dry-run | repo | BLOCKED | render requires Docker; real env and `jq` unavailable |
| backup/restore dry-run | repo | BLOCKED | `jq` and populated env unavailable; live verification also requires containers |
| image manifest `linux/amd64` inspection | repo | BLOCKED | Docker/buildx unavailable |

The first Gradle attempt was sandbox-blocked by a read-only Gradle cache lock; the approved rerun using the existing cache passed. The concurrent mobile run's single timeout was not cleared by changing assertions or timeouts: the exact file passed alone and all 174 tests passed when the full suite was rerun without the concurrent web suite.

## Readiness decisions

| Area | Decision | Evidence / remaining condition |
|---|---|---|
| 1. Waitlist code | **GO** | focused and full web suites pass; real request boundary retained |
| 2. Static Hostinger landing | **GO** | disabled mode renders no intake controls and makes zero API calls; packaging files untouched |
| 3. API hostname consistency | **GO** | web/mobile Azure config uses `api.parkio.dev`; retired host appears only in negative guards/docs |
| 4. Mobile hosted beta | **CONDITIONAL GO** | config/typecheck/lint and 174/174 tests pass; require the built EAS artifact URL gate |
| 5. Azure Compose profile | **CONDITIONAL GO** | deterministic static contract passes; rendered model blocked |
| 6. Reduced 16-GiB runtime | **NOT VERIFIED** | 14-GiB configured total; startup/RSS/OOM/write-load evidence required on D4as_v5 |
| 7. Docker render | **NOT VERIFIED** | rerun five-file validation with Compose v2.24.4+ |
| 8. Deployment dry-run | **NOT VERIFIED** | populated env, Docker, and `jq` required |
| 9. Backup | **CONDITIONAL GO** | all ten DBs statically covered; real encrypted dump/MinIO copy required |
| 10. Restore | **NOT VERIFIED** | profile enforcement is static; ten-DB disposable drill required |
| 11. Closed Azure beta | **CONDITIONAL GO** | create no resources until blocked target checks pass |
| 12. Public beta | **NO-GO** | single VM/RF1/no HA-PITR and broader production blockers remain |

## AMD64 target verification

The overlay requests `linux/amd64` for every targeted service, which makes a missing architecture fail rather than silently selecting another platform. Registry support is still unverified. Run on the Azure VM:

```bash
PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta \
PARKIO_ENV_FILE=docker/.env.azure-hosted-beta \
./scripts/validate-hosted-beta-compose.sh

docker compose --env-file docker/.env.azure-hosted-beta \
  -f docker/docker-compose.yml -f docker/docker-compose.apps.yml \
  -f docker/docker-compose.images.yml -f docker/docker-compose.hosted-beta.yml \
  -f docker/docker-compose.azure-hosted-beta.yml config --images \
  | sort -u | xargs -n1 docker buildx imagetools inspect
```

Confirm `linux/amd64` for PostgreSQL/PostGIS, Redis, Confluent Kafka, MinIO/mc, ClamAV, Prometheus/Grafana/exporters, Caddy/nginx, Eclipse Temurin, and Node build bases before deploy.
