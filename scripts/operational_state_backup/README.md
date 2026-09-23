# Opt-in operational-state snapshot

This helper is independent of the production backup scheduler. It has no service-control or network code. It does not snapshot the gateway PostgreSQL outbox; its backup identity is recorded for later reconciliation. A completed snapshot is **not** a production backup until an operator separately authorizes, schedules, transfers, monitors, and restores it in a drill.

## Required coordination

Before any real invocation, obtain operational approval and quiesce the gateway waitlist exporter, Slack inbox consumer, Slack worker, NR source helper, Fluent Bit collector, and NR budget gate. Keep publishers disabled until the restored gateway outbox, inbox, Slack queue/dedup and NR accounting have been reconciled. The helper cannot enforce a cross-process freeze. If a source changes while copying, it fails for an individual non-SQLite file; it cannot prove a point-in-time cut across multiple stores. Never substitute this for a coordinated stop or checkpoint.

Use the same gateway PostgreSQL backup stamp as `--gateway-outbox-backup-id`. The gateway outbox is a separate consistency domain. Capture its event IDs/statuses from an authorized restored database for `prepare-recovery`; do not query production merely to run this helper. The required input shape is `{"backup_id":"<same stamp>","events":[{"eventId":"<id>"}]}`. Include all relevant waitlist outbox events in the chosen reconciliation window, including exported and pending rows. This JSON is sensitive; keep it in a restricted offline directory and delete it under the approved retention procedure.

## Snapshot contents and commands

All commands below are templates, **not instructions to execute against production without authorization**. Passphrase comes from the existing backup secret-delivery procedure in `BACKUP_ENCRYPT_PASSPHRASE`; never put it on a command line. Use a private filesystem for `--staging-root` and securely handle any interrupted plaintext staging directory. Python 3.10+ and OpenSSL are required.

```sh
python3 scripts/operational_state_backup/state_backup.py --staging-root "$PRIVATE_TMP" snapshot \
  --slack-db /var/lib/parkio/slack-biz/slack_biz.sqlite3 \
  --slack-inbox /var/lib/parkio/waitlist-ops-inbox \
  --nr-budget-db /var/lib/parkio-nr-log-continuous/budget/budget.db \
  --nr-source-state /var/lib/parkio-nr-log-continuous/source/state \
  --nr-source-spool /var/lib/parkio-nr-log-continuous/source/logs \
  --nr-collector-state /var/lib/parkio-nr-log-continuous/collector \
  --gateway-outbox-backup-id "$PG_BACKUP_STAMP" \
  --destination "$PRIVATE_BACKUP_ROOT/opstate-$PG_BACKUP_STAMP"

python3 scripts/operational_state_backup/state_backup.py --staging-root "$PRIVATE_TMP" verify \
  --snapshot "$PRIVATE_BACKUP_ROOT/opstate-$PG_BACKUP_STAMP"

python3 scripts/operational_state_backup/state_backup.py --staging-root "$PRIVATE_TMP" prepare-recovery \
  --snapshot "$PRIVATE_BACKUP_ROOT/opstate-$PG_BACKUP_STAMP" \
  --gateway-outbox-export "$OFFLINE_GATEWAY_EVENT_EXPORT" \
  --destination "$PRIVATE_RECOVERY_ROOT/opstate-staged"
```

The archive includes a SQLite Online Backup API copy of `slack_biz.sqlite3` and `budget.db` (committed WAL included), pending/acked/rejected inbox files, source cursor/status JSON, source spool files, and collector tail DB/filesystem backlog. SQLite files within collector state are also copied through the Online Backup API. It excludes SQLite transient WAL/SHM/journal files because their committed state is already in the online backup. It refuses symlinks and secret-named files. It does not include env files, API keys, gateway PostgreSQL, Docker log files, or off-host objects.

The destination has a 0700 directory and 0600 encrypted archive, SHA256SUMS, and COMPLETE marker. The encrypted tar contains per-file checksums, sizes, SQLite schema hash/version and integrity results, timestamps, and the gateway backup identity. The cipher matches `scripts/lib/backup-common.sh`: OpenSSL AES-256-CBC with PBKDF2 and salt. SHA256 detects accidental corruption; this format has no independent cryptographic authentication, so artifact provenance and access controls still matter. `verify` decrypts, checks all recorded content and SQLite integrity, and removes temporary plaintext. No live SQLite file is copied raw.

## Recovery gate

`prepare-recovery` only creates an **offline staged copy and manual plan**. It never restores production, activates publishers, or calls Slack/New Relic. It rejects an outbox backup identity mismatch and duplicate gateway event IDs. Its counts identify missing/extra event IDs, inbox events already queued, ambiguous `delivery_unknown`/`in_flight`, and dedup conflicts. An event ID match is not proof of Slack delivery. Gateway export and Slack snapshot may be from different instants; HTTP success/timeout after snapshot, queue admission before inbox ack, dedup expiry (default 168 hours), and the gateway outbox retention window can all create duplicate or loss windows. Retained `.invalid` files are captured as forensic bytes and excluded from replay.

The staged NR `budget.db` is forced to `exhausted=1`; this bit survives UTC day/month counter rollover in `budget_gate.py`. Unknown use since the snapshot must be reconciled against an approved external accounting record before an operator chooses a new safe budget. Do **not** clear the bit or start the gate with an older ledger merely because daily/monthly windows rolled over. Reconcile source cursor, spool, Fluent Bit tail DB/backlog and remote acknowledgement conservatively: replay can duplicate logs, skipping can lose them. If records cannot be proven, keep the gate/collector disabled and escalate the accounting decision. The recovery plan reports both publishers as disabled; it does not mark any state safe to resume.

The Slack SQLite worker lock and leases are captured for diagnosis but are disposable after a controlled restart; do not use them as proof that work is delivered. Queue, dedup, DLT, incident thread state, inbox pending files, and budget ledger are durable recovery data. `.acked` and `.invalid` are bounded evidence, not primary delivery state. Metrics and source/collector status are diagnostic; source cursors, spool and collector tail DB/backlog affect duplicate/loss behavior. See `scripts/slack_biz/store.py`, `scripts/slack_biz/waitlist_inbox.py`, `scripts/newrelic_log_pilot/docker_log_source.py`, `scripts/newrelic_log_pilot/budget_gate.py`, and `docker/fluent-bit/fluent-bit-service-and-tail.conf`.
