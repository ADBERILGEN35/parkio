# Recovery evidence contract (isolated design)

Draft only. Not production enablement, host install, or F-03 closure.
Integration baseline: `api` at `0a5d1de` (after #121 destination binding). #104 remains HOLD at `c24f4f3d` and is
not modified.

`verifiedCoverage` stays **false** on the production restore path. A JSON
file that *contains* digest, covered-through, protocol, database identity,
producer, and publication fields is **not** trusted evidence.

This revision corrects the first draft: persist-before-ACK is **proposed**,
the required participant set is **not** auth/user/parking/media, and HMAC
plus a publication receipt do **not** prove completeness or freshness.

## 1. Current erasure path (implemented)

`DELETE /api/v1/account` + password, flag
`parkio.privacy.account-erasure.enabled` default **false**.

In one auth transaction `AccountErasureApplicationService.requestDeletion`:

1. Application clock `now = clock.instant()` at request start.
2. `beginErasure`, revoke refresh, consume reset tokens.
3. Insert `erased_user_tombstones` (`erased_at = now`).
4. Insert `erasure_requests` status `IN_PROGRESS`.
5. Append outbox `UserErasureRequested` (`eventId`, `erasureRequestId`,
   `authUserId`, `occurredAt`).
6. Commit. HTTP returns `IN_PROGRESS` immediately.

After commit, auth outbox relay publishes to Kafka `parkio.privacy.erasure`
(14d). Each configured participant consumes, inserts its local tombstone,
mutates its store, then `POST /internal/erasure/acks`. Auth upserts
`erasure_service_acks`. `COMPLETE` is set only after SUCCESS acks from
**every configured participant**. `replayTombstones` republishes the
outbox; it does not create an off-host record.

There is **no** durable off-host publication in production.
`PARKIO_OFFHOST_ERASURE_ENABLED` stays unset. `#104` lock-capture remains
HOLD. Nightly `erasure-tombstones.sh` is an unlocked `SELECT`, not a
lock-protocol watermark.

### Acknowledgement states

| State | What is true | Implemented today? |
|---|---|---|
| **Request accepted** | Auth TX committed. API returned `IN_PROGRESS`. Tombstone + request + outbox exist in auth DB. | Yes. This is the HTTP ACK. |
| **Erasure pending durable recording** | Identifier is not in any persist-acked lock-protocol watermark. | Yes, for every erasure: no such watermark exists. |
| **Erasure durably recorded** | A SHARE-lock snapshot that includes the identifier was persist-acked in an off-host store and can be re-verified. | **Proposed only.** `DurableAckGate` is the isolated model. Production does not wait for persist. |
| **Erasure completed across participants** | Auth `erasure_requests.status = COMPLETE` after all configured participant SUCCESS acks. Local participant tombstones / deletes / sentinel rewrites done. | Yes, in the service databases and (for media) object storage. Still not off-host coverage. |

Do not present persist-before-ACK as current behavior. A client that
received `IN_PROGRESS` has a **request accepted**, not a durable
erasure record.

## 2. Primary-host loss and unknown tail

Pending requests live in:

- auth PostgreSQL (`erasure_requests`, `erased_user_tombstones`, outbox)
- Kafka `parkio.privacy.erasure` for 14d **if** the outbox already relayed
  **and** Kafka survives
- participant local DBs / media objects **if** those handlers already ran
- backup stamp `erasure-tombstones.json` only up to an unlocked SELECT RPO

None of those is a persist-acked erasure record. Calling a lost request
**unacknowledged** does not preserve it. After auth-DB loss, the coordinator
row is gone unless a dump contains it. Kafka, if still present, is a
14-day command, not coverage. Participant tombstones, if those hosts
survived, are local residue, not a certified watermark.

**Unknown missing tail** (cutoff later than the last persist-acked
watermark, or no watermark at all):

1. Restore verdict is `BLOCKED`.
2. Do **not** lower the cutoff to an older watermark to obtain PASS.
3. Do **not** expose the restored copy because an older watermark or a
   COMPLETE stamp exists.
4. Do **not** invent coverage from backup RPO, Kafka, or “unacknowledged”.
5. The operator keeps the copy unexposed. Clients re-request erasure after
   recovery if the identifier is still present and they still need it.

Database backup RPO is not erasure-record durability.

## 3. Participant inventory (from the repository)

Configured ACK set (`DEFAULT_PARTICIPANTS` /
`parkio.privacy.account-erasure.participants`):

| Participant | Why required | Recoverable user-linked data |
|---|---|---|
| **auth** (coordinator, not in CSV) | Tombstone, request, outbox, ERASED row, refresh/reset revocation | `erased_user_tombstones`, `erasure_requests`, `erasure_service_acks`, `auth_users`, outbox |
| **user** | Handler hard-deletes profile graph | profile, saved/favourite/recent places, prefs, vehicle, trust projection, pending status |
| **parking** | Handler deletes sessions/logs and anonymizes shared facts | sessions, search/view/verify logs, idempotency; spots `owner_user_id` → sentinel; trust/fraud/reward subject ids |
| **media** | Handler deletes objects then soft-deletes metadata | `media_files` + object storage keys; idempotency |
| **moderation** | Handler retains cases; rewrites identities | reports, cases, appeals, violations → sentinel |
| **gamification** | Handler deletes progress; anonymizes points | level progress, trust scores, contribution snapshots, `point_transactions` |
| **notification** | Handler hard-deletes tokens and messages | device tokens, prefs, notifications, delivery attempts |
| **analytics** | Handler deletes user-keyed rows | `analytics_events`, `user_analytics_snapshots` |
| **ai-validation** | Handler anonymizes requester | `ai_validation_results.requested_by_user_id` → sentinel |

### Other stores (required for restore completeness, not extra ACK names)

| Store | Role | Why not a ninth ACK name |
|---|---|---|
| Media object storage (MinIO) | User-owned bytes | Covered by the **media** ACK. Replay must delete restored objects, not only DB rows. |
| Kafka `parkio.privacy.erasure` + auth outbox | Durable command (14d) | Transport, not a participant store of profile data. |
| Backup stamp `erasure-tombstones.json` | Unlocked export in each stamp | RPO artifact. Not lock-protocol coverage. |
| Per-participant `erased_user_tombstones` | Local idempotency | Follows the service ACK; not a separate expose voter. |

### Excluded (not in this workflow)

| Store | Why excluded |
|---|---|
| Gateway waitlist | Email-hash only; no `authUserId`. Separate workflow. |
| Ranking evaluation / shadow tables | No account user id (aggregates / correlation ids). |
| Analytics daily/parking snapshots | Documented as aggregates without `user_id`. |
| Municipal operator VARCHAR / public explore | Not account-linked. |
| SPA telemetry | Designed without user id / coords / facility ids. |
| Gateway Redis status cache | Derived cache, not a recovery dataset. |

auth/user/parking/media is **not** exhaustive. Expose after restore
requires durable ACKs from **auth plus the eight configured services**,
bound as below.

### ACK binding (proposed restore gate)

Each participant ACK is
`{participant, recoveryAttemptId, restoredDatasetId, erasureSetDigest}`.

- `recoveryAttemptId` — this restore run, not a previous drill.
- `restoredDatasetId` — the restored stamp / dump identity.
- `erasureSetDigest` — SHA-256 of the verified ledger bytes.

An ACK from an earlier attempt, a different restored dataset, or a
different digest cannot authorize a new restore. Production
`erasure_service_acks` today bind only `(erasure_request_id, service_name)`
and are **not** restore-attempt scoped.

## 4. Evidence trust (five facts)

Serialization of the signed subset and of the ledger is
`json.dumps(..., separators=(",", ":"), sort_keys=True)` UTF-8.
HMAC-SHA256 is over that canonical signed subset only.

Signed fields: `schemaVersion`, `kind`, `ledgerDigest`, `coveredThrough`,
`captureProtocol`, `databaseIdentity`, `producerId`.
`entries` are bound only through `ledgerDigest` (SHA-256 of
`{"kind":"erasure-ledger","entries":...}`).
`signature`, `publicationId`, and the store receipt are **not** signed.

Producer keys and the expected `databaseIdentity` are taken from
**pre-distributed consumer config**, never from the submitted object.

| Fact | What would prove it | What this consumer actually checks | Not proved by HMAC + receipt |
|---|---|---|---|
| **Producer authenticity** | HMAC with a pre-distributed key for `producerId` | Yes, over `SIGNED_FIELDS` | — |
| **Byte integrity** | Recomputed SHA-256 of store ledger bytes equals `ledgerDigest`; entries rematch | Yes | — |
| **Durable store publication** | Off-host WORM/versioned ack independent of the producer host | Directory `put` + etag only (fixture) | Receipt is local; FileStore is not off-host |
| **Completeness through a boundary** | SHARE lock held at capture; every commit-visible row included | **Not checked.** Protocol is a signed claim | A valid signature on an incomplete snapshot is still `ACCEPT_ISOLATED` |
| **Freshness** | Consumer high-water mark refuses older valid objects | Yes, only if the consumer retained `last_accepted` | First verify, or a reset consumer, accepts any old valid object with cutoff ≤ coveredThrough |

`ACCEPT_ISOLATED` therefore proves producer authenticity and byte
integrity of the presented object. It does **not** certify production,
durable off-host publication, completeness, or freshness without a
consumer watermark. `verifiedCoverage` stays false.

### Assumptions and remaining limitations

- **Delayed / out-of-order publication.** Two valid objects can exist.
  The consumer rejects an older `coveredThrough` after a newer accept,
  and rejects a different digest at the same watermark. It cannot order
  publications that the store has not yet shown it.
- **Old valid evidence.** HMAC remains valid after rotation if the old
  key is still trusted. Freshness is a consumer watermark, not a
  signature expiry or key `not-before`.
- **Key rotation.** Not modelled by this stage-0 evidence (one key per
  `producerId`). Durable evidence format v2 (§7) signs a `keyId` and checks it
  against trust windows and retirement; see "Trust and key rotation".
- **Transaction isolation.** The consumer cannot verify that SHARE was
  held. Isolation is a capture-time property.
- **Clock rollback.** `coveredThrough` is the capture clock. A rolled-back
  DB clock can emit an older legitimate watermark (rejected as stale) or,
  if the consumer watermark is reset, re-accept an older signed object.
- `#104` `FileStore` and `--visibility-protocol` remain operator
  attestation, not this consumer.

## 5. Test classification

| Scenario | Class |
|---|---|
| `verifiedCoverage` stays false | model/unit (source guard) |
| persist-before-ACK is not implemented | model/unit (source guard) |
| configured participant inventory | model/unit (auth source) |
| claim file alone rejected | model/unit (directory store) |
| store-backed `ACCEPT_ISOLATED` | model/unit; **not** a real object-store test |
| in-process SHARE wait | model/unit simulation |
| capture abort publishes nothing | model/unit |
| persist failure does not ACK | model/unit (proposed gate) |
| missing / tampered / wrong DB | model/unit |
| post-watermark host loss / unknown tail | model/unit |
| HMAC+receipt incomplete snapshot | model/unit negative |
| older valid evidence freshness | model/unit negative |
| key rotation / retired key | model/unit (stage 0); format v2: model/unit + shared fixtures + disposable MinIO (`ObjectLockKeyRotationIT`) |
| `databaseIdentity` from `system_identifier` + `datname`; trust pinned to another database refused at startup | **real PostgreSQL** (`ErasureCheckpointPostgresMinioIT`) + model/unit (`ObjectLockDurableStoreConfigTest`) |
| missing participant ACK | model/unit |
| prior-attempt ACK replay | model/unit negative |
| isolated replay preserves unrelated | **modeled replay**, not production-entrypoint acceptance |
| concurrent SHARE vs INSERT | **real PostgreSQL locking** (`test-recovery-evidence-pg-share-lock.py`) |
| aborted SHARE capture | **real PostgreSQL locking** |
| checkpoint bytes (Java writer = Python model) | model/unit (shared fixtures) |
| checkpoint capture waits for an in-flight INSERT; lock timeout publishes nothing; publication only after commit | **real PostgreSQL + disposable MinIO** (`ErasureCheckpointPostgresMinioIT`) |
| latest checkpoint + higher records cover every tombstone (erasure between capture and reservation) | **real PostgreSQL + disposable MinIO** |
| checkpoint reservation left by a failed publication is filled | disposable MinIO (`ObjectLockCheckpointStoreIT`) |
| consumer refuses an older checkpoint after a newer one | model/unit + disposable MinIO |
| checkpoints default off, no caller | model/unit (source guard) |
| `test-restore-safe-preflight.sh` | **real restore-entrypoint** with docker/openssl/psql **stubs**; existing production refusal intact |

## 6. Production path

`parkio_restore_refuse_unverified_production` is unchanged. Isolated
fixtures require #121's destination-bound orchestrator ticket; the CLI
`--isolated-fixture` flag alone cannot authorize any apply. This package does
**not** set `verifiedCoverage=true` and does not add an evidence-file
bypass.

## 7. Recommended architecture

One design. Not implemented. `verifiedCoverage` stays false.

### Trust boundary

The **trusted producer** is the auth-database capture process that holds
the SHARE lock, reads `erased_user_tombstones`, and signs the result
with a pre-distributed `keyId`. The consumer trusts that this producer
followed the tested lock protocol. The consumer does **not**
mathematically re-prove a remote snapshot.

The consumer verifies, independently of the producer host:

1. producer authenticity (`keyId` + signature over the signed subset)
2. byte integrity (recomputed ledger digest)
3. durable publication (store receipt from a store that is not the
   primary host)
4. freshness (monotonic `sequence`, not wall clock)
5. cutoff (`sequence`/`coveredThrough` covers the incident cutoff)
6. `databaseIdentity` equals the pre-distributed auth DB identity
   (`system_identifier` + `datname`, not a DSN)

Completeness through the lock is a **producer protocol claim** backed by
the disposable PostgreSQL SHARE-lock tests. HMAC plus a receipt never
prove it.

### API states

| Public state | When | Durable if primary host is lost? |
|---|---|---|
| `ACCEPTED` | Auth TX committed (today's `IN_PROGRESS`) | No |
| `PENDING_DURABLE` | Persist in flight or last persist failed | No |
| `DURABLY_RECORDED` | Identifier is in a persist-acked pending record or checkpoint | Yes, from the off-host store |
| `COMPLETE` | All required participant SUCCESS acks for that request | Live-system complete only; restore still needs the durable set |

`DURABLY_RECORDED` is the first acknowledgement that may be treated as
an erasure record. `ACCEPTED` is not. Clients retry `PENDING_DURABLE`.
Retry is not how recovery fills an unknown tail.

### Recording before host loss

After the auth TX commits, and **before** any durable ACK:

1. Append a signed **pending record** `{sequence, erasureRequestId,
   authUserId, erasedAt, databaseIdentity}` to the off-host store.
2. Persist-ack that object.
3. Then return `DURABLY_RECORDED` (or keep `IN_PROGRESS` as the public
   umbrella only if the server has already persist-acked — see operator
   note).

A periodic **checkpoint** is a SHARE-lock full-table snapshot with the
next `sequence`. Recovery after total primary-host loss loads the latest
trusted checkpoint plus pending records with higher `sequence`. If that
set does not cover the cutoff, restore is `BLOCKED` and the copy stays
**unexposed**. Do not lower the cutoff. Do not expose because a client
can delete again later. Kafka and participant-local tombstones are
untrusted residue.

### Durable record format v2 (Java and Python)

One object format for the Python model (`scripts/lib/recovery_persist_protocol.py`)
and auth-service (`com.parkio.auth.application.durable`), pinned by shared fixtures:

| Object (`kind`) | Key | Signed fields (hex HMAC-SHA256) | Also bound by |
|---|---|---|---|
| Pending record (`erasure-pending-record`) | `records/<erasureRequestId>.json` | `schemaVersion`, `kind`, `erasureRecordId`, `erasureRequestId`, `authUserId`, `sequence`, `databaseIdentity`, `producerId`, `keyId`, `bodyDigest` | `bodyDigest` = SHA-256 of canonical `{authUserId, erasureRequestId, erasedAt}` |
| Sequence marker (`sequence-allocation`) | `sequences/<sequence, 16 digits>.json` | unsigned if-not-exists reservation | — |
| Frontier (`erasure-expected-frontier`) | `frontier/expected-through.json` | `schemaVersion`, `kind`, `expectedThrough`, `highestReserved`, `databaseIdentity`, `producerId`, `keyId`, `frontierDigest` | `frontierDigest` = SHA-256 of canonical `{kind, expectedThrough, highestReserved}` |
| Checkpoint (`erasure-checkpoint`) | `checkpoints/<sequence, 16 digits>.json` | `schemaVersion`, `kind`, `sequence`, `databaseIdentity`, `producerId`, `keyId`, `ledgerDigest`, `captureProtocol` | `ledgerDigest` over `{kind: "erasure-ledger", entries}` |

- Object bytes are the canonical JSON of §4 (sorted keys, compact separators,
  ASCII escapes), including the unsigned `signature`.
- UUIDs are lowercase text. `erasedAt` is `java.time.Instant#toString` after
  truncation to microseconds (no fraction for whole seconds, otherwise 3 or 6
  digits); the Python model treats it as opaque text. The Java producer stores
  the erasure time at microsecond precision before it is persisted or published:
  the JDBC driver rounds sub-microsecond digits, so a nanosecond time would let a
  record rebuilt from the database differ from the first one (#172 review B1).
- `schemaVersion` is 2. Every signed object names its signing key (`keyId`);
  the consumer's trust maps it to the producer, secret, window and retirement
  state (Trust and key rotation, below). Objects of any other version,
  including format v1 (no `keyId`, never produced outside tests), are refused
  with `unsupported schema version`.
- Both verifiers reach the same outcome and message: no frontier is `UNKNOWN`;
  a missing record in `1..expectedThrough` is `BLOCKED`; requiring a sequence
  above the frontier or in a gap is refused; tampered, re-sequenced,
  wrong-database, unknown-key, foreign-key, retired-key and not-yet-valid-key
  objects are rejected.
- Fixtures: `services/auth-service/src/test/resources/durable-erasure-evidence/v2`,
  generated from the Python model by
  `scripts/generate-durable-erasure-evidence-fixtures.py` (`--check` reports
  drift) and checked by `scripts/test-durable-erasure-evidence-interop.py`,
  `DurableErasureEvidenceInteropTest` and `CanonicalJsonTest`. Each case pins
  its trust and verification instant; `trust-documents.json` pins the trust
  document loader (same accept/refuse and message in both languages).
- Checkpoint `entries` are one `{authUserId, erasedAt}` object per
  `erased_user_tombstones` row, ordered by `authUserId` text (the order of
  PostgreSQL's `ORDER BY auth_user_id`), `erasedAt` as above. The marker that
  reserves a checkpoint's sequence has the nil UUID
  `00000000-0000-0000-0000-000000000000` as `erasureRequestId` (never a
  request id).
- Java writes checkpoints (default-off producer below). Durable recording
  stays default-off and `verifiedCoverage` stays false.

### Trust and key rotation (format v2)

The consumer's **trust document** is pre-distributed and never read from the
evidence. It is JSON (`format` `parkio-erasure-evidence-trust`, `version` 1)
with the pinned `databaseIdentity` and the producer `keys`: `keyId`,
`producerId`, `notBefore`, optional `notAfter`, `retired`, and `keyHex`, an
HMAC secret of at least 32 bytes. It therefore holds secrets: never commit
one (fixtures are synthetic and live only under tests). Loader errors name the
field and key id, never a secret.

- **databaseIdentity** is `postgresql:<system_identifier>:<datname>`.
  `system_identifier` is set by initdb and survives physical replication and
  failover, so the identity names the cluster lineage, not a DSN.
  `pg_control_system()` is readable without superuser rights (PostgreSQL 16).
  The auth service refuses to start when its trust document is pinned to
  another database than the one it uses.
- **Verifier checks**, in order and with the same messages in Python and Java:
  kind; `schemaVersion` 2; `databaseIdentity` equals the trust; `keyId`
  known (`unknown producer key`); the key's `producerId` equals the object's
  (`producer key belongs to another producer`); not retired
  (`retired producer key`); verification instant not before `notBefore`
  (`producer key not yet valid`); signature; digests.
- **Producer rule**: sign only with a key that is not retired and inside
  `[notBefore, notAfter)`. The auth store checks this at startup, before an
  operation reserves anything, and for every signed object; otherwise the
  write is `DURABLE_RECORDING_UNAVAILABLE` and nothing is reserved. The store
  verifies what it reads with the whole trust, so after a rotation it still
  reads the frontier and records the previous key signed.
- `notAfter` ends a key's **signing** period. A verifier keeps accepting what a
  key signed while it was valid: objects are write-once and cannot be
  re-signed, so old evidence stays valid while its key is trusted and not
  retired. Retiring a key revokes it: everything it signed is refused.
- Rotation and custody steps: `docs/operations/erasure-evidence-key-custody.md`.

### Off-host object-lock store (auth-service adapter)

`ObjectLockDurableErasureRecordStore` implements the durable record port on an
S3-compatible bucket with **object lock** (and therefore versioning), writing
format v2. No store product has been chosen (§9 item 1); the adapter assumes
the S3 API with object-lock semantics and is tested only against a disposable
MinIO bucket.

- Protocol as in the Python model: reserve a sequence with a marker, raise the
  frontier's `highestReserved`, publish the signed record, raise
  `expectedThrough` to cover it. A record counts as found only once the signed
  frontier covers it; a crash in between is completed by the next put.
- Every object version is written with a retention lock
  (`retention-mode` GOVERNANCE or COMPLIANCE, `retention` duration; both
  required). Nothing is overwritten or deleted by the adapter.
  **COMPLIANCE is the intended mode for the real evidence store** (owner
  decision B2, 2026-10-03). Under GOVERNANCE, a principal allowed to bypass
  governance retention can delete a locked version; COMPLIANCE refuses that
  until the retention ends. `ObjectLockDurableErasureRecordStoreIT` shows
  both on the disposable bucket. GOVERNANCE stays accepted for disposable
  test buckets. The retention duration follows the erasure-evidence policy
  and is not chosen here; no real bucket is provisioned.
- The **first version** of a record or marker is canonical: a later write only
  adds a version, a version delete is refused by the lock, and a plain delete
  only adds a delete marker; none of them changes what is read. The frontier
  (the one rewritten object) is read as its **highest verified version**
  (`expectedThrough`, then `highestReserved`), not as the version listed
  first: S3 lists versions by modification time, and a backward clock step on
  the store host makes a later version list as older (a MinIO probe on such a
  host saw 102 stale first-listed versions in 14,467 writes). Frontier
  contents only grow, so the highest verified version is the current one.
  Versions that fail verification are ignored while another one verifies; if
  none does, recovery fails with that error. Ignored versions are tamper
  evidence: recovery reports their number (`ignoredFrontierVersions`), and the
  store logs a warning whenever that number grows. The store reads each
  version once (versions never change); recovery reads them all.
  `ObjectLockEvidenceObjects` reads the frontier only through all of its
  versions: `find` refuses the frontier key and `findAll` refuses every other
  key.
- Identical retry returns the existing record; a different body under the same
  request id is a conflict.
- Every put returns a **receipt** for the canonical version of the record: its
  version id, the SHA-256 of its bytes, and the retention mode and
  retain-until date that the store reports for that version (read back, not
  assumed). A store that reports no lock on the version fails the put. An
  identical retry and a conflict return the receipt of the existing canonical
  version. auth-service logs it (`erasure durable receipt`) and does not store
  it in its database; the bucket stays the source of truth.
- Store I/O refuses to run inside a database
  transaction. Any store or verification failure is
  `DURABLE_RECORDING_UNAVAILABLE` (the request stays `PENDING_DURABLE`).
- One auth instance may write a bucket at a time (writes are serialised in the
  JVM; the frontier is read-modify-written).
- Recovery needs only the bucket: `ObjectLockEvidenceObjects.connect(...)` with
  `DurableErasureEvidenceVerifier.recover(...)`.
- Configuration `parkio.privacy.account-erasure.durable-store.object-lock.*`
  (`PARKIO_ERASURE_STORE_*`): `enabled` (default `false`), `endpoint`,
  `region`, `bucket`, `access-key`, `secret-key`, `retention-mode`,
  `retention`, `trust-file` (the trust document), `producer-key-id` (the
  signing key), `connect-timeout`, `call-timeout`. When enabled, a missing
  setting, an invalid trust document, a signing key that is not in it or may
  not sign now, a trust document pinned to another database, or a bucket
  without object lock stops startup (names only, never values).

### Checkpoints (auth-service producer)

`ErasureCheckpointProducer` publishes signed checkpoints (stage 2) to the
object-lock store. The bean exists only with
`parkio.privacy.account-erasure.durable-store.checkpoint.enabled=true`
(`PARKIO_ERASURE_CHECKPOINT_ENABLED`, default `false`), which also requires the
object-lock store, and nothing in the service calls it. The cadence is an
operator decision, so there is no schedule. The Python guard
`checkpoint_producer_is_disabled` checks the default and the absence of callers.

- **Capture** (`JdbcErasureLedgerCapture`; the #104 SQL output, re-implemented):
  one READ COMMITTED transaction sets `lock_timeout` and `statement_timeout`
  (`lock-timeout` 12s and `statement-timeout` 20s by default), takes
  `LOCK TABLE erased_user_tombstones IN SHARE MODE` (waits for in-flight
  INSERTs, blocks new ones), reads `clock_timestamp()` and every row ordered by
  `auth_user_id`, and commits. A timeout or any error rolls back and publishes
  nothing.
- **Publication** happens only after that commit. The store runs the capture
  inside its writer lock and reserves the checkpoint's sequence after it, so no
  record can take a sequence between the snapshot and the reservation. Every
  pending record below the checkpoint's sequence was therefore reserved before
  the snapshot, after its tombstone committed, and its tombstone is in the
  checkpoint. The frontier then covers the checkpoint as it covers a record;
  retention, the canonical first version and the single-writer rule are the
  store's.
- **Erasure set** for recovery: the latest trusted checkpoint's entries plus
  the trusted pending records with a higher sequence. Tombstones without a
  record (for example from before durable recording) are covered by checkpoints
  only. `TrustedErasureSet` (Java) and `trusted_erasure_set`
  (`scripts/lib/recovery_evidence_bundle.py`) compute it from an evidence bundle,
  and only from an ACCEPT_ISOLATED recovery.
  - A pending record below the checkpoint must be in its ledger with the same
    `erasedAt`.
  - One user with two erasure times is refused.
  - Both languages read the frontier as its highest verified version.
- A checkpoint reservation left without its checkpoint (a store failure or
  crash after the marker) becomes a gap, and `BLOCKED`, once a later record
  raises the frontier past it. The next checkpoint fills that sequence. The rule
  above still holds: the sequence was reserved before the new snapshot.
- `coveredThrough` (the database clock while the lock was held) is returned and
  logged but not published, because v1 does not sign it. Ordering and freshness
  use `sequence`.
- **Consumer freshness**: `CheckpointWatermark` holds the consumer's last
  accepted checkpoint (sequence and ledger digest). An older valid checkpoint,
  or another ledger at the same sequence, is refused. The mark is consumer
  state and is never read from the store.

### Participants and restore ACKs

Required: `auth` plus `user`, `parking`, `media`, `moderation`,
`gamification`, `notification`, `analytics`, `ai-validation`.
Media object storage is covered by the media ACK. Gateway waitlist and
aggregates stay excluded.

Restore ACKs are
`{recoveryAttemptId, restoredDatasetId, participant, erasureSetDigest}`.
Live `erasure_service_acks` stay request-scoped and cannot authorize a
new restore. Replay commands, ACKs and the coordinator's verdict are specified
in `docs/architecture/erasure-restore-replay-contract.md`: one replay command
per user of the trusted erasure set, an ACK per participant and user written in
the replay transaction (the U05 outbox), and `COMPLETE` only when every
configured participant acknowledged every user for this attempt, dataset and
digest. Default-off on both sides.

### #104 reuse vs replacement

Reuse (do not import while HOLD):

- `offhost-erasure-locked-snapshot.sql` (READ COMMITTED, SHARE,
  `clock_timestamp()` while locked, abort-on-error): its protocol and output
  shape are re-implemented by `JdbcErasureLedgerCapture` (stage 2); #104
  itself is untouched
- The rule that persist happens after the DB transaction ends
- Disabled-by-default production flag posture

Replace:

- `FileStore` / local files as durability
- `--visibility-protocol` operator attestation
- SHA-256 of a still-present file as authenticity
- unlocked `erasure-tombstones.sh` SELECT as coverage
- persist-after-commit **without** gating the durable ACK
- any four-service participant set
- `#104` seal JSON as the consumer format

`coveredThrough` remains a human commit-horizon. Ordering and freshness
use `sequence`.

### Trade-offs

- Persist-before-ACK adds off-host latency to deletion. That is the cost
  of a record that survives the primary host.
- The consumer trusts the producer for lock completeness. Removing that
  trust would require a second independent observer inside the database,
  which this design rejects as a new framework.
- Dual-writing pending records and checkpoints can go out of order;
  `sequence` makes the later object win. Delayed older objects are
  rejected.

## 8. Implementation stages

0. **This draft.** Contract, inventory, trust split, synthetic tests,
   real PostgreSQL SHARE-lock, production refusal intact.
1. **Next slice (acceptance below).** Isolated persist-before-ACK of
   pending records. Flag off. No production store.
2. Signed lock-protocol checkpoints using the #104 SQL **output** only.
   Still disabled in production. Do not un-HOLD #104. Implemented default-off
   without a caller or schedule (Checkpoints, §7); the cadence is an operator
   decision.
3. Real off-host WORM/versioned store and `keyId` rotation. `keyId`
   rotation is implemented (format v2, trust document, pinned database
   identity); the real store is not provisioned.
4. Restore-hosted-beta consumes the latest trusted checkpoint + pending
   tail; expose stays refused when the tail is unknown.
   `verifiedCoverage` stays false until a later, separate certification.
   - **Implemented: evidence consumption and the replay entry point.**
     - Evidence bundles: a read-only store export with every frontier version.
     - The trusted erasure set, with Java/Python parity.
     - The one-shot recovery-replay command (owner decision P6), described in
       `docs/architecture/erasure-restore-replay-contract.md`, Recovery-replay command.
       Every check that can refuse runs before any application context exists, so a
       refused run never migrates, writes, subscribes or schedules anything on the target.
       Settings that would point a connection elsewhere are refused, every connection the
       command context opens is re-checked against the target (PR #295 review B6), and the
       relay publishes only the attempt's replay commands (N11).
   - **Object binding (PR #295 review B4).** Both verifiers read a signed object only
     under its own key, and refuse anything else instead of skipping it:
     - a pending record only at `records/<erasureRequestId>.json`, which it also signs as
       `erasureRecordId`;
     - a checkpoint only at `checkpoints/<sequence>.json`, and the latest trusted
       checkpoint's body must carry that sequence;
     - one owner per sequence: two published objects with one sequence are refused;
     - one entry per user in a checkpoint ledger.

     Without this, a checkpoint stored under a later checkpoint's key could silently drop
     a tombstone while coverage still read "through sequence N". The cost is fail-closed:
     anyone who can add a mis-keyed object to the store blocks recovery until an operator
     investigates. A missing signed field is a verification failure in both languages, so
     such a frontier version is ignored and counted like a tampered one.
   - **Target identity (PR #295 review B3).** A recovery target on the production
     cluster (the same `system_identifier`, whatever the database name) is refused, both
     when the restore writes the trusted-set file and when the command connects. An
     identity that does not parse strictly is refused as ambiguous.
   - **Tamper evidence.** `ignoredFrontierVersions` (frontier versions that failed
     verification) is written into the trusted-set file and the verdict, and the command
     refuses a file that states another count.
   - **Implemented (isolated only, #296): the restore integration, the expose gate and
     the drill.**
     - `restore-hosted-beta.sh --erasure-evidence --erasure-trust --recovery-attempt
       --recovery-dir`, with an isolated ticket only. It verifies the bundle before anything
       is decrypted: missing, corrupt, gap or frontier-less evidence exits 3 with nothing
       applied. Trusted evidence writes the trusted-set file and a CLOSED expose gate.
     - `scripts/recovery-replay.sh` starts the eight participants on the ticket's internal
       network (restore replay on, no published port) and runs the recovery-replay command
       once. The media participant maps the backup's bucket onto the ticket's bucket for
       erasure only (`docs/architecture/erasure-restore-replay-contract.md`).
     - `scripts/lib/recovery-expose-gate.py open` records OPEN only on a COMPLETE verdict
       of the same attempt, dataset and set. OPEN starts no service and publishes no port.
     - `scripts/recovery-drill.sh` (workflow `recovery-drill.yml`, dispatch and pull
       request paths) runs the whole path on disposable infrastructure and measures it:
       see `docs/operations/disaster-recovery-runbook.md`, RPO / RTO.
     - The production path stays refused, and `verifiedCoverage` stays false.
   - **Coverage semantics (owner decision D1, 2026-10-06).** Recovery proceeds only when:
     - the frontier is the highest verified version, gap-free and ACCEPT_ISOLATED;
     - every erasure the restored auth database marks `DURABLY_RECORDED`, whatever the
       request status, is in the trusted set (the backup anchor). A backup taken before
       V24 has no durable-recording state, so it cannot be anchored and is refused
       (fail-closed).
   - **Coverage is reported only** as "erasure coverage verified through sequence N
     (frontier version V)". v2 evidence signs no time, so no time-based claim is
     made, and erasures after the last durable frontier cannot be proven absent.
   - **No receipt option.** No offline-verifiable receipt exists:
     `DurableErasureReceipt` is unsigned store metadata that is only logged. So there
     is no required-through or receipt option.

### Smallest next slice — acceptance criteria

Scope: isolated auth + directory/versioned fixture store. No #104
changes. No production enablement.

1. After commit, a durable ACK is issued only if a signed pending record
   for that `authUserId` was persist-acked.
2. Persist failure → `PENDING_DURABLE`, no durable ACK, coverage
   unchanged.
3. Simulated primary-host loss after commit and before persist: the
   request is absent from the store; restore with a later cutoff is
   `BLOCKED`; the restored copy is not exposed.
4. Simulated host loss after persist-ack: the pending record is still
   in the store and verify succeeds without the auth DB.
5. `sequence` is monotonic; an older valid signed object is rejected
   after a newer accept.
6. Production `verifiedCoverage` remains hardcoded false. Existing
   restore-hosted-beta refusal remains intact.
7. Tests stay classified (model/unit vs disposable PostgreSQL vs
   restore-entrypoint stubs).

## 9. Operator input still required

1. **Off-host store location.** Must not be the primary host. Product
   choice (object-lock bucket vs equivalent WORM). Isolated directory
   is not that store. The lock mode is decided (COMPLIANCE, B2); the
   product, region, credentials and retention duration are still open.
2. **Public DELETE status.** Either add `DURABLY_RECORDED` /
   `PENDING_DURABLE` to the API, or keep returning `IN_PROGRESS` until
   persist-ack and document that today's immediate `IN_PROGRESS` is
   only `ACCEPTED`.

No other open design question is deferred. CSRF and marketing CodeQL
findings are outside this contract.
