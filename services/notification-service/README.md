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
re-registering a deactivated token reactivates it. A push token is **active for at most
one account**: registering it deactivates every other user's active row for that token in
the same transaction, so the account that used the device before stops receiving pushes
there. A transaction-scoped advisory lock on the token serialises concurrent registrations
(the last one wins). The erasure fence is checked first, so an erased user can neither
register nor take a token over.

The handover update finds the other accounts' rows through the partial index
`idx_device_tokens_active_token` on `device_tokens (token) WHERE active` (V17, #601).
From the token lock on, every lock wait of the registration, the handover's row locks
included, gives up after 5 s (`lock_timeout`, set for that transaction only). The holders
take milliseconds: other registrations of the token, an owner deactivating a token, the
erasure handler, and a delivery tick, which writes a token it deactivated after
`DeviceNotRegistered` only when its batch commits. The datasource itself sets no
`lock_timeout` or `statement_timeout`.

A registration that gives up is rolled back and answers the generic `500 INTERNAL_ERROR`,
with no database detail; the error is logged at ERROR. The previous account's row stays
active, so its pushes keep reaching the device. The mobile apps log the failure and
register again only after their next start or sign-in.

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

**Scheduler.** The worker runs on its own scheduler, `pushDeliveryTaskScheduler`, with
one `push-delivery-` thread. Every other scheduled job (the outbox relay, the retention
cleanup and both Smart Return jobs) keeps Spring Boot's default scheduler, one
`scheduling-` thread. While Expo does not answer, a tick waits out the read timeout once
per attempt (see the Expo bounds below), and only push delivery waits with it (#641).

**Cluster safety.** Each tick claims its batch with
`SELECT … FOR UPDATE SKIP LOCKED` inside the tick's transaction, so multiple
notification-service replicas can run the worker concurrently without ever sending
the same attempt twice: rows claimed by one replica are invisible to the others
until the batch commits. The pending queue is served by a **partial index**
(`idx_nda_pending_next_attempt` on `next_attempt_at WHERE status = 'PENDING'`) that
stays small as terminal rows accumulate.

**Shutdown.** When shutdown interrupts a tick that is still sending (the scheduler calls
`shutdownNow()` once the 30s lifecycle stop phase has ended), the send in flight ends as
`PROVIDER_OUTCOME_UNKNOWN`, because Expo may already have it. The tick stops there: the
attempts it has not tried stay `PENDING` and due, the commit releases their claims, and
the next tick, on any replica, takes them (#651).

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
| `parkio.notification.delivery.push.provider` | `PARKIO_PUSH_DELIVERY_PROVIDER` | `noop` | `noop`, `expo`, or `fcm-disabled` |
| `parkio.notification.delivery.push.fixed-delay-ms` | `PARKIO_PUSH_DELIVERY_FIXED_DELAY_MS` | `30000` | Worker tick delay |
| `parkio.notification.delivery.push.batch-size` | `PARKIO_PUSH_DELIVERY_BATCH_SIZE` | `100` | Attempts per tick |
| `parkio.notification.delivery.push.max-attempts` | `PARKIO_PUSH_DELIVERY_MAX_ATTEMPTS` | `5` | Attempts before `FAILED` |
| `parkio.notification.delivery.push.base-backoff-ms` | `PARKIO_PUSH_DELIVERY_BASE_BACKOFF_MS` | `30000` | Base retry backoff (doubled per failure) |

### Provider modes (local/dev)

- **`noop`** (default): `NoopPushNotificationSender` "delivers" without contacting any
  provider and returns a synthetic provider message id — attempts end up `SENT`. No
  credentials required. This is the local/dev/test default.
- **`expo`**: `ExpoPushNotificationSender` delivers to Expo push tokens via the Expo Push
  API. Requires `PARKIO_EXPO_ACCESS_TOKEN`. Invalid tokens (`DeviceNotRegistered`) are
  deactivated automatically.
- **`fcm-disabled`**: `FcmPushNotificationSender` is a **placeholder** marking where the
  real Firebase Cloud Messaging adapter will live. It performs no network call, needs no
  credentials, and returns a sanitised `FCM_NOT_CONFIGURED` failure. Real FCM is backlog.

### Required env vars for Expo push (hosted beta / production mobile)

| Property | Env var | Default | Meaning |
|----------|---------|---------|---------|
| `parkio.notification.delivery.push.expo.access-token` | `PARKIO_EXPO_ACCESS_TOKEN` | _(empty)_ | Expo access token |
| `parkio.notification.delivery.push.expo.base-url` | `PARKIO_EXPO_PUSH_BASE_URL` | `https://exp.host/--/api/v2` | Expo Push API base |
| `parkio.notification.delivery.push.expo.android-channel-id` | `PARKIO_EXPO_ANDROID_CHANNEL_ID` | `default` | Android notification channel |
| `parkio.notification.delivery.push.expo.connect-timeout` | `PARKIO_EXPO_PUSH_CONNECT_TIMEOUT` | `2s` | Bound on opening the connection, TLS handshake included, DNS lookup not included; at most `30s` |
| `parkio.notification.delivery.push.expo.read-timeout` | `PARKIO_EXPO_PUSH_READ_TIMEOUT` | `5s` | Bound on the whole exchange, DNS lookup and connecting included; at most `60s`, and longer than the connect timeout |

The delivery tick sends while it holds the `FOR UPDATE SKIP LOCKED` claims of its batch,
so a send without a bound would stop push delivery until a restart (#595). A connection
that does not open within the connect timeout stays `PROVIDER_UNAVAILABLE` and is retried;
a response that does not complete within the read timeout is `PROVIDER_OUTCOME_UNKNOWN`,
because Expo may already have the request. The read timeout runs from the start of the
exchange, so the connect timeout must be shorter. The connect timeout does not cover the
DNS lookup of the Expo host: the JDK resolves the name before it starts the connect timer,
while the read timeout is already running (#641). When the lookup and the connection
attempt together outlast the read timeout, the send therefore ends at the read timeout as
`PROVIDER_OUTCOME_UNKNOWN` and is not retried, although no connection was opened. That is
the case for a lookup slower than the read timeout, and for one slower than the read
timeout minus the connect timeout (3s with the defaults) when the connection then does not
open. The result errs on the side of no resend. A lookup that fails before the read
timeout (unknown host) stays `PROVIDER_UNAVAILABLE`. The service refuses to start with a
zero, negative or over-limit value, or with a connect timeout that is not shorter than the
read timeout; the hosted-beta preflight checks the same rules. The Expo client is always
the JDK HTTP client, whatever factory `spring.http.clients` selects for other clients,
because only its `HttpConnectTimeoutException` is classified as a connect timeout that is
safe to retry. While Expo stalls, a tick lasts at most about `batch-size` x `read-timeout`
(100 x 5s by default). It runs on the worker's own scheduler thread, so no other scheduled
job waits for it (#641).

Push metrics: `parkio.notification.push.{sent,failed,invalid_token,provider_unavailable,outcome_unknown}.count`,
`parkio.notification.delivery.retry.count`, `parkio.notification.delivery.worker.*`.
`provider_unavailable` counts only retryable failures (no connection, or an HTTP rejection
other than 401/403); `outcome_unknown` counts terminal `PROVIDER_OUTCOME_UNKNOWN` results;
HTTP 401/403 counts under `failed`, like the ticket-level credential errors.

Expo send accepts either a single ticket or a one-element ticket array. `SENT`
means **Expo ticket accepted**, not delivery to FCM/APNs or a device; the real ticket
ID is retained. Missing IDs, malformed/empty responses and transport failures after
the connection is established (read timeouts, resets, unreadable responses) are terminal
`FAILED` with `PROVIDER_OUTCOME_UNKNOWN`: acceptance is unresolved and the worker
does not resend automatically. This trades possible missed notifications for
avoiding duplicate sends when acceptance cannot be established. A failure to
connect (refused, unresolved host, no route, connect timeout) proves Expo never
received the request, so it is `PROVIDER_UNAVAILABLE` and keeps bounded backoff,
like HTTP rejection (except 401/403) and other explicit ticket failures. The one
exception is a slow DNS lookup: when the read timeout ends the send before the connection
attempt has failed, it is `PROVIDER_OUTCOME_UNKNOWN` (see the Expo bounds above).
`DeviceNotRegistered` is terminal and deactivates the token. Credential/configuration
failures (HTTP 401/403, `InvalidCredentials`, `UNAUTHORIZED`, `MismatchSenderId`)
are terminal `PROVIDER_CONFIGURATION_ERROR` and preserve the token.

Receipt lookup is a **separate API contract** (IDs mapped to receipt objects), not
implemented by this sender. Credential errors in receipts concern provider setup,
not token validity. Receipt collection, retention and operator reconciliation of
unknown outcomes require a separate design; no receipt polling or automatic
unknown-outcome replay is implied here. See the
[Expo send/ticket/receipt contract](https://docs.expo.dev/push-notifications/sending-notifications/).

### Required env vars for a real FCM provider (later)

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
- Real **Firebase/APNS** direct push (the `fcm-disabled` placeholder marks the seam; mobile uses Expo Push today).
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
