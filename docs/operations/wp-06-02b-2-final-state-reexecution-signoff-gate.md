# WP-06.2B.2 — Final-State Re-execution & Human Sign-off Gate

**Status:** TECHNICALLY_COMPLETE_SIGNOFF_REQUIRED (LOCAL_REPRESENTATIVE)
**Date:** 2026-07-29
**Branch:** `decision`
**HEAD:** `550848277748cf086a738c7135f26f1ff27ae9e8`
**Final evidence run:** `wp062b2-20260729073440`
**Historical reference (preserved):** `wp062b-20260728211226`
**Does not claim production readiness. Does not start WP-06.3. Does not claim shared-staging execution.**

## 1. Executive summary

WP-06.2B.2 re-executed restored-stack verification on the **final current worktree** after
WP-06.2B.1 evidence/governance changes. Docker was required and available. Full regression
(including Docker-backed `integrationTest`) passed after defensible IT/test fixes. A new
immutable run `wp062b2-20260729073440` produced `APPLICATION_VERIFICATION_SUCCEEDED` with
automation `SIGNOFF_REQUIRED`, `signOffDecision=NOT_REVIEWED`, and `wp063Eligible=false`.
Historical run `wp062b-20260728211226` was not rewritten. Shared staging remains
`SHARED_STAGING_REQUIRED` / `INFRA_INPUT_REQUIRED`.

## 2. Scope and non-goals

In scope: post-historical change audit; ExposureShadow time-budget resolution; Docker gate;
full regression; fresh isolated restored-stack; live cleanup; historical comparison; human
sign-off package (NOT_REVIEWED).

Out of scope: WP-06.3, production deploy/data/creds, deployment automation, authority
expansion, public API/Kafka/migration rewrites, automatic approval, labeling local as shared staging.

## 3. Repository state

| Field | Value |
|-------|-------|
| Branch | `decision` |
| HEAD | `550848277748cf086a738c7135f26f1ff27ae9e8` |
| Worktree | DIRTY_WP05_WP06_PRESERVED (not reset/cleaned) |
| Developer Compose | `parkio` remained running (~32 containers) |
| Isolation prefix | `parkio-wp062-b2-*` |
| Drill DB marker | `*_drill_wp062b2_*` |
| MinIO prefix | `wp062-` |

## 4. Post-historical-run change audit

Baseline: `wp062b-20260728211226`. Matrix:
`build/operational-evidence/wp062b2-prereq/post-historical-change-matrix.json`.

Mandatory re-run drivers included journey rollup fields (`run-critical-journeys.sh`),
schema validation preference (`validate-evidence-schema.sh`), drill marker override
(`PARKIO_WP062B_RST_MARKER`), ExposureShadow unit-test resolution, governance assertions,
and Trust/Reward migration IT expectation updates to Flyway **26**.

## 5. Time-budget test review

File: `ExposureShadowApplicationServiceTest.java` (`evaluatesBoundedNearbyCandidatesAndReplays`).

| Item | Decision |
|------|----------|
| Original 50 ms | FLAKY_WALL_CLOCK_ASSERTION (functional success path, not SLO) |
| 5000 ms widening | Rejected (hides flakiness; unjustified vs production default 25 ms) |
| Final | Success path uses `Long.MAX_VALUE` (`BUDGET_DISABLED_FOR_FUNCTIONAL_TEST`); budget=0 covers `TIME_BUDGET_EXCEEDED` |
| Production | Unchanged (`ParkingProperties` `timeBudgetMillis=25`) |

Artifact: `build/operational-evidence/wp062b2-prereq/performance-test-review.json`
(also copied into the final evidence run).

## 6. Docker prerequisite result

**PASSED.** Docker API reachable (29.5.3), Compose available, developer `parkio` present,
minimal disposable container probe passed, ports for WP-06.2B reserved range free at launch.
Artifact: `build/operational-evidence/wp062b2-prereq/docker-prerequisite-gate.json`.

Failed first launcher attempt `wp062b2-20260729073306` disclosed
`PRE_EXECUTION_PREREQUISITE_FAILURE` (missing `PARKIO_STAGING_ISOLATION_MARKER`); corrected
in `scripts/staging/_wp062b2_run_restored_stack.sh` before the successful run.

## 7. Full regression results

Regression folder: `build/operational-evidence/wp062b2-regression-20260729102728/`
(+ IT logs from `wp062b2-regression-20260729102544`).

| Suite | Exit | Counts (this execution) |
|-------|------|-------------------------|
| parking-service:test (full, XML snapshot) | 0 | 563 passed |
| gateway-service:test (full, XML snapshot) | 0 | 119 passed |
| auth-service:test | 0 | 134 passed |
| media-service:test | 0 | 102 passed |
| parking-service:integrationTest (Docker/PostGIS) | 0 | 70 passed |
| WP-05 replay/targeted + governance | 0 | executed |
| bash-syntax / python-compile / compose / workflow / safety-guards / utf8-nul / prometheus / grafana / migration-monotonicity | 0 | structural |

IT fixes prior to green: Trust/Reward migration ITs expect Flyway `"26"` after full migrate;
`TrustShadowPersistencePostgresIT.concurrentDistinctEvidencePreservesBothUpdatesAndMatchesReplay`
reprocesses `SNAPSHOT_CONFLICT` survivors (production retry path unchanged).

## 8. Fresh restored-stack execution

| Field | Value |
|-------|-------|
| Run ID | `wp062b2-20260729073440` |
| Compose project | `parkio-wp062-b2-20260729073440` |
| Marker | `wp062b2_20260729073440` |
| Classification | LOCAL_REPRESENTATIVE |
| technicalStatus | APPLICATION_VERIFICATION_SUCCEEDED |
| automation status | SIGNOFF_REQUIRED |
| signOffDecision | NOT_REVIEWED |
| wp063Eligible | false |
| Orchestrator | `scripts/staging/run-wp062b-restored-stack-verification.sh` via `_wp062b2_run_restored_stack.sh` |

## 9. Final evidence inventory

Root: `build/operational-evidence/wp062b2-20260729073440/`

Includes: `environment-manifest.json`, `datasource-repoint-report.json`,
`critical-journeys-source/`, `critical-journeys-restored/`, restored auth/parking/media
journeys, `post-restore-write-report.json`, `source-restore-comparison.json`,
`wp05-defaults-report.json`, `gateway-route-baseline.json`, `regression-summary.json`,
`performance-test-review.json`, `historical-run-comparison.json`, `cleanup-report.json`,
`cleanup-live-revalidation.json`, `final-state-summary.json`, `summary.json`,
`human-signoff.md`, `shared-staging-signoff.md`, `evidence-consistency-audit.json`,
`checksums/`, `logs/`.

## 10. Historical versus final-run comparison

Artifact: `historical-run-comparison.json` — **PASSED**, no `MATERIAL_REGRESSION` /
`UNEXPLAINED` blockers.

Expected differences: compose prefix `parkio-wp062-b2-*`, drill marker `wp062b2_*`,
journey rollup now includes `repositoryCommit` (**MATERIAL_IMPROVEMENT**).
Historical artifacts checksum-verified preserved.

## 11. Live cleanup revalidation

`cleanup-report.json`: `CLEANED`.
`cleanup-live-revalidation.json`: `PASSED`; developer `parkio` unchanged (34→34);
no remaining `parkio-wp062*` containers/networks. Evidence preserved.

## 12. Shared-staging availability

**SHARED_STAGING_REQUIRED** / **INFRA_INPUT_REQUIRED**. No authorized shared-staging host
or credentials used. `sharedStagingLabel=false`. Not claimed as SHARED_STAGING.

## 13. Human sign-off package

`build/operational-evidence/wp062b2-20260729073440/human-signoff.md` (and
`shared-staging-signoff.md`): factual fields prepopulated; reviewer identity / review date /
approval blank; **Decision: NOT_REVIEWED**. Automation cannot set
`APPROVED_FOR_WP_06_3` / `APPROVED_WITH_WAIVER`.

## 14. Non-waivable controls

Isolation from developer `parkio`; drill-only JDBC after restore; no production endpoints/
credentials; no auto-approval; no silent rewrite of historical evidence; mandatory restored
journeys; cleanup must not leave unsafe isolated services; WP-06.3 requires explicit human
approval record.

## 15. Potential waiver candidates

- Shared staging capacity absence (infra)
- Gateway per-route timeout baselining (`BASELINING_REQUIRED`)
- ACTIVE nearby path when synthetic spot remains `PENDING_VALIDATION`
  (`EXTERNAL_VALIDATION_REQUIRED` / product-correct non-ACTIVE visibility)

## 16. Remaining blockers

1. Authorized human review of `wp062b2-20260729073440`
2. Shared-staging platform inputs (if shared-staging sign-off is required)
3. Gateway timeout baselining approval (separate from this package)

## 17. WP-06.2B final technical decision

**TECHNICALLY_VERIFIED_SIGNOFF_REQUIRED** on final-state LOCAL_REPRESENTATIVE evidence
`wp062b2-20260729073440` (supersedes WP-06.2B.1 as certification basis for post-historical
code/evidence generators; historical run remains preserved reference).

## 18. WP-06.3 eligibility

**NOT_ELIGIBLE** until repository-backed human decision
`APPROVED_FOR_WP_06_3` or `APPROVED_WITH_WAIVER` naming reviewer/team, run ID, review date,
exclusions, and waiver owner/expiration where applicable.

## 19. Recommended reviewer action

1. Review `human-signoff.md` and `final-state-summary.json` for run `wp062b2-20260729073440`
2. Confirm historical `wp062b-20260728211226` untouched
3. Decide APPROVED_FOR_WP_06_3 / APPROVED_WITH_WAIVER / REJECTED
4. Do not start WP-06.3 without that record