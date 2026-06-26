# Event Contract Registry

This is the canonical registry of every domain event currently defined in Parkio.
It is the source of truth for **producers**, **consumers**, and **payload schemas**.
It complements [`../ai-context/06-event-guidelines.md`](../ai-context/06-event-guidelines.md)
and [`kafka-transport.md`](kafka-transport.md) (topic map, partitioning, retention, DLT
and producer/consumer config); where this file and code disagree, the code is
authoritative and this file must be updated in the same change.

> Per ai-context/01 and /03, event **payloads** are never a shared library. Each
> consumer duplicates the payload contract locally as its own DTO. This registry
> is documentation for payload models — copy the schema, don't import it.
>
> The transport envelope is different: the service-agnostic Kafka
> `EventEnvelope` lives in `platform/parkio-platform` so compatibility
> annotations and wire names cannot drift between services. Its `payload` remains
> an opaque `JsonNode`; domain event payloads are still service-owned.

## Transport & delivery

- Events are produced via the **transactional outbox**: a row is written to the
  producer's `outbox_events` table in the same transaction as the state change. A
  relay (not yet implemented) publishes unpublished rows to **Kafka**.
- Delivery is **at-least-once**. Consumers must be **idempotent** and deduplicate by
  `eventId` (via their `inbox_events` table). Redelivery must be safe.
- Partition by the **aggregate id** to preserve per-entity ordering.

### Outbox row (transport envelope)

Each event is stored/transported with this envelope metadata (outbox columns),
separate from the JSON `payload`:

| Field | Meaning |
|-------|---------|
| `event_id` | The domain event's `eventId` — the **dedup key**, stored as its own column. |
| `aggregateType` | The aggregate kind (e.g. `ParkingSpot`, `Media`, `AuthUser`, `GamificationUser`). |
| `aggregateId` | The aggregate's id; also the Kafka partition key. |
| `eventType` | The event name (e.g. `ParkingSpotCreated`). |
| `occurredAt` | When the event occurred (UTC). |
| `payload` | The JSON document described in each event's **Payload schema** below. |

Kafka records use the shared `com.parkio.platform.messaging.EventEnvelope`
transport record with top-level JSON fields `eventId`, `eventType`,
`aggregateType`, `aggregateId`, `occurredAt`, `version`, `traceId`, and
`payload`. The record ignores unknown future envelope fields.

> The outbox row's primary key `id` is a **separate** random row id, **not** the event's
> `eventId`. The `eventId` is also stored in its own `event_id` column (added across all
> services) so the relay reads the dedup key without parsing the JSON `payload`; a partial
> `UNIQUE (event_id)` index guards against accidental double-publish. The `payload` JSON
> still repeats `eventId` and `occurredAt` (they are part of the event record). `eventId`
> is the **dedup key** consumers must use.

## Global conventions

- **Identifiers**: UUID, serialized as a JSON string.
- **Timestamps**: `occurredAt` is an instant, serialized ISO-8601 UTC (e.g.
  `2026-06-07T12:00:00Z`).
- **Enums**: serialized as their **name** string (e.g. `"AVAILABLE"`).
- **Money/points**: integers (JSON number).
- **`userId` semantics**: every `userId`/`ownerUserId`/`actorUserId`/`verifierUserId`
  is the **platform-wide authUserId** issued by auth-service.

### Consumer (de)serialization requirements

Every consumer's JSON reader (and the producer's outbox serializer) **must** be
configured so the contracts above hold and evolve safely:

- **`FAIL_ON_UNKNOWN_PROPERTIES = false`** — consumer DTOs are intentionally narrower
  subsets of producer payloads; unknown/new fields must be ignored (rule 4 below).
- **`JavaTimeModule` registered** with **ISO-8601 timestamps**
  (`WRITE_DATES_AS_TIMESTAMPS = false`) so `Instant`/`occurredAt` round-trips as
  `2026-06-07T12:00:00Z`, not an epoch number.
- **Enums as strings** — producers serialize enums by `name()`; consumers read enum
  fields **as `String`** (not as a local enum copy) so unknown future enum values
  deserialize safely (treat as "other").
- **Unknown future fields ignored** — never reject a payload for carrying a field the
  consumer doesn't know about.

> Spring Boot's autoconfigured `ObjectMapper` already registers `JavaTimeModule` and
> disables timestamp-numerics; consumers must additionally disable
> `FAIL_ON_UNKNOWN_PROPERTIES` (the default is strict). The full Kafka consumer config is
> **not** implemented yet — this section is the contract those consumers must honour.

## Versioning & compatibility rules (apply to ALL events)

1. **Payloads are append-only.** Fields are only ever added, never removed.
2. **Existing fields never change meaning** (or type, or units, or nullability from
   required→optional in a breaking way).
3. **New fields are optional by default.** Producers may start emitting a new field
   at any time; the absence of a field must be valid.
4. **Consumers must ignore unknown fields** (configure JSON readers to not fail on
   unknown properties).
5. A change that would violate 1–4 (rename, remove, repurpose, type change) is a
   **breaking change** and requires a **new event type / version** (e.g.
   `ParkingSpotClaimedV2`), with the old one kept until all consumers migrate.
6. **Current version of every event below is `1`.** There is no explicit `version`
   field in payloads yet; until the Kafka relay adds the recommended envelope
   `version` (ai-context/06), treat all events as v1. Adding `version` later is an
   additive change (rule 3).

---

# Auth

## UserRegisteredEvent

- **Producer:** `auth-service`
- **Expected consumers:** `user-service` (provision a default profile — wired as a
  direct handler today); `notification-service` (welcome message, planned).
- **Envelope:** `aggregateType=AuthUser`, `aggregateId=userId`, `eventType=UserRegistered`.

### Payload schema

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `eventId` | UUID (string) | yes | Unique event id; consumer dedup key. |
| `userId` | UUID (string) | yes | The new account's authUserId (platform-wide user id). |
| `email` | string | yes | The registered email (PII — consumers minimise/retain per ai-context/07). |
| `occurredAt` | timestamp (UTC) | yes | When registration committed. |

```json
{
  "eventId": "2b3c...",
  "userId": "9f1a...",
  "email": "rider@parkio.example",
  "occurredAt": "2026-06-07T12:00:00Z"
}
```

- **Version:** 1.
- **Compatibility:** append-only; `userId` is the stable join key — never repurpose.

---

# User

## UserProfileCreatedEvent

- **Producer:** `user-service` (relay: `UserOutboxRelay` → `parkio.user.profile`).
- **Expected consumers:** **none yet.** Published for completeness / future read-model
  projections (e.g. analytics, notification welcome flows). Consumers must dedup by
  `eventId` when added.
- **Envelope:** `aggregateType=UserProfile`, `aggregateId=authUserId`,
  `eventType=UserProfileCreated`.

### Payload schema

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `eventId` | UUID (string) | yes | Unique event id; consumer dedup key. |
| `userProfileId` | UUID (string) | yes | The internal user-service profile id (this service's own aggregate id). |
| `authUserId` | UUID (string) | yes | The platform-wide user id (partition key). |
| `occurredAt` | timestamp (UTC) | yes | When the profile was created. |

- **Version:** 1.
- **Compatibility:** append-only. `authUserId` is the stable platform-wide join key;
  `userProfileId` is user-service-internal and should not be used as a cross-service key.

---

# Media

> `MediaUploadedEvent` exposes the storage `bucketName`/`objectKey` to **internal
> event consumers** only; these are never returned on the public media API.

## MediaUploadedEvent

- **Producer:** `media-service`
- **Expected consumers:** `ai-validation-service` (advisory image analysis);
  `parking-service` / `analytics-service` (planned). None wired yet.
- **Envelope:** `aggregateType=Media`, `aggregateId=mediaId`, `eventType=MediaUploaded`.

### Payload schema

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `eventId` | UUID (string) | yes | Unique event id; dedup key. |
| `mediaId` | UUID (string) | yes | The stored media object's id (the external reference other services hold). |
| `ownerUserId` | UUID (string) | yes | Uploading user (authUserId). |
| `bucketName` | string | yes | Storage bucket (internal). |
| `objectKey` | string | yes | Generated storage object key (internal; never user-derived). |
| `contentType` | string | yes | Normalized stored MIME type. Uploads are currently re-encoded to `image/jpeg`. |
| `fileSize` | integer | yes | Normalized stored size in bytes (> 0). |
| `checksum` | string | yes | SHA-256 hex of the normalized stored content (duplicate-detection key). |
| `occurredAt` | timestamp (UTC) | yes | When the upload was validated/stored. |

- **Version:** 1.
- **Compatibility:** append-only. `mediaId`/`checksum` are stable identifiers.

## MediaRejectedEvent

- **Producer:** `media-service`
- **Expected consumers:** `moderation-service` / `analytics-service` (abuse signals);
  `notification-service` (inform uploader). Planned; none wired yet.
- **Envelope:** `aggregateType=Media`, `aggregateId=mediaId`, `eventType=MediaRejected`.

### Payload schema

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `eventId` | UUID (string) | yes | Unique event id; dedup key. |
| `mediaId` | UUID (string) | yes | Correlation id for the rejection. No media row is persisted on rejection, so this is a **generated** id, not a stored media id. |
| `ownerUserId` | UUID (string) | yes | The user whose upload was rejected. |
| `validationType` | string (enum) | yes | Which check failed: `FILE_SIZE`, `MIME_TYPE`, `DUPLICATE`, `IMAGE_SAFETY`, `PARKING_RELEVANCE`. |
| `reason` | string | yes | Short human-readable reason. |
| `checksum` | string | no | SHA-256 of the content; present for duplicate rejections, otherwise `null`. |
| `occurredAt` | timestamp (UTC) | yes | When the rejection occurred. |

- **Version:** 1.
- **Compatibility:** append-only. New `validationType` values may be added — consumers
  must tolerate unknown enum values (treat as "other").

---

# Parking

All parking events share: `aggregateType=ParkingSpot`, `aggregateId=parkingSpotId`,
and every payload carries `eventId`, `parkingSpotId`, `ownerUserId`, `status`,
`occurredAt`. Action events also carry `actorUserId` (the user who triggered it).
`status` is the spot's status **after** the event: one of `ACTIVE`, `VERIFIED`,
`SUSPICIOUS`, `FILLED`, `EXPIRED`, `REJECTED`.

## ParkingSpotCreatedEvent

- **Producer:** `parking-service`
- **Expected consumers:** `gamification-service` (owner upload reward — wired);
  `notification-service`, `analytics-service` (planned).
- **Envelope:** `eventType=ParkingSpotCreated`.

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `eventId` | UUID (string) | yes | Dedup key. |
| `parkingSpotId` | UUID (string) | yes | The created spot's id. |
| `ownerUserId` | UUID (string) | yes | Contributor (authUserId). |
| `mediaId` | UUID (string) | yes | External reference to the spot's photo (media-service). |
| `latitude` | number | yes | WGS84 latitude. |
| `longitude` | number | yes | WGS84 longitude. |
| `status` | string (enum) | yes | `ACTIVE` at creation. |
| `occurredAt` | timestamp (UTC) | yes | Creation time. |

- **Version:** 1. **Compatibility:** append-only.

## ParkingSpotVerifiedEvent

- **Producer:** `parking-service`
- **Expected consumers:** `gamification-service` (owner +verifier rewards — wired);
  `moderation-service` (opens a case for `ILLEGAL_OR_RISKY` — wired);
  `notification-service`, `analytics-service` (planned).
- **Envelope:** `eventType=ParkingSpotVerified`.

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `eventId` | UUID (string) | yes | Dedup key. |
| `parkingSpotId` | UUID (string) | yes | The verified spot. |
| `ownerUserId` | UUID (string) | yes | Spot owner. |
| `actorUserId` | UUID (string) | yes | The verifier (never the owner). |
| `result` | string (enum) | yes | `AVAILABLE` or the unconfirmed community signal `ILLEGAL_OR_RISKY`. |
| `verificationCount` | integer | yes | Total AVAILABLE verifications so far. |
| `status` | string (enum) | yes | `VERIFIED` for available confirmation; `SUSPICIOUS` for illegal/risky input. |
| `occurredAt` | timestamp (UTC) | yes | Verification time. |

- **Version:** 1. **Compatibility:** append-only.

## ParkingSpotMarkedFilledEvent

- **Producer:** `parking-service`
- **Expected consumers:** `notification-service` (notify owner), `analytics-service`.
  Planned. (Distinct from a claim — this is community filled-reports crossing the
  threshold, not a single claimer.)
- **Envelope:** `eventType=ParkingSpotMarkedFilled`.

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `eventId` | UUID (string) | yes | Dedup key. |
| `parkingSpotId` | UUID (string) | yes | The spot now marked filled. |
| `ownerUserId` | UUID (string) | yes | Spot owner. |
| `status` | string (enum) | yes | Resulting status (`FILLED`). |
| `occurredAt` | timestamp (UTC) | yes | When it was marked filled. |

- **Version:** 1. **Compatibility:** append-only.

## ParkingSpotClaimedEvent

- **Producer:** `parking-service`
- **Expected consumers:** `gamification-service` (owner +claimer rewards — wired);
  `notification-service`, `analytics-service` (planned).
- **Envelope:** `eventType=ParkingSpotClaimed`.
- **Semantics:** a user **successfully claimed/parked in** the spot. This is the
  authoritative "spot taken by a user" signal (vs `ParkingSpotMarkedFilled`).

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `eventId` | UUID (string) | yes | Dedup key. |
| `parkingSpotId` | UUID (string) | yes | The claimed spot. |
| `ownerUserId` | UUID (string) | yes | Spot owner (rewarded as contributor). |
| `actorUserId` | UUID (string) | yes | The claimer (never the owner). |
| `status` | string (enum) | yes | Resulting status (`FILLED`). |
| `occurredAt` | timestamp (UTC) | yes | Claim time. |

- **Version:** 1. **Compatibility:** append-only.

## ParkingSpotExpiredEvent

- **Producer:** `parking-service`
- **Expected consumers:** `notification-service`, `analytics-service` (planned).
- **Envelope:** `eventType=ParkingSpotExpired`.

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `eventId` | UUID (string) | yes | Dedup key. |
| `parkingSpotId` | UUID (string) | yes | The expired spot. |
| `ownerUserId` | UUID (string) | yes | Spot owner. |
| `status` | string (enum) | yes | Resulting status (`EXPIRED`). |
| `occurredAt` | timestamp (UTC) | yes | Expiry time. |

- **Version:** 1. **Compatibility:** append-only.

## ParkingSpotRejectedEvent

- **Producer:** `parking-service` (legacy contract; no longer emitted by community
  verification).
- **Expected consumers:** `moderation-service` accepts legacy records to open a
  case. Gamification intentionally ignores this event.
- **Envelope:** `eventType=ParkingSpotRejected`.
- **Semantics:** retained for backward compatibility with already-published
  records. A single community report must not produce this event or trigger a
  penalty.

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `eventId` | UUID (string) | yes | Dedup key. |
| `parkingSpotId` | UUID (string) | yes | The rejected spot. |
| `ownerUserId` | UUID (string) | yes | Spot owner (penalised). |
| `actorUserId` | UUID (string) | yes | The reporting user. |
| `result` | string (enum) | yes | Verification result (`ILLEGAL_OR_RISKY`). |
| `status` | string (enum) | yes | Resulting status (`REJECTED`). |
| `occurredAt` | timestamp (UTC) | yes | Rejection time. |

- **Version:** 1. **Compatibility:** append-only.

---

# Gamification

All gamification events share: `aggregateType=GamificationUser`,
`aggregateId=userId`. Points are integers; `userId` is the authUserId.

## PointsEarnedEvent

- **Producer:** `gamification-service`
- **Expected consumers:** `notification-service` (inform user), `user-service`
  (level/contribution projection), `analytics-service`. Planned.
- **Envelope:** `eventType=PointsEarned`.

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `eventId` | UUID (string) | yes | Dedup key. |
| `userId` | UUID (string) | yes | The user who earned points. |
| `points` | integer | yes | Positive magnitude earned. |
| `sourceType` | string (enum) | yes | Why: `PARKING_UPLOAD`, `PARKING_VERIFIED`, `PARKING_CLAIMED`, `PARKING_FILLED_BY_USER`. |
| `totalPoints` | integer | yes | The user's new lifetime total after this change. |
| `relatedEventId` | UUID (string) | no | The upstream event (e.g. the parking event) that caused this award; may be `null`. |
| `occurredAt` | timestamp (UTC) | yes | When the award was applied. |

- **Version:** 1. **Compatibility:** append-only; new `sourceType` values may appear.

## PointsDeductedEvent

- **Producer:** `gamification-service`
- **Expected consumers:** `notification-service`, `user-service` (projection),
  `analytics-service`. Planned.
- **Envelope:** `eventType=PointsDeducted`.

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `eventId` | UUID (string) | yes | Dedup key. |
| `userId` | UUID (string) | yes | The penalised user. |
| `points` | integer | yes | Positive magnitude deducted. |
| `sourceType` | string (enum) | yes | Why: `PENALTY_ILLEGAL_RISK`, `PENALTY_FAKE`, `PENALTY_SPAM`. |
| `totalPoints` | integer | yes | New lifetime total after deduction (never below 0). |
| `relatedEventId` | UUID (string) | no | The upstream event that triggered the penalty; may be `null`. |
| `occurredAt` | timestamp (UTC) | yes | When the deduction was applied. |

- **Version:** 1. **Compatibility:** append-only; new `sourceType` values may appear.

## UserLevelChangedEvent

- **Producer:** `gamification-service`
- **Expected consumers:** `notification-service` (congratulate / notify), `user-service`
  (project `currentLevel`), `analytics-service`. Planned.
- **Envelope:** `eventType=UserLevelChanged`.

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `eventId` | UUID (string) | yes | Dedup key. |
| `userId` | UUID (string) | yes | The user whose level changed. |
| `previousLevel` | integer | yes | Level before the change. |
| `newLevel` | integer | yes | Level after the change (may be lower on penalty). |
| `totalPoints` | integer | yes | Lifetime total at the time of the change. |
| `occurredAt` | timestamp (UTC) | yes | When the level changed. |

- **Version:** 1. **Compatibility:** append-only.

## ContributionScoreUpdatedEvent

- **Producer:** `gamification-service`
- **Expected consumers:** `user-service` (project contribution/trust signals),
  `analytics-service`. Planned.
- **Envelope:** `eventType=ContributionScoreUpdated`.

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `eventId` | UUID (string) | yes | Dedup key. |
| `userId` | UUID (string) | yes | The user. |
| `contributionScore` | integer | yes | Current contribution score. **Note:** currently equals lifetime points; rolling-window decay (ai-context/02) is a planned change to the computation, not the schema. |
| `occurredAt` | timestamp (UTC) | yes | When recomputed. |

- **Version:** 1. **Compatibility:** append-only. The *value* semantics of
  `contributionScore` will tighten when decay lands, but the field/type are stable.

---

# AI Validation

## AiValidationCompletedEvent

- **Producer:** `ai-validation-service`
- **Expected consumers:** `moderation-service` (advisory signal to open/inform a case),
  `parking-service` (attach AI advisory, move spot to `AI_REVIEWED`),
  `analytics-service`. Planned; none wired yet.
- **Envelope:** `aggregateType=AiValidationResult`, `aggregateId=mediaId`,
  `eventType=AiValidationCompleted`.
- **Semantics:** an **advisory** result only (ai-context/02). It never rejects a spot
  or bans a user — moderation/humans decide. `status=FAILED` means the image is clearly
  unusable or not parking-related, not a rejection.

### Payload schema

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `eventId` | UUID (string) | yes | Dedup key. |
| `mediaId` | UUID (string) | yes | The analysed media object (external media-service id); also the partition key. |
| `parkingSpotId` | UUID (string) | no | The spot the media belongs to, if known (null for a standalone media upload). |
| `status` | string (enum) | yes | Advisory outcome: `PASSED`, `WARNING`, `FAILED`. |
| `emptySpaceConfidence` | integer | yes | 0-100 confidence an empty space is present. |
| `legalRiskScore` | integer | yes | 0-100 estimated legal/placement risk (higher = riskier). |
| `imageQualityScore` | integer | yes | 0-100 image quality (higher = better). |
| `aiConfidence` | integer | yes | 0-100 overall confidence in the analysis. |
| `detectedRiskTypes` | string[] (enum) | yes | Distinct detected risk types (`AiRiskType` names); may be empty. |
| `occurredAt` | timestamp (UTC) | yes | When the validation completed. |

```json
{
  "eventId": "7c2a...",
  "mediaId": "3f9b...",
  "parkingSpotId": "a1d4...",
  "status": "PASSED",
  "emptySpaceConfidence": 82,
  "legalRiskScore": 12,
  "imageQualityScore": 90,
  "aiConfidence": 88,
  "detectedRiskTypes": [],
  "occurredAt": "2026-06-08T12:00:00Z"
}
```

- **Version:** 1. **Compatibility:** append-only. New `AiValidationStatus`/`AiRiskType`
  values may appear — consumers must tolerate unknown enum values (treat as "other").
  `status` and `detectedRiskTypes` are therefore consumed as strings.

### Consumer behavior — `moderation-service`

moderation-service's local DTO mirrors this schema exactly (`status` as `String`,
`detectedRiskTypes` as `List<String>`). The advisory result **never auto-rejects**
(ai-context/02); moderation only opens a review case when the signal is *meaningful*:

| AI signal | Moderation outcome |
|-----------|--------------------|
| `status=PASSED` | **No case.** |
| `status=WARNING` with **no** legal/placement risk (e.g. only `LOW_IMAGE_QUALITY`) | **No case** (advisory only). |
| `status=WARNING` with a legal-risk `detectedRiskTypes` value **or** high `legalRiskScore` (≥ 50) | Opens an `ILLEGAL_OR_RISKY` case — `LOW`, or `MEDIUM` for an explicit legal-risk flag / `legalRiskScore` ≥ 75. |
| `status=FAILED`, **or** `detectedRiskTypes` contains `NOT_A_PARKING_SPOT` | Opens a `HIGH`-severity `NOT_A_PARKING_SPOT` case. |

**`parkingSpotId` nullable behavior:** the target is chosen from which id is present —
`parkingSpotId` present ⇒ `targetType=PARKING_SPOT` (target = the spot); `parkingSpotId`
null (a standalone media validation) ⇒ `targetType=MEDIA` (target = `mediaId`). A case
is opened only if no active case already exists for that target (idempotent), and
consumption is deduplicated by `eventId` via the inbox.

> `detectedRiskTypes` is the primary driver of moderation decisioning here; the legal-risk
> values mirror `AiRiskType.isLegalRisk()` (`NO_PARKING_SIGN`, `GARAGE_ENTRANCE`,
> `BUS_STOP`, `PEDESTRIAN_CROSSING`, `FIRE_HYDRANT`, `SIDEWALK`, `TRAFFIC_FLOW_BLOCKING`,
> `PRIVATE_PROPERTY`).

---

# Moderation

Moderation publishes case-lifecycle events to `parkio.moderation.case` and outward
moderator actions to `parkio.moderation.action` (relay: `ModerationOutboxRelay`).

## ParkingSpotRejectedByModeratorEvent

- **Producer:** `moderation-service` (topic `parkio.moderation.action`,
  `aggregateType=ParkingSpot`, `aggregateId=parkingSpotId`).
- **Consumers:** `parking-service` (authoritative `REJECTED` transition),
  `gamification-service` (owner point penalty), and `notification-service`
  (warn the owner). Parking records inbox deduplication and does not emit a new
  parking rejection event; see the loop guard in
  [`kafka-transport.md`](kafka-transport.md). Owner-targeted.

### Payload schema

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `eventId` | UUID (string) | yes | Dedup key. |
| `parkingSpotId` | UUID (string) | yes | The rejected spot (partition key). |
| `ownerUserId` | UUID (string) | no | Spot owner (authUserId). Present when the case knows the owner (including `ParkingSpotVerified` illegal/risky signals); **null** for cases opened from a user report or an AI/media signal. Consumers apply owner-targeted side effects only when present. |
| `moderatorUserId` | UUID (string) | yes | The moderator who resolved the case. |
| `moderationCaseId` | UUID (string) | yes | The resolved moderation case. |
| `reason` | string (enum) | yes | The case's `ModerationReason` name (e.g. `ILLEGAL_OR_RISKY`). |
| `occurredAt` | timestamp (UTC) | yes | When the moderator rejected the spot. |

- **Version:** 1. **Compatibility:** append-only. `ownerUserId` is optional by design;
  consumers must tolerate its absence (skip owner-targeted side effects).

> **Other moderation events** (`UserSuspended`, `UserRestored` on `…action`;
> `ModerationCaseOpened/Resolved`, `AppealCreated/Resolved` on `…case`) are published by
> the same relay and consumed by user/auth/gamification/notification per
> `kafka-transport.md`; their payloads are documented in code (`domain.event`).

## UserSuspendedEvent / UserRestoredEvent

- **Producer:** `moderation-service` (topic `parkio.moderation.action`,
  `aggregateType=User`, `aggregateId=userId`).
- **Consumers:** `user-service` (profile account status; events arriving before the
  profile exists are parked in `pending_user_status_events` and applied at
  provisioning), `auth-service` (AuthUser status — suspension blocks login/refresh and
  revokes the user's active refresh tokens; restoration re-enables login without
  resurrecting old tokens), `notification-service` (inform the user).
- **Ordering:** consumers apply a status event only when
  `occurredAt >= ` the last applied status event's `occurredAt`
  (`user_profiles.last_status_event_at` / `auth_users.status_changed_at`), so a stale
  out-of-order restore never overrides a newer suspension (and vice versa).

### Payload schema (both events)

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `eventId` | UUID (string) | yes | Dedup key. |
| `caseId` | UUID (string) | yes | The moderation case the decision came from (audit reference; payload carries no free-text `reason`). |
| `userId` | UUID (string) | yes | The suspended/restored user (authUserId; partition key). |
| `moderatorId` | UUID (string) | yes | The moderator who decided. |
| `occurredAt` | timestamp (UTC) | yes | When the decision was made — drives consumer-side ordering. |

- **Version:** 1. **Compatibility:** append-only.

---

## Maintenance

When you add, change, or consume an event:

1. Update the producing service's event record **and** this registry in the same PR.
2. Adding a field → optional, append-only (no registry-breaking change).
3. Any rename/removal/repurpose → introduce a **new event type/version**; keep the
   old until all listed consumers migrate.
4. Keep the **consumer lists** current as Kafka wiring is implemented (today most
   consumers are "planned"; gamification consumes the parking events via direct
   handler calls until the relay/consumer is built).
