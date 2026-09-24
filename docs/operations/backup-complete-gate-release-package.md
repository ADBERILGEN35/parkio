# F-02 backup COMPLETE gate - scoped release package

**Draft only.** No production mutation, cron change, script install,
service control, live env edit, Azure change, retention change, image
publish, or PR merge is authorized by this package.

**Baseline:** origin/api a865a255564464bed207a9061244af2641edd3d
**Branch:** ix/backup-minio-complete-gate
**Independent of** paused integration PR #104 and drafts #101/#102/#103.

## What this installs (later)

Only these host files change backup behavior. Tests and docs stay in git.

| File | Role |
|---|---|
| scripts/lib/backup-common.sh | COMPLETE gate (optional MinIO), discard partial MinIO, unfinalized COMPLETE trap, success-only prune helper, atomic COMPLETE write |
| scripts/backup-hosted-beta.sh | Pass MINIO_OK into the gate; do not seal/upload/prune on required-stage failure |
| scripts/backup-databases.sh | DB-only COMPLETE/upload/prune only after dumps+ledger+integrity |

scripts/run-production-backup.sh, restore entrypoints, deploy guards,
production pins, gateway/auth/web, and Azure config are **unchanged**.

Working-tree SHA-256 of the three install files (recompute on the
exact installed revision before any later host copy):

- scripts/lib/backup-common.sh 69ff315af4280404fa78342ca8c3a2da3213d0ebd75e7cfa6338240b0644aea6
- scripts/backup-hosted-beta.sh c0eff1194f0517cad4c0dc7ade92f7bbe0f2e4a654a389babb927140fd33aa4a
- scripts/backup-databases.sh 51513868e72247663b3db8e113872708758628555dac082d9de102cefd4c0236


## Backup lock

Cron already uses lock -n /var/lock/parkio-backup.lock around

un-production-backup.sh. A later host install must take the same
lock (or wait for the 03:30 UTC window to be idle) so a running stamp
is not replaced mid-write.

Do not change the lock path, cron line, or scheduler unit in this
release.

## Rollback

Restore the three files from a865a25 (or the previously installed
revision). **Reverting this fix restores the known COMPLETE defect:**
a failed MinIO mirror or a failed standalone DB/ledger run can again
write a usable COMPLETE, upload that stamp, and prune previous good
backups.

## PR 104 later integration (after this merge)

PR 104 (c24f4f3d at HOLD) only sources 
ecovery-coordination.sh and
calls parkio_ordinary_ops_snapshot_after_complete after a successful
hosted-beta run. It still uses the two-argument COMPLETE gate and still
copies ackup-current.json on failure.

After this F-02 PR merges to pi:

1. Leave #104 disabled. Do not merge recovery integration to get F-02.
2. Rebase or merge pi into #104. Do not rebase #104 in the F-02 task.
3. Keep the ordinary-ops hook after PARKIO_BACKUP_FINALIZED=1 and
   only on the success path (after the fail-closed exit).
4. Take this branch three-argument
   parkio_backup_allow_complete dest db_failed minio_ok and
   success-only ackup-current.json / prune.
5. Do not move the hook before COMPLETE or run it on a failed stamp.

Source drafts #101/#102/#103 stay closed/disabled.

## F-03 remains open

Unsafe restore entrypoints are **not** fixed here. Do not treat this
package as restore-safe. Isolated real restore of
2026-09-24T03-30-01Z is still unauthorized.

## Remaining decision (not started)

Whether and when to install these three scripts on the production host
is a separate operator decision. This package only prepares the draft.
