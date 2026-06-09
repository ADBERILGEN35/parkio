# Kafka Transport

How Parkio's asynchronous event backbone is wired. This complements
[`event-contracts.md`](event-contracts.md) (the payload/contract registry) and
[`../ai-context/06-event-guidelines.md`](../ai-context/06-event-guidelines.md).

> **Status:** topics + shared Kafka build/config conventions are in place, and **the
> following end-to-end flows are live:**
> 1. `auth-service` → `parkio.auth.user` → `user-service`
> 2. `parking-service` → `parkio.parking.spot` → **`gamification`, `notification`,
>    `analytics`, `ai-validation`, `moderation`** (fan-out: five consumer groups on one
>    topic, each handling the subset it cares about)
> 3. `gamification-service` → `parkio.gamification.score` → `notification-service` **and**
>    `analytics-service`
> 4. `media-service` → `parkio.media.media` → `ai-validation-service` (`MediaUploaded`)
>    **and** `moderation-service` (`MediaRejected`)
> 5. `ai-validation-service` → `parkio.aivalidation.result` → `moderation-service`
>    (`AiValidationCompleted`)
> 6. `moderation-service` → `parkio.moderation.action` → **`user`** (UserSuspended/Restored
>    → account status), **`gamification`** (ParkingSpotRejectedByModerator → owner penalty),
>    **`notification`** (suspend/restore/spot-rejected notices); and
>    `moderation-service` → `parkio.moderation.case` → **`notification`** (AppealResolved,
>    ModerationCaseResolved for USER-targeted cases)
> - **Relays implemented:** `auth`, `parking`, `gamification`, `media`, `ai-validation`,
>   `moderation` (`ModerationOutboxRelay`, routes case events → `parkio.moderation.case`
>   and action events → `parkio.moderation.action`), **and `user`** (`UserOutboxRelay`,
>   publishes `UserProfileCreated` → `parkio.user.profile`). **All six producing services
>   now relay; no relay remains outstanding.** `UserProfileCreated` currently has **no
>   consumer** — it is published for completeness/future projections (it is not on any
>   live end-to-end flow yet).
> - **Consumers implemented:** `user` (`parkio.user`); `gamification` (`parkio.gamification`);
>   `notification`, `analytics`, `ai-validation`, `moderation` each run **multiple**
>   `@KafkaListener`s under their one group across the topics they subscribe to. All use
>   inbox idempotency + DLT and ignore+ack unknown/unsupported types.
> - **Loop guard (intentional):** `ParkingSpotRejectedByModerator` is **published** to
>   `parkio.moderation.action` but **parking-service must NOT consume it** — see the
>   loop-guard section below. The event now carries `ownerUserId` (moderation stores the
>   owner on the case when it is opened from a community `ParkingSpotRejected`), so
>   gamification's owner penalty and notification's owner warning are **active when the
>   owner is known** and skipped (null owner) for report/AI/media-opened cases.
> - **Not yet implemented:** a **consumer** for `UserProfileCreated` (`parkio.user.profile`
>   is produced but nothing subscribes yet); a parking consumer of `parkio.moderation.action`
>   (deferred by design); `ContributionScoreUpdated` consumer.

## Loop guard: parking ↔ moderation (must not close the cycle)

A community-rejected spot already flows `parking` → `ParkingSpotRejected` →
`moderation` (opens a case). When a moderator then resolves that case with REJECT/
MARK_RISKY, moderation emits `ParkingSpotRejectedByModerator` to
`parkio.moderation.action`. If parking-service consumed that event, set the spot
`REJECTED`, and **re-emitted** `ParkingSpotRejected`, moderation would open another case →
resolve → emit again → **infinite loop**.

Guard rules (enforced for now by omission, to be honored when the flow is wired):
1. **No parking-service consumer of `parkio.moderation.action` exists** (intentionally not
   built in this task).
2. When parking-service *does* apply a moderator-driven rejection, it **must set the spot
   status without re-emitting `ParkingSpotRejected`** (a moderator rejection is terminal,
   not a new community report). Treat `ParkingSpotRejectedByModerator` as the authoritative
   end state.
3. moderation already dedupes per active case (`openCaseIfAbsent`), but that is a
   second line of defence, not the primary guard.

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
> now exists in the relays (`auth`, `parking`, `gamification`, `media`, `ai-validation`,
> `moderation`) and the consumers (`user`, `gamification`, `notification`, `analytics`,
> `ai-validation`, `moderation`); the remaining services add their own copy when their
> relay/consumer is built. The relays map `outbox_events` columns → envelope (key = `aggregate_id`, dedup
> key = `event_id`) and mirror the routing fields into Kafka headers.

## Implementation order

1. **(done)** `spring-kafka` per service; common `spring.kafka.*` config; topic + DLT
   provisioning via `KafkaTopicsConfig`; the `event_id` outbox column.
2. **(done for auth + parking + gamification + media + ai-validation + moderation + user)**
   **Outbox relay**: poll unpublished `outbox_events`, wrap each in the envelope (key =
   `aggregate_id`, dedup key = `event_id`), publish with the idempotent producer, mark
   `published=true` only on ack. `ModerationOutboxRelay` routes by event type to
   `parkio.moderation.case` vs `parkio.moderation.action`. `UserOutboxRelay` publishes
   `UserProfileCreated` → `parkio.user.profile`. **All producing relays are now done.**
3. **(done for user + gamification + notification + analytics + ai-validation + moderation)**
   **Consumer**: `@KafkaListener` → dispatch by `eventType` → existing `handleXxx` use case
   (inbox dedup by `eventId`) → manual ack; `DefaultErrorHandler` +
   `DeadLetterPublishingRecoverer` → `parkio.dlt.<service>`. A service may run multiple
   listeners under one group across topics (e.g. notification now consumes
   `gamification.score`, `parking.spot`, `moderation.action` and `moderation.case`).
   Per-service listeners reuse the service's single string/manual-ack/DLT container factory.
4. **(next)** A **consumer** for `UserProfileCreated` on `parkio.user.profile` (the relay
   is **done** — `UserOutboxRelay` — but nothing subscribes yet); a parking consumer of
   `parkio.moderation.action` **only with the loop guard above**; a
   `ContributionScoreUpdated` consumer (or stop publishing it). **(done)** moderation now
   populates `ownerUserId` on `ParkingSpotRejectedByModerator` (stored on the case from the
   community-rejection path), so the gamification penalty / notification owner-warning are
   active when the owner is known.
5. DLT redrive tooling, consumer-lag / outbox-lag metrics, replay/backfill runbooks.
6. Later: optional Debezium CDC relay; schema registry; Testcontainers integration tests
   for the live round-trips (currently unit-tested; see backlog below).

## Integration tests (Testcontainers)

Every relay and consumer is covered by **unit tests** (envelope/header/key/topic building
+ publish-then-mark for relays; deserialize→dispatch→ack + idempotency + ignore-unsupported
for consumers). The **first real-broker integration tests** now exist, using a
Testcontainers Kafka broker and the production serializers/classes:

| IT | Module | Verifies |
|----|--------|----------|
| `AuthOutboxRelayKafkaIT` | auth | Real `AuthOutboxRelay` publishes `UserRegistered` to `parkio.auth.user`; asserts topic, key, all six headers (`eventId/eventType/aggregateType/aggregateId/occurredAt/version`) and the JSON envelope+payload round-trip. |
| `UserRegisteredConsumerKafkaIT` | user | Envelope produced to a real broker is consumed by the real `UserRegisteredKafkaConsumer`, deserialized and dispatched to the real `UserApplicationService` (in-memory fakes) → profile provisioned **once**; redelivery deduped by the inbox; both deliveries acked. |
| `ParkingOutboxRelayKafkaIT` | parking | Real `ParkingOutboxRelay` publishes `ParkingSpotCreated` to `parkio.parking.spot`, keyed by spot id, with headers. |
| `GamificationKafkaIT` | gamification | (1) `ParkingSpotCreated` envelope → real `ParkingEventsKafkaConsumer` + real `GamificationApplicationService` (fakes) awards owner points **once** across a redelivery (inbox idempotency); (2) a malformed/poison record routed through the **real container factory's error handler** lands on `parkio.dlt.gamification` (DLT behaviour). |

**How to run:** integration tests are tagged `@Tag("integration")` and `@Testcontainers(disabledWithoutDocker = true)`.
- `./gradlew build` / `test` — **unit tests only** (the `integration` tag is excluded); no Docker required, always green.
- `./gradlew integrationTest` — runs the tagged ITs; **requires Docker** (Testcontainers pulls `confluentinc/cp-kafka:7.7.1`). Without Docker the ITs are **skipped**, not failed.

### Remaining integration-test backlog
- Consumer-side ITs for the other live flows: parking→{notification, analytics, ai-validation},
  gamification→{notification, analytics}, media→{ai-validation, moderation},
  ai-validation→moderation, moderation→{user, gamification, notification}.
- A full `@SpringBootTest` boot that drives the real `@KafkaListener` containers end-to-end
  (the current ITs invoke the real consumer/relay classes directly against a real broker to
  stay fast and avoid seeding reference data).
- DLT ITs for the other consumer DLTs (`parkio.dlt.{user,notification,analytics,moderation,aivalidation}`).

## Local broker

`docker/docker-compose.yml` runs a single-broker Kafka (KRaft) with
`KAFKA_AUTO_CREATE_TOPICS_ENABLE=false` — hence explicit provisioning. Host listener:
`localhost:29092`; in-cluster: `kafka:9092`.
