# parking-service

Parking spots, availability and reservations

- **Package:** `com.parkio.parking`
- **Default port:** `8083` (override with `SERVER_PORT`)

## Architecture

This service follows clean architecture. Source lives under
`src/main/java/com/parkio/parking`:

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
./gradlew :services:parking-service:bootRun
```

## Build & test

```bash
./gradlew :services:parking-service:build
```

## Docker

```bash
docker build -f services/parking-service/Dockerfile -t parkio/parking-service .
docker run -p 8083:8083 parkio/parking-service
```
