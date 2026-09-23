# Off-host erasure recovery — draft release package

**PR:** https://github.com/ADBERILGEN35/parkio/pull/102 (draft)
**Branch:** `feat/offhost-erasure-recovery`
**Reviewed base:** `origin/api` `aa865a255564464bed207a9061244af2641edd3d`
**First head:** `c86fd69b825691427a0fa19ee39b56b7b3a6c0bc`
**Decision:** see `PR102-RELEASE-DECISION.md`

**Production:** disabled. No host access, no real erasure, no backup
download/restore, no cloud provisioning. Do not enable this PR.

File boundary with Codex PR #101: this change owns only
`offhost-erasure*` helpers/tests/docs and
`agent-tools/parkio-offhost-erasure-recovery-01/`. It does not touch
`scripts/operational_state_backup/**` or
`agent-tools/parkio-operational-state-backup-01/**`.

## Problem

G2: after production host loss, erasures requested after the newest offsite
stamp exist only on the lost host. `erased_at` is the request-start app
clock and becomes visible only at COMMIT. An unlocked SELECT plus a
query-time stamp is not complete cutoff coverage.

## What shipped

New files only. Shared backup entrypoints, `backup-common.sh`,
`restore-drill-01.sh`, Slack/NR, BRR-01, and #101 files are untouched.

Coverage advances only for `visibilityProtocol=table-share-lock` after both
snapshot and seal persist. Directory `FileStore` is test/local unless
durability is independently established.

## Integration (separate change; no scheduler here)

1. Independent 15-minute lock-protocol exporter — **not** “after nightly
   backup only”. That does not close the between-backups gap.
2. Freshness fail when `incident − coveredThrough` exceeds 20 minutes
   while the job is supposed to be healthy.
3. Recover exit 3 refuses restore when cutoff coverage is not established.
4. Off-host location remains a blocker (no Azure container/login; do not
   retrieve `BACKUP_AZURE_*`).

## Rollback

Leave the flag off or revert this PR. No cron, host script, or secret
changed.
