# media-service

Upload and serving of images and other media

- **Package:** `com.parkio.media`
- **Default port:** `8084` (override with `SERVER_PORT`)

## Architecture

This service follows clean architecture. Source lives under
`src/main/java/com/parkio/media`:

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
./gradlew :services:media-service:bootRun
```

## Build & test

```bash
./gradlew :services:media-service:build
```

## Docker

```bash
docker build -f services/media-service/Dockerfile -t parkio/media-service .
docker run -p 8084:8084 parkio/media-service
```
