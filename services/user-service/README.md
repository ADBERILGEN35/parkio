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
