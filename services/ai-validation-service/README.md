# ai-validation-service

AI-assisted validation of submissions

- **Package:** `com.parkio.aivalidation`
- **Default port:** `8088` (override with `SERVER_PORT`)

## Architecture

This service follows clean architecture. Source lives under
`src/main/java/com/parkio/aivalidation`:

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
./gradlew :services:ai-validation-service:bootRun
```

## Build & test

```bash
./gradlew :services:ai-validation-service:build
```

## Docker

```bash
docker build -f services/ai-validation-service/Dockerfile -t parkio/ai-validation-service .
docker run -p 8088:8088 parkio/ai-validation-service
```
