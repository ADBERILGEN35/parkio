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
injects a verified `X-User-Id` (and `X-User-Roles`). Requests without a valid
`X-User-Id` fail closed (`401`).

| Method & path                                | Purpose                                              |
|----------------------------------------------|------------------------------------------------------|
| `POST /api/v1/media/upload`                  | Multipart `file` plus required `Idempotency-Key`. Validates mime/size, computes SHA-256, rejects duplicates (409), stores the object, returns `{ mediaId, status, contentType, fileSize }`. |
| `GET /api/v1/media/{mediaId}`                | Metadata — **owner or MODERATOR/ADMIN only**.        |
| `GET /api/v1/media/{mediaId}/access-url`     | Short-lived presigned GET URL — **owner or MODERATOR/ADMIN only**. |
| `DELETE /api/v1/media/{mediaId}`             | Soft-delete (**owner only**, `403` otherwise); best-effort object removal. |
| `GET /api/v1/media/{mediaId}/validation-results` | Validation internals — **owner or MODERATOR/ADMIN only**. |

### Access control

- Every endpoint requires the gateway-injected `X-User-Id`; missing/invalid → `401`.
- Reads (metadata, access-url, validation-results) are allowed for the **owner**, or
  for callers whose `X-User-Roles` header contains `MODERATOR` or `ADMIN`.
- Unauthorized reads are answered as **`404 MEDIA_NOT_FOUND`**, not `403`, so media
  ids cannot be probed/enumerated (IDOR prevention).
- Delete remains owner-only (`403 NOT_MEDIA_OWNER` for others); moderator/admin
  delete is deferred (backlog).
- **Storage internals are hidden:** responses never contain `bucketName`,
  `objectKey` or `checksum`, and no access URL is ever persisted.

#### Parking-mediated access for visible spot photos

media-service has **no local knowledge of parking-spot visibility**, so the public
access-url endpoint stays owner/moderator-only. Normal users view another user's
spot photo through **parking-service mediation** instead:

```
POST /internal/media/{mediaId}/access-url
```

- **Internal only:** the gateway routes only `/api/v1/**`, so `/internal/**` is
  never publicly reachable, and it must not be added to gateway route config.
  Direct calls still require the shared `X-Gateway-Auth` secret (the same
  ingress guard as every other endpoint); without it → `401`.
- **No ownership check:** the caller is a trusted internal service
  (parking-service) that has already authorized the requester against its own
  rules (spot visibility / spot ownership). The optional JSON body
  `{ "requesterUserId": "…", "purpose": "SPOT_PHOTO_VIEW" }` is used for audit
  logging only — never for authorization.
- Returns the same shape as the public endpoint
  (`mediaId`, `accessUrl`, `expiresAt`); bucket/object-key/checksum are never
  exposed. Deleted/unknown media → `404 MEDIA_NOT_FOUND`.

End users call
`GET /api/v1/parking/spots/{spotId}/media-access-url` on parking-service, which
verifies the spot is visible to them (or owned by them) and then calls this
internal endpoint. See the parking-service README for the full flow.

### Signed access URLs

`GET /api/v1/media/{mediaId}/access-url` returns:

```json
{ "mediaId": "…", "accessUrl": "https://…X-Amz-Signature=…", "expiresAt": "2026-06-09T19:05:00Z" }
```

- The URL is a **presigned, GET-only** object-store URL generated **per request**
  (signed locally by the MinIO adapter — no network call) and **never persisted**.
- TTL is configurable via `parkio.media.access-url-ttl`
  (`PARKIO_MEDIA_ACCESS_URL_TTL`), default **5 minutes**.
- The response carries no bucket/object-key fields; metadata responses contain no
  `accessUrl` either — clients must call the access-url endpoint when they need
  the bytes.

#### Frontend flow

1. Spot detail (parking-service) contains `mediaId` only — no URL.
2. The frontend calls `GET /api/v1/media/{mediaId}/access-url` through the gateway
   with the user's token.
3. media-service authorizes the request (owner or moderator/admin) and returns a
   short-lived signed URL.
4. The frontend renders the image from `accessUrl` before `expiresAt`, re-requesting
   a fresh URL when it expires.

### Validation

- Allowed content types: `image/jpeg`, `image/png`, `image/webp`.
- Max file size configurable (`parkio.media.max-file-size`, default 10MB); empty
  files rejected.
- Object keys are **generated** (`media/{ownerUserId}/{uuid}.{ext}`) — the original
  filename and any user-controlled path are never trusted.
- Duplicate checksum → `409`.

### HTTP idempotency

`POST /api/v1/media/upload` requires an `Idempotency-Key` of 8-128 characters.
Frontends should generate a UUID for each upload action and reuse it only when
retrying that exact upload. A completed retry returns the original `201` response
and `mediaId` without another metadata row, outbox event, or object-storage write.

The request fingerprint contains the operation path, original filename, declared
content type, file size, and SHA-256 checksum; raw file bytes are never stored in
`idempotency_records`. Reusing a key for different upload metadata/content returns
`409 IDEMPOTENCY_KEY_CONFLICT`.

Records are scoped by authenticated user, HTTP method, operation path, and key.
They expire after `parkio.idempotency.ttl` (default `24h`). Concurrent duplicates
serialize on the database uniqueness constraint. If a persisted request is
unexpectedly still marked in progress, the service returns
`409 IDEMPOTENCY_REQUEST_IN_PROGRESS` rather than storing the upload twice.

### Events (outbox)

`MediaUploadedEvent` is written to the outbox in the same transaction as a successful
upload; `MediaRejectedEvent` is recorded (in its own transaction) on rejection or
duplicate. A Kafka publisher/relay is not implemented yet (ai-context/06).

## Configuration

Object storage is configured via `parkio.media.storage.*` (`endpoint` for internal SDK
ops, `public-endpoint` for presigned GET URL host, `bucket`, `access-key`,
`secret-key`, `region`). Credentials are injected via environment variables; the
defaults are **local-dev only** and must be overridden elsewhere (ai-context/07).
In Docker compose, use `PARKIO_MEDIA_STORAGE_ENDPOINT=http://minio:9000` and
`PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT=http://localhost:9000` so SigV4-signed URLs
match the browser host.

## Run locally

From the repository root:

```bash
./gradlew :services:media-service:bootRun
```

## Build & test

```bash
./gradlew :services:media-service:build
```

The normal build uses H2 and does not require Docker. The opt-in infrastructure
test starts PostgreSQL 16 and MinIO, runs all Flyway migrations with Hibernate
validation enabled, and exercises upload/stat/delete plus presigned GET URL
generation (including an unauthenticated fetch of the signed URL) through the
real `MinioMediaStorageAdapter`:

```bash
./gradlew :services:media-service:integrationTest
```

The integration test uses only disposable test credentials and a test bucket,
and is skipped cleanly when Docker is unavailable.

## Docker

```bash
docker build -f services/media-service/Dockerfile -t parkio/media-service .
docker run -p 8084:8084 parkio/media-service
```
