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
>    → account status), **`auth`** (UserSuspended/Restored → AuthUser status: suspension
>    blocks login/refresh and revokes active refresh tokens; restoration re-enables login),
>    **`parking`** (authoritative spot rejection),
>    **`gamification`** (ParkingSpotRejectedByModerator → owner penalty),
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
> - **Consumers implemented:** `user` (`parkio.user`); `auth` (`parkio.auth`, consumes
>   `parkio.moderation.action` for account-status sync); `parking` (`parkio.parking`);
>   `gamification` (`parkio.gamification`);
>   `notification`, `analytics`, `ai-validation`, `moderation` each run **multiple**
>   `@KafkaListener`s under their one group across the topics they subscribe to. All use
>   inbox idempotency + DLT and ignore+ack unknown/unsupported types.
> - **Loop guard (intentional):** parking consumes
>   `ParkingSpotRejectedByModerator` and applies `REJECTED` without emitting
>   `ParkingSpotRejected`. The event carries `ownerUserId` when moderation opened
>   the case from a community illegal/risky verification, so
>   gamification's owner penalty and notification's owner warning are **active when the
>   owner is known** and skipped (null owner) for report/AI/media-opened cases.
> - **Not yet implemented:** a **consumer** for `UserProfileCreated`
>   (`parkio.user.profile` is produced but nothing subscribes yet);
>   `ContributionScoreUpdated` consumer.

## Loop guard: parking ↔ moderation (must not close the cycle)

An illegal/risky community verification flows `parking` →
`ParkingSpotVerified(result=ILLEGAL_OR_RISKY, status=SUSPICIOUS)` → `moderation`,
which opens a case. A moderator rejection then emits
`ParkingSpotRejectedByModerator`.

Guard rules:
1. Parking consumes the moderator action idempotently through `inbox_events`.
2. Parking changes the spot to `REJECTED` and records history but emits no
   `ParkingSpotRejected` event.
3. Gamification ignores community rejection/verification signals and applies a
   penalty only from `ParkingSpotRejectedByModerator`.
4. Moderation deduplicates case-opening events and reuses an active case per target.

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
| `parkio.dlt.auth` | auth | 3 | 14d |
| `parkio.dlt.user` | user | 3 | 14d |
| `parkio.dlt.parking` | parking | 3 | 14d |
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
- The shared `com.parkio.platform.messaging.EventEnvelope` transport record is
  annotated with `@JsonIgnoreProperties(ignoreUnknown = true)`. Payload DTOs stay
  service-local and must also ignore unknown fields where they deserialize
  external event payloads.

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

## Correlation and trace propagation

Parkio carries two related identifiers:

- OpenTelemetry trace context uses standard W3C propagation: `traceparent`, optional
  `tracestate`, and optional `baggage`.
- `X-Correlation-Id` remains the support-facing request id and is represented in API errors,
  event envelopes and legacy Kafka headers as `traceId`.

1. `gateway-service` forwards a client-supplied `X-Correlation-Id` or generates a
   UUID, then echoes it on the HTTP response.
2. Every downstream HTTP service puts that value into MDC as `correlationId` for the
   request lifetime. If called without the header, the service generates a UUID. Micrometer
   tracing separately puts the OTel `traceId` and `spanId` into MDC.
3. Every `ApiError` contains `code`, `message`, `traceId`, `timestamp`, and
   optional `fieldErrors`. Clients should report the `traceId` when contacting
   support.
4. Transactional outbox appenders capture the current W3C carrier into nullable
   `outbox_events.trace_id` when a span is active. They also retain the current `correlationId`.
   Existing/background rows may contain only the legacy correlation id or no trace value.
5. Relays restore the stored W3C context before calling `KafkaTemplate.send(...)`, publish
   `traceparent`, `tracestate` and `baggage` Kafka headers, and copy the correlation id into the
   envelope `traceId` plus legacy Kafka `traceId` header. When absent, the legacy header is
   omitted and the envelope field is null.
6. Spring Kafka observation extracts W3C headers for listener spans. Consumer record interceptors
   only populate MDC with `correlationId` and `eventId` while the listener runs, then clear them.
   Events emitted by a consumer therefore retain the originating trace through their own outbox
   write.

## Outbox DLQ / poison-message handling (producer side)

The relays must never let a single poison row block the rest of the outbox. Before this
was added, `publishPending()` was one transaction over the whole batch and **any** publish
failure (broker error *or* an unreadable JSON payload *or* an unroutable event type) threw
and rolled back the entire batch — so one bad row stalled every later event forever
(head-of-line blocking). The fix makes each row independent:

- **Per-row isolation.** Each row's ack is awaited in its own try/catch. A failure is
  recorded on the row and the relay **continues** to the next row; only a thread interrupt
  aborts the poll. Successful rows in the same batch still commit.
- **Pipelined dispatch (no per-row blocking).** `publishPending()` first dispatches *all*
  sends in the batch (`kafkaTemplate.send(...)`, no per-row `.get()`), then awaits the acks
  together under a single `send-timeout-ms` deadline. The batch settles in roughly **one**
  broker round-trip instead of `batch-size` serial ones, so the relay transaction (and its
  `FOR UPDATE` row locks + pooled connection) is held only briefly — Kafka network I/O never
  extends the transaction across more than that single await. The business transaction that
  wrote the row already committed independently, so business latency is unaffected regardless.
- **Failure tracking columns** (added to every `outbox_events` table):
  `failure_count` (int), `last_failure_reason` (text, bounded to 2000 chars),
  `last_failed_at` (timestamptz), `dead_lettered` (boolean).
- **Skip after N.** When `failure_count` reaches `parkio.kafka.relay.max-attempts`
  (default **10**) the row is marked `dead_lettered = true`. Dead-lettered rows are
  **retained in place** (never deleted, full payload intact) for inspection and manual
  redrive, but are excluded from the claim query, so they can never be retried
  automatically or block later events.
- **Claim query** is now
  `WHERE published = false AND dead_lettered = false ORDER BY created_at, id … FOR UPDATE
  SKIP LOCKED`, backed by a partial index `idx_outbox_events_relayable`.
- **Deterministic poison** (unreadable payload, no topic mapping) and **transient
  failures** (broker down/timeout) both count toward `max-attempts`. This is intentional
  for minimalism, with one operational caveat (below).
- **No separate DLQ table.** A `dead_lettered` status column on `outbox_events` keeps the
  poison row co-located with its payload/headers — the table *is* the DLQ.

Metrics (see `observability-metrics.md`): gauge `parkio.outbox.deadlettered.count`
(current poison depth — any non-zero value is actionable) plus counters
`parkio.outbox.publish.failed` and `parkio.outbox.deadlettered`. The
`parkio.outbox.unpublished.count` backlog gauge now **excludes** dead-lettered rows.
The relay also exports `parkio.outbox.publish.success` (counter), `parkio.outbox.publish.duration`
(timer: per-row dispatch→ack latency) and `parkio.outbox.batch.size` (summary: rows claimed
per poll) for throughput/latency tracking. A sustained `parkio.outbox.publish.failed` rate
trips the `OutboxPublishFailuresElevated` warning alert before rows dead-letter.

Tunables (per service `application.yml`, all overridable by env):

| Property | Default | Meaning |
|----------|---------|---------|
| `parkio.kafka.relay.max-attempts` | `10` | Failed publishes before a row is dead-lettered. |
| `parkio.kafka.relay.batch-size` | `100` | Rows claimed per poll. |
| `parkio.kafka.relay.poll-interval-ms` | `1000` | Relay poll interval. |
| `parkio.kafka.relay.send-timeout-ms` | `10000` | Shared deadline for awaiting a batch's broker acks (sends are pipelined, not serial). |

> **Operational caveat (broker outage vs poison).** Because transient send failures also
> increment `failure_count`, a broker outage lasting longer than ~`max-attempts` poll
> cycles will dead-letter rows that *would* have succeeded. They are **not lost** — they
> are retained and redrivable (`UPDATE outbox_events SET dead_lettered = false,
> failure_count = 0 WHERE dead_lettered = true`). For a planned outage longer than the
> window, set `parkio.kafka.relay.enabled=false` or raise `max-attempts` first.

### Manual inspection / redrive (current limitation)

There is **no automated redrive tooling yet**. Dead-lettered rows are inspected and
redriven with SQL against the owning service's database:

```sql
-- Inspect poison rows
SELECT id, event_type, failure_count, last_failed_at, last_failure_reason
FROM outbox_events WHERE dead_lettered = true ORDER BY last_failed_at DESC;

-- Redrive one row after fixing the root cause (relay re-claims it next poll)
UPDATE outbox_events
SET dead_lettered = false, failure_count = 0, last_failure_reason = NULL
WHERE id = '<row-id>';
```

The consumer DLT side (`parkio.dlt.<service>`) is separate and is monitored/redriven at
the broker level (see below).

## Outbox and inbox retention

Every service that owns `outbox_events` or `inbox_events` runs a bounded,
service-local cleanup job:

- Published outbox rows older than 7 days are deleted. Unpublished rows are
  never deleted by retention cleanup.
- Processed inbox rows older than 30 days are deleted.
- Each run deletes at most the configured batch size; the job repeats on a
  fixed delay.
- All lifecycle jobs are disabled in test configuration unless a test
  explicitly enables or invokes them.

The common property names are local to each service and can be overridden
independently:

| Property | Environment variable | Default |
|----------|----------------------|---------|
| `parkio.lifecycle.retention.outbox-enabled` | `PARKIO_OUTBOX_RETENTION_ENABLED` | `true` |
| `parkio.lifecycle.retention.inbox-enabled` | `PARKIO_INBOX_RETENTION_ENABLED` | `true` |
| `parkio.lifecycle.retention.outbox-retention` | `PARKIO_OUTBOX_RETENTION` | `P7D` |
| `parkio.lifecycle.retention.inbox-retention` | `PARKIO_INBOX_RETENTION` | `P30D` |
| `parkio.lifecycle.retention.fixed-delay-ms` | `PARKIO_RETENTION_FIXED_DELAY_MS` | `3600000` |
| `parkio.lifecycle.retention.batch-size` | `PARKIO_RETENTION_BATCH_SIZE` | `1000` |

Inbox retention bounds the deduplication window. After an inbox row is deleted,
replaying that older Kafka event can execute the consumer again. Production
retention must therefore exceed Kafka retention plus the expected DLT redrive,
replay, and backfill window; temporarily extend or disable cleanup before an
older replay.

> The envelope is a shared **infrastructure** class in
> `platform/parkio-platform`, not a shared domain/event-payload model. The relays
> map `outbox_events` columns → envelope (key = `aggregate_id`, dedup key =
> `event_id`) and mirror the routing fields into Kafka headers. Payload DTOs
> remain service-local.

## Implementation order

1. **(done)** `spring-kafka` per service; common `spring.kafka.*` config; topic + DLT
   provisioning via `KafkaTopicsConfig`; the `event_id` outbox column.
2. **(done for auth + parking + gamification + media + ai-validation + moderation + user)**
   **Outbox relay**: poll unpublished `outbox_events`, wrap each in the envelope (key =
   `aggregate_id`, dedup key = `event_id`), publish with the idempotent producer, mark
   `published=true` only on ack. `ModerationOutboxRelay` routes by event type to
   `parkio.moderation.case` vs `parkio.moderation.action`. `UserOutboxRelay` publishes
   `UserProfileCreated` → `parkio.user.profile`. **All producing relays are now done**,
   and all isolate failures per-row with skip-after-N dead-lettering (see "Outbox DLQ /
   poison-message handling").
3. **(done for user + parking + gamification + notification + analytics + ai-validation + moderation)**
   **Consumer**: `@KafkaListener` → dispatch by `eventType` → existing `handleXxx` use case
   (inbox dedup by `eventId`) → manual ack; `DefaultErrorHandler` +
   `DeadLetterPublishingRecoverer` → `parkio.dlt.<service>`. A service may run multiple
   listeners under one group across topics (e.g. notification now consumes
   `gamification.score`, `parking.spot`, `moderation.action` and `moderation.case`).
   Per-service listeners reuse the service's single string/manual-ack/DLT container factory.
4. **(next)** A **consumer** for `UserProfileCreated` on `parkio.user.profile` (the relay
   is **done** — `UserOutboxRelay` — but nothing subscribes yet); a
   `ContributionScoreUpdated` consumer (or stop publishing it). **(done)** moderation now
   populates `ownerUserId` on `ParkingSpotRejectedByModerator` (stored on the case from the
   community illegal/risky verification path), so the gamification penalty / notification owner-warning are
   active when the owner is known.
5. **(partially done)** Outbox-lag metrics are live: every outbox-owning service exports
   `parkio.outbox.unpublished.count` / `parkio.outbox.oldest.unpublished.age.seconds`
   (plus `parkio.inbox.processed.count` where an inbox exists) at `/actuator/prometheus`
   — see `docs/architecture/observability-metrics.md`. **(done)** Producer-side DLQ /
   poison-message handling: all seven relays isolate failures per-row and dead-letter a
   row after `parkio.kafka.relay.max-attempts`, exporting `parkio.outbox.deadlettered.count`
   plus the `parkio.outbox.publish.failed` / `parkio.outbox.deadlettered` counters (see
   "Outbox DLQ / poison-message handling" above). Still open: **automated** DLT/outbox
   redrive tooling and replay/backfill runbooks (outbox redrive is manual SQL today).
   **Consumer lag and DLT depth are monitored at the broker level** (e.g.
   `kafka-consumer-groups.sh --describe`, Burrow, or a Kafka exporter scraping the
   broker) — the apps deliberately do not duplicate them.
6. Later: optional Debezium CDC relay; schema registry; Testcontainers integration tests
   for the live round-trips (currently unit-tested; see backlog below).

## Integration tests (Testcontainers)

Integration tests use Testcontainers for Kafka and production infrastructure that
cannot be represented accurately by H2. They share the normal test source set but
are tagged `integration`, so they are opt-in:

| IT | Module | Verifies |
|----|--------|----------|
| `AuthOutboxRelayKafkaIT` | auth | Real `AuthOutboxRelay` publishes `UserRegistered` to `parkio.auth.user`; asserts topic, key, routing headers and the JSON envelope+payload round-trip. When the outbox row has a trace, it is mirrored into the `traceId` header and envelope. |
| `UserRegisteredConsumerKafkaIT` | user | Envelope produced to a real broker is consumed by the real `UserRegisteredKafkaConsumer`, deserialized and dispatched to the real `UserApplicationService` (in-memory fakes) → profile provisioned **once**; redelivery deduped by the inbox; both deliveries acked. |
| `ParkingOutboxRelayKafkaIT` | parking | Real `ParkingOutboxRelay` publishes `ParkingSpotCreated` to `parkio.parking.spot`, keyed by spot id, with headers. |
| `GamificationKafkaIT` | gamification | (1) `ParkingSpotCreated` envelope → real `ParkingEventsKafkaConsumer` + real `GamificationApplicationService` (fakes) awards owner points **once** across a redelivery (inbox idempotency); (2) a malformed/poison record routed through the **real container factory's error handler** lands on `parkio.dlt.gamification` (DLT behaviour). |
| `ParkingPostgisIntegrationTest` | parking | Starts PostGIS 16, runs every parking Flyway migration, validates the schema with Hibernate, verifies the PostGIS extension, location trigger and GiST index, and exercises radius/status/legal/expiry filtering plus nearest-first ordering through the production repository. |
| `OutboxDlqPostgisIntegrationTest` | parking | Starts PostGIS 16 and drives the real `ParkingOutboxRelay` (with a partly-failing Kafka producer) against the real schema: a poison row dead-letters after exhausting attempts, a later row still publishes, and the native claim query then skips both — proving the `dead_lettered` columns/migration and the `published = false AND dead_lettered = false` predicate that mocked unit tests can't exercise. |
| `MediaInfrastructureIntegrationTest` | media | Starts PostgreSQL 16 and MinIO, runs every media Flyway migration with Hibernate validation, then uploads, stats and deletes a PNG through the production MinIO adapter, and verifies a working presigned GET URL on the public endpoint. |

**How to run:** integration tests are tagged `@Tag("integration")` and `@Testcontainers(disabledWithoutDocker = true)`.
- `./gradlew build` / `test` — **unit tests only** (the `integration` tag is excluded); no Docker required, always green.
- `./gradlew integrationTest` — runs all tagged tests; **requires Docker**.
- Service-specific runs are available with
  `./gradlew :services:parking-service:integrationTest`,
  `./gradlew :services:media-service:integrationTest`, etc.
- Images currently match local Compose: Kafka `confluentinc/cp-kafka:7.7.1`,
  PostGIS `postgis/postgis:16-3.4`, PostgreSQL `postgres:16-alpine`, and MinIO
  `minio/minio:RELEASE.2024-09-13T20-26-02Z`.
- **Docker handling:** without Docker, container suites are discovered and
  **skipped** (not failed) — convenient locally. CI passes
  `-Pparkio.integrationTest.requireDocker=true`, which makes the `integrationTest`
  task **fail fast** if the daemon is unreachable, so a misconfigured runner can
  never report a false-green by silently skipping every suite.
- **CI:** these suites run in their own workflow
  (`.github/workflows/backend-integration.yml`) — on PRs that touch
  backend/build/docker paths, nightly, and on demand — not in the fast
  `backend-ci.yml` PR gate. See the README's *Continuous integration* section.

### Remaining integration-test backlog
- Consumer-side ITs for the other live flows: parking→{notification, analytics, ai-validation},
  gamification→{notification, analytics}, media→{ai-validation, moderation},
  ai-validation→moderation, moderation→{user, gamification, notification}.
- A full `@SpringBootTest` boot that drives the real `@KafkaListener` containers end-to-end
  (the current ITs invoke the real consumer/relay classes directly against a real broker to
  stay fast and avoid seeding reference data).
- DLT ITs for the other consumer DLTs (`parkio.dlt.{user,notification,analytics,moderation,aivalidation}`).
- Flyway smoke coverage for the remaining service databases and S3 providers
  other than MinIO remain untested.

## Local broker

`docker/docker-compose.yml` runs a single-broker Kafka (KRaft) with
`KAFKA_AUTO_CREATE_TOPICS_ENABLE=false` — hence explicit provisioning. Host listener:
`localhost:29092`; in-cluster: `kafka:9092`.
