# auth-service

Authentication, authorization and token issuance

- **Package:** `com.parkio.auth`
- **Default port:** `8081` (override with `SERVER_PORT`)

## Architecture

This service follows clean architecture. Source lives under
`src/main/java/com/parkio/auth`:

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
./gradlew :services:auth-service:bootRun
```

## Build & test

```bash
./gradlew :services:auth-service:build
```

## Docker

```bash
docker build -f services/auth-service/Dockerfile -t parkio/auth-service .
docker run -p 8081:8081 parkio/auth-service
```
