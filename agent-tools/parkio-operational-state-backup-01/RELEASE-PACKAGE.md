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

The tool makes SQLite Online Backup API copies and validates `PRAGMA integrity_check`, supported Slack schema version, NR budget schema/row and per-DB schema hashes. It captures the pending/acked/rejected inbox, source state/spool and collector state after a coordinated quiesce. It rejects symlinks, secret-named files and changing non-SQLite files. Its encrypted archive uses the existing OpenSSL AES-256-CBC PBKDF2 convention; 0700 directory, 0600 files, SHA256SUMS, COMPLETE, per-file checksums/sizes, timestamps, schema/version metadata, and gateway backup identity. Separately named environment/secret files are excluded; log content may still be sensitive. Verification decrypts only to a restricted temporary directory. An interrupted run has no published COMPLETE destination; the run removes its own temporary directory on handled failures. After an uncatchable kill, a private staging filesystem must be checked by an operator for plaintext remnants.

The source directories are separate consistency domains. A multi-store point-in-time claim requires pausing the gateway exporter, Slack consumer/worker, NR source helper, collector and gate under separate authorization. Without that coordination, `snapshot` is an internally checked set of files but not an atomic operational checkpoint.

## Cross-store cut and offline recovery

SQLite online backup makes each database copy internally consistent, including committed WAL. It cannot make the filesystem inbox, Slack relay database, NR source/collector state and gateway PostgreSQL outbox one atomic snapshot. The proposed coordinator must pause Slack worker, Fluent Bit collector, gateway export loop, inbox consumer and any other queue writer, NR source helper and budget gate; await in-flight work; then capture a consistent gateway PostgreSQL backup followed by the operational snapshot while the operational writers remain stopped. Gateway outbox admission may continue because the PostgreSQL backup defines the database cut while export is paused. The paused interval runs from acknowledged quiescence through `COMPLETE` plus `verify`, with a proposed 15-minute ceiling and abort/resume on timeout. Details and order are in the scoped README. Current gateway exporter has no exporter-only pause/ack control, and the NR unit couples helper/collector/gate stopping, so this protocol needs separate integration work. Disabling `ops-notifications.enabled` would also suppress outbox admission and is unsuitable.

Use `verify`, then `prepare-recovery` with a **separately recovered** gateway outbox event export carrying the same backup ID. A matching ID only pairs claimed artifacts; it does not establish event reconciliation. The tool stages files offline and reports missing/extra event **counts**, duplicate pending vs queue counts, and ambiguous Slack delivery statuses. Operators must inspect the exact event IDs and statuses in the recovered PostgreSQL outbox, inbox and SQLite queue/dedup before any replay decision. It refuses mismatched backup identity, duplicate exported IDs and queue/dedup conflicts. Slack HTTP success or timeout after the snapshot, enqueue before inbox ack, outbox retention/purge and 168-hour dedup expiry are duplicate/loss windows. Exactly-once Slack delivery is not promised.

The tool sets the staged NR ledger's persistent `exhausted=1`, records its checksum, and marks both publishers disabled and reconciliation incomplete in `RECOVERY-PLAN.json`. This is an **offline staging result**, not an enforced production interlock. The real `Budget.reserve()` honors `exhausted=1` across day/month rollover if the gate is started with that exact restored DB. The real `Budget.__init__()` creates a fresh ledger if its path is missing; a wrong bind mount could therefore grant a fresh budget. A future recovery-mode gate must require a verified pre-existing `budget.db` at the container path and refuse startup on missing/mismatched state. Until this is implemented and tested, keep the NR gate/collector disabled in a restore. Unknown post-snapshot usage must be reconciled against independent accounting before selecting any new budget. Source cursor, spool and Fluent Bit tail DB/backlog require separate duplicate/loss review.

## Synthetic test evidence

Command: `PYTHONDONTWRITEBYTECODE=1 python3 -W error::ResourceWarning -m unittest discover -s scripts/operational_state_backup -p 'test_*.py' -v`.

| Requested case | Synthetic test and assertion |
| --- | --- |
| Concurrent SQLite/WAL writes | `test_wal_concurrent_writes_and_encrypted_integrity`: writer thread runs during online backup; copied DB passes integrity and contains a committed seed row. |
| Corrupt/missing state | `test_missing_and_corrupt_state`, `test_nr_schema_mismatch_and_corrupt_sqlite`: missing budget DB, tampered archive and corrupt Slack DB fail without a completed destination. |
| Schema mismatch | `test_schema_mismatch_and_interrupted_snapshot`, `test_nr_schema_mismatch_and_corrupt_sqlite`: Slack version 999 and renamed NR budget table fail. |
| Interrupted snapshot | `test_schema_mismatch_and_interrupted_snapshot`: injected interruption leaves no COMPLETE destination or handled plaintext staging directory. A hard kill still needs operator cleanup. |
| Inbox mutation | `test_inbox_membership_and_content_mutation_abort`: late file and content mutation during copy abort. Drift detection does not replace a pause. |
| Outbox/queue mismatch | `test_reconciliation_mismatch_duplicate_and_invalid_retention`: wrong backup ID rejected; unmatched IDs counted and publishers remain disabled. |
| Duplicate events | Same test rejects duplicate outbox IDs; `test_duplicate_pending_envelope_and_old_budget_periods_fail_closed` reports a queued event also present in the pending inbox. |
| NR day/month recovery | `test_real_gate_day_month_rollover_and_missing_ledger_limit`: real `Budget` class rejects reserve after a day or month rollover with staged exhausted DB; deliberately missing DB demonstrates the current fresh-ledger gap. |
| Archive recovery safety | `test_archive_password_traversal_symlink_and_no_overwrite`: wrong password, forged traversal/symlink members and existing destination fail; staged outputs are 0700/0600. |

Ten synthetic tests passed. No Docker, Slack, New Relic or production calls. A real coordinated quiesce/restore drill remains a separate acceptance gate. The archive has SHA256 checksums and an unkeyed COMPLETE marker, which detect accidental corruption and ordinary tampering but **do not authenticate** a maliciously replaced artifact. OpenSSL AES-256-CBC PBKDF2 matches the existing convention and does not add AEAD authentication. Preserve artifact provenance and restrictive access; a future format change should add authenticated sealing without weakening current encrypted backups.

## Proposed integration and rollback

**Standalone tool: PASS (synthetic only). Production integration: NOT PERFORMED. Real operational-state snapshot/restore: NOT PERFORMED.** The integration decision remains open; this PR does not authorize deployment or claim the stores are in production backups.

After separate approval, a follow-up should change only these integration surfaces: add an exporter-only pause/ack gate to `services/gateway-service/.../WaitlistOpsNotificationExporter.java` and its properties/control path while leaving outbox admission active; add a dedicated coordinator beside `scripts/operational_state_backup/` to inventory/pause the two Civo Slack units, optional queue writers, NR source helper and collector/gate, pair the PostgreSQL stamp with the operational archive, enforce the 15-minute abort/resume protocol, and monitor artifact completion; add a recovery-mode existing-ledger/hash/exhausted startup guard to `scripts/newrelic_log_pilot/budget_gate.py` and its dedicated compose/systemd launch path so a missing bind cannot open a fresh budget; define protected retention/off-host transfer and a controlled isolated restore drill. Do not alter shared backup entrypoints or Cursor's erasure recovery without separate ownership coordination. Verify real state paths/readability first, including the unverified Slack DB existence. Any enabled registration/Kafka queue consumer needs offset coordination beyond this snapshot.

For offline restore, verify archive provenance/checksums, decrypt only to a 0700 staging directory, run SQLite integrity/schema checks, verify paired PostgreSQL backup identity, compare **actual event IDs/statuses**, quarantine delivery-unknown/in-flight rows, restore only into isolated targets, and keep both publishers off. Reconcile unknown NR spending and cursor/spool/tail backlog before a separately approved activation. On snapshot failure, discard incomplete artifacts and resume unchanged original services in the order documented in the README; on restore failure, leave isolated state unused and publishers disabled. No automatic retry of `delivery_unknown`.

Rollback of this source-only addition is to stop invoking the helper and remove its dedicated schedule/integration if separately added later. Existing backups and runtime services are untouched by this PR. No production state has been snapshotted or restored, and the stores must still be treated as outside the production consistent backup set.
