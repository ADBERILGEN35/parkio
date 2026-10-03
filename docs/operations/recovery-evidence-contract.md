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
- **Key rotation.** No key id, not-before, or retirement schedule.
  Tests use a fixture HMAC key.
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
| key rotation / retired key | model/unit |
| missing participant ACK | model/unit |
| prior-attempt ACK replay | model/unit negative |
| isolated replay preserves unrelated | **modeled replay**, not production-entrypoint acceptance |
| concurrent SHARE vs INSERT | **real PostgreSQL locking** (`test-recovery-evidence-pg-share-lock.py`) |
| aborted SHARE capture | **real PostgreSQL locking** |
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

### Durable record format v1 (Java and Python)

One object format for the Python model (`scripts/lib/recovery_persist_protocol.py`)
and auth-service (`com.parkio.auth.application.durable`), pinned by shared fixtures:

| Object (`kind`) | Key | Signed fields (hex HMAC-SHA256) | Also bound by |
|---|---|---|---|
| Pending record (`erasure-pending-record`) | `records/<erasureRequestId>.json` | `schemaVersion`, `kind`, `erasureRecordId`, `erasureRequestId`, `authUserId`, `sequence`, `databaseIdentity`, `producerId`, `bodyDigest` | `bodyDigest` = SHA-256 of canonical `{authUserId, erasureRequestId, erasedAt}` |
| Sequence marker (`sequence-allocation`) | `sequences/<sequence, 16 digits>.json` | unsigned if-not-exists reservation | — |
| Frontier (`erasure-expected-frontier`) | `frontier/expected-through.json` | `schemaVersion`, `kind`, `expectedThrough`, `highestReserved`, `databaseIdentity`, `producerId`, `frontierDigest` | `frontierDigest` = SHA-256 of canonical `{kind, expectedThrough, highestReserved}` |
| Checkpoint (`erasure-checkpoint`) | `checkpoints/<sequence, 16 digits>.json` | `schemaVersion`, `kind`, `sequence`, `databaseIdentity`, `producerId`, `ledgerDigest`, `captureProtocol` | `ledgerDigest` over `{kind: "erasure-ledger", entries}` |

- Object bytes are the canonical JSON of §4 (sorted keys, compact separators,
  ASCII escapes), including the unsigned `signature`.
- UUIDs are lowercase text. `erasedAt` is `java.time.Instant#toString` after
  truncation to microseconds (no fraction for whole seconds, otherwise 3 or 6
  digits); the Python model treats it as opaque text. The Java producer stores
  the erasure time at microsecond precision before it is persisted or published:
  the JDBC driver rounds sub-microsecond digits, so a nanosecond time would let a
  record rebuilt from the database differ from the first one (#172 review B1).
- `producerId` selects the consumer's pre-distributed key; v1 has no separate
  key id (rotation is stage 3 below).
- Both verifiers reach the same outcome and message: no frontier is `UNKNOWN`;
  a missing record in `1..expectedThrough` is `BLOCKED`; requiring a sequence
  above the frontier or in a gap is refused; tampered, re-sequenced,
  wrong-database or unknown-producer objects are rejected.
- Fixtures: `services/auth-service/src/test/resources/durable-erasure-evidence/v1`,
  generated from the Python model by
  `scripts/generate-durable-erasure-evidence-fixtures.py` (`--check` reports
  drift) and checked by `scripts/test-durable-erasure-evidence-interop.py`,
  `DurableErasureEvidenceInteropTest` and `CanonicalJsonTest`.
- Not part of v1 in Java: checkpoint production, key distribution and rotation. Durable recording stays
  default-off and `verifiedCoverage` stays false.

### Off-host object-lock store (auth-service adapter)

`ObjectLockDurableErasureRecordStore` implements the durable record port on an
S3-compatible bucket with **object lock** (and therefore versioning), writing
format v1. No store product has been chosen (§9 item 1); the adapter assumes
the S3 API with object-lock semantics and is tested only against a disposable
MinIO bucket.

- Protocol as in the Python model: reserve a sequence with a marker, raise the
  frontier's `highestReserved`, publish the signed record, raise
  `expectedThrough` to cover it. A record counts as found only once the signed
  frontier covers it; a crash in between is completed by the next put.
- Every object version is written with a retention lock
  (`retention-mode` GOVERNANCE or COMPLIANCE, `retention` duration; both
  required). Nothing is overwritten or deleted by the adapter.
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
  none does, recovery fails with that error. The store reads each version once
  (versions never change); recovery reads them all.
- Identical retry returns the existing record; a different body under the same
  request id is a conflict. Store I/O refuses to run inside a database
  transaction. Any store or verification failure is
  `DURABLE_RECORDING_UNAVAILABLE` (the request stays `PENDING_DURABLE`).
- One auth instance may write a bucket at a time (writes are serialised in the
  JVM; the frontier is read-modify-written).
- Recovery needs only the bucket: `ObjectLockEvidenceObjects.connect(...)` with
  `DurableErasureEvidenceVerifier.recover(...)`.
- Configuration `parkio.privacy.account-erasure.durable-store.object-lock.*`
  (`PARKIO_ERASURE_STORE_*`): `enabled` (default `false`), `endpoint`,
  `region`, `bucket`, `access-key`, `secret-key`, `retention-mode`,
  `retention`, `database-identity`, `producer-id`, `producer-key`,
  `connect-timeout`, `call-timeout`. When enabled, a missing setting or a
  bucket without object lock stops startup (names only, never values).

### Participants and restore ACKs

Required: `auth` plus `user`, `parking`, `media`, `moderation`,
`gamification`, `notification`, `analytics`, `ai-validation`.
Media object storage is covered by the media ACK. Gateway waitlist and
aggregates stay excluded.

Restore ACKs are
`{recoveryAttemptId, restoredDatasetId, participant, erasureSetDigest}`.
Live `erasure_service_acks` stay request-scoped and cannot authorize a
new restore.

### #104 reuse vs replacement

Reuse later (do not import while HOLD):

- `offhost-erasure-locked-snapshot.sql` (READ COMMITTED, SHARE,
  `clock_timestamp()` while locked, abort-on-error)
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
   Still disabled in production. Do not un-HOLD #104.
3. Real off-host WORM/versioned store and `keyId` rotation.
4. Restore-hosted-beta consumes the latest trusted checkpoint + pending
   tail; expose stays refused when the tail is unknown.
   `verifiedCoverage` stays false until a later, separate certification.

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
   is not that store.
2. **Public DELETE status.** Either add `DURABLY_RECORDED` /
   `PENDING_DURABLE` to the API, or keep returning `IN_PROGRESS` until
   persist-ack and document that today's immediate `IN_PROGRESS` is
   only `ACCEPTED`.

No other open design question is deferred. CSRF and marketing CodeQL
findings are outside this contract.
