# Erasure participant ACK outbox contract (U05)

A participant may report `SUCCESS` for an erasure request only after its local
erase has committed. Sending the ACK from inside the erase transaction (the
original `AuthErasureAckClient` call) is unsafe: a rollback or commit failure
after the call leaves the coordinator with a `SUCCESS` for data that still
exists. An `afterCommit` callback is not enough either: a crash between commit
and send loses the ACK.

This contract reuses each service's existing transactional outbox and relay and
auth's existing Kafka ACK consumer. It adds no new framework, table or topic.

Status: **gamification** (pilot, #132) and **user** (#133) are merged. The other
six participants implement the same contract in separate PRs: parking (#137),
moderation (#135), notification (#140), ai-validation (#139),
analytics (#138) and media (#141). Until each merges, that participant
still sends its HTTP ACK inside its transaction.

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
   (see "Media: two-phase erase" below).
6. Inspect the full erase scope, not only the handler's statements: copies of
   the user id inside JSON/text columns, ids derived from the user id, and
   uniqueness constraints that make a sentinel rewrite skip or fail a row. A
   whole-schema residue scan in the IT (every uuid/text/json column, excluding
   the tombstone and the ACK outbox row) catches these.

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
- **media (V14): two-phase erase.**
  1. One short transaction: tombstone, soft-delete of the user's media
     metadata, idempotency records, and a durable `media_erasure_jobs` row keyed
     by the ACK event id (reopened on redelivery). No storage I/O, no ACK.
  2. After commit, `MediaObjectErasureWorker` deletes every soft-deleted object
     of the user outside any transaction (an absent object counts as deleted),
     records `media_files.object_deleted_at`, and only when none remains queues
     the ACK in one short transaction that locks the job and re-checks. Failures
     leave the job pending with attempts, last error and exponential backoff; a
     scheduled, leased poll (`parkio.media.erasure-worker.*`) retries due jobs.
     Metrics: `parkio.media.erasure.jobs.pending`,
     `parkio.media.erasure.object.delete.failed`.

### Participant inventory (target state once the participant PRs merge)

| Participant | ACK path | Relay | PR |
|-------------|----------|-------|----|
| user | outbox (`ErasureAckOutbox` port) | `UserOutboxRelay` | #133 (merged) |
| parking | outbox + ledger JSON scrub | `ParkingOutboxRelay` | #137 |
| media | outbox after confirmed object deletion | `MediaOutboxRelay` | #141 |
| moderation | outbox + sentinel-exempt uniqueness (V14) | `ModerationOutboxRelay` | #135 |
| gamification | outbox (pilot) | `GamificationOutboxRelay` | #132 (merged) |
| notification | outbox | `NotificationOutboxRelay` | #140 |
| analytics | outbox | `AnalyticsOutboxRelay` (new, V10) | #138 |
| ai-validation | outbox | `AiValidationOutboxRelay` | #139 |

Retained by design (PRIV-001): the tombstone, the ACK outbox payload (carries
`authUserId` per the auth contract; published rows are purged by retention),
staff/operator audit ids, and soft-deleted media metadata (`owner_user_id`,
checksum, perceptual hash). Whether media metadata must be hard-deleted is an
open policy question (the PRIV-001 matrix and per-service table disagree).
