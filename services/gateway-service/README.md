# gateway-service

API gateway and edge routing for all Parkio services

- **Package:** `com.parkio.gateway`
- **Default port:** `8080` (override with `SERVER_PORT`)

## Architecture

This service follows clean architecture. Source lives under
`src/main/java/com/parkio/gateway`:

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
./gradlew :services:gateway-service:bootRun
```

## Build & test

```bash
./gradlew :services:gateway-service:build
```

## Docker

```bash
docker build -f services/gateway-service/Dockerfile -t parkio/gateway-service .
docker run -p 8080:8080 parkio/gateway-service
```
