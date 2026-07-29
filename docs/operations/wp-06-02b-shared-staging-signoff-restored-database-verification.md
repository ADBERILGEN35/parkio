# WP-06.2B — Shared Staging Sign-off & Restored-Database Application Verification

**Status:** TECHNICALLY_VERIFIED_SIGNOFF_REQUIRED or PARTIALLY_VERIFIED (see executed evidence)
**Date:** 2026-07-28
**Branch:** `decision`
**Does not claim production readiness. Does not start WP-06.3.**

## 1. Executive summary

WP-06.2B adds a dedicated isolated restored-application Compose overlay and an
orchestration script that seeds synthetic data, backs up, restores into
`*_drill_wp062b_*` databases, repoints only the isolated stack, and re-runs
HTTP journeys. Automation stops at `SIGNOFF_REQUIRED`.

## 2. Scope and non-goals

In scope: isolated restored full-stack verification, evidence, sign-off templates, manual workflow.
Out of scope: WP-06.3, production deploy, authority expansion, migration rewrites, automatic approvals.

## 3. Repository state

Recorded at execution time (branch `decision`, dirty WP-05/06 worktree preserved).

## 4. Pre-implementation capability matrix

See final report section 4.

## 5–12. Design

- Overlay: `docker/docker-compose.restored-application-verification.yml`
- Orchestrator: `scripts/staging/run-wp062b-restored-stack-verification.sh`
- Ports: 18xxx / 15xxx reserved range; `assert_host_ports_free`
- DBs: `*_wp062b_src` then `*_drill_wp062b_*` with `parkio_wp062_restore_marker`
- Kafka: isolated broker in project (`ISOLATED_BROKER`)
- Redis: empty rebuilt (`EMPTY_REBUILT`)
- MinIO: `wp062-parkio-media-src` / `wp062-parkio-media-rst`

## 13–22. Execution results

Filled from `build/operational-evidence/*/shared-staging-summary.json` after run.

## 23–26. Evidence, workflow, tests

Schema statuses include `SIGNOFF_REQUIRED` (automation) and human-only
`APPROVED_FOR_WP_06_3` / `APPROVED_WITH_WAIVER`.

## 27–31. Sign-off, blockers, decisions

Human sign-off required. WP-06.3 remains `NOT_ELIGIBLE` without A/B approval.

## Executed evidence (LOCAL_REPRESENTATIVE)

- Run ID: `wp062b-20260728211226`
- Compose project: `parkio-wp062-b-20260728211226` (cleaned after success)
- Technical status: `APPLICATION_VERIFICATION_SUCCEEDED`
- Automation status: `SIGNOFF_REQUIRED` / `signOffDecision=NOT_REVIEWED`
- WP-06.3 eligibility: `NOT_ELIGIBLE` (`wp063Eligible=false`)
- Datasource repoint: all authoritative services on `*_drill_wp062b_*`
- Kafka isolation: isolated broker in compose project
- Redis isolation: EMPTY_REBUILT
- MinIO: `wp062-parkio-media-src` -> `wp062-parkio-media-rst`
- Developer project `parkio` remained running and was not repointed
- Shared staging: **not executed** (do not label this run as SHARED_STAGING)

Evidence root: `build/operational-evidence/wp062b-20260728211226/`

## WP-06.2B.1 follow-up

Evidence finalization and human sign-off preparation: [wp-06-02b-1-evidence-finalization-signoff-preparation.md](wp-06-02b-1-evidence-finalization-signoff-preparation.md).
Sign-off package for run `wp062b-20260728211226`: `build/operational-evidence/wp062b-20260728211226/shared-staging-signoff.md` (decision remains `NOT_REVIEWED`).

## WP-06.2B.2 follow-up

Final-state re-execution and sign-off gate: [wp-06-02b-2-final-state-reexecution-signoff-gate.md](wp-06-02b-2-final-state-reexecution-signoff-gate.md).
Authoritative technical candidate for human review: `build/operational-evidence/wp062b2-20260729073440/` (`signOffDecision=NOT_REVIEWED`).
Historical run `wp062b-20260728211226` remains preserved and is not silently rewritten.