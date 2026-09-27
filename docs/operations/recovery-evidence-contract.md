# Recovery evidence contract (isolated design)

Draft only. Not production enablement, host install, or F-03 closure.
Baseline: merged #109 `2877ec81`. #104 remains HOLD at `c24f4f3d` and is
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
fixtures may continue to use `--isolated-fixture`. This package does
**not** set `verifiedCoverage=true` and does not add an evidence-file
bypass.

## 7. Unresolved design decisions

1. When (if ever) auth HTTP ACK waits for persist-ack.
2. Off-host store account, WORM/versioning, and deletion resistance
   (`#104` blocker). Not designed here.
3. Producer key distribution, rotation, and retirement (`not-before`).
4. `databaseIdentity` schema (system identifier vs DSN).
5. Whether `#104` seal JSON is adopted or replaced.
6. How restore-attempt ACKs are stored so they cannot be reused across
   runs (production table is request-scoped only).
7. Whether Kafka / participant-local tombstones after auth-DB loss are
   treated as residue to reconcile or as untrusted.
8. Independent monotonic publication sequence vs DB `clock_timestamp()`
   under clock rollback.
9. Completeness proof a consumer can verify without trusting the
   producer’s protocol claim.

## 8. Implementation sequence

1. This draft: corrected contract, inventory, trust split, synthetic
   tests including disposable PostgreSQL SHARE-lock, restore refusal
   retained.
2. Later, without un-HOLD of #104: adapt lock-capture **output** to this
   consumer (still disabled in production).
3. Later: real off-host store and producer-key distribution.
4. Later: product change for persist-before-ACK, if accepted.
5. Later: bind replay-completion into `restore-hosted-beta.sh` **after**
   a production watermark exists. Not in this PR.