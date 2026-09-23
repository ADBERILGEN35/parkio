# Off-host erasure recovery — draft release package

**Branch:** `feat/offhost-erasure-recovery`
**Base:** `origin/api` `aa865a255564464bed207a9061244af2641edd3d`
**Production:** disabled. No host access, no real erasure, no backup
download/restore, no cloud provisioning.

## Problem

G2 in BRR-01: after production host loss, erasures requested after the newest
offsite stamp exist only on the lost host. Replaying the stamp ledger resurrects
those accounts as ACTIVE. `restore-erasure-ledger.py` already accepts an
operator supplement with an explicit `covered-through`; nothing produced that
supplement off-host.

## What shipped (this PR only)

New files:

- `scripts/lib/offhost_erasure.py`
- `scripts/offhost-erasure-export.py`
- `scripts/offhost-erasure-recover.py`
- `scripts/test_offhost_erasure_recovery.py`
- `docs/operations/offhost-erasure-recovery.md`
- `.github/workflows/offhost-erasure-recovery.yml` (dedicated; does not edit
  shared backup/drill workflows)

Not modified: `backup-*.sh`, `backup-common.sh`, `restore-drill-01.sh`,
`restore-erasure-ledger.py`, Slack/NR code, BRR-01 report.

No application/Java change. The authoritative table is still auth
`erased_user_tombstones`. Export is `--from-ledger` (same two-field JSON).

## Guarantees / limits

- Durable off-host recoverability begins only after a **complete-table**
  snapshot and its coverage seal both persist. `coveredThrough` is the query
  time, not the upload time.
- Remote write failure: coverage does not advance; `--retry` replays pending.
- Cutoff after last seal: recover exit 3 BLOCKED. Do not expose the copy.
- Periodic export is **not** zero-loss. The uncovered window is
  (incident time − last verified `coveredThrough`).
- No names, emails, tokens.

## Synthetic evidence

Local: `python -m unittest scripts.test_offhost_erasure_recovery -v` → 13 OK.
See `synthetic-tests.txt`. CI job “Synthetic off-host erasure tests” on this PR.

## Integration (separate change)

1. Optional call from `backup-hosted-beta.sh` after ledger export, only when
   `PARKIO_OFFHOST_ERASURE_ENABLED=1`, using the export query time — not
   `date -u` after upload. Decide whether export failure fails the backup.
2. `restore-drill-01.sh`: run `offhost-erasure-recover.py` and feed its
   supplement + printed `coverageThrough` to `restore-erasure-ledger.py`.
3. Off-host location: **blocker**. This PR’s store is a directory. Production
   enablement needs a directory or object prefix that survives host loss.
   Azure listing/login is not available from this task; do not retrieve
   `BACKUP_AZURE_*`. Do not invent a container.
4. Update BRR-01 G2 text after (1)–(3), not in this PR.

## Rollback

Revert this PR or leave the flag off. No cron, host script, or secret change
was made. Shared backup entrypoints are untouched.

## Deployment implications

None until someone enables the flag and points `--store-dir` at an off-host
path. Auth service, backup cron, Slack, New Relic, Alertmanager, and web are
out of scope.
