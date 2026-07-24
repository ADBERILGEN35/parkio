# analytics-service

Event ingestion and analytics aggregation

- **Package:** `com.parkio.analytics`
- **Default port:** `8089` (override with `SERVER_PORT`)

## Architecture

This service follows clean architecture. Source lives under
`src/main/java/com/parkio/analytics`:

| Layer            | Responsibility                                                        |
|------------------|-----------------------------------------------------------------------|
| `domain`         | Enterprise rules: entities, value objects, domain services, ports.    |
| `application`    | Use cases / orchestration of domain logic.                            |
| `infrastructure` | Adapters: persistence, messaging, external clients, configuration.    |
| `presentation`   | Inbound adapters: REST controllers, request/response models.          |
| `shared`         | Cross-cutting helpers scoped to this service only.                    |

> This service owns its own models. Domain models are **not** shared across services.

## Responsibilities

analytics-service owns **event analytics, KPIs, aggregates and reporting
projections**. It is **projection-only** (ai-context/03): it never modifies source
business data and makes no business/moderation decisions. `user_id` is the
platform-wide authUserId.

## Event ingestion

Upstream events (see `docs/architecture/event-contracts.md`) are ingested
**idempotently** via `inbox_events` (dedup by `eventId`). Inbound DTOs are **local
copies** of the producers' payloads (contracts are duplicated, never shared). For
each event the service: records a raw `AnalyticsEvent` (audit; lets snapshots be
recomputed), then increments the **daily**, **per-user** and (for parking metrics)
**parking-funnel** snapshots — all in one transaction.

| Event | Metric |
|-------|--------|
| `ParkingSpotCreated` | `PARKING_CREATED` |
| `ParkingSpotVerified` | `PARKING_VERIFIED` |
| `ParkingSpotClaimed` | `PARKING_CLAIMED` |
| `ParkingSpotRejected` | `PARKING_REJECTED` |
| `ParkingSessionStarted` (MANUAL) | `PARKING_SESSION_STARTED_MANUAL` → product `parking_session_started` |
| `ParkingSessionStarted` (COMMUNITY) | `PARKING_SESSION_STARTED_COMMUNITY` → product `parking_session_started` |
| `ParkingSessionStarted` (other) | `PARKING_SESSION_STARTED_OTHER` → product `parking_session_started` |
| `ParkingSessionCompleted` | `PARKING_SESSION_COMPLETED` → product `parking_session_completed` (value = duration seconds) |
| `ParkingSessionCancelled` | `PARKING_SESSION_CANCELLED` → product `parking_session_cancelled` (value = duration seconds) |
| `PointsEarned` | `POINTS_EARNED` (value = points) |
| `UserLevelChanged` | `LEVEL_UP` |
| `NotificationCreated` | `NOTIFICATION_CREATED` |

Kafka listeners (group `parkio.analytics`):

- `parkio.parking.spot` → `ParkingEventsKafkaConsumer`
- `parkio.parking.session` → `ParkingSessionEventsKafkaConsumer` (S1-P0-09)
- `parkio.gamification.score` → `GamificationScoreKafkaConsumer`
- `parkio.notification.notification` → `NotificationEventsKafkaConsumer`

Session lifecycle details: `docs/architecture/PARKING-SESSION-ANALYTICS-INGESTION.md`.

Counts accumulate as `event_count`; `POINTS_EARNED` and terminal session metrics also accumulate
into `sum_value` (points / duration seconds).

## API

Platform (aggregate) endpoints are **`ADMIN`-only** reporting (separation of duties —
moderators have no access): they require `ADMIN` in the gateway-injected `X-User-Roles`
header, enforced both at the gateway and re-checked in the controller (defense in depth,
fail closed → `403`). The personal endpoint reads the gateway-injected `X-User-Id` and
only lets a user view their own analytics.

| Method & path | Access | Purpose |
|---------------|--------|---------|
| `GET /api/v1/analytics/overview` | `ADMIN` | Lifetime KPI totals |
| `GET /api/v1/analytics/daily` | `ADMIN` | Daily time series (per metric) |
| `GET /api/v1/analytics/users/{userId}` | owner | A user's own metrics (`X-User-Id` must match; else `403`) |
| `GET /api/v1/analytics/parking` | `ADMIN` | Parking funnel totals |
| `GET /api/v1/analytics/metrics` | `ADMIN` | All metric totals |

`overview` reports `totalParkingCreated`, `totalParkingVerified`,
`totalParkingClaimed`, `totalParkingRejected`, `totalPointsEarned`,
`totalLevelUps`, `totalNotificationsCreated`.

## Backlog (not yet implemented)

- Snapshot **recompute** job from the raw `analytics_events` log.
- Weekly/monthly rollups (the `TimeGranularity` enum is defined; only daily is wired).
- The `outbox_events` table is provisioned but unused (analytics emits no events yet);
  no BI tooling or dashboards are implemented here.
- `parking_history_deleted` ingestion (Sprint R22; deferred past S1-P0-09).

## Run locally

From the repository root:

```bash
./gradlew :services:analytics-service:bootRun
```

## Build & test

```bash
./gradlew :services:analytics-service:build
```

## Docker

```bash
docker build -f services/analytics-service/Dockerfile -t parkio/analytics-service .
docker run -p 8089:8089 parkio/analytics-service
```
