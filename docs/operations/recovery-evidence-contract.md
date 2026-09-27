# Recovery evidence contract (isolated design)

Draft only. Not production enablement, host install, or F-03 closure.
Baseline: merged #109 `2877ec81`. #104 remains HOLD at `c24f4f3d` and is
not modified.

`verifiedCoverage` stays **false** on the production restore path. A JSON
file that *contains* digest, covered-through, protocol, database identity,
producer, and publication fields is **not** trusted evidence.

## 1. Trustworthy coverage

The consumer verifies six independent facts. All must pass. Any one
missing is `REJECT`.

| Fact | How it is verified | Not sufficient |
|---|---|---|
| Ledger digest | SHA-256 of the **store** object bytes, recomputed by the consumer | A `ledgerDigest` field on a local file |
| Covered-through | Taken from the **signed store object**, compared to the requested cutoff | Stamp `timestamp`, merged IDs, `--supplemental-covered-through` |
| Capture protocol | Must be `table-share-lock` on the signed object | Operator-written protocol on a claim file |
| Database identity | Signed `databaseIdentity` must equal the expected auth DB | Hostname or env guess |
| Producer authenticity | HMAC over the canonical store object with a **pre-distributed** fixture/producer key | Checksum of a still-present file |
| Durable publication | The object store returns the bytes and a publication receipt **before** ACK | “We wrote a file next to the stamp” |

Handing the consumer only a claim file, with no store object, is `REJECT`
even when every field looks complete.

### #104 SHARE-lock capture (reference, not imported)

`#104` `offhost-erasure-locked-snapshot.sql` is the intended live capture:

1. `BEGIN` at **READ COMMITTED**
2. Bounded `lock_timeout` / `statement_timeout`
3. `LOCK TABLE erased_user_tombstones IN SHARE MODE`
4. `SELECT` the full table and `clock_timestamp()` **while the lock is held**
5. `COMMIT` or abort. Persist happens **after** the DB transaction ends.

Reviewed properties (not re-implemented here, not enabled):

- **Isolation.** SHARE waits for in-flight writers and blocks new
  `INSERT`/`UPDATE`/`DELETE` on the table. The following `SELECT` sees
  every row whose inserting transaction has committed.
- **Snapshot timing.** `coveredThrough` is the auth-DB
  `clock_timestamp()` in the same locked statement as the row set. It is
  a commit horizon on that clock, **not** a bound on `erased_at`
  (request-start application clock).
- **Concurrent writes.** A concurrent insert waits. It cannot commit
  during the lock, so it cannot enter that snapshot. After `COMMIT` the
  lock is released; later commits are the post-watermark gap.
- **Rollback / timeout.** `ON_ERROR_STOP` aborts. Nonzero psql is not
  publishable coverage (`#104` `locked_snapshot` raises).
- **Persist-after-transaction.** `#104` `snapshot_then_publish` returns
  from psql (lock released) before `persist_complete_snapshot`. A persist
  failure writes no seal and does not advance coverage. The window
  between COMMIT and persist-ack is uncertified.

`#104` does **not** yet provide producer authenticity, database-identity
binding, or a durable publication receipt independent of a local file.
`--visibility-protocol` on a file is operator attestation. `FileStore`
is not off-host. This contract treats those as unresolved.

## 2. Post-watermark erasure gap

If an erasure **commits** after the last persist-acked watermark and the
primary host is then lost, that erasure is **uncertified**. The recovery
cutoff is the incident requirement. It is not lowered to the watermark
to obtain PASS. An older watermark is not permission to resurrect a
later-deleted principal from a dump that still has them `ACTIVE`.

**Acknowledgement semantics**

An erasure is **acknowledged** only after:

1. the tombstone is committed in auth, and
2. a lock-protocol snapshot that **includes that identifier** is
   persist-acked in the off-host store, and
3. the consumer can re-verify that store object.

Until then the requester sees `ERASURE_PENDING_DURABLE`. Client retries
are expected. A committed-but-unpublished tombstone on a host that later
dies is **not** an ACK.

**Failure handling**

| Failure | Durable effect | Caller |
|---|---|---|
| Capture TX abort / lock timeout | No snapshot, no ACK | Retry capture |
| Persist failure after successful capture | No publication receipt, no ACK, coverage does not advance | Retry persist of that snapshot; do not ACK |
| Host loss after ACK | Watermark + ledger survive in the off-host store | Restore may use that watermark only |
| Host loss before ACK | Erasure is uncertified and unacknowledged | Restore BLOCKED if cutoff exceeds last ACK’d watermark; user/client retries erasure after recovery |

**Database backup RPO ≠ erasure-record durability.** A COMPLETE stamp
can be minutes or hours behind. Nightly dump export is an unlocked
`SELECT` and does not certify commit-visible coverage. Erasure-record
durability is only the persist-acked lock-protocol watermark. Backup
RPO may lose recent rows; it must not be used as erasure ACK.

## 3. Replay completion

Public traffic stays disabled until every required participant has a
**durable ACK** for the same erasure-set digest.

Required isolated participants: `auth`, `user`, `parking`, `media`.

- **auth:** replay tombstones; no tombstoned account is `ACTIVE`.
- **user / parking:** participant erase/de-identify for those IDs.
- **media:** listed objects for those IDs are absent from the isolated
  bucket; unrelated objects remain.

ACKs are `{participant, erasureSetDigest, status=erased}` and are
idempotent. Retry repeats the same digest. If any required participant
is missing or reports a different digest, expose is `REFUSED`. This
check does not start gateway, Slack, or Fluent Bit.

## 4. Production path

`parkio_restore_refuse_unverified_production` is unchanged. Isolated
fixtures may continue to use `--isolated-fixture`. This package does
**not** set `verifiedCoverage=true` and does not add an evidence-file
bypass.

## 5. Implementation sequence

1. This draft: contract consumer, isolated capture/persist/replay model,
   synthetic tests, restore-entrypoint refusal retained.
2. Later, without un-HOLD of #104: adapt #104 lock-capture **output** to
   this consumer’s store object (still disabled in production).
3. Later: real off-host WORM/versioned store and producer-key
   distribution. Checksums alone stay insufficient.
4. Later: auth ACK only after persist-ack (product change).
5. Later: wire replay-completion into `restore-hosted-beta.sh` **after**
   a production watermark exists. Not in this PR.

## 6. Unresolved assumptions and #104 overlap

- Producer key distribution and rotation are not designed for
  production. Tests use a fixture HMAC key.
- The durable store (account, container, WORM, deletion resistance) is
  still a `#104` blocker. This PR uses a disposable directory store.
- Auth `databaseIdentity` schema (system identifier vs DSN) is not
  finalized.
- Whether `#104` seal JSON is adopted or replaced is open. Do not merge
  the formats by copying `#104` files here.
- `#104` `restore-drill-01.sh` sources recovery-coordination; this PR
  does not.
- Live Postgres SHARE-lock behavior is accepted as `#104`’s claim; this
  PR models it in-process for isolated proof and does not start
  production exporters.
