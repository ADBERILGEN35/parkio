# moderation-service

Content moderation and reporting workflows

- **Package:** `com.parkio.moderation`
- **Default port:** `8087` (override with `SERVER_PORT`)

## Architecture

This service follows clean architecture. Source lives under
`src/main/java/com/parkio/moderation`:

| Layer            | Responsibility                                                        |
|------------------|-----------------------------------------------------------------------|
| `domain`         | Enterprise rules: entities, value objects, domain services, ports.    |
| `application`    | Use cases / orchestration of domain logic.                            |
| `infrastructure` | Adapters: persistence, messaging, external clients, configuration.    |
| `presentation`   | Inbound adapters: REST controllers, request/response models.          |
| `shared`         | Cross-cutting helpers scoped to this service only.                    |

> This service owns its own models. Domain models are **not** shared across services.

## Responsibilities

moderation-service owns **moderation cases, user reports, appeals, violation records
and moderation decisions**. It makes the final call on flagged content (ai-context/02)
but **never mutates** parking, user, gamification or media data directly
(ai-context/03): every outcome is emitted as an event for the owning service to react
to. `user_id` everywhere is the platform-wide authUserId. AI output (when it lands) is
**advisory only** — moderators decide, the model does not.

## API

All endpoints live under `/api/v1/moderation` and require the gateway-injected
`X-User-Id` (fail closed `401` if absent/invalid). Moderator endpoints additionally
require a `MODERATOR` or `ADMIN` entry in the `X-User-Roles` header (comma-separated);
otherwise `403`. **Account-level actions require `ADMIN`** (separation of duties): see
the role matrix below. Authorization is enforced in the controller and re-checked in
the application service (defense in depth, fail closed).

### User-facing

| Method & path | Purpose |
|---------------|---------|
| `POST /reports` | File a report against a target (PARKING_SPOT / USER / MEDIA) |
| `GET /reports/me` | The caller's own reports |
| `POST /appeals` | Appeal a resolved case that targeted the caller |

### Moderator (`MODERATOR` or `ADMIN`)

| Method & path | Purpose |
|---------------|---------|
| `GET /cases` (optional `?status=`) | List recent cases, or filter by status |
| `GET /cases/{caseId}` | A single case |
| `POST /cases/{caseId}/assign` | Claim the case (assigns it to the caller, → `IN_REVIEW`) |
| `POST /cases/{caseId}/resolve` | Resolve with a **content** action (`APPROVE`/`REJECT`/`MARK_FILLED`/`MARK_RISKY`) |
| `GET /appeals` | Read the appeal queue |

### Admin only (`ADMIN`)

| Method & path | Purpose |
|---------------|---------|
| `POST /cases/{caseId}/resolve` with `SUSPEND_USER` / `RESTORE_USER` / `REDUCE_TRUST` / `DEDUCT_POINTS` | Account sanctions and trust/score overrides — `403` for `MODERATOR` |
| `POST /appeals/{appealId}/resolve` | Accept/reject an appeal (reverses sanctions / can restore a suspended account) |

> Account state, trust and points are never mutated directly here — these actions emit
> events for auth/user/gamification to react to. The ADMIN gate on `resolveCase` actions
> and `resolveAppeal` is therefore the single chokepoint for all account-level effects.

### Rules

- The reporter / appellant is always the authenticated user.
- **Serious** reasons (`ILLEGAL_OR_RISKY`, `FAKE_PHOTO`, `PRIVATE_PROPERTY`,
  `ABUSE_REPORT`) open a case immediately; repeated serious reports for the same target
  feed the one still-open case (no duplicate cases). Non-serious reports are recorded
  only (threshold-based opening is backlog).
- A report is **unique** per `(reporter, target, reason)` → duplicates return `409`.
- Appeals are allowed **only** for a `RESOLVED` case that targeted the appealing user;
  unrelated cases return `404` (no information leak), un-resolved cases `409`, and a
  duplicate appeal `409`.
- `resolve` with `APPROVE` dismisses the case (`REJECTED` status); any other action
  upholds it (`RESOLVED`). Decisions and user violations are **append-only / auditable**.

## Events (outbox)

Each state change appends to the transactional `outbox_events` table (published
atomically with the change; a Kafka relay is backlog). Emitted events:
`ModerationCaseOpenedEvent`, `ModerationCaseResolvedEvent`,
`ParkingSpotRejectedByModeratorEvent` (PARKING_SPOT case resolved with
`REJECT`/`MARK_RISKY`), `UserSuspendedEvent` / `UserRestoredEvent` (USER case),
`AppealCreatedEvent`, `AppealResolvedEvent`. Accepting an appeal against a
`SUSPEND_USER` case emits `UserRestoredEvent`.

## Event handling (inbox)

Upstream events (see `docs/architecture/event-contracts.md`) are consumed
**idempotently** via `inbox_events` (dedup by `eventId`). Inbound DTOs are **local
copies** of producers' payloads (contracts are duplicated, never shared):

- `ParkingSpotVerified` with `ILLEGAL_OR_RISKY` → opens a `PARKING_SPOT` case
  without rejecting or penalizing the owner
- Legacy `ParkingSpotRejected` → opens the same case for already-published records
- `MediaRejected` → opens a `MEDIA` case for `IMAGE_SAFETY` (→ `FAKE_PHOTO`) or
  `PARKING_RELEVANCE` (→ `NOT_A_PARKING_SPOT`); other media rejections are ignored
- `AiValidationCompleted` (**placeholder** — ai-validation-service not built yet): a
  not-a-parking-spot verdict opens a `PARKING_SPOT` case (`NOT_A_PARKING_SPOT`)

Handlers open a case only if no active one already exists for the target.

## Backlog (not yet implemented)

- Reconcile the **placeholder** `AiValidationCompleted` shape with ai-validation-service
  and register it in `event-contracts.md` when that service lands.
- Threshold-based case opening for accumulated non-serious reports.
- Spot-owner appeals (moderation does not currently resolve a spot → owner mapping).

## Run locally

From the repository root:

```bash
./gradlew :services:moderation-service:bootRun
```

## Build & test

```bash
./gradlew :services:moderation-service:build
```

## Docker

```bash
docker build -f services/moderation-service/Dockerfile -t parkio/moderation-service .
docker run -p 8087:8087 parkio/moderation-service
```
