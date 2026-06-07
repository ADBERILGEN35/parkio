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
