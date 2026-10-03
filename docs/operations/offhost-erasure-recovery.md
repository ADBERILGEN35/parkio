# Off-host erasure recovery (G2)

**Status:** standalone tools implemented, **production disabled**. **HOLD.**
**Does not** change nightly backup scripts, `backup-common.sh`,
`restore-drill-01.sh`, Slack/NR, Codex PR #101 files, or the BRR-01 report.

A passing unit or CI test is **standalone-tool acceptance**, not off-host
durability, production enablement, or recovery acceptance.

## 1. Current flow (authoritative record)

| Step | Where | Identifiers | Failure |
|---|---|---|---|
| Request | auth `AccountErasureApplicationService.requestDeletion` | `clock.instant()` then `erased_user_tombstones` insert (`auth_user_id`, `erased_at`) in the same `@Transactional` method as status → `ERASURE_IN_PROGRESS` | No tombstone if the request is rejected |
| Commit | auth Postgres | tombstone becomes visible only at COMMIT | In-flight TX is invisible to other sessions |
| Fan-out | auth outbox → `parkio.privacy.erasure` | request id + user id | `FAILED_RETRYING` does **not** remove the tombstone |
| Replay | `POST /internal/erasure/replay` | walks tombstones | DB replay only forces non-ACTIVE; PII purge needs the application path |
| Backup export | `parkio_export_erasure_tombstones` | unlocked `SELECT` of `{authUserId, erasedAt}` | FU-1: production backup refuses `COMPLETE` if this export fails |
| Restore set | `restore-erasure-ledger.py` | union of stamp ledgers + optional supplement | Exit 3 BLOCKED if coverage &lt; cutoff |

The **authoritative** record is auth `erased_user_tombstones` (append-only
PK `auth_user_id`). `erased_at` is the **request-start application clock**,
not the commit timestamp.

## 2. Commit-visibility guarantee (narrow)

Cutoff-comparable `coveredThrough` is produced only by
`table-share-lock` (`scripts/lib/offhost-erasure-locked-snapshot.sql`):

1. `BEGIN` at **READ COMMITTED** (set explicitly)
2. `SET LOCAL lock_timeout = '12s'` and `statement_timeout = '20s'`
3. `LOCK TABLE erased_user_tombstones IN SHARE MODE`
4. read the complete table; `clock_timestamp()` while the lock is held
5. `COMMIT` (or abort). **The database transaction must end before any
   remote persist.** `persist_complete_snapshot` never holds a DB TX.

A timeout or error sets `ON_ERROR_STOP` and aborts the transaction. That
output is not publishable coverage (`locked_snapshot` raises unless psql
exits 0).

**Certified:** every tombstone whose inserting transaction **committed
before** that lock-held auth-DB `clock_timestamp()` is in the snapshot.

**Not certified:**

- every row with `erased_at ≤ coveredThrough`;
- an unlocked SELECT plus `--query-time` / wall-clock;
- erasures that **commit after** the last verified watermark;
- authenticity, deletion resistance, or off-host durability of the store;
- zero data-loss from any periodic export.

`--from-ledger` defaults to `row-set-only` and never advances coverage.
`--visibility-protocol table-share-lock` on a file is operator attestation.

## 3. Recovery limitation (do not paper over)

Periodic snapshots **cannot** certify erasures that committed after the
last verified watermark. A 15-minute cadence does **not** close that
window; it only bounds how large the tail can grow while the exporter is
healthy. After host loss the uncovered tail is

`incident_time − last_trusted_coveredThrough`

and is unknown. Recover exit 3 **BLOCKED** when the requested cutoff
exceeds that watermark. **Do not lower the recovery cutoff to obtain
PASS.** The requested cutoff is the incident/recovery requirement, not a
knob.

## 4. Publication / retry

- Older pending after a newer seal is discarded. Coverage does not regress.
- Concurrent older persist is rejected. Snapshot-without-seal does not
  advance coverage.
- Recover unions all verified lock-protocol snapshots.
- CLI reports counts/hashes only. `authUserId` is a sensitive identifier.

## 5. Storage (directory is test/local)

`FileStore` is not off-host unless durability is independently proven.
SHA-256 detects corruption of a still-present object. It is **not**
authenticity and **not** protection against deletion or rollback.

## 6. Operator configuration (disabled)

```bash
unset PARKIO_OFFHOST_ERASURE_ENABLED
```

`--query-time` on export is the lock-held watermark from the SQL script,
not `date -u` after an unlocked SELECT.

## 7. Next integration decisions (not implemented)

Do **not** add a scheduler or edit shared backup entrypoints in this PR.

| Decision | Status |
|---|---|
| Remote storage and authentication | Dedicated prefix off the production VM; already-authorized identity; no `BACKUP_AZURE_*` retrieval here. Account/container/WORM still a **blocker**. |
| Publication integrity | Need signed seals or immutable store versions. Checksums alone are insufficient. |
| Exporter scheduling | Independent lock-protocol job. Nightly-backup-only export does not close the between-backups gap. A 15-minute cadence is a freshness target, not a closed window. |
| Freshness monitoring | Alert when watermark age exceeds the operator SLA. Backup COMPLETE is not off-host coverage. |
| Uncovered tail after host loss | Refuse restore (exit 3). Do not lower cutoff. Operator must accept that post-watermark erasures are uncertified, or keep the copy unexposed. |

## 8. Acceptance layers

| Layer | This PR |
|---|---|
| Standalone-tool acceptance | Synthetic + isolated Postgres tests; dedicated workflow |
| Real off-host durability | **Not accepted** |
| Production enablement | **Not accepted** |
| Actual recovery acceptance | **Not accepted** |

**Rollback:** delete the new files. Flag stays off.
