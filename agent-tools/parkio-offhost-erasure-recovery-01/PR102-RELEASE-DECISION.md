# PR #102 release decision (draft — do not merge or enable)

**PR:** https://github.com/ADBERILGEN35/parkio/pull/102
**Base:** `origin/api` `aa865a255564464bed207a9061244af2641edd3d`
**First reviewed head:** `c86fd69b825691427a0fa19ee39b56b7b3a6c0bc`
**Correctness-review head:** `47f0f26810a3aa180e3a8bfa677a9aaad30ae660`
**Decision:** **HOLD** — standalone tools only. Not production-ready.
Keep draft. No merge, deploy, enablement, or real recovery.

Sibling Codex PR #101 (`fix/slack-nr-operational-state-backup`) is out of
scope. No files from that PR were modified.

## 1. Exact-head CI (reused, `c86fd69b`)

Required on `api` (`strict: true`):

| Check | Result | Run |
|---|---|---|
| Build & unit tests | **pass** 6m3s | https://github.com/ADBERILGEN35/parkio/actions/runs/35911784042/job/107353158193 |
| Secret scan | **pass** 10s | https://github.com/ADBERILGEN35/parkio/actions/runs/35911783929/job/107353158277 |

Affected dedicated workflow:

| Check | Result | Run |
|---|---|---|
| Synthetic off-host erasure tests | **pass** 4s | https://github.com/ADBERILGEN35/parkio/actions/runs/35911784177/job/107353159069 |

Other `c86fd69b` PR checks also passed (CodeQL, container scans, config
checks, Trivy). Deploy/invite jobs skipped. That suite does **not** include
the PostgreSQL concurrency tests added in the follow-up commit.

## 2. Tombstone transaction (inspected, not changed)

`AccountErasureApplicationService.requestDeletion` (`@Transactional`):

1. `Instant now = clock.instant()` — application clock at request start
2. `tombstones.save(new ErasedUserTombstoneEntity(user.id(), now))`
3. request row + outbox event in the **same** transaction
4. row visible to other sessions only at COMMIT

`erased_at` is therefore **not** a commit timestamp.
`parkio_export_erasure_tombstones` is an unlocked `SELECT` (READ COMMITTED).
A transaction that starts before the export, writes an earlier `erased_at`,
and commits afterward is missing from that SELECT. A source-query timestamp
is not proof of complete cutoff coverage.

## 3. Tested guarantees (narrow)

Local `python -m unittest scripts.test_offhost_erasure_recovery -v`:
**19 OK** after the review commit (publication/retry/protocol cases).

PostgreSQL tests (`scripts/test_offhost_erasure_pg.py`) are wired to the
dedicated workflow with `postgres:16.10`. They prove:

- unlocked SELECT + client `clock_timestamp()` can omit an in-flight
  insert whose `erased_at` is earlier than that clock;
- `LOCK TABLE … IN SHARE MODE` waits for that writer and includes it;
- `row-set-only` persist does not PASS a cutoff;
- a later insert is not in the lock-held snapshot; later cutoff BLOCKED.

Publication tests (no Postgres):

- older pending retried after a newer seal is discarded;
- older concurrent persist raises `StaleSnapshotError` (no regression);
- interruption between snapshot and seal does not advance coverage;
- `state.json` pointed at an older seal still recovers the newer ID set;
- public CLI output contains no `authUserId`.

**Honest coverage statement:** a verified `table-share-lock` seal means
every tombstone whose inserting transaction **committed before** the
lock-held auth-DB `clock_timestamp()` is in the snapshot. It does **not**
mean every row with `erased_at ≤ coveredThrough` is present.

## 4. Residual limitations

- Periodic export is not zero-loss. Uncovered window =
  `incident_time − last_trusted_coveredThrough`.
- `--from-ledger --visibility-protocol table-share-lock` is operator
  attestation, not a cryptographic proof the lock was held.
- SHA-256 is integrity of present objects, not authenticity, not
  deletion/rollback protection.
- Directory backend is test/local unless independently proven off-host.
- No remote store, no production enablement, no real restore evidence.
- Nightly backup export remains unlocked; this PR does not change it.
- Auth/DB clock vs operator incident-time clock are assumed UTC-aligned.

## 5. Concrete remote-storage requirements (blocker)

Before anyone calls this “off-host durable”:

1. Prefix/container **not** on the production VM.
2. Already-authorized identity; do not retrieve `BACKUP_AZURE_*` here.
3. Versioning + delete protection (object lock / MFA-delete).
4. Checksum **and** store version history. Checksums alone are insufficient.
5. Least privilege; no payload logs; 0600 supplements.
6. Retention ≥ backup retention and until a newer trusted seal exists.

Azure listing/login is unavailable from this task. Do not invent a
container or credentials.

## 6. Scoped integration (later; not in this PR)

Do **not** add a scheduler or edit shared backup entrypoints now.

1. Independent lock-protocol exporter, proposed **every 15 minutes**.
2. Measurable window: `now − coveredThrough`. Freshness fail at **20
   minutes** if the job should be healthy (interval + 5 minute slack).
3. Export exit 1/4 or `coverageAdvanced=false` while enabled is a fail.
4. Recover exit 3 refuses restore when coverage is insufficient.
5. Nightly-backup-only export does **not** close the between-backups gap
   and must not be described as doing so.
6. Optional later: restore-drill calls recover, then
   `restore-erasure-ledger.py` with the printed `coverageThrough` only.

## 7. Acceptance layers

| Layer | Status |
|---|---|
| Standalone-tool acceptance | Tools + synthetic tests exist; `c86fd69b` dedicated workflow PASS. Follow-up CI must re-run including Postgres tests. |
| Real off-host durability | **Not accepted** |
| Production enablement | **Not accepted** (`PARKIO_OFFHOST_ERASURE_ENABLED` off) |
| Actual recovery acceptance | **Not accepted** (no real export/restore/erasure) |

**HOLD.** Keep #102 draft.
