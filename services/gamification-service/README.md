# gamification-service

Points, badges and leaderboards

- **Package:** `com.parkio.gamification`
- **Default port:** `8085` (override with `SERVER_PORT`)

## Architecture

This service follows clean architecture. Source lives under
`src/main/java/com/parkio/gamification`:

| Layer            | Responsibility                                                        |
|------------------|-----------------------------------------------------------------------|
| `domain`         | Enterprise rules: entities, value objects, domain services, ports.    |
| `application`    | Use cases / orchestration of domain logic.                            |
| `infrastructure` | Adapters: persistence, messaging, external clients, configuration.    |
| `presentation`   | Inbound adapters: REST controllers, request/response models.          |
| `shared`         | Cross-cutting helpers scoped to this service only.                    |

> This service owns its own models. Domain models are **not** shared across services.

## Responsibilities

gamification-service owns **points, level rules, reward/penalty rules, contribution
score and access policy**. It does not own parking lifecycle, user profiles, auth,
media or notifications (ai-context/03). `user_id` everywhere means the platform-wide
**authUserId**.

## API

`/me/*` endpoints read the gateway-injected `X-User-Id` and fail closed (`401`) if
absent/invalid.

| Method & path | Purpose |
|---------------|---------|
| `GET /api/v1/gamification/me/progress` | Total points + current level |
| `GET /api/v1/gamification/me/points` | Total points + recent ledger entries |
| `GET /api/v1/gamification/me/level` | Current level + points to next level |
| `GET /api/v1/gamification/me/access-policy` | Access policy from the current level rule |
| `GET /api/v1/gamification/levels` | All level definitions |
| `GET /api/v1/gamification/leaderboard?limit=` | Top users by points (limit capped) |

Unknown users get a **default** progress (0 points, level 1) without a row being
written on read.

## Rules as data

Point values and level thresholds are **seeded in Flyway migrations**, not hardcoded:
`level_rules`, `reward_rules`, `penalty_rules`. The handlers select a rule *key*; the
*value* comes from the database (ai-context/02). Seeded rewards: upload owner +5;
verified owner +20 / verifier +5; claimed owner +30 / claimer +10. Seeded penalty:
rejected (illegal/risky) owner −25 (fake/spam penalties seeded but not yet wired).

## Event handling (inbox) and outbox

Inbound parking events are consumed **idempotently** via `inbox_events` (by
`eventId`) and each point change is also idempotent via `point_transactions.idempotency_key`:

- `ParkingSpotCreated` → owner upload reward
- `ParkingSpotVerified` (AVAILABLE) → owner + verifier rewards
- `ParkingSpotClaimed` → owner + claimer rewards
- `ParkingSpotRejectedByModerator` → owner penalty

Community `ParkingSpotVerified(result=ILLEGAL_OR_RISKY)` signals are recorded as
processed but award/deduct no points. Legacy `ParkingSpotRejected` events are
ignored; moderator confirmation is the only parking-rejection penalty source.

Each point change appends to the outbox: `PointsEarned` / `PointsDeducted`,
`UserLevelChanged` (on level change), and `ContributionScoreUpdated`. The inbound
event DTOs are **local copies** of parking-service's payloads (contracts are
duplicated, never shared). A Kafka consumer/publisher is not wired yet — handlers are
invoked directly.

## Backlog (not yet implemented)

- Kafka consumer (parking events) + outbox relay (publish to Kafka).
- Contribution-score **decay** over a rolling window (currently the snapshot tracks
  lifetime points); a scheduled job will recompute it.
- Wiring of the seeded fake/spam penalties to moderation events.

## Run locally

From the repository root:

```bash
./gradlew :services:gamification-service:bootRun
```

## Build & test

```bash
./gradlew :services:gamification-service:build
```

## Docker

```bash
docker build -f services/gamification-service/Dockerfile -t parkio/gamification-service .
docker run -p 8085:8085 parkio/gamification-service
```
