# Erasure participant ACK outbox contract (U05)

A participant may report `SUCCESS` for an erasure request only after its local
erase has committed. Sending the ACK from inside the erase transaction (the
original `AuthErasureAckClient` call) is unsafe: a rollback or commit failure
after the call leaves the coordinator with a `SUCCESS` for data that still
exists. An `afterCommit` callback is not enough either: a crash between commit
and send loses the ACK.

This contract reuses each service's existing transactional outbox and relay and
auth's existing Kafka ACK consumer. It adds no new framework or topic. Two
participants add schema: media adds `media_erasure_jobs` (V14) for object
deletion that is still pending and a claim token on it (V15), and analytics adds
DLQ columns to its existing outbox for its new relay (V10).

## Rollout status (`api` `9dd3f485`, 2026-10-01)

Source merge, deployment and production acceptance are separate states. This
table records the source state of `api`; the PR that merges last updates it.

| Participant | PR | Source on `api` | Deployed | Production acceptance |
|-------------|----|-----------------|----------|-----------------------|
| gamification | #132 | merged (`1146211f`) | not established | not run |
| user | #133 | merged (`5e417266`) | not established | not run |
| moderation | #135 | draft, not merged | no | not run |
| parking | #137 | draft, not merged | no | not run |
| analytics | #138 | draft, not merged | no | not run |
| ai-validation | #139 | draft, not merged | no | not run |
| notification | #140 | draft, not merged | no | not run |
| media | #141 | draft, not merged | no | not run |

"Not established": the repository holds no evidence that an image built from
the merged source runs anywhere. Deployment pins and the PRIV-001A runtime
acceptance are tracked outside this document. Until a participant's PR merges,
its code on `api` still sends the HTTP ACK inside its erase transaction (the U05
defect).

## Participant side

1. In the same `@Transactional` method as the local erase (tombstone first, then
   deletes/anonymization), append one outbox row:

   | Column | Value |
   |--------|-------|
   | `aggregate_type` | `AccountErasure` |
   | `aggregate_id` | `erasureRequestId` |
   | `event_type` | `UserErasureAcknowledged` |
   | `event_id` | `nameUUIDFromBytes("<requestEventId>:<erasureRequestId>:<service>:ack")` |
   | `payload` | `{eventId, erasureRequestId, authUserId, serviceName, status, occurredAt}` |

   `status` is `SUCCESS`. A handler that fails throws; its transaction rolls back
   with the outbox row, and the erase command is retried by the consumer (then
   dead-lettered by the service's container factory). No `SUCCESS` is ever
   written for work that did not commit.

2. The service's outbox relay publishes only committed rows. Rows with
   `aggregate_type = AccountErasure` go to `parkio.privacy.erasure` (owned and
   provisioned by auth), keyed by `erasureRequestId`, wrapped in the standard
   `EventEnvelope` with `eventType` header `UserErasureAcknowledged`. A row is
   marked published only after the broker ack; otherwise it stays queued and
   the next poll retries it. Delivery is at-least-once.

3. Nothing else sends the ACK. The participant no longer calls
   `POST /internal/erasure/acks`.

### Event identity and duplicates

- The ACK `eventId` is derived from the **incoming request event's** `eventId`,
  plus the request id and service name.
  - Kafka redelivery of the same request event re-runs the full erase
    (idempotent: tombstone upsert, deletes, sentinel rewrite) and re-derives the
    same ACK `eventId`. The outbox appender skips an `eventId` that is already
    present, so the redelivery adds no second row. It cannot skip unfinished
    work: an ACK row exists only if an earlier transaction committed the erase
    together with it, and the erase itself is always re-run.
  - A coordinator replay (`replayTombstones` / Kafka republish) carries a **new**
    request `eventId`, so it queues a fresh ACK. That lets a coordinator whose
    ACK state was lost (for example after a restore) still converge.
- `serviceName` is the participant's configured name (`gamification`); it must
  match an entry in auth's `parkio.privacy.account-erasure.participants`.

## Coordinator side (auth, unchanged code)

`ErasureAckKafkaConsumer` → `AccountErasureApplicationService.handleAcknowledgement`,
one transaction:

- inbox claim by `eventId` (duplicate `eventId` → no-op);
- ACKs from names outside the configured participant set are ignored;
- upsert `erasure_service_acks` by `(erasure_request_id, service_name)`, so a
  replayed ACK with a new `eventId` does not add a row;
- `FAILED` → request `FAILED_RETRYING`, never `COMPLETE`;
- a status outside `SUCCESS`/`FAILED` violates `chk_erasure_service_acks_status`,
  the transaction (inbox claim included) rolls back, the consumer rethrows and
  the auth container factory retries and dead-letters it;
- `COMPLETE` only when the `SUCCESS` count reaches the configured participant
  count (default eight: `user, parking, media, moderation, gamification,
  notification, analytics, ai-validation`).

`AccountErasureParticipantContractPostgresIT` pins this on PostgreSQL for every
participant: missing, `FAILED`, unknown status, duplicate and replayed ACKs.

## Terminal failure

- **Erase fails permanently:** no ACK row, so no `SUCCESS`; the request stays
  `IN_PROGRESS` and the `AccountErasureStuck` alert fires.
- **Media objects cannot be confirmed gone** (storage outage, object-lock
  retention, an object in another bucket): the media job stays pending and
  retries with backoff, with no attempt cap; no `SUCCESS`, the request stays
  `IN_PROGRESS`, `AccountErasureStuck` fires and
  `parkio.media.erasure.jobs.pending` stays above zero.
- **ACK row cannot be published:** asynchronous broker failures count toward
  `parkio.kafka.relay.max-attempts`; the row is then dead-lettered
  (`parkio.outbox.deadlettered`) and can be redriven. The request stays
  `IN_PROGRESS` until then, so the failure is safe: no false `COMPLETE`.

### Known limitation (tracked under U18)

When the broker is unreachable, `KafkaTemplate.send()` can fail synchronously
(`max.block.ms`). The relay does not catch that during dispatch, so the whole
poll transaction rolls back without recording `failure_count`. The ACK row
stays committed and queued and is retried on the next poll, so it is never
lost. It just never dead-letters or shows up in the failure counter during a
broker outage. The relay code is shared by every service, so this is fixed
under U18 (outbox poison/retry/DLT), not in this pilot.

## Rolling out to another participant

Per participant subtask:

1. Append the ACK event to that service's outbox in the erase transaction, with
   the `eventId` derivation above; remove the in-transaction HTTP ACK call.
2. Make the service's outbox append skip an existing `eventId`. Where the
   existing outbox port is typed to one domain event (user-service), add a
   dedicated ACK port/adapter over the same `outbox_events` table instead of
   widening that port.
3. Route `AccountErasure` rows to `parkio.privacy.erasure` in the relay.
4. Add a PostgreSQL + Kafka IT mirroring `AccountErasureAckOutboxPostgresIT`
   (gamification, user): commit failure → no row and no record; publisher
   failure → retried by a fresh relay; duplicate and replayed delivery. Give
   the real-broker test producer a realistic `max.block.ms` (first send to a
   not-yet-created topic can exceed a couple of seconds).
5. Media must additionally cover object-storage delete before `SUCCESS`
   (see media under "Participant specifics").
6. Inspect the full erase scope, not only the handler's statements: copies of
   the user id inside JSON/text columns, ids derived from the user id, and
   uniqueness constraints that make a sentinel rewrite skip or fail a row. A
   whole-schema residue scan in the IT (every uuid/text/json column) catches
   these; see "What remains after SUCCESS" for what each scan may exclude.

## Participant specifics

- **moderation (V14):** `uq_user_reports_reporter_target_reason` and
  `uq_appeals_case_user` are partial unique indexes that exempt the erased-user
  sentinel, so identity rewrites are unconditional. The former `NOT EXISTS`
  guards left the real user id behind when the sentinel already held an
  equivalent row, and a reporter's reports against two erased users failed the
  second erase. Real-user uniqueness is unchanged.
- **parking:** the shadow ledgers serialize the subject into JSON
  (`trust_ledger`, `trust_snapshot`, `fraud_evaluation_ledger`,
  `pending_reward_ledger`); anonymization rewrites the id there too. A trust
  snapshot's id is derived from its subject, so the user's snapshot is re-keyed
  to the sentinel-derived id, or deleted when the sentinel already has one.
- **analytics (V10):** analytics had the outbox table but no relay; it now has
  `AnalyticsOutboxRelay` (same shape as the other relays, DLQ columns via V10)
  and publishes only erasure ACKs.
- **media (V14, V15): owner write fence, two-phase erase, metadata deleted.**
  PRIV-001's policy matrix says "User-owned media: delete metadata + object
  storage"; the old handler only soft-deleted the rows, which kept
  `owner_user_id`, the object key (it embeds the user id), checksum and
  perceptual hash. Now:
  1. **Owner write fence.** Every write path for a user's media starts by
     joining that user's fence: upload, claimed-region update and owner delete.
     The fence is a PostgreSQL transaction-scoped advisory lock (class `MED1`,
     key = hash of the user id) taken shared. Under it the write reads the
     user's tombstone. If the tombstone exists, the write is refused with
     `ACCOUNT_ERASED` (HTTP 403) before it stores anything. Its transaction,
     the idempotency claim included, rolls back. An admitted write holds the
     fence until its transaction ends. For an upload that covers the scan, the
     object write, and the commit of the row, validation results and
     `MediaUploaded` event. The erasure takes the same lock exclusively. There
     is no in-memory lock: PostgreSQL releases it on commit, rollback or a lost
     connection. Two users whose keys collide only wait for each other; the
     tombstone check is per user.
  2. **Phase 1.** One short transaction, with no storage I/O and no ACK. It takes
     the fence exclusively first, so it waits for every write already admitted
     (an upload in the middle of its object write included) to commit or roll
     back. It then writes the tombstone, soft-deletes the user's media rows (no
     longer served), deletes the user's idempotency records, and opens a
     `media_erasure_jobs` row keyed by the ACK event id (on redelivery the row
     is kept and attempted again). From its commit on, every media write of the
     user is refused.
     The job holds the request id and user id only while work is pending.
  3. **Phase 2.** `MediaObjectErasureWorker` claims the job and works outside any
     transaction. The claim is a fresh `claim_token` plus a lease end in
     `next_attempt_at`. For each media row of the user it lists the versions and
     delete markers of exactly that key, one page (`listing-page-size`, 100) per
     storage call; an unversioned bucket lists its one object. It removes each
     by version id and lists again until a fresh listing is empty. For a key,
     an empty listing is backed by a HEAD. A version listed again after its
     delete returned normally fails the attempt. Only then does a short
     transaction delete that row and its validation results. Once every row is
     gone, the worker empties the owner key namespace `media/<userId>/` with the
     same page-by-page delete. That removes objects of uploads whose row never
     committed. Unlike a key, an orphan's absence rests on the fresh prefix
     listing alone: there is no per-key HEAD, because no key is known for it.
  4. **Completion.** One transaction:
     - takes the fence exclusively;
     - requires that this attempt still holds its claim and that the lease has
       not expired (`claim_token` matches, `next_attempt_at` > now, row locked);
     - re-checks that the user owns no media row;
     - then deletes late idempotency records, queues the ACK and deletes the
       job.

     An attempt whose claim expired or was taken over by another instance's poll
     never queues the ACK. A stale attempt cannot record a retry or release on
     the job either.

  **What `SUCCESS` means for media.** A media `SUCCESS` is queued only when both
  hold at its commit:
  - the user owns no media row, no media write of the user is in flight, and
    none can be admitted any more;
  - the worker removed every listed version of each of the user's keys and of
    the owner namespace, and saw a fresh empty listing afterwards (HEAD-backed
    for keys).

  No media metadata and no job state of the user outlives `SUCCESS`, apart from
  the tombstone and the transport copies listed below. Never `SUCCESS` while an
  object cannot be confirmed gone. That covers:
  - a version the store refuses to remove (object lock or retention);
  - an object in a bucket other than the configured one;
  - an object still listed after its delete;
  - a storage outage.

  Every failed attempt is recorded on the job, whether an object failure or an
  unexpected one such as a failed ACK append: `attempts`, `last_error` and an
  exponential backoff. A scheduled poll (`parkio.media.erasure-worker.*`)
  claims due jobs and jobs whose claim expired.

  **Bounded attempts.** An attempt checks its deadline (`attempt-budget-ms`,
  60 s) before every storage call:
  - Each storage call is bounded by the storage client's end-to-end
    `parkio.media.storage.call-timeout` (15 s) and returns at most one listing
    page.
  - So the attempt's storage work ends at most two storage calls (a key listing
    and its HEAD) after the budget.
  - The service refuses to start unless `lease-ms` >= `attempt-budget-ms` + 2 x
    `call-timeout` + 10 s. The defaults give 120 s >= 100 s.
  - An attempt that reaches its budget releases its claim and keeps its
    progress: removed versions and deleted rows stay deleted. No failure is
    counted and the next poll continues.

  Database statements are not on the budget: the fence wait, the row deletes and
  the completion transaction. A slow completion can run past the lease, but its
  lease check then refuses to finalize. The Kafka consumer thread runs phase 1
  and one immediate attempt. Phase 1 can wait for the user's in-flight writes,
  each bounded by its own scan and storage timeouts.

  Metrics:
  - `parkio.media.erasure.jobs.pending`
  - `parkio.media.erasure.object.delete.failed`
  - `parkio.media.erasure.attempt.failed`
  - `parkio.media.erasure.attempt.budget_exhausted`

  **Known limitations (media).**
  - **Late object writes.** A storage write the client abandoned at its call
    timeout can still complete on the store later. The upload failed and
    released the fence, and its cleanup delete may have run first. If the late
    write lands after the namespace sweep, that object outlives `SUCCESS`
    unnoticed. The client side is bounded by `call-timeout`; the store side is
    not.
  - **H2.** The fence is PostgreSQL-only. On the H2 database of the context
    tests only the tombstone check runs. Every deployed profile uses
    PostgreSQL.
  - **Stuck jobs.** There is no terminal attempt cap. A job that can never be
    confirmed, such as an object under a retention lock, retries with capped
    backoff. The request stays `IN_PROGRESS`, `AccountErasureStuck` fires and
    the pending gauge stays above zero; `last_error` holds the cause. How
    operators resolve a stuck job is an open decision.
  - **V14 edited in place.** V14 changed after its first review, while no
    release contains it. Check at rollout that no database applied an earlier
    V14.

  Storage topology: the checked-in Compose bucket is unversioned
  (`docker/docker-compose.yml`, `minio-setup`), while
  `production-readiness.md` recommends versioning. The delete works by version,
  so enabled and suspended versioning are handled, and an object-lock bucket
  fails closed while a retention period holds. The deployed bucket mode and the
  service's bucket permissions (version listing and deletion) are not recorded
  in the repository; check them at rollout.

### Participant inventory

| Participant | ACK path | Relay | PR |
|-------------|----------|-------|----|
| user | outbox (`ErasureAckOutbox` port) | `UserOutboxRelay` | #133 |
| parking | outbox + ledger JSON scrub | `ParkingOutboxRelay` | #137 |
| media | outbox after every stored version is confirmed gone, under the owner write fence and a live claim; metadata deleted | `MediaOutboxRelay` | #141 |
| moderation | outbox + sentinel-exempt uniqueness (V14) | `ModerationOutboxRelay` | #135 |
| gamification | outbox (pilot) | `GamificationOutboxRelay` | #132 |
| notification | outbox | `NotificationOutboxRelay` | #140 |
| analytics | outbox | `AnalyticsOutboxRelay` (new, V10) | #138 |
| ai-validation | outbox | `AiValidationOutboxRelay` | #139 |

## What remains after SUCCESS

| Data | Where | Why | Until |
|------|-------|-----|-------|
| `auth_user_id`, `erased_at` | `erased_user_tombstones` (each participant) | Resurrection prevention (PRIV-001) | Tombstone retention, longer than backup retention |
| ACK row (`erasureRequestId`, `authUserId`) | participant `outbox_events` | Auth's ACK contract | Published rows: the existing outbox retention (`RetentionCleanupJob`, P7D, [kafka-transport.md](kafka-transport.md)) |
| Event copies written before the erase, e.g. `MediaUploaded` (`ownerUserId`, object key, checksum) | participant `outbox_events`, Kafka topics | Event transport, not product state | The same outbox retention; topic retention (Kafka records are not erased) |
| Sentinel-rewritten shared facts (spots, moderation records, point transactions, AI requests) | participant tables | PRIV-001 matrix: retain, identities → sentinel | Retained, without the user id |
| Staff `moderator_id` on decisions | moderation | Audit (PRIV-001) | Retained |
| A media object whose write the store completed after the client's timeout and after the namespace sweep | media bucket | Known limitation (media, late object writes), not by design | Not removed by this erasure |

Only media has pending state between its commit and its `SUCCESS`: the job and
the soft-deleted rows hold the user id and object keys. They are needed to
finish the erasure and are deleted before the ACK.

Transport retention predates U05 and applies to every service; U05 adds no
retention period and changes none. PRIV-001's policy matrix does not mention
transport copies yet. The privacy policy owner has to confirm that they are
acceptable until the transport retention removes them.

How the per-service ITs check this (whole-schema residue scan, every
uuid/text/json column):

- media: excludes only this erasure's ACK row, and also proves that the
  retention job removes the published transport rows;
- moderation, parking, analytics, ai-validation and notification: exclude the
  tombstone table and the whole `outbox_events` table;
- gamification and user (merged): no whole-schema scan.
