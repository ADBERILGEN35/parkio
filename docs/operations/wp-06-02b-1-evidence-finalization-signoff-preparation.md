# WP-06.2B.1 — Verification Evidence Finalization & Human Sign-off Preparation

**Status:** COMPLETE for evidence finalization / sign-off package preparation  
**Date:** 2026-07-29  
**Branch:** `decision`  
**HEAD at evidence run:** `550848277748cf086a738c7135f26f1ff27ae9e8`  
**Evidence run:** `wp062b-20260728211226`  
**Does not claim production readiness. Does not start or approve WP-06.3.**

## 1. Executive summary

WP-06.2B.1 revalidated the successful LOCAL_REPRESENTATIVE restored-stack evidence
bundle `wp062b-20260728211226`, reran post-change regression suites, documented the
pre-execution patch failure honestly, prepared a run-specific human sign-off package
with `signOffDecision=NOT_REVIEWED`, and classified shared staging as
`SHARED_STAGING_REQUIRED` / `INFRA_INPUT_REQUIRED` (no authorized shared platform
executed). Automation remains capped at `SIGNOFF_REQUIRED`. WP-06.3 stays
`NOT_ELIGIBLE`.

## 2. Scope and non-goals

**In scope:** evidence consistency, post-change regression recording, patch disclosure,
cleanup revalidation (to the extent Docker allows), sign-off package, roadmap/docs.

**Out of scope:** WP-06.3, production deploy, auto-approval, authority/default changes,
migration rewrites, fake shared-staging claims, destructive Git/Docker cleanup of
developer resources.

## 3. Repository state

| Item | Value |
|------|-------|
| Branch | `decision` |
| HEAD | `550848277748cf086a738c7135f26f1ff27ae9e8` |
| Worktree | Dirty (WP-05/WP-06 artifacts preserved; not reset) |
| Staged | None at audit start |
| Migrations V20–V26 | Untracked additive migrations present; no rewrite of V1–V19 |
| Generated evidence | Ignored via `.gitignore` → `build/operational-evidence/` |

## 4. Evidence run inventory

Root: `build/operational-evidence/wp062b-20260728211226/`

Key artifacts: `environment-manifest.json`, `source-manifest.json`,
`backup-manifest.json`, `restore-manifest.json`, `datasource-repoint-report.json`,
`service-readiness.json`, `critical-journeys-restored/summary.json`,
`restored-*-journey.json`, `post-restore-write-report.json`,
`source-restore-comparison.json`, `wp05-defaults-report.json`,
`gateway-route-baseline.json`, `cleanup-report.json`, `shared-staging-summary.json`,
`checksums/`, `logs/`.

WP-06.2B.1 additions: `evidence-consistency-audit.json`, `summary.json` (+ amendment),
`critical-journeys*/summary.schema-amended.json`, `shared-staging-signoff.md`,
`initial-patch-failure-disclosure.json`, `cleanup-revalidation.json`,
`regression-rerun-20260729084805/`.

## 5. Evidence consistency audit

Command: `python3 scripts/staging/lib/wp062b1-evidence-consistency-audit.py`

Result: **PASSED** (`evidence-consistency-audit.json`, `ok_count=26`, `issues=[]`).

Confirmed fields include: run ID/dir match; commit `550848277748cf086a738c7135f26f1ff27ae9e8`;
`executionClassification=LOCAL_REPRESENTATIVE`; `sharedStagingLabel=false`;
`status=SIGNOFF_REQUIRED`; `signOffDecision=NOT_REVIEWED`; `wp063Eligible=false`;
source vs restore DBs differ with `*_drill_*` pattern; MinIO src/rst differ;
Kafka `ISOLATED_BROKER`; Redis `EMPTY_REBUILT`; datasource `PASSED` all JDBC to restore;
journeys `APPLICATION_VERIFICATION_SUCCEEDED`; cleanup `CLEANED`; gateway
`BASELINING_REQUIRED`.

Schema: originals for journey rollups lacked some required top-level fields. Amendments
preserved originals and synthesized run-root `summary.json` (see `summary.amendment.json`).
`validate-evidence-schema.sh` → **OK** for `summary.json`.

## 6. Full post-change regression

Directory: `build/operational-evidence/wp062b-20260728211226/regression-rerun-20260729084805/`  
Authoritative detail: `results.ndjson`, `index.txt`, `regression-summary.json`.

| Suite | Command / notes | Result |
|-------|-----------------|--------|
| parking-service unit | `./gradlew :services:parking-service:test` | Initial **FAIL** 562/1 (`ExposureShadow` 50ms budget); after test budget fix **PASS** 562/0 (`parking-service-test-final`) |
| gateway-service | `./gradlew :services:gateway-service:test` | **PASS** (exit 0; see logs; final re-run recorded) |
| auth-service | `./gradlew :services:auth-service:test` | **PASS** (exit 0; JUnit XML ~134 tests) |
| media-service | `./gradlew :services:media-service:test` | **PASS** (exit 0; JUnit XML ~102 tests) |
| StagingVerificationGovernanceTest + OperationalReadinessGovernanceTest | parking targeted | **PASS** |
| GatewayDownstreamTimeoutGovernanceTest | gateway targeted | **PASS** |
| WP-05 replay filters | `*Calibration* *Replay* *ContinuousCalibration*` | **PASS** |
| WP-05 PostgreSQL/Testcontainers | `integrationTest` task | **SKIPPED/BLOCKED** — Docker unavailable; 70 ignored (`disabledWithoutDocker`) |
| bash-syntax / python-compile / utf8-nul / safety-guards / evidence-schema | scripts | **PASS** |
| compose-config | docker unavailable initially | Structural overlay rerun **PASS** (`!override`, `pull_policy`) |
| workflow-yaml | shared-staging-verification.yml | **PASS** |
| prometheus/grafana structural | file presence | **PASS** |
| migration monotonicity | numeric V1–V26 | Harness lex sort fail then numeric rerun **PASS** |

Do not treat WP-06.2A.1 results as this package’s re-run evidence.

## 7. Failed initial patch disclosure

Classification: **PRE_EXECUTION_PATCH_FAILURE** (also **RECOVERED_IMPLEMENTATION_ERROR**).

Artifact: `initial-patch-failure-disclosure.json`.

A temporary Python patch intended to insert the JDBC/shell env-export fix into
`scripts/staging/run-wp062b-restored-stack-verification.sh` failed with a Python
quoting / unterminated triple-quoted string error. It was **not** part of the
successful evidence orchestration. Corrective exports (`POSTGRES_*_DB`, `MINIO_BUCKET`,
`PARKIO_ENV_FILE` to drill names) were applied afterward; run `wp062b-20260728211226`
then succeeded. No invented stack traces. Final evidence does not contain the failed
attempt. No untracked temporary `_fixrepoint.py` remains.

## 8. Optional restored-stack re-run decision

**NOT REQUIRED / NOT EXECUTED.** Evidence consistency PASSED; no contradictory
implementation gap requiring overwrite; Docker daemon unavailable for a new live stack.
Prior run `wp062b-20260728211226` remains authoritative. A newer run would use a new ID
and must not overwrite this directory.

## 9. Local representative result

**TECHNICALLY_VERIFIED_SIGNOFF_REQUIRED** — `technicalStatus=APPLICATION_VERIFICATION_SUCCEEDED`,
`executionClassification=LOCAL_REPRESENTATIVE`, `sharedStagingLabel=false`.

## 10. Shared-staging availability result

Inspected `.github/workflows/shared-staging-verification.yml`: `runs-on: ubuntu-latest`,
capability note that full stack may be `INFRA_INPUT_REQUIRED`; structural-only path when
`PARKIO_CI_HAS_STAGING_ENV != yes`. No repository-backed authorized shared-staging host
or secrets were exercised.

**Classification:** `SHARED_STAGING_REQUIRED` + `INFRA_INPUT_REQUIRED`.  
Local Docker is **not** shared staging.

## 11. Non-waivable control result

All non-waivable controls for the successful evidence run **passed** (auth, parking read,
restore integrity, source≠restore targets, no developer stack hijack, no production
data/credentials, cleanup CLEANED per evidence). No auto-waiver issued.

## 12. Potential waiver candidates

- Lack of shared-staging capacity (`SHARED_STAGING_REQUIRED`)
- Incomplete route timeout baseline (`BASELINING_REQUIRED`)
- Unavailable ACTIVE external validation path for nearby visibility
- Docker-unavailable PostGIS `integrationTest` execution in this finalization window

Waiver requires named owner + expiration; **none recommended automatically**.

## 13. Gateway baseline status

`gateway-route-baseline.json`: `baseliningStatus=BASELINING_REQUIRED`,
`environment=LOCAL_REPRESENTATIVE`, `policyUnchanged=true`. No per-route timeout policy
change in this package.

## 14. Cleanup revalidation

Prior evidence: `cleanup-report.json` → `CLEANED`, `developerProjectUntouched=true`.  
Live Docker revalidation: **BLOCKED** (engine unavailable) — see
`cleanup-revalidation.json`. Evidence preserved. Developer `parkio` project was not
removed.

## 15. Sign-off package status

Prepared: `build/operational-evidence/wp062b-20260728211226/shared-staging-signoff.md`  
Decision default: **NOT_REVIEWED**. Reviewer identity and approval date left blank.
Automation cannot self-approve (`automationMayNotApprove=true` in summary).

## 16. Human review instructions

1. Read `shared-staging-signoff.md` and `shared-staging-summary.json`.
2. Confirm evidence consistency audit PASSED.
3. Review regression-summary and known exclusions.
4. Decide among `APPROVED_FOR_WP_06_3` / `APPROVED_WITH_WAIVER` / `REJECTED` / leave
   `NOT_REVIEWED`.
5. If approving with waiver, complete `shared-staging-waiver-template.md` with owner and
   expiration. Non-waivable failures cannot be waived.
6. Do not start WP-06.3 without repository-backed approval record.

## 17. WP-06.2B final technical decision

**TECHNICALLY_VERIFIED_SIGNOFF_REQUIRED** (LOCAL_REPRESENTATIVE).

## 18. WP-06.3 eligibility decision

**NOT_ELIGIBLE** until human `APPROVED_FOR_WP_06_3` or `APPROVED_WITH_WAIVER` exists.

## 19. Remaining blockers

- Human sign-off still `NOT_REVIEWED`
- Shared-staging capacity / authorization
- Gateway route timeouts still baselining-only
- Live Docker cleanup revalidation blocked in this session
- PostGIS integrationTest not executed against Docker here (skipped)

## 20. Recommended next action

Authorized reviewer completes sign-off. Optionally provision real shared staging and
re-run with `PARKIO_STAGING_SHARED_OPT_IN=yes` under a new evidence run ID. Do **not**
start WP-06.3 prematurely.
## WP-06.2B.2 superseding final-state certification

WP-06.2B.1 amendments and audits do **not** certify code or evidence generators changed after
`wp062b-20260728211226`. Final-state technical evidence for human approval is produced by
[WP-06.2B.2](wp-06-02b-2-final-state-reexecution-signoff-gate.md) run `wp062b2-20260729073440`.