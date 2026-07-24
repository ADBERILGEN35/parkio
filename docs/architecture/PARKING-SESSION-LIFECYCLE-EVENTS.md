# ParkingSession lifecycle events

**Decision / task:** S1-P0-08  
**Status:** Implemented in parking-service  
**Date:** 2026-07-24  
**Companion registry:** [`event-contracts.md`](event-contracts.md) (payload schemas)  
**Transport:** [`kafka-transport.md`](kafka-transport.md)

This document records producer ownership, atomicity, idempotency, ordering, and privacy
rules for authoritative ParkingSession lifecycle facts. Payload field tables live in the
event contract registry.

## Events

| Public eventType | Aggregate | When emitted |
|---|---|---|
| `ParkingSessionStarted` | `ParkingSession` | Successful create of an ACTIVE session row (MANUAL start or COMMUNITY claim start) |
| `ParkingSessionCompleted` | `ParkingSession` | Successful ACTIVE → COMPLETED |
| `ParkingSessionCancelled` | `ParkingSession` | Successful ACTIVE → CANCELLED |

Wire `eventType` strings are PascalCase (repository convention), matching `ParkingSpotClaimed`.
Analytics product names such as `parking_session_started` map 1:1 to these types in S1-P0-09
(see [`PARKING-SESSION-ANALYTICS-INGESTION.md`](PARKING-SESSION-ANALYTICS-INGESTION.md)).

**Not emitted by S1-P0-08:** deletion events, account-erasure events, ambiguous “ended” events.

## Topic and key

| Item | Value |
|---|---|
| Topic | `parkio.parking.session` |
| Partitions / retention | 6 / 30 days (same hot-topic profile as spot) |
| Message key | `aggregateId` = `sessionId` |
| Producer | `parking-service` (`ParkingOutboxRelay`) |
| Envelope version | `EventEnvelope.CURRENT_VERSION` = 1 |

Spot claim still publishes `ParkingSpotClaimed` to `parkio.parking.spot`. That event is
**not** a substitute for `ParkingSessionStarted`. Community claim commits both facts in one
transaction when both succeed.

## Envelope / schema version

- Transport envelope: `EventEnvelope` (`eventId`, `eventType`, `aggregateType`,
  `aggregateId`, `occurredAt`, `version`, `traceId`, `payload`).
- Payload schema version: **1** (append-only; no breaking field renames).
- Payload repeats `eventId` and `occurredAt` (dedup / audit).

## Payload (logical)

Common: `eventId`, `sessionId`, `userId`, `status`, `source`, `startedAt`, `occurredAt`.  
Completed/Cancelled also: `endedAt` (server-controlled).  
Duration is **not** on the wire — consumers derive from `startedAt`/`endedAt`.

## Explicitly excluded fields

latitude, longitude, location, geohash, address, estimatedFee, reminderAt,
idempotency keys, tokens, headers, raw HTTP bodies, email/display name, spotId
(no session→spot FK).

## Atomicity

`ParkingSessionService` appends via `OutboxEventAppender` in the same `@Transactional`
boundary as `sessions.save(...)`. No direct Kafka publish on the request path.
Outbox serialize/save failure rolls back the session mutation.

## HTTP idempotency

`IdempotencyService.execute` skips the supplier on COMPLETED replay. Therefore replay of
start/complete/cancel with the same key does **not** append a second outbox row.

## Races

Only a winning valid transition appends an event. Complete-after-cancel / cancel-after-complete
raise `PARKING_SESSION_NOT_ACTIVE` with no outbox row. Concurrent complete/cancel: exactly one
terminal event for the winning status.

## Ordering guarantees

- Per-session partition ordering via Kafka key = `sessionId`.
- Started logically precedes Completed/Cancelled for that aggregate.
- **No** global cross-session ordering claim.
- At-least-once relay delivery; consumers must dedupe by `eventId` (inbox).

## Retention / replay

Outbox rows are never deleted by the relay. Kafka topic retention 30 days.
Replaying Kafka messages must not mutate `parking_sessions` (consumers are projections).

## Tests

- `ParkingSessionServiceTest` (emission / conflict / no delete events)
- `ParkingSessionLifecycleEventContractTest` (privacy payload lock)
- `ParkingSessionControllerTest` (HTTP idempotency + outbox counts)
- `ParkingOutboxRelayTest` (session topic routing)
- `ParkingSessionPostgisIntegrationTest` (lifecycle + community claim dual outbox)