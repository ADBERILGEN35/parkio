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

## Responsibilities

media-service owns **image upload metadata, file validation, storage object keys,
checksums and duplicate detection**. It does not own parking-spot business logic and
does not perform AI image analysis (that is advisory, in another service).

The bytes live in S3-compatible object storage (MinIO); this service's database
holds only metadata, the generated object key and a checksum.

## API

All routes are behind the gateway, which strips client-supplied identity headers and
injects a verified `X-User-Id`. Requests without a valid `X-User-Id` fail closed.

| Method & path                                | Purpose                                              |
|----------------------------------------------|------------------------------------------------------|
| `POST /api/v1/media/upload`                  | Multipart `file`. Validates mime/size, computes SHA-256, rejects duplicates (409), stores the object, returns `{ mediaId, status, contentType, fileSize }`. |
| `GET /api/v1/media/{mediaId}`                | Metadata only (no raw bucket/object-key internals).  |
| `DELETE /api/v1/media/{mediaId}`             | Soft-delete (owner only); best-effort object removal. |
| `GET /api/v1/media/{mediaId}/validation-results` | Recorded validation outcomes.                   |

### Validation

- Allowed content types: `image/jpeg`, `image/png`, `image/webp`.
- Max file size configurable (`parkio.media.max-file-size`, default 10MB); empty
  files rejected.
- Object keys are **generated** (`media/{ownerUserId}/{uuid}.{ext}`) — the original
  filename and any user-controlled path are never trusted.
- Duplicate checksum → `409`.

### Events (outbox)

`MediaUploadedEvent` is written to the outbox in the same transaction as a successful
upload; `MediaRejectedEvent` is recorded (in its own transaction) on rejection or
duplicate. A Kafka publisher/relay is not implemented yet (ai-context/06).

## Configuration

Object storage is configured via `parkio.media.storage.*` (`endpoint`, `bucket`,
`access-key`, `secret-key`, `region`). Credentials are injected via environment
variables; the defaults are **local-dev only** and must be overridden elsewhere
(ai-context/07).

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
