# Shared Staging / Restored-Stack Verification Runbook (WP-06.2B)

## Purpose

Prove application journeys against **restored** drill databases on an isolated
`parkio-wp062-*` Compose project — without hijacking developer project `parkio`.

## Prerequisites

- Docker Engine with enough RAM for a second slim app stack alongside any developer stack
- `docker/.env` present (staging credentials only)
- Python 3 available to the shell runner
- Free host ports: 18080–18084, 19000–19001, 15432–15435, 15441, 16379, 19092

## Safety

```bash
export PARKIO_ENVIRONMENT_TYPE=STAGING_LOCAL
export PARKIO_STAGING_ISOLATION_MARKER="wp062b-local-$(date -u +%Y%m%d)"
export PARKIO_STAGING_ALLOW_DESTRUCTIVE=yes
# Never set PRODUCTION or parkio as COMPOSE_PROJECT_NAME
```

## Execute (local representative)

```bash
./scripts/staging/run-wp062b-restored-stack-verification.sh
```

Evidence lands under `build/operational-evidence/<run-id>/`.
Automation stops at **SIGNOFF_REQUIRED** — complete
`docs/operations/evidence/shared-staging-signoff-template.md` for WP-06.3 eligibility.

## Cleanup

Trap cleanup removes only `COMPOSE_PROJECT_NAME=parkio-wp062-*`.
Or: `COMPOSE_PROJECT_NAME=parkio-wp062-b-... ./scripts/staging/cleanup-isolated-stack.sh`

## Shared staging

If a shared host/runner exists, set `PARKIO_WP062B_EXECUTION_CLASS=SHARED_STAGING`
and `PARKIO_STAGING_SHARED_OPT_IN=yes` with `PARKIO_ENVIRONMENT_TYPE=STAGING_SHARED`.
Do not label a laptop run as shared staging.

## WP-06.2B.1 evidence finalization

After a successful restored-stack run, finalize with [WP-06.2B.1](wp-06-02b-1-evidence-finalization-signoff-preparation.md): consistency audit, post-change regression re-run, and run-specific sign-off leaving `NOT_REVIEWED`. Do not label LOCAL_REPRESENTATIVE evidence as SHARED_STAGING.

## WP-06.2B.2 final-state re-execution

Before human approval, execute [WP-06.2B.2](wp-06-02b-2-final-state-reexecution-signoff-gate.md) on the final worktree with Docker available. Use a new run ID (do not overwrite historical evidence). Stop at `SIGNOFF_REQUIRED` / `NOT_REVIEWED`.
