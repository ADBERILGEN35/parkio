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

## Domain events (outbox)

These events are written to `outbox_events` in the same transaction as the state
change and published by the Kafka relay (ai-context/06). Consumers — notably
gamification — duplicate this contract locally; it is **not** a shared model.
Every payload carries `eventId`, `parkingSpotId`, `ownerUserId`, `status` and
`occurredAt`; action events also carry the `actorUserId` who triggered them.

| Event | When | Key payload fields |
|-------|------|--------------------|
| `ParkingSpotCreatedEvent` | A spot is created | `parkingSpotId`, `ownerUserId`, `mediaId`, `latitude`, `longitude`, `status` (`ACTIVE`) |
| `ParkingSpotVerifiedEvent` | A user confirms availability or reports an unconfirmed illegal/risky signal | `parkingSpotId`, `ownerUserId`, `actorUserId`, `result` (`AVAILABLE` or `ILLEGAL_OR_RISKY`), `verificationCount`, `status` (`VERIFIED` or `SUSPICIOUS`) |
| `ParkingSpotMarkedFilledEvent` | Filled-reports cross the threshold | `parkingSpotId`, `ownerUserId`, `status` (`FILLED`) |
| `ParkingSpotClaimedEvent` | A user **successfully claimed/parked in** the spot | `parkingSpotId`, `ownerUserId`, `actorUserId` (claimer), `status` (`FILLED`) |
| `ParkingSpotExpiredEvent` | The validity window elapsed | `parkingSpotId`, `ownerUserId`, `status` (`EXPIRED`) |
| `ParkingSpotRejectedEvent` | Legacy confirmed-rejection contract; no longer emitted for community verification | `parkingSpotId`, `ownerUserId`, `actorUserId`, `result`, `status` (`REJECTED`) |

`ParkingSpotClaimedEvent` is the authoritative "a user took this spot" signal
(distinct from `ParkingSpotMarkedFilledEvent`, which is driven by community
filled-reports rather than a single claimer). Gamification should reward the
`actorUserId` (claimer) and the `ownerUserId` (contributor) off this event.

## Community illegal/risky reports

A single `ILLEGAL_OR_RISKY` verification reduces confidence and changes the spot
to `SUSPICIOUS`; it does not reject the spot or penalize its owner. Parking emits
`ParkingSpotVerifiedEvent` with that result, and moderation opens a review case.

Only `ParkingSpotRejectedByModerator` is authoritative for rejection and owner
penalties. Parking consumes that moderation action idempotently through
`inbox_events`, changes the spot to `REJECTED`, and records
`MODERATOR_REJECTED` history without emitting another parking rejection event.
This prevents a parking-to-moderation event loop. The consumer is controlled by
`parkio.kafka.moderation-consumer.enabled` /
`PARKIO_MODERATION_CONSUMER_ENABLED` (default `true`).

## Scheduled expiration

Parking spots still expire lazily when read or mutated, and a scheduled job now
expires unaccessed `ACTIVE`, `VERIFIED`, and `SUSPICIOUS` spots whose `expiresAt`
has elapsed. Each bounded database batch uses row locking, transitions each spot
once, writes an `EXPIRED` status-history row, and appends
`ParkingSpotExpiredEvent` in the same transaction. Terminal spots are never
selected again.

Configuration:

| Property | Environment variable | Default |
|----------|----------------------|---------|
| `parkio.lifecycle.parking-expiry.enabled` | `PARKIO_PARKING_EXPIRY_ENABLED` | `true` |
| `parkio.lifecycle.parking-expiry.fixed-delay-ms` | `PARKIO_PARKING_EXPIRY_FIXED_DELAY_MS` | `60000` |
| `parkio.lifecycle.parking-expiry.batch-size` | `PARKIO_PARKING_EXPIRY_BATCH_SIZE` | `100` |

The test profile disables this scheduler unless a test explicitly enables it.

## Nearby search bounds

`GET /spots/nearby` accepts optional `radius` and `limit`. Both default from config
(`parkio.parking.search.default-*`) and are capped (`max-radius-meters`,
`max-result-limit`); values `<= 0` or above the cap are rejected with `400`.

## Public vs. owner views

`GET /spots/{id}` and `GET /spots/nearby` (and the verify/claim responses) return a
privacy-safe view that omits `ownerUserId`, `confidenceScore`, `verificationCount`
and `filledReportCount`. Owners see those full fields only via
`GET /my-spots` and `GET /my-spots/{id}`.

## Spot photo access (parking-mediated signed URLs)

media-service protects media as owner/moderator-only, so a normal user cannot ask
it directly for another user's spot photo. parking-service mediates that access:
it owns the visibility rules, and media-service trusts it via an internal,
non-public endpoint.

```
GET /api/v1/parking/spots/{spotId}/media-access-url
```

Response:

```json
{
  "spotId": "…",
  "mediaId": "…",
  "accessUrl": "https://…signed…",
  "expiresAt": "2026-06-09T12:05:00Z"
}
```

Authorization rules (evaluated before any media-service call):

- The **owner** can always fetch their own spot's photo, even when the spot is no
  longer visible.
- Everyone else gets the photo only while the spot is **publicly visible** — the
  same rule as nearby search: status `ACTIVE` or `VERIFIED`, `expiresAt` in the
  future, and not `ILLEGAL_OR_RISKY`. `SUSPICIOUS`, `FILLED`, `EXPIRED` and
  `REJECTED` spots are hidden.
- Hidden/unknown spots answer `404 SPOT_NOT_FOUND` (never `403`), so spot ids
  cannot be probed or enumerated.

When the request is authorized, parking-service calls media-service's internal
endpoint (`POST /internal/media/{mediaId}/access-url`) with the shared
`X-Gateway-Auth` secret and the current `X-Correlation-Id`, and returns the
short-lived signed URL it gets back. If media-service is unavailable or answers
unexpectedly, the endpoint degrades to `503 MEDIA_ACCESS_UNAVAILABLE` (a deleted
media maps to `404`). Storage internals (bucket, object key, checksum) never
appear in any response.

**Frontend flow for a spot photo:**

1. `GET /api/v1/parking/spots/{spotId}` — spot detail contains `mediaId` only.
2. `GET /api/v1/parking/spots/{spotId}/media-access-url` — returns the signed URL.
3. Render the image from `accessUrl`.
4. The URL expires at `expiresAt` (TTL configured in media-service,
   `parkio.media.access-url-ttl`, default 5 minutes). After expiry, repeat step 2
   — URLs are generated per request and never persisted.

Signed URLs are intentionally **not** included in spot list/nearby responses:
that would mint one signed URL per listed spot per search. Fetch them on demand
from the detail view.

Client configuration:

| Property | Environment variable | Default |
|----------|----------------------|---------|
| `parkio.media.client.base-url` | `PARKIO_MEDIA_SERVICE_URI` | `http://localhost:8084` |
| `parkio.media.client.connect-timeout` | `PARKIO_MEDIA_CLIENT_CONNECT_TIMEOUT` | `2s` |
| `parkio.media.client.read-timeout` | `PARKIO_MEDIA_CLIENT_READ_TIMEOUT` | `5s` |

## HTTP idempotency

These high-risk writes require an `Idempotency-Key` header:

- `POST /api/v1/parking/spots`
- `POST /api/v1/parking/spots/{spotId}/claim`
- `POST /api/v1/parking/spots/{spotId}/verify`

Keys must be 8-128 characters. Frontends should generate a UUID for each user
action and reuse that UUID only when retrying the exact same action. A completed
retry returns the original status and response body without repeating spot,
verification, status-history, or outbox writes. Reusing a key for a different
body or path returns `409 IDEMPOTENCY_KEY_CONFLICT`.

Records are scoped by authenticated user, HTTP method, operation path, and key.
They expire after `parkio.idempotency.ttl` (default `24h`); an expired key may be
used as a new action. Concurrent duplicates serialize on the database uniqueness
constraint. If a persisted request is unexpectedly still marked in progress, the
service returns `409 IDEMPOTENCY_REQUEST_IN_PROGRESS` rather than executing the
write again.

## Backlog (not yet implemented)

- **Enum-set normalization** — `suitable_vehicle_types` / `violation_reasons` are stored
  as comma-separated strings; normalize (Postgres array/`jsonb` + GIN, or a join table)
  before adding vehicle-type-filtered search.

## Run locally

From the repository root:

```bash
./gradlew :services:parking-service:bootRun
```

## Build & test

```bash
./gradlew :services:parking-service:build
```

The normal build uses H2 and does not require Docker. The opt-in infrastructure
test starts `postgis/postgis:16-3.4`, runs all Flyway migrations with Hibernate
validation enabled, and verifies the PostGIS extension, location trigger, GiST
index, and production nearby query:

```bash
./gradlew :services:parking-service:integrationTest
```

The integration test is skipped cleanly when Docker is unavailable.

## Docker

```bash
docker build -f services/parking-service/Dockerfile -t parkio/parking-service .
docker run -p 8083:8083 parkio/parking-service
```
