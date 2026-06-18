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
| `POST /api/v1/media/upload`                  | Multipart `file` plus required `Idempotency-Key`. Validates mime/size/magic-bytes, **scans the original bytes for malware**, decodes and re-encodes the image to metadata-stripped JPEG, computes SHA-256 over the normalized bytes, rejects duplicates (409), stores the normalized object and returns `{ mediaId, status, contentType, fileSize }` with `status=READY`. Infected → `422 MEDIA_INFECTED`; scan unavailable → `503 MEDIA_SCAN_UNAVAILABLE`; corrupt/oversized image → `422 INVALID_IMAGE` (fail-closed, nothing stored). |
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
- Magic-byte detection confirms the bytes match the declared image type.
- The malware scanner receives the original upload bytes before any image parsing.
- Images are decoded, bounded by pixel limits, flattened to RGB, and re-encoded to
  server-generated **JPEG** bytes. This strips EXIF/GPS/device metadata and avoids
  storing user-supplied image structure. The original upload bytes are not stored.
- Transparent PNG input is flattened onto a white background. ICC profiles are not
  preserved; stored bytes contain only the normalized image pixels.
- WebP remains an accepted declared type, but the runtime must provide a reliable
  ImageIO WebP reader. If not available, WebP uploads fail closed with
  `422 INVALID_IMAGE` rather than storing unnormalized bytes.
- Stored `contentType`, object-key extension, response `contentType`, and signed URL
  object metadata reflect the normalized output (`image/jpeg`, `.jpg`).
- Duplicate detection uses the checksum of the normalized stored bytes, not the
  original upload bytes, so deduplication matches what is actually persisted.
- Duplicate checksum → `409`.
- **Malware scan** and **image normalization** (see below) are blocking checks before
  storage.

Image safety limits:

| Property | Env | Default |
|----------|-----|---------|
| `parkio.media.max-image-width` | `PARKIO_MEDIA_MAX_IMAGE_WIDTH` | `8000` |
| `parkio.media.max-image-height` | `PARKIO_MEDIA_MAX_IMAGE_HEIGHT` | `8000` |
| `parkio.media.max-image-pixels` | `PARKIO_MEDIA_MAX_IMAGE_PIXELS` | `40000000` |

### Media lifecycle & malware scanning

Uploaded images must pass a basic anti-malware scan before they can ever be
served. The lifecycle status (`MediaStatus`) is:

| Status         | Meaning                                                                 |
|----------------|-------------------------------------------------------------------------|
| `PENDING_SCAN` | Awaiting/undergoing the scan; **not** servable. Durable initial state for a future async pipeline; rarely observed committed under the synchronous flow. |
| `READY`        | Passed every check **including** the malware scan; servable.            |
| `REJECTED`     | Rejected by validation or the scan (audit only; not persisted as a row today). |
| `DELETED`      | Soft-deleted; metadata retained, never served.                          |

**Design: synchronous, scan-normalize-store.** The upload request scans the raw
bytes in memory (ClamAV `INSTREAM` over TCP) *before* decoding or storing. A clean
scan is then decoded and re-encoded to normalized JPEG bytes; only those
server-generated bytes are written to object storage and persisted as `READY`.
Because the scan and normalization precede the write, infected, unscanned,
corrupt, oversized, or original untrusted bytes never reach storage and there is
no orphan to clean up.

**Fail-closed.** If the scan cannot be completed (clamd unreachable, timeout,
protocol/size error) the upload returns **`503 MEDIA_SCAN_UNAVAILABLE`** and no
media row or object is created. An infected file returns **`422 MEDIA_INFECTED`**
(the matched signature is logged for audit, never returned to the client). Media
never becomes `READY` without a clean scan.

**Serving gates (only `READY` media is servable):**

- `GET /api/v1/media/{mediaId}/access-url` and the internal access-url endpoint
  issue a signed URL **only** for `READY` media; non-ready media answers
  `404 MEDIA_NOT_FOUND` (no state probing).
- `GET /internal/media/{mediaId}/status` (internal, `X-Gateway-Auth` only) returns
  `{ mediaId, status }` so **parking-service rejects spot creation that references
  non-`READY` media** (parking maps it to `422 MEDIA_NOT_READY`, and a media-service
  outage to `503` — also fail-closed).

**Scanner configuration** (`parkio.media.scanner.*`, env in parentheses):

| Property          | Env                                   | Default     | Notes                                  |
|-------------------|---------------------------------------|-------------|----------------------------------------|
| `enabled`         | `PARKIO_MEDIA_SCANNER_ENABLED`        | `true`      | `false` wires a **pass-through** scanner — local dev without clamd / tests only. **Never** disable where real users are served. |
| `host`            | `PARKIO_MEDIA_SCANNER_HOST`           | `localhost` | clamd host (the `clamav` container in compose). |
| `port`            | `PARKIO_MEDIA_SCANNER_PORT`           | `3310`      | clamd TCP port.                         |
| `connect-timeout` | `PARKIO_MEDIA_SCANNER_CONNECT_TIMEOUT`| `2s`        | TCP connect timeout.                    |
| `read-timeout`    | `PARKIO_MEDIA_SCANNER_READ_TIMEOUT`   | `10s`       | Bounds how long an upload waits on the scan before failing closed. |

**Metrics** (at `/actuator/prometheus`): `media_scan_clean_total`,
`media_scan_rejected_total`, `media_scan_failed_total` (counters), and
`media_pending_scan_count` (gauge of rows in `PENDING_SCAN`; ~0 under the
synchronous flow — exists to detect stuck rows / support a future async pipeline).

> **Known limitation — this is malware scanning, not content moderation.** ClamAV
> detects malware/abuse payloads; it does **not** classify illegal or abusive
> imagery (e.g. CSAM). That still requires a dedicated provider and/or human
> moderation. AI image validation remains **advisory** unless explicitly enforced.
> Metadata stripping and re-encoding reduce privacy and parser risk; they are
> **not** abusive-content detection or CSAM detection.

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

Upload image-normalization limits are configured with `parkio.media.max-image-width`,
`parkio.media.max-image-height`, and `parkio.media.max-image-pixels`. Keep these
bounded in production to avoid decompression bombs and excessive memory use.

## Run locally

From the repository root:

```bash
./gradlew :services:media-service:bootRun
```

The malware scanner is **enabled by default** and fails closed, so `bootRun`
without a reachable clamd will reject uploads with `503`. For local dev either run
the `clamav` compose service (`docker compose up clamav`) and point the scanner at
it, or disable scanning explicitly:

```bash
PARKIO_MEDIA_SCANNER_ENABLED=false ./gradlew :services:media-service:bootRun
```

The H2-based test suite runs with the scanner disabled; scan behaviour is covered
by unit tests with a fake scanner and by `MediaScanUploadTest` with a mocked one.

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
