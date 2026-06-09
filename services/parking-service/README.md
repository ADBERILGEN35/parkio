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
change (a Kafka relay is not implemented yet, ai-context/06). Consumers — notably
gamification — should duplicate this contract locally; it is **not** a shared model.
Every payload carries `eventId`, `parkingSpotId`, `ownerUserId`, `status` and
`occurredAt`; action events also carry the `actorUserId` who triggered them.

| Event | When | Key payload fields |
|-------|------|--------------------|
| `ParkingSpotCreatedEvent` | A spot is created | `parkingSpotId`, `ownerUserId`, `mediaId`, `latitude`, `longitude`, `status` (`ACTIVE`) |
| `ParkingSpotVerifiedEvent` | A user confirms a spot as available | `parkingSpotId`, `ownerUserId`, `actorUserId` (verifier), `result` (`AVAILABLE`), `verificationCount`, `status` (`VERIFIED`) |
| `ParkingSpotMarkedFilledEvent` | Filled-reports cross the threshold | `parkingSpotId`, `ownerUserId`, `status` (`FILLED`) |
| `ParkingSpotClaimedEvent` | A user **successfully claimed/parked in** the spot | `parkingSpotId`, `ownerUserId`, `actorUserId` (claimer), `status` (`FILLED`) |
| `ParkingSpotExpiredEvent` | The validity window elapsed | `parkingSpotId`, `ownerUserId`, `status` (`EXPIRED`) |
| `ParkingSpotRejectedEvent` | A verification reported it illegal/risky | `parkingSpotId`, `ownerUserId`, `actorUserId` (reporter), `result` (`ILLEGAL_OR_RISKY`), `status` (`REJECTED`) |

`ParkingSpotClaimedEvent` is the authoritative "a user took this spot" signal
(distinct from `ParkingSpotMarkedFilledEvent`, which is driven by community
filled-reports rather than a single claimer). Gamification should reward the
`actorUserId` (claimer) and the `ownerUserId` (contributor) off this event.

## Nearby search bounds

`GET /spots/nearby` accepts optional `radius` and `limit`. Both default from config
(`parkio.parking.search.default-*`) and are capped (`max-radius-meters`,
`max-result-limit`); values `<= 0` or above the cap are rejected with `400`.

## Public vs. owner views

`GET /spots/{id}` and `GET /spots/nearby` (and the verify/claim responses) return a
privacy-safe view that omits `ownerUserId`, `confidenceScore`, `verificationCount`
and `filledReportCount`. Owners see those full fields only via
`GET /my-spots` and `GET /my-spots/{id}`.

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

- **PostGIS Testcontainers integration test** — run the Flyway migrations and exercise
  the `findNearby` geography query (radius/status/expiry filtering, distance order).
  Tests currently use H2 with Flyway disabled, so the geo query is unverified in CI.
- **Scheduled expiration job** — spots currently expire lazily on access; a scheduled
  expirer is needed so unaccessed spots transition to `EXPIRED` and emit
  `ParkingSpotExpiredEvent`.
- **Enum-set normalization** — `suitable_vehicle_types` / `violation_reasons` are stored
  as comma-separated strings; normalize (Postgres array/`jsonb` + GIN, or a join table)
  before adding vehicle-type-filtered search.
- **Illegal/risky verification threshold/moderation** — a single `ILLEGAL_OR_RISKY`
  report currently REJECTs a spot permanently; gate behind a report threshold or
  moderation review to prevent abuse.

## Run locally

From the repository root:

```bash
./gradlew :services:parking-service:bootRun
```

## Build & test

```bash
./gradlew :services:parking-service:build
```

## Docker

```bash
docker build -f services/parking-service/Dockerfile -t parkio/parking-service .
docker run -p 8083:8083 parkio/parking-service
```
