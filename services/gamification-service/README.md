# gamification-service

Points, badges and leaderboards

- **Package:** `com.parkio.gamification`
- **Default port:** `8085` (override with `SERVER_PORT`)

## Architecture

This service follows clean architecture. Source lives under
`src/main/java/com/parkio/gamification`:

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
./gradlew :services:gamification-service:bootRun
```

## Build & test

```bash
./gradlew :services:gamification-service:build
```

## Docker

```bash
docker build -f services/gamification-service/Dockerfile -t parkio/gamification-service .
docker run -p 8085:8085 parkio/gamification-service
```
