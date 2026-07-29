# ParkingSession analytics ingestion

**Decision / task:** S1-P0-09  
**Status:** Implemented in analytics-service  
**Date:** 2026-07-24  
**Producer companion:** [`PARKING-SESSION-LIFECYCLE-EVENTS.md`](PARKING-SESSION-LIFECYCLE-EVENTS.md)  
**Contracts:** [`event-contracts.md`](event-contracts.md)  
**Transport:** [`kafka-transport.md`](kafka-transport.md)

## Source topic and consumer

| Item | Value |
|---|---|
| Topic | `parkio.parking.session` |
| Consumer group | `parkio.analytics` |
| Listener | `ParkingSessionEventsKafkaConsumer` |
| Container factory | `gamificationScoreKafkaListenerContainerFactory` (shared) |
| Ack | Manual, after successful handler TX |
| DLT | `parkio.dlt.analytics` (existing FixedBackOff 2 retries) |
| Producer | `parking-service` |

## Supported producer contracts

| Wire `eventType` | Version | Canonical product name | Metric type |
|---|---|---|---|
| `ParkingSessionStarted` | 1 | `parking_session_started` | `PARKING_SESSION_STARTED_MANUAL` / `_COMMUNITY` / `_OTHER` |
| `ParkingSessionCompleted` | 1 | `parking_session_completed` | `PARKING_SESSION_COMPLETED` |
| `ParkingSessionCancelled` | 1 | `parking_session_cancelled` | `PARKING_SESSION_CANCELLED` |
| `ParkingHistoryDeleted` | 1 | `parking_session_history_deleted` | `PARKING_SESSION_HISTORY_DELETED` |

Producer PascalCase wire names are **not** renamed. Product snake_case names are the
canonical analytics vocabulary.

Started source encoding (no schema migration):

- `MANUAL` → `PARKING_SESSION_STARTED_MANUAL`
- `COMMUNITY` → `PARKING_SESSION_STARTED_COMMUNITY`
- `FACILITY` / `CURB` / `AUTO` → `PARKING_SESSION_STARTED_OTHER`

## Persistence

Reuses generic `analytics_events` + daily/user snapshots via existing `ingest()`:

| Field | Mapping |
|---|---|
| `source_event_id` | producer `eventId` (unique; inbox dedupe key) |
| `metric_type` | table above |
| `user_id` | producer `userId` (authUserId; needed for per-user KPIs) |
| `related_entity_id` | `sessionId` for lifecycle and single delete; `userId` for bulk delete |
| `metric_value` | `0` for started; duration seconds for terminal; `deletedCount` for history delete |
| `occurred_at` | producer `occurredAt` (not rewritten) |
| `created_at` | analytics ingestion clock |

Session metrics are **not** part of the spot parking-funnel snapshot (`isParking()` remains
spot-only). They appear in daily/user snapshots and `GET /api/v1/analytics/metrics`.

## Duration

For Completed/Cancelled: `durationSeconds = endedAt - startedAt` (whole seconds,
non-negative). Negative spans are rejected (contract exception → DLT). Zero allowed.
Uses producer timestamps only — never consumer wall clock, never `occurredAt` as end.

## Privacy exclusions

Not ingested or stored: latitude, longitude, location, geohash, address, spotId,
idempotency keys, tokens, HTTP bodies/headers, email, display name, device IDs,
raw Kafka payload JSON columns.

`userId` retention follows existing analytics policy (authUserId on projections).
Account-erasure / anonymization of historical analytics rows is **not** implemented
(see S1-P0-05 open items / PRIV-001). This task does not claim legal compliance.

## Inbox / transactions

1. `inbox.tryClaim(eventId, wireEventType, now)`
2. Save `AnalyticsEvent` + increment snapshots
3. Commit TX → Kafka ack

Duplicate Kafka delivery → claim fails → no second row. Failure before commit leaves
inbox unclaimed → retryable.

## Out-of-order policy

Each valid authoritative event is persisted independently. No synthetic Started.
Delayed Started does not overwrite a terminal observation. Conflicting Completed and
Cancelled for one session (corrupt/impossible producer history) both persist under
distinct `eventId`s. No second ParkingSession state machine.

## Community claim anti-double-count

`ParkingSpotClaimed` (spot topic → `PARKING_CLAIMED`) and `ParkingSessionStarted` with
`source=COMMUNITY` (session topic → `PARKING_SESSION_STARTED_COMMUNITY`) are separate
facts. Community claim increments session-start once via the session event, not via
spot claim.

## ParkingHistoryDeleted (R22 / WP-07.3)

**Producer (`parking-service`, commit `0a70b03`):**

| Field | Value |
|---|---|
| Wire `eventType` | `ParkingHistoryDeleted` |
| `aggregateType` | `ParkingSession` |
| Topic / key | `parkio.parking.session` / producer `aggregateId` |
| Scope | `SINGLE_TERMINAL_SESSION` or `ALL_TERMINAL_HISTORY` |
| Single delete `aggregateId` | `sessionId` (`deletedCount` = 1) |
| Bulk delete `aggregateId` | `userId` (`deletedCount` = rows removed, `>= 1`) |
| Emission rule | Append to outbox in the same TX as the delete when `deletedCount >= 1`; no event on zero-delete or ACTIVE rejection |

**Consumer (`analytics-service`, commit `d482bcc`):**

- Validates producer-compatible envelope fields (version, aggregate, scope, counts, timestamps).
- Records append-only deletion analytics; does **not** reverse prior lifecycle observations.
- Metric excluded from spot parking-funnel snapshots (`isParking()` remains spot-only).
- Duplicate delivery is idempotent via inbox `tryClaim(eventId)`.
- Invalid or unsupported payloads follow the existing DLT path (`parkio.dlt.analytics`).

## Not in scope (remain deferred)

- Account erasure
- Frontend click/share analytics
- Rewards / trust consumers

## Verification

```bash
./gradlew :services:analytics-service:test
./gradlew :services:analytics-service:test --tests ParkingSessionLifecycle* --tests ParkingSessionEventsKafkaConsumerTest
```