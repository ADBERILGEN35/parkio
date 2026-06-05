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
