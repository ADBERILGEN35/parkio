# Backup and restore follow-up changes (from BRR-01)

These are **separate changes**, each for its own PR and review. PR #94 (BRR-01) only documents
them. It does not change `backup-databases.sh`, `backup-hosted-beta.sh`,
`backup-restore-drill.yml`, the default branch or `master`.

## FU-1: production backup must fail when the erasure ledger cannot be exported

**Defect.** `scripts/backup-databases.sh` runs
`parkio_export_erasure_tombstones "${DEST_DIR}" || true`. If the export fails (auth container
down, query error, disk), the backup still reports success, metrics stay green, and
`COMPLETE` is written and uploaded. A production-mode restore then fail-closes on the
missing ledger (`erasure-tombstones.sh`). The stamp is unusable, and nothing flags it until
someone needs it. `backup-hosted-beta.sh` also writes `COMPLETE` and uploads when
`DB_FAILED > 0`; only the manifest's `databasesFailed` records the failure.

**Scope (one PR, backup scripts only):**

1. In `backup-databases.sh`, when `BACKUP_PRODUCTION_MODE=1`, count a failed export as a
   failure. Also fail when the auth tombstone table is absent: production auth has run V21,
   so the silent `[]` fallback is valid only in dev mode. Validate that the file is a JSON
   array before accepting it.
2. In `backup-hosted-beta.sh`, do not write `COMPLETE` and do not upload offsite when
   `DB_FAILED > 0` or the ledger is missing. Keep the failure metric
   (`parkio_backup_last_success=0`), so `BackupFailed` fires.
3. Tests:
   - a unit case with a fake `docker` that fails only the tombstone query: exit non-zero
     in production mode, zero in dev mode;
   - a case asserting no `COMPLETE` and no offsite call;
   - the existing `backup-restore-drill.yml` must stay green;
   - update `scripts/test-invite-production-backup-scheduler.sh`, which greps for the
     export call.
4. Rollout note: the host runs scripts from its own `/opt/parkio` checkout, which had drifted
   as of 2026-09-22 (WSN-F7). The fix takes effect only after that checkout is reconciled.
   Before enabling it, run `restore-stamp-preflight.py` on the newest stamp. If its ledger is
   already missing, the first run after the fix turns red, which is correct.

**Not in scope:** changing ledger contents, cadence, encryption or retention.

## FU-2: scheduled drill runs stale default-branch code

**Defect.** GitHub runs `on: schedule` only from workflow files on the **default branch**,
at the default branch's latest commit. The default branch is `master`, last updated at
`e5692428` (2026-07-30). Its old `backup-restore-drill.yml` does not start
`postgres-gateway`, so every Monday run has failed since at least 2026-08-31. Meanwhile
`api`, where development happens, is green on push/PR runs. The weekly schedule therefore
proves nothing about current code.

**Supported fix, without changing the default branch or merging `master` wholesale:**
a small, targeted PR **into `master`** that touches only workflow files:

1. Add `.github/workflows/scheduled-restore-drills.yml` on `master`:

   ```yaml
   on:
     schedule: [{ cron: "23 4 * * 1" }]
     workflow_dispatch:
   permissions:
     actions: write
     contents: read
   jobs:
     dispatch:
       runs-on: ubuntu-latest
       steps:
         - env: { GH_TOKEN: "${{ github.token }}", GH_REPO: "${{ github.repository }}" }
           run: |
             gh workflow run backup-restore-drill.yml --ref api
             gh workflow run restore-drill-01-procedure.yml --ref api
   ```

   A `workflow_dispatch` created with `GITHUB_TOKEN` is one of the documented exceptions that
   does start a new run. The dispatched run uses **`api`'s** workflow definition and code.
   Optionally, the dispatcher can `gh run watch` the dispatched runs and fail if they fail,
   so the failure also shows in the schedule's own history.
2. In the same PR, remove the `schedule:` block from `master`'s old `backup-restore-drill.yml`
   so the stale job stops running. On `api` the `schedule:` block is inert, because only
   default-branch schedules fire. It can stay or go.
3. Acceptance: dispatch the new workflow manually on `master`. Confirm two runs appear on `api`
   at its head SHA and both pass. On the following Monday, confirm the scheduled dispatch.

Rejected alternative: keeping `master`'s old workflow and adding `ref: api` to its checkout.
The steps would still come from `master`'s stale YAML while the scripts come from `api`, so
the two would drift.

**Owner decision needed:** a PR into `master` is unusual in this repo. It needs the release
owner's approval even though it touches only the two workflow files above.
