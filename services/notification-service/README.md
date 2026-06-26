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

## Delivery

The service has a production-shaped **push delivery foundation**. It runs locally
with no Firebase/APNS/SMTP credentials.

### In-app vs push

- **In-app**: every notification is stored and recorded as `SENT` immediately. The
  `GET /api/v1/notifications/me` API serves these.
- **Push**: when a notification is created, `NotificationDeliveryService` enqueues
  delivery attempts in `notification_delivery_attempts`:
  - push allowed + **active device token(s)** → one `PENDING` attempt per active
    token (due immediately via `next_attempt_at`);
  - push **explicitly enabled** + **no active token** → one `SKIPPED` attempt
    (reason `NO_ACTIVE_DEVICE_TOKEN`) for auditability;
  - push **explicitly disabled** → **no** attempt is created;
  - **no preference row** (privacy-safe default) → push is allowed **only if the
    user has an active device token**. Registering a device token is the explicit
    opt-in signal (it requires OS-level push permission); with no preference and no
    token, nothing is recorded.
- **Email**: intentionally **not** implemented (see backlog).

### Push preference default (product decision)

Push is **disabled until an opt-in signal exists**: either an explicit
`pushEnabled=true` preference or a registered active device token. An explicit
preference always wins (disabled blocks delivery even with active tokens). The
preferences API continues to report the all-channels-enabled defaults for users
without a stored preference row; actual push delivery additionally requires the
device-token opt-in above.

Delivery attempts store only a `device_token_id` reference, a provider message id and
a sanitised failure reason — **never** the raw token value (ai-context/07).

### Delivery worker

`PushDeliveryWorker` is a scheduled job that drains **due** `PENDING` push attempts
(`status = PENDING` and `next_attempt_at <= now`), sends each via the configured
`PushNotificationSender`, and marks the attempt `SENT` (with the provider message id)
or records the failure. It is **disabled by default in tests**.

**Cluster safety.** Each tick claims its batch with
`SELECT … FOR UPDATE SKIP LOCKED` inside the tick's transaction, so multiple
notification-service replicas can run the worker concurrently without ever sending
the same attempt twice: rows claimed by one replica are invisible to the others
until the batch commits. The pending queue is served by a **partial index**
(`idx_nda_pending_next_attempt` on `next_attempt_at WHERE status = 'PENDING'`) that
stays small as terminal rows accumulate.

**Retry / backoff.** A failed send keeps the attempt `PENDING` and pushes
`next_attempt_at` into the future with exponential backoff —
`base-backoff-ms * 2^(attemptCount-1)` (30s, 60s, 120s, … by default) — until
`max-attempts` (default **5**) is reached, at which point the attempt becomes
`FAILED` (terminal). A failing attempt is recorded as state, never thrown, so one
bad attempt cannot roll back the rest of the batch. Failure reasons are sanitised
codes (e.g. `PROVIDER_ERROR`, `DEVICE_TOKEN_INACTIVE`) — never raw provider errors
or token values.

| Property | Env var | Default | Meaning |
|----------|---------|---------|---------|
| `parkio.notification.delivery.push.enabled` | `PARKIO_PUSH_DELIVERY_ENABLED` | `true` | Enable the worker |
| `parkio.notification.delivery.push.provider` | `PARKIO_PUSH_DELIVERY_PROVIDER` | `noop` | `noop` or `fcm-disabled` |
| `parkio.notification.delivery.push.fixed-delay-ms` | `PARKIO_PUSH_DELIVERY_FIXED_DELAY_MS` | `30000` | Worker tick delay |
| `parkio.notification.delivery.push.batch-size` | `PARKIO_PUSH_DELIVERY_BATCH_SIZE` | `100` | Attempts per tick |
| `parkio.notification.delivery.push.max-attempts` | `PARKIO_PUSH_DELIVERY_MAX_ATTEMPTS` | `5` | Attempts before `FAILED` |
| `parkio.notification.delivery.push.base-backoff-ms` | `PARKIO_PUSH_DELIVERY_BASE_BACKOFF_MS` | `30000` | Base retry backoff (doubled per failure) |

### Provider modes (local/dev)

- **`noop`** (default): `NoopPushNotificationSender` "delivers" without contacting any
  provider and returns a synthetic provider message id — attempts end up `SENT`. No
  credentials required. This is the local/dev/test default.
- **`fcm-disabled`**: `FcmPushNotificationSender` is a **placeholder** marking where the
  real Firebase Cloud Messaging adapter will live. It performs no network call, needs no
  credentials, and returns a sanitised `FCM_NOT_CONFIGURED` failure. Real FCM is backlog.

### Required env vars for a real push provider (later)

Real FCM is **not** wired yet. When it is implemented, configure (and inject as secrets —
never commit them):

| Property | Env var | Default | Meaning |
|----------|---------|---------|---------|
| `parkio.notification.delivery.push.fcm.enabled` | `PARKIO_FCM_ENABLED` | `false` | Enable real FCM |
| `parkio.notification.delivery.push.fcm.project-id` | `PARKIO_FCM_PROJECT_ID` | _(empty)_ | Firebase project id |
| `parkio.notification.delivery.push.fcm.credentials-location` | `PARKIO_FCM_CREDENTIALS_LOCATION` | _(empty)_ | Service-account credentials location |

## Backlog (not yet implemented)

- **EMAIL delivery** (SMTP / provider): no attempts are created for the EMAIL channel
  yet; preferences expose `emailEnabled` but it is unused at send time.
- Real **Firebase/APNS** push (the `fcm-disabled` placeholder marks the seam).
- Outbox relay for `NotificationCreatedEvent`; upstream Kafka consumers are
  implemented for parking, moderation and gamification events.
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
