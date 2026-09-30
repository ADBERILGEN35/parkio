# Erasure participant ACK outbox contract (U05)

A participant may report `SUCCESS` for an erasure request only after its local
erase has committed. Sending the ACK from inside the erase transaction (the
original `AuthErasureAckClient` call) is unsafe: a rollback or commit failure
after the call leaves the coordinator with a `SUCCESS` for data that still
exists. An `afterCommit` callback is not enough either: a crash between commit
and send loses the ACK.

This contract reuses each service's existing transactional outbox and relay and
auth's existing Kafka ACK consumer. It adds no new framework, table or topic.

Status: **gamification is the pilot**. The other seven participants still send
HTTP ACKs inside their transaction until their own U05 subtasks land.

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
2. Make the service's outbox append skip an existing `eventId`.
3. Route `AccountErasure` rows to `parkio.privacy.erasure` in the relay.
4. Add a PostgreSQL + Kafka IT mirroring `AccountErasureAckOutboxPostgresIT`
   (gamification): commit failure → no row and no record; publisher failure →
   retried by a fresh relay; duplicate and replayed delivery.
5. Media must additionally cover object-storage delete before `SUCCESS`.

### Participant inventory (api `554333e6`, 2026-09-30)

| Participant | `outbox_events` + `uq_outbox_events_event_id` | Outbox relay | ACK today |
|-------------|-----------------------------------------------|--------------|-----------|
| user | yes | `UserOutboxRelay` | HTTP inside tx |
| parking | yes | `ParkingOutboxRelay` | HTTP inside tx |
| media | yes | `MediaOutboxRelay` | HTTP inside tx (+ object delete) |
| moderation | yes | `ModerationOutboxRelay` | HTTP inside tx |
| gamification | yes | `GamificationOutboxRelay` | **outbox (this pilot)** |
| notification | yes | `NotificationOutboxRelay` | HTTP inside tx |
| analytics | yes (table only) | **none** | HTTP inside tx |
| ai-validation | yes | `AiValidationOutboxRelay` | HTTP inside tx |

Six of the remaining seven can follow the pilot steps unchanged. Analytics has
the table but no relay (and no appender in `src/main`), so its subtask must add
a relay, mirroring the others, before step 1.
