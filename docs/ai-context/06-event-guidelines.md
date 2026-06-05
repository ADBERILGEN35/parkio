# 06 — Event Guidelines

Asynchronous side effects flow through **Kafka**. Events keep services decoupled.

## When to use events

- Use **events** for side effects and fan-out: scoring, notifications, analytics,
  moderation triggers, read-model updates.
- Use **REST/Feign** only when the caller needs an immediate response.
- A service emits events about **its own** domain; it never emits on behalf of
  another service.

## Topic & naming

- Topic per aggregate/domain, e.g. `parking.spot`, `parking.claim`,
  `moderation.decision`, `gamification.score`, `user.account`.
- Event type names are past-tense facts: `SpotSubmitted`, `SpotVerified`,
  `ClaimConfirmed`, `SpotRejected`, `UserPenalized`, `PointsAwarded`.
- Partition by the aggregate id (e.g. `spotId`, `userId`) to preserve per-entity
  ordering.

## Event envelope (recommended)

```json
{
  "eventId": "uuid",
  "type": "SpotVerified",
  "occurredAt": "2026-06-05T18:00:00Z",
  "aggregateId": "spot-uuid",
  "version": 1,
  "traceId": "...",
  "payload": { }
}
```

- `eventId` is the dedup key for consumers.
- Include `version`; evolve payloads **backward-compatibly** (add optional fields;
  don't repurpose or remove fields). Breaking change → new event type/version.
- Payloads carry **IDs and the minimum data** needed — not another service's full
  domain model.

## Reliability (required for production paths)

- **Outbox (producers):** within the same DB transaction as the state change,
  insert the event into an `outbox` table. A relay/poller publishes to Kafka and
  marks it sent. This guarantees the event iff the state change committed.
- **Inbox (consumers):** record handled `eventId`s in a `processed_messages` table;
  skip duplicates. Kafka is **at-least-once**, so redelivery will happen.
- **Idempotent consumers:** handling the same event twice must produce the same
  result (e.g. awarding points keyed by `eventId`).

## Consumption rules

- Consumers commit offsets only after successful processing (+ inbox write).
- Handle poison messages with a **dead-letter topic**; never block the partition
  indefinitely.
- Do not call back synchronously into the producer inside a consumer if it can be
  avoided — prefer further events.

## Examples (illustrative)

- `parking-service` → `SpotVerified` → consumed by `gamification` (points),
  `notification` (notify), `analytics` (metrics).
- `moderation-service` → `UserPenalized` → consumed by `gamification` (score down),
  `user` (account status), `notification`.
