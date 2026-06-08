# notification-service

Push, email and in-app notifications

- **Package:** `com.parkio.notification`
- **Default port:** `8086` (override with `SERVER_PORT`)

## Architecture

This service follows clean architecture. Source lives under
`src/main/java/com/parkio/notification`:

| Layer            | Responsibility                                                        |
|------------------|-----------------------------------------------------------------------|
| `domain`         | Enterprise rules: entities, value objects, domain services, ports.    |
| `application`    | Use cases / orchestration of domain logic.                            |
| `infrastructure` | Adapters: persistence, messaging, external clients, configuration.    |
| `presentation`   | Inbound adapters: REST controllers, request/response models.          |
| `shared`         | Cross-cutting helpers scoped to this service only.                    |

> This service owns its own models. Domain models are **not** shared across services.

## Responsibilities

notification-service owns **in-app notifications, device tokens, notification
channel preferences and delivery records**. It does not own user profiles, auth,
parking lifecycle, gamification scoring or media (ai-context/03). `user_id` is the
platform-wide authUserId.

## API

All endpoints require the gateway-injected `X-User-Id` and fail closed (`401`) if
it's absent/invalid. A user may only read/modify their **own** notifications and
device tokens (another user's id is treated as `404`).

| Method & path | Purpose |
|---------------|---------|
| `GET /api/v1/notifications/me` | The caller's recent notifications |
| `PATCH /api/v1/notifications/{notificationId}/read` | Mark a notification READ |
| `POST /api/v1/notifications/device-token` | Register/re-activate a device token |
| `DELETE /api/v1/notifications/device-token/{tokenId}` | Deactivate a device token |
| `GET /api/v1/notifications/me/preferences` | Channel preferences (defaults if unset) |
| `PATCH /api/v1/notifications/me/preferences` | Update channel preferences |

Device tokens are **unique per (user, token)** and are **deactivated, not deleted**;
re-registering a deactivated token reactivates it.

## Event handling (inbox)

Upstream events (see `docs/architecture/event-contracts.md`) are consumed
**idempotently** via `inbox_events` (dedup by `eventId`). Inbound DTOs are **local
copies** of the producers' payloads (contracts are duplicated, never shared):

- `UserLevelChanged` → `LEVEL_UP` in-app notification
- `PointsEarned` → `POINT_EARNED` in-app notification
- `PointsDeducted` → `WARNING` in-app notification
- `ParkingSpotRejected` → `WARNING` notification for the spot owner
- `ParkingSpotCreated` → **no-op for now** (nearby fan-out is backlog)

Content is rendered from seeded `notification_templates` (with a code fallback).
Each created notification appends a `NotificationCreatedEvent` to the outbox.

## Delivery (placeholder)

Real push/email is **not** implemented. In-app notifications are recorded as `SENT`;
external channels (`PUSH`/`EMAIL`) would start `PENDING` for a future delivery relay.

## Backlog (not yet implemented)

- Kafka consumer (upstream events) + outbox relay (publish to Kafka).
- Real Firebase/APNS push and SMTP email delivery (+ honouring channel preferences
  and active device tokens at send time).
- **Nearby fan-out** for `ParkingSpotCreated` once location-based user targeting
  exists.

## Run locally

From the repository root:

```bash
./gradlew :services:notification-service:bootRun
```

## Build & test

```bash
./gradlew :services:notification-service:build
```

## Docker

```bash
docker build -f services/notification-service/Dockerfile -t parkio/notification-service .
docker run -p 8086:8086 parkio/notification-service
```
