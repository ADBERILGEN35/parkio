# user-service

User profiles and account management

- **Package:** `com.parkio.user`
- **Default port:** `8082` (override with `SERVER_PORT`)

## Architecture

This service follows clean architecture. Source lives under
`src/main/java/com/parkio/user`:

| Layer            | Responsibility                                                        |
|------------------|-----------------------------------------------------------------------|
| `domain`         | Enterprise rules: entities, value objects, domain services, ports.    |
| `application`    | Use cases / orchestration of domain logic.                            |
| `infrastructure` | Adapters: persistence, messaging, external clients, configuration.    |
| `presentation`   | Inbound adapters: REST controllers, request/response models.          |
| `shared`         | Cross-cutting helpers scoped to this service only.                    |

> This service owns its own models. Domain models are **not** shared across services.

## Identity contract

- **`userId` in the public API means `authUserId`** — the platform-wide user id
  issued by `auth-service` and carried in the JWT `sub` / `X-User-Id` header. This
  is the only id other services and clients use to reference a user (ai-context/03).
- `GET /api/v1/users/{userId}/public-profile` resolves `{userId}` as the
  `authUserId`, and the response `userId` echoes that same `authUserId`.
- **`user_profiles.id` is internal** to user-service (its database primary key). It
  is never accepted as input nor exposed in any API response.

## Internal endpoints (service-to-service only)

- `GET /internal/users/{authUserId}/status` → `{ userId, status }`. Used by the
  **gateway** for per-request account-status enforcement (a JWT proves identity, not
  that the account is still active). It returns only the id + account `status`
  (`ACTIVE`/`SUSPENDED`/`BANNED`) — **no** profile data — and a missing profile yields
  `404` (the gateway treats that as non-active, failing closed). These `/internal/**`
  paths are **not** routed by the gateway (it forwards only `/api/v1/**`), so they are
  reachable only on the internal network and must never be exposed publicly
  (ai-context/07).

## Moderation account status (suspend / restore)

user-service consumes `UserSuspended` / `UserRestored` from
`parkio.moderation.action` (group `parkio.user`) and applies the result to
`user_profiles.status`. Two production realities are handled explicitly:

- **Events can arrive before the profile exists** (Kafka gives no cross-topic
  ordering with `UserRegistered` on `parkio.auth.user`). Such events are **never
  dropped**: they are parked in `pending_user_status_events` (keyed by the
  moderation `eventId`) inside the same transaction that marks the inbox row
  processed. When `UserRegistered` later provisions the profile, the **latest
  pending event by `occurredAt`** is applied — a profile provisioned after a
  suspension starts `SUSPENDED` — and the user's pending rows are removed.
- **Events can arrive out of order.** `user_profiles.last_status_event_at`
  records the `occurredAt` of the last applied status event; an event is applied
  only when `occurredAt >= last_status_event_at`. A stale restore therefore never
  overrides a newer suspension (and vice versa). Duplicates are deduplicated by
  `eventId` via `inbox_events`.

auth-service applies the same events to login/refresh independently (see its
README); the gateway's per-request status check uses this service's
`/internal/users/{authUserId}/status` endpoint.

## Run locally

From the repository root:

```bash
./gradlew :services:user-service:bootRun
```

## Build & test

```bash
./gradlew :services:user-service:build
```

## Docker

```bash
docker build -f services/user-service/Dockerfile -t parkio/user-service .
docker run -p 8082:8082 parkio/user-service
```
