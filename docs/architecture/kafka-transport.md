# Kafka Transport

How Parkio's asynchronous event backbone is wired. This complements
[`event-contracts.md`](event-contracts.md) (the payload/contract registry) and
[`../ai-context/06-event-guidelines.md`](../ai-context/06-event-guidelines.md).

> **Status:** topics + shared Kafka build/config conventions are in place, and **the
> following end-to-end flows are live:**
> 1. `auth-service` → `parkio.auth.user` → `user-service`
> 2. `parking-service` → `parkio.parking.spot` → `gamification-service`
> 3. `gamification-service` → `parkio.gamification.score` → `notification-service` **and**
>    `analytics-service` (fan-out: two consumer groups on one topic)
> 4. `media-service` → `parkio.media.media` → `ai-validation-service` (`MediaUploaded`)
>    **and** `moderation-service` (`MediaRejected`)
> 5. `ai-validation-service` → `parkio.aivalidation.result` → `moderation-service`
>    (`AiValidationCompleted`)
> - **Relays implemented:** `auth`, `parking`, `gamification`, `media` (`MediaOutboxRelay`)
>   and `ai-validation` (`AiValidationOutboxRelay`). Still **no relay** in user, moderation.
> - **Consumers implemented:** `user` (group `parkio.user`), `gamification`
>   (`parkio.gamification`), `notification` + `analytics` (`parkio.notification` /
>   `parkio.analytics`), `ai-validation` (`MediaEventsKafkaConsumer`, group
>   `parkio.aivalidation` — handles `MediaUploaded`, ignores `MediaRejected`), and
>   `moderation` (group `parkio.moderation`, **two** listeners: `MediaEventsKafkaConsumer`
>   handles `MediaRejected` from the media topic, `AiValidationEventsKafkaConsumer` handles
>   `AiValidationCompleted` from the aivalidation topic). All use inbox idempotency + DLT
>   and ignore+ack unknown/unsupported types.
> - **Not yet implemented:** relays for `user` and `moderation`; the parking→{notification,
>   analytics, ai-validation, moderation} and moderation→{user, gamification, notification}
>   consumer flows.

## Why no shared module

Per [`../ai-context/01-architecture-rules.md`](../ai-context/01-architecture-rules.md)
and `settings.gradle.kts`, Parkio has **no shared domain or library module** — contracts
are duplicated locally. We keep that here: `spring-kafka` is added **per service** via
the version catalog (not to `gateway-service`, which neither produces nor consumes), the
Kafka properties live in each service's `application.yml`, and **each service provisions
only the topics it owns** (single-writer ownership) plus its own dead-letter topic.

## Topic map

Each topic is created by exactly one owning service's `KafkaTopicsConfig` (`NewTopic`
beans; a Spring Boot `KafkaAdmin` creates them idempotently on startup). Key = the
event's `aggregateId` (see `event-contracts.md`). **No log compaction** — these are
event facts, not latest-state snapshots.

| Topic | Owner (producer) | Partitions | Retention | Key |
|-------|------------------|-----------:|-----------|-----|
| `parkio.auth.user` | auth | 3 | 7d | userId |
| `parkio.user.profile` | user | 3 | 7d | userId |
| `parkio.parking.spot` | parking | **6** | **30d** | parkingSpotId |
| `parkio.media.media` | media | **6** | 7d | mediaId |
| `parkio.gamification.score` | gamification | **6** | **30d** | userId |
| `parkio.notification.notification` | notification | 3 | 7d | notificationId |
| `parkio.moderation.case` | moderation | 3 | **30d** | caseId |
| `parkio.moderation.action` | moderation | 3 | **30d** | parkingSpotId / userId |
| `parkio.aivalidation.result` | ai-validation | **6** | 7d | mediaId |

**Hot topics** (6 partitions): `parking.spot`, `media.media`, `gamification.score`,
`aivalidation.result`. All others use 3. Partition count is effectively immutable for
keyed ordering, so it is sized deliberately up-front.

### Dead-letter topics (DLT)

One DLT **per consuming service** (not per source topic): a topic like `parking.spot`
has several consumer groups, so a per-consumer DLT keeps poison-message triage and
redrive scoped to one service.

| DLT | Owner (consumer) | Partitions | Retention |
|-----|------------------|-----------:|-----------|
| `parkio.dlt.user` | user | 3 | 14d |
| `parkio.dlt.gamification` | gamification | 3 | 14d |
| `parkio.dlt.notification` | notification | 3 | 14d |
| `parkio.dlt.moderation` | moderation | 3 | 14d |
| `parkio.dlt.aivalidation` | ai-validation | 3 | 14d |
| `parkio.dlt.analytics` | analytics | 3 | 14d |

DLT retention is 14 days (within the 14–30d band) to allow triage/redrive.

## Replication factor

`replicas` comes from `parkio.kafka.replication-factor` (default **1** for the
single-broker docker-compose). **Production must override** to ≥ 3 (with
`min.insync.replicas=2`, `acks=all`) via `PARKIO_KAFKA_REPLICATION_FACTOR`.

## Producer / consumer config assumptions

Set in each service's `application.yml` (`spring.kafka.*`); the broker is externalized
via `PARKIO_KAFKA_BOOTSTRAP_SERVERS` (default `localhost:29092`, the compose host
listener; in-cluster use `kafka:9092`).

- **Producer:** `acks=all`, `enable.idempotence=true`, `StringSerializer` key,
  `JsonSerializer` value.
- **Consumer:** `group-id = <service name>` (one consumer group per service),
  `auto-offset-reset=earliest` (local/dev), `enable-auto-commit=false` (commit only
  after successful processing + inbox write), `StringDeserializer` key, and
  `ErrorHandlingDeserializer` → `JsonDeserializer` for the value (poison messages route
  to the DLT instead of blocking the partition).
- **Listener:** `ack-mode=manual` (explicit ack after handling + inbox).
- **Admin:** `fail-fast=false` so a missing broker at startup never fails the context
  (there are no relay/consumers yet).

### Required Jackson (de)serialization compatibility

Consumers (and the outbox serializer) **must** be configured so contracts evolve safely
(also documented in `event-contracts.md`):

- `FAIL_ON_UNKNOWN_PROPERTIES = false` — consumer DTOs are intentional subsets; ignore
  unknown/new fields.
- `JavaTimeModule` registered with **ISO-8601** timestamps
  (`WRITE_DATES_AS_TIMESTAMPS = false`) for `Instant`/`occurredAt`.
- **Enums as strings** — read enum fields as `String` so unknown future values are safe.
- Unknown future fields are ignored, never rejected.

## Transport envelope (canonical shape)

The Kafka message **value** is a self-describing envelope; routing fields are mirrored
into headers so a consumer can dedup/route without full deserialization. This is an
**infrastructure transport** shape only — it carries **no business fields** (the
business event is the opaque `payload`):

| Field | Meaning |
|-------|---------|
| `eventId` | UUID — dedup key (matches the producer's outbox `event_id` column). |
| `eventType` | Event name (e.g. `ParkingSpotCreated`). |
| `aggregateType` | Aggregate kind (e.g. `ParkingSpot`). |
| `aggregateId` | Aggregate id; also the Kafka **key**. |
| `occurredAt` | Event time (UTC, ISO-8601). |
| `version` | Envelope/schema version (currently `1`). |
| `traceId` | Correlation id propagated producer → consumer. |
| `payload` | The event's JSON document (the outbox `payload`, embedded verbatim). |

> The envelope is **not** a shared class — consistent with the no-shared-module rule it
> is duplicated **locally** per service as `infrastructure.messaging.EventEnvelope`. It
> now exists in the relays (`auth`, `parking`, `gamification`, `media`, `ai-validation`)
> and the consumers (`user`, `gamification`, `notification`, `analytics`, `ai-validation`,
> `moderation`); the remaining services add their own copy when their relay/consumer is
> built. The relays map `outbox_events` columns → envelope (key = `aggregate_id`, dedup
> key = `event_id`) and mirror the routing fields into Kafka headers.

## Implementation order

1. **(done)** `spring-kafka` per service; common `spring.kafka.*` config; topic + DLT
   provisioning via `KafkaTopicsConfig`; the `event_id` outbox column.
2. **(done for auth + parking + gamification + media + ai-validation)** **Outbox relay**:
   poll unpublished `outbox_events`, wrap each in the envelope (key = `aggregate_id`, dedup
   key = `event_id`), publish with the idempotent producer, mark `published=true` only on
   ack (`AuthOutboxRelay`, `ParkingOutboxRelay`, `GamificationOutboxRelay`,
   `MediaOutboxRelay`, `AiValidationOutboxRelay`). Run a single instance per service (or
   partition-aware) to preserve per-aggregate order. Still TODO for user, moderation.
3. **(done for user + gamification + notification + analytics + ai-validation + moderation)**
   **Consumer**: `@KafkaListener` → dispatch by `eventType` → existing `handleXxx` use case
   (inbox dedup by `eventId`) → manual ack; `DefaultErrorHandler` +
   `DeadLetterPublishingRecoverer` → `parkio.dlt.<service>`. A service may run multiple
   listeners under one group on different topics (moderation consumes both `media.media`
   and `aivalidation.result`).
4. **(next)** Roll out the remaining flows: parking→{notification, analytics,
   ai-validation, moderation}; moderation→{user, gamification, notification}; and relays
   for user + moderation.
5. DLT redrive tooling, consumer-lag / outbox-lag metrics, replay/backfill runbooks.
6. Later: optional Debezium CDC relay; schema registry; Testcontainers integration tests
   for the live round-trips (currently unit-tested; see backlog below).

## Integration test backlog

The relays and consumers are covered by **unit tests** (envelope/header/key/topic
building + publish-then-mark for the relays; deserialize→dispatch→ack + idempotency +
ignore-unsupported for the consumers). **Testcontainers** integration tests that run the
live round-trips (auth→user, parking→gamification, gamification→{notification, analytics},
media→{ai-validation, moderation}, ai-validation→moderation) against a real broker are
deferred — they need a Kafka container in the test runtime, which the current
self-contained (H2-only, no Docker) test setup intentionally avoids (ai-context/08).

## Local broker

`docker/docker-compose.yml` runs a single-broker Kafka (KRaft) with
`KAFKA_AUTO_CREATE_TOPICS_ENABLE=false` — hence explicit provisioning. Host listener:
`localhost:29092`; in-cluster: `kafka:9092`.
