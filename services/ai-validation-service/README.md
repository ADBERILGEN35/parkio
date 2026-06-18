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

## Responsibilities

ai-validation-service owns **advisory AI/media validation results** (results, findings,
vehicle-fit estimates). It is an **advisor, not a decision maker** (ai-context/02): it
**never** rejects a spot, bans a user, or mutates parking, media, moderation, user or
gamification data (ai-context/03). It records a result and emits an advisory event for
moderation/parking to consider. `media_id` / `parking_spot_id` are external references
(ID only); `requested_by_user_id` is the platform-wide authUserId.

### Status rule (advisory)

`AiValidationStatusPolicy` derives the status from scores + detected risks:

- **PASSED** — low legal risk, acceptable image quality and AI confidence.
- **WARNING** — legal/placement risk, poor-but-usable quality, or low confidence.
- **FAILED** — image clearly unusable, or not a parking-related image
  (`NOT_A_PARKING_SPOT`). `FAILED` is still advisory — it does **not** reject a spot.

All scores (`empty_space_confidence`, `legal_risk_score`, `image_quality_score`,
`ai_confidence`, finding `score`, `fit_score`) are constrained to **0-100** (domain
invariant + DB check); out-of-range values are rejected (`422`).

## Placeholder validator (no real AI)

`DeterministicAiValidator` produces **deterministic, safe advisory scores derived from
the media id** — **no real AI provider is called**. The same input always yields the
same result. **Real model integration is backlog**: it would be introduced as an
adapter behind a port in `infrastructure`, replacing the placeholder bean — the domain
status rule and event contract stay unchanged.

## API

All endpoints are under `/api/v1/ai-validations`. Validation findings are advisory
moderation data, so **all read endpoints and manual validation require
`MODERATOR`/`ADMIN`**. The controller requires the gateway-injected `X-User-Id`
(fail closed `401`) and a `MODERATOR`/`ADMIN` entry in `X-User-Roles` (`403`
otherwise). Ordinary owners do not read validation findings through this service;
user-facing photo/spot visibility stays in media-service and parking-service.

| Method & path | Purpose |
|---------------|---------|
| `GET /{validationId}` | **MODERATOR/ADMIN** — a single validation result (`404` if missing) |
| `GET /media/{mediaId}` | **MODERATOR/ADMIN** — results for a media object (most recent first) |
| `GET /parking/{parkingSpotId}` | **MODERATOR/ADMIN** — results for a parking spot (most recent first) |
| `POST /manual` | **MODERATOR/ADMIN** — run a manual placeholder validation for a `mediaId` (optional `parkingSpotId`) |

## Event handling (inbox)

Upstream events (see `docs/architecture/event-contracts.md`) are consumed
**idempotently** via `inbox_events` (dedup by `eventId`). Inbound DTOs are **local
copies** of producers' payloads (contracts are duplicated, never shared):

- `MediaUploaded` → `handleMediaUploaded`: validate the media, record a result.
- `ParkingSpotCreated` → `handleParkingSpotCreated`: validate the spot's photo and
  link the result to the `parkingSpotId`.

Each completed validation appends an `AiValidationCompletedEvent` to the outbox
(published atomically with the result; payload documented in the event registry).

## Backlog (not yet implemented)

- Kafka consumer (to invoke the inbox handlers) + outbox relay (publish to Kafka).
- **Real AI/vision model integration** behind a port + infrastructure adapter
  (the deterministic placeholder is for the foundation only).
- Environment-tunable status thresholds (currently placeholder defaults in
  `AiValidationStatusPolicy`).
- Duplicate-image correlation using the upload `checksum`.

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
