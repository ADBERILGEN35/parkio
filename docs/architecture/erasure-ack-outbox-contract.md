# Erasure participant ACK outbox contract (U05)

A participant may report `SUCCESS` for an erasure request only after its local
erase has committed. Sending the ACK from inside the erase transaction (the
original `AuthErasureAckClient` call) is unsafe: a rollback or commit failure
after the call leaves the coordinator with a `SUCCESS` for data that still
exists. An `afterCommit` callback is not enough either: a crash between commit
and send loses the ACK.

This contract reuses each service's existing transactional outbox and relay and
auth's existing Kafka ACK consumer. It adds no new framework or topic. Two
participants add schema. Media adds three tables or columns:
- `media_erasure_jobs` (V14) for object deletion that is still pending;
- a claim token on it (V15);
- the object write ledger `media_object_writes` (V16).

Analytics adds DLQ columns to its existing outbox for its new relay (V10).

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
  retention, an object in another bucket), **or a media PUT of the user could
  still be applied** (a write of unknown outcome whose object has not been
  observed):
  - the media job stays pending and retries with backoff, with no attempt cap;
  - there is no `SUCCESS`, and the request stays `IN_PROGRESS`;
  - `AccountErasureStuck` fires;
  - `parkio.media.erasure.jobs.pending` stays above zero; for unknown-outcome
    writes, `parkio.media.object_writes.outcome_unknown` does too.
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
- **media (V14–V16): owner write fence, object write ledger, two-phase erase,
  metadata deleted.** PRIV-001's policy matrix says "User-owned media: delete
  metadata + object storage". The old handler only soft-deleted the rows, which
  kept `owner_user_id`, the object key (it embeds the user id), the checksum and
  the perceptual hash.

  **Storage write-completion contract.** The guarantee below rests on these
  facts about PUT and DELETE requests to an S3-compatible store. Request
  authentication, request acceptance, client timeout and server-side completion
  are four different things:
  - **Authentication and acceptance.** SigV4 bounds when a request may still be
    *accepted*: AWS says a request "must reach AWS within five minutes of the
    time stamp". MinIO RELEASE.2024-09-13T20-26-02Z rejects a request whose
    `x-amz-date` is more than 15 minutes from its clock. It checks this once,
    when the request is dispatched, before the handler reads the body
    (`cmd/auth-handler.go`, `globalMaxSkewTime` in `cmd/globals.go`).
    Nothing ties the completion of an accepted request to its signature date.
    MinIO's own request deadline only limits time spent waiting in its queue.
  - **Client timeout.** OkHttp's call timeout bounds how long *the client*
    waits ("the entire call ... server processing, and reading the response
    body"). It says nothing about what the server does. A request whose client
    timed out, or whose connection broke after sending, may still be applied,
    including after a delay in the network path. A TCP-relay test shows exactly
    that.
  - **Server-side completion.** S3 "never adds partial objects; if you receive
    a success response, Amazon S3 added the entire object". Neither AWS nor
    MinIO documents an upper bound on when an accepted PUT completes. Go's HTTP
    server cancels a request's context when the client disconnects; MinIO does
    not document that it abandons a PUT then.
  - **Client retries.** One upload call can transmit its PUT more than once
    without the caller seeing it. OkHttp 4.12 (`RetryAndFollowUpInterceptor`)
    has two such paths:
    - *recovery*: after a failed attempt it may send the request again on a new
      connection, even once the body was sent, unless the body is one-shot;
    - *follow-ups*: after a reply it may send the request again on its own, the
      body included: a 503 with `Retry-After: 0`, a 307 or 308 redirect, a 408,
      a 421, an authentication challenge (401/407). A 301, 302 or 303 turns the
      PUT into a GET whose reply then stands for the PUT. Only a one-shot body
      stops these follow-ups.

    The MinIO Java SDK 8.6.0 turns off only recovery, and only for PUT/POST
    bodies that are not byte arrays (S3Base, "Issue #924"). Its request body is
    not one-shot: it re-reads the buffered part. The SDK itself repeats no PUT;
    its only own retry is a second HEAD after a region redirect, which a
    configured region (default `us-east-1`) never triggers. The upload code
    calls the store once per upload; the idempotency wrapper runs the upload at
    most once per request. An independent review showed the consequence: a 503
    with `Retry-After: 0`, then a 403 to the resent PUT. The 403 was read as a
    rejection, the write was forgotten, and `SUCCESS` was queued while the first
    transmission could still be applied.

  Conclusion: no finite time bound makes a PUT of unknown outcome harmless.
  Media does not wait out a time window. It records such writes and keeps
  `SUCCESS` blocked until each one is accounted for.

  1. **Owner write fence.** Every write path for a user's media starts by
     joining that user's fence: upload, claimed-region update and owner delete.
     - The fence is a PostgreSQL transaction-scoped advisory lock (class
       `MED1`, key = hash of the user id) taken shared.
     - Under it the write reads the user's tombstone. If the tombstone exists,
       the write is refused with `ACCOUNT_ERASED` (HTTP 403) before it stores
       anything. Its transaction, the idempotency claim included, rolls back.
     - An admitted write holds the fence until its transaction ends.
     - The erasure takes the same lock exclusively. There is no in-memory lock:
       PostgreSQL releases it on commit, rollback or a lost connection.
     - Two users whose keys collide only wait for each other; the tombstone
       check is per user.

     The fence orders database transactions only. A storage request that
     outlives its transaction is not covered by it; the next item covers that.
  2. **Object write ledger (V16).** An upload records its PUT in
     `media_object_writes` as `PENDING` before sending it, in a transaction of
     its own that survives the upload's rollback or a crash.
     - **One PUT per upload, transmitted at most once.**
       - The part size is at least the content length, so there is no
         multipart upload.
       - The storage client's single-transmission guard
         (`SingleTransmissionInterceptor`) makes every request body one-shot.
         OkHttp then sends no follow-up and no retry once the body went out.
         The client follows no redirect and uses no proxy.
       - Each upload uses a fresh key.

       Regression tests run the production client against each reply after
       which OkHttp may repeat a request (`MediaObjectWriteRetryIT`,
       `StorageClientSingleTransmissionTest`). Each sees one transmission:
       - 503 with `Retry-After: 0`, 307, 308, 301, 302 and 303 were repeated
         (or replaced by a GET) before the guard;
       - 401 and 408 were not: no authenticator is configured, and the SDK's
         per-call setting already blocked the 408 retry;
       - after a reply, no second connection is opened either.

       A PUT whose connection breaks after sending is also sent once
       (`MediaDelayedObjectWriteIT`).

       A connection that never carried the body may still be retried. Listings,
       HEAD and version-specific deletes keep OkHttp's recovery: falling back
       to a host's other addresses and replacing stale pooled connections.
       Repeating them is harmless. For the PUT the SDK turns that recovery off,
       so an upload to a host whose first address refuses fails with nothing
       sent.
     - **Confirmed.** When the store confirms the PUT, the write becomes
       `APPLIED`, and the committed media row takes over from it. It is deleted
       with that row.
     - **Rolled back.** If the upload rolls back, its cleanup deletes the
       object and then forgets the write.
     - **Rejected.** A write the store certainly did not apply is forgotten at
       once. The adapter judges this from the guard's evidence about every
       transmission of the call, never from the last reply alone. A write
       counts as rejected in two cases only:
       - no attempt sent any byte of the body;
       - the store answered the body's only transmission with a 4xx, and no
         follow-up request was made.

       A reply to a later request, or a failed later connection, never counts.
     - **Unknown.** Every other failure leaves the write `PENDING` with an
       unknown outcome: a timeout, a broken connection after sending, a 5xx or
       3xx reply, a crash.
  3. **Phase 1.** One short transaction, with no storage I/O and no ACK.
     - It takes the fence exclusively first, so it waits for every admitted
       write transaction to commit or roll back.
     - It then writes the tombstone, soft-deletes the user's media rows (no
       longer served), deletes the user's idempotency records, and opens a
       `media_erasure_jobs` row keyed by the ACK event id. On redelivery the
       row is kept and attempted again.
     - From its commit on, every media write of the user is refused, and no new
       ledger entry can appear for the user.
  4. **Phase 2.** `MediaObjectErasureWorker` claims the job (a `claim_token`
     plus a lease end in `next_attempt_at`) and works outside any transaction.
     - **Media rows.** For each media row it lists the versions and delete
       markers of exactly that key. Each lookup is one ListObjectVersions
       request with the key as prefix. Keys are listed in order, and a key
       sorts before every longer key it prefixes, so the key's own entries come
       first. Keys that only share the prefix are never paged through. The
       worker removes each entry by version id until a fresh listing is empty;
       for a key, an empty listing is backed by a HEAD. Then a short
       transaction deletes the row and its validation results.
     - **Owner namespace.** It then empties `media/<userId>/` the same way, one
       listing request per page. An orphan's absence there rests on the prefix
       listing alone, with no HEAD, because no key is known for it.
       - An object version found there proves that every recorded write of its
         key was applied. A delete marker proves nothing: a delete made it.
       - Before the version is removed, the worker commits that observation for
         all of the user's `PENDING` writes of that key (by user and key, in
         the database).
       - A failure or restart between the two steps keeps the observation.
     - **Ledger entries.** Last, it goes through all of the user's ledger
       entries, 100 per query:
       - an `APPLIED` write is forgotten once its key is confirmed empty;
       - a `PENDING` write is settled only once an object version of its key
         has been observed: the worker commits it as `APPLIED`, removes the
         object, then forgets the write;
       - while a `PENDING` write's object has never been seen, the attempt
         queues no `SUCCESS`. It records "N object write(s) of unknown
         outcome; SUCCESS waits until each is observed" on the job and retries
         with capped backoff.
  5. **Completion.** One transaction:
     - takes the fence exclusively;
     - requires that this attempt still holds its claim and that the lease has
       not expired;
     - re-checks that the user owns no media row and no ledger entry;
     - then deletes late idempotency records, queues the ACK and deletes the
       job.

     An expired or taken-over claim never finalizes, and cannot record a
     retry or release.

  Every storage delete removes versions by version id. That covers the
  worker, the upload's rollback cleanup and the owner delete; the HEAD fallback
  uses the current version id. So a delete that reaches the store late can only
  remove data; it can never add a delete marker. AWS documents that a simple
  DELETE in a versioning-enabled bucket inserts a delete marker. The tested
  MinIO release added none for a key without versions.

  **What `SUCCESS` means for media.** A media `SUCCESS` is queued only when
  all of the following hold at its commit:
  - the user owns no media row and no ledger entry;
  - no media write transaction of the user is open, and none can be admitted;
  - every PUT the user's uploads ever sent is accounted for:
    - committed with a row that the erasure deleted after removing its
      versions;
    - or rejected by the store: no byte of it was sent, or its only
      transmission got a 4xx;
    - or applied, with its object observed and removed;
  - the worker removed every listed version of each of the user's keys and of
    the owner namespace, and saw a fresh empty listing afterwards (HEAD-backed
    for keys).

  No media metadata, ledger entry or job state of the user outlives `SUCCESS`,
  apart from the tombstone and the transport copies listed below. Never
  `SUCCESS` while either of these holds:
  - an object cannot be confirmed gone (object-lock retention, another bucket,
    an object still listed after its delete, a storage outage);
  - a PUT of the user could still be applied.

  **Bounded attempts.** An attempt checks its deadline (`attempt-budget-ms`,
  60 s) before every storage call.
  - A storage call is one delete or one listing. A listing is exactly one
    ListObjectVersions request of at most `listing-page-size` entries; the
    SDK's listing iterator, which requests further pages on its own, is not
    used. An exact-key lookup adds a HEAD when it lists nothing.
  - Each request is bounded by the end-to-end `call-timeout` (15 s). OkHttp's
    own recovery within a request stays inside that timeout.
  - So the attempt's storage work ends at most two requests (a key listing and
    its HEAD) after the budget.
  - With a configured region (default `us-east-1`) the SDK makes no region
    lookup. Without one, the first request per bucket adds a GetBucketLocation
    request, which is then cached.
  - Progress needs no listing marker: the next listing starts afresh and shows
    what is left after the removals. A version listed again after its removal
    fails the attempt.
  - The service refuses to start unless all of these hold:
    - `attempt-budget-ms` > 0;
    - `lease-ms` > 0;
    - all four storage timeouts > 0 (OkHttp reads 0 as "no timeout");
    - `lease-ms` >= `attempt-budget-ms` + 2 x `call-timeout` + 10 s. The
      defaults give 120 s >= 100 s.
  - A budget stop releases the claim and keeps the progress made so far; no
    failure is counted.
  - Database statements are not on the budget: the fence wait, row deletes,
    ledger updates and the completion transaction. A late completion is
    refused by the lease check.
  - The Kafka consumer thread runs phase 1, which can wait for the user's
    admitted write transactions, and one immediate attempt.

  Metrics:
  - `parkio.media.erasure.jobs.pending`
  - `parkio.media.object_writes.outcome_unknown` (ledger entries of unknown
    outcome, all users)
  - `parkio.media.erasure.object.delete.failed`
  - `parkio.media.erasure.attempt.failed`
  - `parkio.media.erasure.attempt.budget_exhausted`

  **Known limitations (media).**
  - **A write of unknown outcome can block erasure indefinitely.** A `PENDING`
    write whose object never appears keeps its owner's erasure pending. That
    happens when the request was lost, or the process crashed between
    recording and sending. No automated rule settles it, because no evidence
    shows that the request can no longer be applied. It stays observable:
    - the request stays `IN_PROGRESS`;
    - `AccountErasureStuck` fires;
    - the outcome-unknown gauge stays above zero;
    - `last_error` says why.

    How operators may resolve such a write is an open decision. It is not
    decided here.
  - **Topology assumptions.** "Observed once" settles a write only if its PUT
    is applied at most once.
    - The client side is enforced and tested: one PUT per upload, transmitted
      at most once (the guard; no redirect; no proxy), and a fresh key per
      upload.
    - The network path cannot be enforced by the client. HTTP treats PUT as
      idempotent, so a proxy, load balancer or service-mesh retry policy may
      repeat it, and that would break the assumption.
    - The supported deployment connects media-service directly to MinIO. Any
      intermediary in between, and its retry behaviour, must be checked at
      rollout.
  - **PUT address fallback.** The MinIO SDK turns OkHttp's recovery off for
    the PUT. An upload to a host whose first address refuses the connection
    therefore fails (nothing sent, recorded as rejected) instead of trying
    the host's next address. Other storage calls keep the fallback.
  - **H2.** The fence is PostgreSQL-only. On the H2 database of the context
    tests only the tombstone check runs. Every deployed profile uses
    PostgreSQL.
  - **Stuck jobs.** There is no terminal attempt cap. A job that can never be
    confirmed retries with capped backoff (object-lock retention, another
    bucket, a write of unknown outcome). The request stays `IN_PROGRESS` and
    `AccountErasureStuck` fires. How operators resolve a stuck job is an open
    decision.
  - **V14 edited in place.** V14 changed after its first review, while no
    release contained it. Check at rollout that no database applied an earlier
    V14. V15 and V16 only add.

  Storage topology: the checked-in Compose bucket is unversioned
  (`docker/docker-compose.yml`, `minio-setup`), while
  `production-readiness.md` recommends versioning. Deletes work by version, so
  enabled and suspended versioning are handled. An object-lock bucket fails
  closed while a retention period holds. The deployed bucket mode and the
  service's bucket permissions (version listing and deletion) are not recorded
  in the repository; check them at rollout.

### Participant inventory

| Participant | ACK path | Relay | PR |
|-------------|----------|-------|----|
| user | outbox (`ErasureAckOutbox` port) | `UserOutboxRelay` | #133 |
| parking | outbox + ledger JSON scrub | `ParkingOutboxRelay` | #137 |
| media | outbox after every stored version is confirmed gone and every recorded object write is accounted for, under the owner write fence and a live claim; metadata deleted | `MediaOutboxRelay` | #141 |
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

Only media has pending state between its commit and its `SUCCESS`:
- the job;
- the soft-deleted rows;
- the user's object write ledger entries.

They hold the user id and object keys. They are needed to finish the erasure
and are deleted before the ACK. A PUT that could still be applied blocks
`SUCCESS` instead of leaving an object behind it.

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
