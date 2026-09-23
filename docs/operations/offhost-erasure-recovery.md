# Off-host erasure recovery (G2)

**Status:** standalone tools implemented, **production disabled**.
**Does not** change nightly backup scripts, `backup-common.sh`,
`restore-drill-01.sh`, Slack/NR, or the BRR-01 readiness report.

This document is the contract. A passing unit test is **standalone-tool
acceptance**, not off-host durability, production enablement, or recovery
acceptance.

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
not the commit timestamp. Nightly stamps copy committed rows at stamp time.
Erasures after the newest retrievable offsite stamp exist only on the lost
host unless a **trusted** off-host journal exists.

Retries: participant ACK failures mark `FAILED_RETRYING`; the tombstone stays.
Duplicates: a second deletion request returns the existing request.

## 2. Why a query timestamp is not coverage

Postgres default isolation is READ COMMITTED. The current backup export is
an unlocked `SELECT`. A concurrent `requestDeletion` can:

1. assign `erased_at = T_early` (`clock.instant()`);
2. `INSERT` the tombstone and continue other work in the same transaction;
3. remain uncommitted while an exporter `SELECT`s and stamps `now()` / a
   client query time `T_query` where `T_early < T_query`;
4. `COMMIT` afterward.

The snapshot then lacks a row whose `erased_at` is earlier than the stamped
time. Isolated PostgreSQL tests in `scripts/test_offhost_erasure_pg.py`
reproduce this. **Do not certify complete cutoff coverage from wall-clock
or `--query-time` alone.**

## 3. Visibility protocol

Cutoff-comparable `coveredThrough` is produced only by
`table-share-lock` (`scripts/lib/offhost-erasure-locked-snapshot.sql`):

1. `BEGIN`
2. `LOCK TABLE erased_user_tombstones IN SHARE MODE`  
   (waits for in-flight `INSERT`s; blocks new ones)
3. read the complete table
4. `clock_timestamp()` **while the lock is still held**
5. `COMMIT`

**Guarantee (narrow):** every tombstone whose inserting transaction
**committed before** that lock-held `clock_timestamp()` on the **auth
database clock** is in the snapshot.

**Not guaranteed:**

- every row with `erased_at ≤ coveredThrough` (request clock ≠ commit time);
- erasures that commit after the watermark (measurable uncovered window);
- authenticity, deletion resistance, or off-host durability of the store;
- zero data-loss from any periodic export.

`--from-ledger` defaults to `row-set-only`: identifiers may be stored;
coverage does **not** advance. `--visibility-protocol table-share-lock` on
a ledger file is **operator attestation** that the file was produced by the
SQL script. The Python store cannot verify that attestation.

## 4. What this change adds

Isolated, opt-in helpers (new files only):

- `scripts/lib/offhost_erasure.py`
- `scripts/lib/offhost_erasure_pg.py`
- `scripts/lib/offhost-erasure-locked-snapshot.sql`
- `scripts/offhost-erasure-export.py`
- `scripts/offhost-erasure-recover.py`
- `scripts/test_offhost_erasure_recovery.py`
- `scripts/test_offhost_erasure_pg.py`

Rules:

- Payload: `{authUserId, erasedAt}` only. Names, emails, tokens rejected.
- `authUserId` is a **sensitive identifier**. CLI and logs print counts and
  hashes only (`public_result`). Recover writes `--out` only on PASS.
- Coverage advances only when protocol is `table-share-lock` **and** both
  snapshot and seal persist.
- Incremental appends never become a seal source.
- Default: `PARKIO_OFFHOST_ERASURE_ENABLED` unset/`0`.

Publication / retry:

- An older pending snapshot retried after a newer lock-protocol seal is
  discarded (`stale-pending`). Coverage does not regress.
- A concurrent older exporter is rejected (`StaleSnapshotError`).
- Interruption between snapshot and seal does not advance coverage; retry
  may complete the pair.
- Recover unions **all** verified lock-protocol snapshots so previously
  covered identifiers do not disappear if `state.json` points at an older
  seal. Coverage time is still the newest trusted `coveredThrough`.
- A newer snapshot that drops an older identifier is rejected.

## 5. Storage guarantees (directory backend)

`FileStore` / `--store-dir` is a **test/local backend**. Placing the
directory on the production VM is not off-host. A directory becomes an
off-host store only when its durability is **independently** established
(object store or equivalent that survives host loss, with evidence).

| Property | SHA-256 of snapshot/seal | Directory backend |
|---|---|---|
| Detect bitrot / truncation | Yes, if the object is still present | Yes |
| Authenticity (who wrote it) | **No** (no signature / MAC) | **No** |
| Protection against deletion | **No** | **No** |
| Protection against rollback to an older seal | **No** | **No** (recover scans remaining seals; an attacker can delete the newest) |

Minimum requirements before anyone treats this as real off-host durability:

1. Location **not** on the production host (separate account/prefix).
2. Identity with write to that prefix only; no embedding of new secrets in git.
3. Versioning **and** delete protection (object lock / WORM or MFA-delete).
4. Independent integrity check (checksum **plus** the store’s own version
   history). Checksums alone are not authenticity.
5. Access restricted to backup/restore operators; 0600 on emitted
   supplements; no payload in logs or tickets.
6. Retention at least backup retention (14 days documented) **and** until a
   newer verified lock-protocol seal exists.

**Azure:** not enabled. Blocker: no dedicated container, no login from this
task, and this change must not retrieve `BACKUP_AZURE_*` or modify backup
upload helpers.

## 6. Operator configuration (disabled)

```bash
unset PARKIO_OFFHOST_ERASURE_ENABLED

# drill only, after running the lock-protocol SQL against auth
export PARKIO_OFFHOST_ERASURE_ENABLED=1
python3 scripts/offhost-erasure-export.py \
  --from-ledger /path/to/locked-snapshot-entries.json \
  --query-time 2026-09-24T03:30:01Z \
  --visibility-protocol table-share-lock \
  --store-dir /offhost/erasure-journal

python3 scripts/offhost-erasure-recover.py \
  --store-dir /offhost/erasure-journal \
  --recovery-cutoff 2026-09-24T04:00:00Z \
  --data-stamp /restore/stamp \
  --out /restore/offhost-supplement.json
# exit 3 => do not expose the restored copy
```

`coveredThrough` in the recover report is the seal watermark, not `date -u`.

## 7. Independent export cadence (not implemented here)

Exporting only after the nightly backup **does not** close the
between-backups erasure gap. That path also uses an unlocked SELECT today.

Proposed later integration (describe-only; **no scheduler and no shared
backup entrypoint changes in this PR**):

1. **Independent exporter** (separate host or job) that runs
   `offhost-erasure-locked-snapshot.sql` against auth and persists with
   `--visibility-protocol table-share-lock`.
2. **Proposed cadence:** every **15 minutes**. Measurable uncovered window
   after host loss is `incident_time − last_trusted_coveredThrough`, which
   is about one interval plus export/persist runtime when the job is
   healthy — **not** zero, and **not** “15 minutes” if the job is failing.
3. **Failure detection:** export exit 1/4; `coverageAdvanced=false` while
   enabled; `uncoveredSeconds` / watermark age exceeding **20 minutes**
   (interval + 5 minute slack) is a coverage-freshness fail. Alert that
   condition. Do not treat backup COMPLETE as off-host coverage.
4. **Recovery refusal:** `offhost-erasure-recover.py` exit 3 when cutoff
   exceeds trusted `coveredThrough` or no lock-protocol seal exists. Do not
   expose the restored copy. Do not hand-write
   `--supplemental-covered-through`.
5. Optional later: restore-drill procedure calls recover, then
   `restore-erasure-ledger.py`. Still a separate change.
6. BRR-01 G2 text updates after (1)–(4), not here.

**Rollback of this PR:** delete the new files. No cron, host script, or
secret change to revert.

## 8. Acceptance layers

| Layer | This PR |
|---|---|
| Standalone-tool acceptance | Synthetic + isolated Postgres tests; dedicated workflow |
| Real off-host durability | **Not accepted.** Directory backend; no provisioned remote store |
| Production enablement | **Not accepted.** Flag off; no host install |
| Actual recovery acceptance | **Not accepted.** No real backup download, restore, or erasure |

## 9. Tests

```
python3 -m unittest scripts.test_offhost_erasure_recovery -v
python3 -m unittest scripts.test_offhost_erasure_pg -v
```

The Postgres tests start an ephemeral `postgres:16.10` (or use
`PARKIO_OFFHOST_PG_PSQL` / `PARKIO_OFFHOST_PG_DSN` in CI). They do not
touch production.
