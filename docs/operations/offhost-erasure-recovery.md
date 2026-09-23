# Off-host erasure recovery (G2)

**Status:** implemented, **production disabled**. Synthetic tests only.
**Does not** change nightly backup scripts, `restore-drill-01.sh`, or the
BRR-01 readiness report. Integration steps are listed at the end.

## 1. Current flow (authoritative record)

| Step | Where | Identifiers | Failure |
|---|---|---|---|
| Request | auth `POST` account deletion | password check, then `erased_user_tombstones` insert (`auth_user_id`, `erased_at`) in the same transaction as status → `ERASURE_IN_PROGRESS` | No tombstone if the request is rejected |
| Fan-out | auth outbox → `parkio.privacy.erasure` | request id + user id | Participants ACK; `FAILED_RETRYING` does **not** remove the tombstone |
| Replay | `POST /internal/erasure/replay` | walks tombstones, finishes local erasure | DB-level restore replay (`erasure-tombstones.sh`) only forces non-ACTIVE; PII purge still needs the application path |
| Backup export | `parkio_export_erasure_tombstones` | JSON array `{authUserId, erasedAt}` only | FU-1: production backup refuses `COMPLETE` if this export fails |
| Restore set | `restore-erasure-ledger.py` | union of the data-stamp ledger, newer stamp ledgers, optional supplement | Exit 3 BLOCKED if coverage &lt; recovery cutoff |

The **authoritative** record is auth `erased_user_tombstones`. It is append-only
(primary key `auth_user_id`). Nightly stamps copy that table at stamp time.
Erasures **after** the newest retrievable offsite stamp exist only on the lost
host unless an off-host journal exists.

Retries: participant ACK failures mark `FAILED_RETRYING`; the tombstone stays.
Duplicates: a second deletion request for the same user returns the existing
request. Ordering: `erased_at` is the request-time clock.

## 2. What this change adds

An **opt-in, isolated** complete-table snapshot plus a **coverage seal**, stored
off the production host.

- Payload: `{authUserId, erasedAt}` only. Names, emails, tokens, and dump
  contents are rejected.
- Coverage advances only when **both** the snapshot and its seal persist.
- `coveredThrough` is the **source-table read time** (`--query-time`), not the
  persist clock and not “now” at recovery.
- Incremental appends are not a coverage mechanism and are not implemented
  as a seal source.
- Default: `PARKIO_OFFHOST_ERASURE_ENABLED` unset/`0`. Nothing is exported.

Helpers (new files only):

- `scripts/lib/offhost_erasure.py`
- `scripts/offhost-erasure-export.py`
- `scripts/offhost-erasure-recover.py`
- `scripts/test_offhost_erasure_recovery.py`

## 3. Guarantees and limits (honest)

| Claim | True? |
|---|---|
| After a verified seal with `coveredThrough` ≥ cutoff, every tombstone that was in the auth table **at that query time** is in the off-host snapshot | **Yes**, if the operator actually exported a complete `SELECT` of `erased_user_tombstones` at that time |
| A successful PUT / `persistedAt` timestamp means coverage through that time | **No** |
| Periodic export gives zero data-loss exposure | **No**. Erasures after the last verified `coveredThrough` are unknown after host loss |
| Incremental “export since last watermark” advances coverage to now | **No** (not offered) |
| Restore of an unrelated ACTIVE account is changed | **No**; only identifiers in the union set are replayed |

**When an erasure becomes durably recoverable off-host:** after it appears in a
complete-table snapshot that has a matching coverage seal stored off-host, and
only through that seal’s `coveredThrough`.

**When remote persist fails:** coverage does **not** advance. The payload stays
in `state.json` `pending` for `--retry`. Restore through a later cutoff stays
BLOCKED.

**Duplicates / ordering:** identifiers are lower-cased; duplicate rows keep the
earliest `erasedAt`. Snapshot bytes are canonical (sorted keys and ids). A newer
snapshot that drops an older identifier is rejected.

**Coverage through cutoff:** a seal exists, its snapshot hash matches, record
counts match, and `coveredThrough` ≥ cutoff. Otherwise BLOCKED (exit 3) or FAIL
(exit 1) if seals are present but corrupt. A handwritten `--supplemental-covered-through`
on `restore-erasure-ledger.py` is **not** sufficient by itself; operators must
use the `coveredThrough` printed by `offhost-erasure-recover.py`.

## 4. Storage, access, retention, integrity

**Default store (this PR):** a filesystem directory (`--store-dir`). For a real
off-host copy the directory must be **not** on the production VM (NFS, object
sync, or a future Azure prefix). This PR does **not** provision that location.

**Azure object store:** not enabled here. Blocker: no dedicated container, no
login session, and this change must not retrieve `BACKUP_AZURE_*` secrets or
modify backup upload helpers. A later integration may add an Azure adapter that
reuses an already-authorized identity and a **separate** prefix; that is out of
scope.

**Access:** 0600 on emitted supplemental ledgers. Store objects are hashes of
content (`snapshots/<sha256>.json`, `seals/<sha256>.json`). No PII fields.

**Retention:** keep every verified seal/snapshot at least as long as backup
retention (14 days documented) **and** until the next newer verified seal exists.
Deleting the newest seal re-opens a coverage gap.

**Integrity:** SHA-256 of canonical snapshot JSON; seal points at that digest
and at `recordCount`. Recover refuses digest mismatch or a truncated JSON array.

## 5. Operator configuration (disabled)

```bash
# still off
unset PARKIO_OFFHOST_ERASURE_ENABLED

# synthetic / drill host only
export PARKIO_OFFHOST_ERASURE_ENABLED=1
python3 scripts/offhost-erasure-export.py \
  --from-ledger /path/to/erasure-tombstones.json \
  --query-time 2026-09-24T03:30:01Z \
  --store-dir /offhost/erasure-journal

python3 scripts/offhost-erasure-recover.py \
  --store-dir /offhost/erasure-journal \
  --recovery-cutoff 2026-09-24T04:00:00Z \
  --data-stamp /restore/stamp \
  --out /restore/offhost-supplement.json
# exit 3 => do not expose the restored copy
```

`offhost-erasure-recover.py` writes `--out` only on PASS. BLOCKED (exit 3) and
FAIL (exit 1) do not emit a supplement.

`--query-time` must be the time of the complete-table read (for example the
`statement_timestamp()` of the export query), not `date -u` after upload.

Do not schedule this on production until: an off-host directory or object store
exists, export identity is authorized without embedding new secrets in git, and
the integration below is merged separately.

## 6. Required integration (not in this PR)

Describe-only. Do **not** apply these in this change.

1. `backup-hosted-beta.sh` (after a successful ledger export): optionally invoke
   `offhost-erasure-export.py --from-ledger "$DEST_DIR/erasure-tombstones.json"
   --query-time <export-query-time>` when enabled. Failure must **not** be
   treated as COMPLETE coverage; decide separately whether it fails the backup.
2. `restore-drill-01.sh` / procedure: run `offhost-erasure-recover.py` and pass
   its `--out` plus the printed `coverageThrough` into
   `restore-erasure-ledger.py --supplemental … --supplemental-covered-through`.
   Refuse if recover exits 3.
3. BRR-01 readiness report: record G2 as “mechanism present, production off,
   no live off-host store”.
4. Optional later: a systemd timer on a **non-production** exporter host that
   reads auth tombstones (same SELECT as `erasure-tombstones.sh`) and writes
   off-host. Do not add that timer in this PR.

**Rollback of this PR:** delete the new files. No backup entrypoint, cron, or
host script changes to revert. Production behavior is unchanged while the flag
is off.

## 7. Tests

`python3 -m unittest scripts.test_offhost_erasure_recovery -v`

Covers: backup-then-erase, duplicates/out-of-order, remote write failure +
retry, missing/corrupt/incomplete off-host objects, unrelated accounts,
BLOCKED when cutoff exceeds coverage. All synthetic UUIDs.
