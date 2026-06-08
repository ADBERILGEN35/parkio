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
| `PointsEarned` | `POINTS_EARNED` (value = points) |
| `UserLevelChanged` | `LEVEL_UP` |
| `NotificationCreated` | `NOTIFICATION_CREATED` |

Counts accumulate as `event_count`; `POINTS_EARNED` also accumulates points into
`sum_value`.

## API

Aggregate endpoints are not user-specific; the personal endpoint reads the
gateway-injected `X-User-Id` and only lets a user view their own analytics.

| Method & path | Purpose |
|---------------|---------|
| `GET /api/v1/analytics/overview` | Lifetime KPI totals |
| `GET /api/v1/analytics/daily` | Daily time series (per metric) |
| `GET /api/v1/analytics/users/{userId}` | A user's own metrics (`X-User-Id` must match; else `403`) |
| `GET /api/v1/analytics/parking` | Parking funnel totals |
| `GET /api/v1/analytics/metrics` | All metric totals |

`overview` reports `totalParkingCreated`, `totalParkingVerified`,
`totalParkingClaimed`, `totalParkingRejected`, `totalPointsEarned`,
`totalLevelUps`, `totalNotificationsCreated`.

## Backlog (not yet implemented)

- Kafka consumer (upstream events) — handlers are invoked directly for now.
- Snapshot **recompute** job from the raw `analytics_events` log.
- Weekly/monthly rollups (the `TimeGranularity` enum is defined; only daily is wired).
- The `outbox_events` table is provisioned but unused (analytics emits no events yet);
  no BI tooling or dashboards are implemented here.

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
