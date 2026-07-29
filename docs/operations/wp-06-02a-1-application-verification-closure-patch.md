# WP-06.2A.1 — Application-Level Staging Verification Closure Patch

**Status:** Closure patch (does not claim production readiness)
**Date:** 2026-07-28
**Branch:** `decision`
**HEAD:** `550848277748cf086a738c7135f26f1ff27ae9e8`

## 1. Executive summary

WP-06.2A.1 closes locally solvable application-level gaps left after WP-06.2A:

- Removes undeclared `jq` dependency from smoke/journey scripts (Python 3 JSON helper).
- Treats HTTP 200 with empty/invalid JWKS as failure; validates `keys`, `kid`, `kty`, `alg`.
- Hardens `run-critical-journeys.sh` with bounded stages, sanitized evidence, fail-closed mandatory stages.
- Adds optional restored-DB API verification (`verify-restored-application-apis.sh`) that restores into `*_drill_*` databases, repoints JDBC, and re-runs journeys.
- Extends evidence status with `APPLICATION_VERIFICATION_SUCCEEDED`.
- Keeps per-route gateway timeouts **BASELINING_REQUIRED** and shared staging **EXTERNAL_STAGING_REQUIRED**.

## 2. Scope and non-goals

In scope: parser, JWKS/auth contract, synthetic journeys, restored JDBC isolation, evidence/CI/docs.
Out of scope: WP-06.3, deployment automation, authority expansion, migration rewrites, SLO approval.

## 3. Initial repository state

Branch `decision` @ `550848277748cf086a738c7135f26f1ff27ae9e8` with large dirty WP-05/06 worktree. No destructive git ops.

## 4. Remaining WP-06.2A gaps (pre-patch)

| Gap | Prior status |
|-----|--------------|
| Critical journeys incomplete | PARTIALLY_CLOSED |
| JWKS empty body in smoke | FAILED (see root cause) |
| Undeclared `jq` | FAILED |
| Restored API login/read/nearby | NOT_RUN |
| Media app verification | EXTERNAL_STAGING_REQUIRED possible |
| Gateway route baselining | BASELINING_REQUIRED |
| Shared staging | EXTERNAL_STAGING_REQUIRED |

## 5. Parser dependency root cause and fix

**Root cause:** `scripts/smoke-hosted-beta.sh` called `jq -e` / `jq -r`. When `jq` was absent, the JWKS branch treated parse failure as "empty body", then later aborted on `jq: command not found`.

**Fix:** `scripts/staging/lib/json-helper.sh` (`json_require_python`, `json_get`, `json_assert_jwks`, `json_array_contains_id`). Smoke and journeys source this helper. CI structural job runs `json_require_python`.

## 6. JWKS/auth root cause and fix

**Architecture (repository truth):** RS256 + JWKS — `JwtService` / `JwksController` at `GET /api/v1/auth/.well-known/jwks.json`; gateway `PARKIO_AUTH_JWKS_URI`. Not HS256.

**Root cause of prior "empty body":** primarily missing `jq` false negative; empty body is now an explicit reject via `json_assert_jwks`.

**Fix:** assert non-empty JSON, `keys.length >= 1`, and required key fields. Do not invent JWKS if architecture were HS256 (N/A).

## 7. Synthetic restore fixture design

- Auth: `scripts/seed-real-e2e.sh` — verified ACTIVE user `@real-e2e.parkio.local`, BCrypt via pgcrypto.
- Parking/media: created through public APIs in `run-critical-journeys.sh` (upload PNG → create spot).
- Restored path: backup → restore into `*_drill_wp062a1-*` → JDBC repoint via `POSTGRES_*_DB` → journeys with `PARKIO_JOURNEY_STORE_MODE=restored_drill`.

## 8–12. Journey results

Recorded at execution time under `build/operational-evidence/*/critical-journeys/`. See final report sections for PASS/FAIL/EXTERNAL.

## 13. Gateway route timeout evidence

Routes inventoried: `media-service` (`/api/v1/media/**`), `ai-validation-service` (`/api/v1/ai-validations/**`).
Global timeouts unchanged. Local media upload duration samples written to `gateway-media-timeout-sample.json` with `baseliningStatus=BASELINING_REQUIRED`. No policy change from one sample.

## 14. Evidence changes

Schema status enum adds `APPLICATION_VERIFICATION_SUCCEEDED`. Mandatory journey failure prevents overall success. Tokens/passwords excluded from evidence JSON.

## 15. CI workflow changes

PR: structural only (bash -n, python3 helper, governance tests).
Scheduled/dispatch: restore + MinIO; journeys/restored APIs only when explicitly requested (`run_journeys` / `run_restored_apis`). Full app stack may be **INFRA_INPUT_REQUIRED** on GitHub-hosted runners.

## 16–17. Tests and blockers

See final response. Shared staging and runner capacity remain external.

## 18. WP-06.2 / WP-06.2A closure decision

Updated after executed evidence. Local application verification complete only when mandatory journeys PASS. Production readiness remains blocked.

## 19. WP-06.3 eligibility

`ELIGIBLE_AFTER_SHARED_STAGING_SIGN_OFF` — not started.

## Follow-up: WP-06.2B

Restored-database full application stack verification and shared-staging sign-off are tracked in [WP-06.2B](wp-06-02b-shared-staging-signoff-restored-database-verification.md).
