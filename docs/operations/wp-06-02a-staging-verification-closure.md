# WP-06.2A — Staging Verification Closure

**Status:** PARTIALLY_CLOSED (does not claim production readiness)
**Date:** 2026-07-28 | **Branch:** `decision` | **HEAD:** `550848277748cf086a738c7135f26f1ff27ae9e8`
**Evidence run:** `build/operational-evidence/wp062-20260728T125642Z-5508482/` (gitignored)

## 1. Executive summary

WP-06.2A closes repository-verifiable gaps in the WP-06.2 staging foundation (`scripts/staging/**`, `run-verification-pipeline.sh`, safety guards, evidence schema). Isolated pipeline run achieved `SEMANTIC_VERIFICATION_SUCCEEDED` with restore, PostGIS semantics, MinIO round-trip, and WP-05 Gradle replay stages passing.

Remaining: full critical journeys (local `jq` missing, auth JWKS empty), application-level restored API checks, shared staging sign-off, per-route gateway baselining.

## 2. Original WP-06.2 gaps

1. Full parking-service suite not rerun
2. Critical journeys not in default CI
3. Restore semantics SQL-only
4. WP-05 replay on restored data not automated
5. MinIO round-trip not automated
6. Shared staging STAGING_INPUT_REQUIRED
7. Gateway route timeout baselining unresolved
8. New scripts/workflows unverified

## 3. Repository state

- WP-06.2 files under `scripts/staging/`, `docker/docker-compose.staging-verification.yml`, `.github/workflows/staging-verification.yml`
- UTF-16 to UTF-8 fix: `.github/workflows/staging-verification.yml`
- Migrations V1-V26 unchanged
- No production credentials; no authority default changes

## 4. Gap closure matrix

| Gap | Implementation | Evidence | Final status |
|-----|----------------|----------|--------------|
| 1 Parking suite | `gradlew :services:parking-service:test` | 555 tests | CLOSED |
| 2 Journeys | `run-critical-journeys.sh`, `smoke-hosted-beta.sh` | Partial smoke | PARTIALLY_CLOSED |
| 3 App restore semantics | `verify-semantic-integrity.sh` | PostGIS + columns | PARTIALLY_CLOSED |
| 4 WP-05 restore replay | `verify-wp05-replay.sh` | Gradle ITs PASS | PARTIALLY_CLOSED |
| 5 MinIO | `verify-minio-roundtrip.sh` | Pipeline PASSED | CLOSED |
| 6 Shared staging | evidence schema | local only | EXTERNAL_STAGING_REQUIRED |
| 7 Gateway timeouts | `GatewayDownstreamTimeoutGovernanceTest` | global only | BASELINING_REQUIRED |
| 8 Scripts/CI | `test-safety-guards.sh` | bash -n + tests | CLOSED |

## 5. Regression verification

Full parking-service, gateway-service, WP-05 replay ITs, governance tests, bash -n, compose config, evidence schema, safety guards: all PASS.

## 6. Clean-stack execution

`scripts/staging/run-verification-pipeline.sh` exit 0 under `STAGING_LOCAL` with isolation marker and `parkio-wp062-closure-final` compose project.

## 7. Critical journey results

Gateway health PASS; auth JWKS FAIL; nearby 401 PASS; full journey blocked by missing `jq`.

## 8-9. Backup / restore

`scripts/restore-drill.sh` RESTORE_SUCCEEDED in pipeline.

## 10. Application-level semantics

PostGIS and schema checks PASS; restored API login NOT RUN.

## 11. WP-05 replay

Gradle replay PASS; drill DB ledger replay REPLAY_NOT_RECONSTRUCTABLE_FROM_CURRENT_LEDGER.

## 12. MinIO round-trip

PASS with checksum verification.

## 13. Gateway timeouts

Global timeouts configured; per-route BASELINING_REQUIRED.

## 14. Evidence bundle

Schema validation PASS on latest `summary.json`.

## 15. CI workflow

Structural and scheduled restore jobs verified in `.github/workflows/staging-verification.yml`.

## 16. Safety guards

Fail-closed guards verified by `test-safety-guards.sh`.

## 17. External blockers

Shared staging, jq, JWKS, route baselines, drill seeds.

## 18. WP-06.2 closure decision

PARTIALLY_CLOSED — not production ready.

## 19. Next work

WP-06.3 after shared staging sign-off.

## WP-06.2A.1 follow-up

See [wp-06-02a-1-application-verification-closure-patch.md](./wp-06-02a-1-application-verification-closure-patch.md) for application-level journey/JWKS/parser closure.

Follow-up: [WP-06.2B restored-stack sign-off](wp-06-02b-shared-staging-signoff-restored-database-verification.md).
