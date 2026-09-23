# PARKIO Slack / New Relic operational-state backup: preparation package

## Status and scope

This branch adds only `scripts/operational_state_backup/{state_backup.py,test_state_backup.py,README.md}` and this package. It is an opt-in helper, not a production backup schedule, production snapshot, restore, deployment, or acceptance of possible duplicate/lost notifications. The gateway PostgreSQL outbox remains in its existing backup domain. The expected Slack filename is `slack_biz.sqlite3`; access to `/var/lib/parkio/slack-biz` was restricted in prior evidence, so its **existence has not been established**. The known NR budget ledger is `/var/lib/parkio-nr-log-continuous/budget/budget.db`. The current consistent backup set does not cover these operational stores. No real store was accessed for this change.

Source base: `origin/api` at `aa865a255564464bed207a9061244af2641edd3d` when this worktree was created. Final source and PR identity are in the PR head. No shared backup entrypoint, `backup-common.sh`, `restore-drill-01.sh`, workflow, readiness report, erasure helper, production pin, or secret file was changed.

## Recovery state and evidence

| Domain | Required to recover | Retained for diagnosis / disposable |
| --- | --- | --- |
| Slack | SQLite queue, dedup, DLT and incident threads; pending inbox envelopes | metrics and worker lock/leases are diagnostic; `.acked` and `.invalid` are bounded evidence and are never replayed blindly |
| Gateway | Independently backed-up PostgreSQL waitlist outbox and matching backup stamp | Its EXPORTED marker does not prove Slack delivery |
| New Relic | `budget.db` accounting, source cursor JSON, source spool, Fluent Bit tail DB and backlog | source/status JSON is diagnostic; log replay can duplicate and skip can lose records |

Evidence: `scripts/slack_biz/store.py` (SQLite WAL, queue/dedup/DLT/worker lock/schema v2), `scripts/slack_biz/waitlist_inbox.py` (ack after queue admission, pending/acked/rejected files), `scripts/newrelic_log_pilot/budget_gate.py` (budget singleton, UTC window rollover and total exhausted bit), `scripts/newrelic_log_pilot/docker_log_source.py` (cursor and spool), `docker/fluent-bit/fluent-bit-service-and-tail.conf` (tail DB), `docker/docker-compose.newrelic-log-continuous.yml` (mounts). The gateway outbox is PostgreSQL and independent of the filesystem/Slack snapshot.

## Contents and integrity

The tool makes SQLite Online Backup API copies and validates `PRAGMA integrity_check`, supported Slack schema version, NR budget schema/row and per-DB schema hashes. It captures the pending/acked/rejected inbox, source state/spool and collector state after a coordinated quiesce. It rejects symlinks, secret-named files and changing non-SQLite files. Its encrypted archive uses the existing OpenSSL AES-256-CBC PBKDF2 convention; 0700 directory, 0600 files, SHA256SUMS, COMPLETE, per-file checksums/sizes, timestamps, schema/version metadata, and gateway backup identity. Source environment files and secrets are excluded. Verification decrypts only to a restricted temporary directory. An interrupted run has no published COMPLETE destination; the run removes its own temporary directory on handled failures. After an uncatchable kill, a private staging filesystem must be checked by an operator for plaintext remnants.

The source directories are separate consistency domains. A multi-store point-in-time claim requires pausing the gateway exporter, Slack consumer/worker, NR source helper, collector and gate under separate authorization. Without that coordination, `snapshot` is an internally checked set of files but not an atomic operational checkpoint.

## Offline recovery

Use `verify`, then `prepare-recovery` with a **separately recovered** gateway outbox event export carrying the same backup ID. The tool stages files offline, reports missing/extra IDs, duplicate pending vs queue IDs, and ambiguous Slack delivery statuses. It refuses mismatched outbox identity, duplicate exported IDs and queue/dedup conflicts. It sets the staged NR ledger's persistent `exhausted=1`, records the staged DB checksum, and keeps both publishers disabled in `RECOVERY-PLAN.json`. No automatic restore or publisher start exists. Operators must reconcile gateway outbox, inbox, queue/dedup and Slack delivery uncertainty manually; exactly-once Slack delivery is not promised. Operators must account for unknown New Relic usage after the snapshot, including across UTC daily/monthly rollovers, before setting any new budget. The source cursor, spool and collector backlog must be reconciled separately. See the scoped README for command templates and decision gates.

## Synthetic test evidence

Command: `PYTHONDONTWRITEBYTECODE=1 python3 -W error::ResourceWarning -m unittest discover -s scripts/operational_state_backup -p 'test_*.py' -v`.

Result: 7 tests passed on synthetic fixtures only. Covers concurrent Slack SQLite WAL writes with online backup/integrity, encrypted artifact verification/mode, missing and corrupt state, Slack and NR schema mismatch, interrupted snapshot cleanup, gateway backup identity and duplicate-ID rejection, mismatched outbox/queue counts, retained invalid inbox bytes, duplicate pending/queued event, old daily/monthly NR ledger recovery and `Budget.reserve()` refusal after rollover, and secret-named file exclusion. No Docker, Slack, New Relic or production calls. A real coordinated quiesce/restore drill remains a separate acceptance gate.

## Proposed integration and rollback

After a separate approval, integrate this opt-in helper with the established backup procedure **without replacing or modifying its shared entrypoints in this PR**: define a coordinated pause, paired gateway PostgreSQL backup stamp, secure staging filesystem, protected passphrase delivery, private artifact retention/off-host transfer, monitoring, and a controlled restore drill. Verify all sources exist and are readable, including the previously unverified Slack DB. Inventory service-specific state paths before scheduling. Restore drills must keep publishers disabled until Slack and NR reconciliation is signed off; no automatic retry of `delivery_unknown`.

Rollback of this source-only addition is to stop invoking the helper and remove its dedicated schedule/integration if separately added later. Existing backups and runtime services are untouched by this PR. No production state has been snapshotted or restored, and the stores must still be treated as outside the production consistent backup set.
