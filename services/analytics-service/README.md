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
