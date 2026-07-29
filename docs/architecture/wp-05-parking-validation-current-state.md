# WP-05 Current-State Audit - Parking Validation & Decision Architecture

**Work package:** WP-05.1  
**Date:** 2026-07-27  
**Branch inspected:** `decision` @ `550848277748cf086a738c7135f26f1ff27ae9e8`  
**Scope:** Community parking-spot photo submission / publication gate (not `ParkingSession`)  
**Status:** Documentation only - no runtime behavior changed

Naming note: the repository has no type named `ParkingReport`. Spot sharing with photo moderation is the `ParkingSpot` aggregate. Separate community flagging is `UserReport` in moderation-service.

---

## 1. Executive summary

Today's publication authority for a newly shared parking spot is a **status-mapped AI gate inside parking-service**, with a human moderator escape hatch for uncertain cases.

| Question | Current answer (code) |
|---|---|
| Who decides visibility? | `ParkingApplicationService.applyAiValidationResult` maps AI `PASSED` / `WARNING` / `FAILED` (+ `NOT_A_PARKING_SPOT`) onto spot status |
| Does AI write parking rows? | No - AI emits Kafka `AiValidationCompleted`; parking consumes it |
| When is a spot searchable? | Only `ACTIVE` or `VERIFIED`, not expired, not `ILLEGAL_OR_RISKY` (`ParkingSpot.isVisibleForSearch`) |
| When are upload points granted? | On `ParkingSpotActivated` (publication), not on create while pending |
| Pending-review SLA timeout? | Records `review_sla_breached_at` and extends deadline - **does not** reject (`ParkingSpot.recordReviewSlaBreach`) |
| Decision Engine? | **Absent** - no Risk/Evidence/Trust/Availability scores driving publication |

This is incompatible with the target principle: *"The Decision Engine decides. AI is only one evidence provider."*

---

## 2. Current end-to-end flow

```text
Client (mobile-v2 / web / legacy mobile)
  │  POST /api/v1/media/upload  → media-service (scan → READY)
  │  POST /api/v1/parking/spots → gateway → parking-service
  ▼
ParkingApplicationService.createSpot
  │  MediaReadinessPort.ensureMediaReady (HTTP /internal/media/{id}/status)
  │  ParkingSpot.create → status=PENDING_VALIDATION, expiresAt=null
  │  outbox ParkingSpotCreated → topic parkio.parking.spot
  ▼
ai-validation-service (ParkingEventsKafkaConsumer)
  │  vision / ModerationDecisionPolicy → verdict → AiValidationStatus
  │  outbox AiValidationCompleted → topic parkio.aivalidation.result
  ▼
parking-service AiValidationEventsKafkaConsumer (group parkio.parking)
  │  inbox dedupe by eventId
  │  applyAiValidationResult:
  │     PASSED           → ACTIVE (+ expiresAt) + ParkingSpotActivated
  │     WARNING          → PENDING_REVIEW
  │     FAILED / NOT_A_PARKING_SPOT → REJECTED
  │     unknown          → no-op (fail-closed, stays PENDING_VALIDATION)
  ▼
Optional human path (PENDING_REVIEW)
  │  moderation-service opens case on advisory signals
  │  moderator resolve → parkio.moderation.action
  │  parking approveSpotByModerator / rejectSpotByModerator
  ▼
gamification-service on ParkingSpotActivated → PARKING_UPLOAD_OWNER points
notification-service / analytics-service project selected events
```

Primary symbols:

| Step | Path | Symbol |
|---|---|---|
| Gateway route | `services/gateway-service/src/main/resources/application.yml` | `/api/v1/parking/**` → parking-service |
| Create API | `services/parking-service/.../presentation/ParkingController.java` | `createSpot` |
| Create DTO | `.../presentation/dto/CreateSpotRequest.java` | `CreateSpotRequest` |
| Create use-case | `.../application/ParkingApplicationService.java` | `createSpot` |
| Aggregate | `.../domain/ParkingSpot.java` | `create`, `isVisibleForSearch` |
| Media gate | `.../infrastructure/client/MediaReadinessClient.java` | `ensureMediaReady` |
| AI consume (parking) | `.../infrastructure/messaging/AiValidationEventsKafkaConsumer.java` | `onMessage` → `applyAiValidationResult` |
| AI policy | `services/ai-validation-service/.../vision/ModerationDecisionPolicy.java` | `evaluate` |
| Moderator approve | `.../ParkingApplicationService.java` | `approveSpotByModerator` |
| Upload reward | `services/gamification-service/.../GamificationApplicationService.java` | `handleParkingSpotActivated` |

Client entry (mobile-v2): `frontend/apps/mobile-v2/src/features/share/useCreateSpot.ts` → `createParkingSpot`.  
Shared SDK: `frontend/packages/api-client/src/parking.ts`, types `frontend/packages/types/src/parking.ts`.

---

## 3. Current state machines

### 3.1 ParkingSpot (`ParkingSpotStatus`)

Defined in `services/parking-service/.../domain/ParkingSpotStatus.java`:

| Status | Role |
|---|---|
| `PENDING_VALIDATION` | Created; waiting AI publication gate |
| `PENDING_REVIEW` | AI uncertain; not discoverable |
| `ACTIVE` | Published; within advertised TTL |
| `VERIFIED` | Community confirmed available |
| `SUSPICIOUS` | Negative community signal |
| `FILLED` | Claimed / confirmed full (terminal) |
| `EXPIRED` | Advertised window elapsed (terminal) |
| `REJECTED` | AI or moderator reject (terminal) |
| `REVIEW_FAILED` | AI retries exhausted or stale-before-publication (terminal) |

Helpers: `isPendingModeration()`, `isTerminal()`.

**Visibility:** `ParkingSpot.isVisibleForSearch(Instant)` - only `ACTIVE`/`VERIFIED`, not time-expired, `legalStatus != ILLEGAL_OR_RISKY`.

### 3.2 Media (`MediaStatus`)

`PENDING_SCAN` → `READY` / `REJECTED` / `DELETED` (`services/media-service/.../domain/MediaStatus.java`).  
Spot create **requires** `READY` via HTTP readiness check (not a Kafka "MediaReady" event).

### 3.3 AI advisory (`AiValidationStatus`)

`PASSED` / `WARNING` / `FAILED` (`services/ai-validation-service/.../domain/AiValidationStatus.java`).

Product-facing three-way policy (rc7): `ModerationDecision` = `AUTO_ACCEPT` | `MANUAL_REVIEW` | `AUTO_REJECT` (`ModerationDecision.java`), mapped to classifier verdicts then into status.

### 3.4 Moderation case (`ModerationStatus` / `ModerationAction`)

Separate queue aggregate in moderation-service. Actions include `APPROVE`, `REJECT`, `MARK_RISKY`. Target type includes `PARKING_SPOT`.

---

## 4. Current approval / publication authority

**Authoritative publication gate (current):**  
`ParkingApplicationService.applyAiValidationResult`  
(`services/parking-service/src/main/java/com/parkio/parking/application/ParkingApplicationService.java`)

Triggered by:  
`AiValidationEventsKafkaConsumer.onMessage` on topic `parkio.aivalidation.result`.

| AI input | Parking transition |
|---|---|
| `PASSED` and no `NOT_A_PARKING_SPOT` | `applyAiValidationPassed` → typically `ACTIVE` (+ `ParkingSpotActivatedEvent`) |
| `WARNING` and no `NOT_A_PARKING_SPOT` | `applyAiValidationUncertain` → `PENDING_REVIEW` |
| `FAILED` or risk `NOT_A_PARKING_SPOT` | `applyAiValidationRejected` → `REJECTED` |
| Unknown status | Fail-closed: no transition |

**Human authority:**  
`approveSpotByModerator` / `rejectSpotByModerator` via `ModerationActionsKafkaConsumer` consuming `parkio.moderation.action` (`ParkingSpotApprovedByModerator` / `ParkingSpotRejectedByModerator`).

**Important asymmetry:**  
AI-validation-service comments historically describe results as "advisory," but parking-service **treats mapped statuses as the publication gate**. There is no intermediate Decision Engine.

**Scores not used at the gate:**  
Producer payload includes `emptySpaceConfidence`, `legalRiskScore`, `imageQualityScore`, `aiConfidence` (`AiValidationCompletedEvent`). Parking's consumer DTO keeps only `status` + `detectedRiskTypes` - rich signals are ignored for the transition decision.

---

## 5. Current AI integration

| Concern | Evidence |
|---|---|
| Trigger | `ParkingSpotCreated` / `ParkingSpotModerationRetryRequested` on `parkio.parking.spot` |
| Consumer | `services/ai-validation-service/.../messaging/ParkingEventsKafkaConsumer` |
| Vision | `VisionContentRiskClassifier` + `GeminiVisionClient` (or heuristic) |
| Policy | `ModerationDecisionPolicy.evaluate` - policyVersion default `2026-07-photo-policy-v2` (`VisionProperties`) |
| Provenance | `ModerationProvenance` (model/prompt/policy/threshold versions + `canonicalImageHash`) |
| Persist | `AiValidationResult` + findings; Flyway `V1`, `V2`, `V10` in ai-validation-service |
| Emit | `AiValidationCompleted` on `parkio.aivalidation.result` |
| Media bytes | Internal `GET /internal/media/{id}/content` (READY only) |
| Idempotency | Inbox claim; single-flight / hash reuse for analysis |

TX boundary (documented + code): provider I/O outside short DB transactions (`docs/architecture/ai-vision-validation.md`, `AiValidationApplicationService`).

---

## 6. Current reward lifecycle

| Event | Gamification handler | Effect |
|---|---|---|
| `ParkingSpotCreated` (pending status) | `handleParkingSpotCreated` | **Skipped** (`isPendingPublication`) |
| `ParkingSpotActivated` | `handleParkingSpotActivated` | Award `PARKING_UPLOAD_OWNER` (`RewardRuleKeys.UPLOAD_OWNER`, default +5) |
| `ParkingSpotVerified` (AVAILABLE) | `handleParkingSpotVerified` | Owner + verifier points; trust rule |
| `ParkingSpotClaimed` | `handleParkingSpotClaimed` | Owner + claimer points; trust rule |
| `ParkingSpotRejectedByModerator` | `handleParkingSpotRejectedByModerator` | Owner penalty + trust hit |

**Reward issuance timing for uploads:** after publication (`ParkingSpotActivated`), not on submission.  
Idempotency: inbox `claimEvent` + transaction key `transactionKey(eventId, ruleKey)`.

There is **no pending-reward ledger** awaiting outcome validation.

---

## 7. Current moderation lifecycle

1. AI `WARNING` → spot `PENDING_REVIEW`.  
2. moderation-service may open a case from `AiValidationCompleted` (`ModerationApplicationService.handleAiValidationCompleted`) for FAILED / legal WARNING signals.  
3. Moderator resolves via `ModerationController.resolveCase`.  
4. Emits `ParkingSpotApprovedByModerator` / `ParkingSpotRejectedByModerator` on `parkio.moderation.action`.  
5. parking-service applies approve/reject; approve may emit `ParkingSpotActivated`.

**Review SLA (rc7):**  
`processModerationTimeouts` → for `PENDING_REVIEW`, `recordReviewSlaBreach` sets `reviewSlaBreachedAt` and extends `moderationDeadlineAt`. Status stays `PENDING_REVIEW`.  
**Doc drift:** `ModerationPolicy` javadoc still says breach moves to `REVIEW_FAILED`; **code does not** - treat code as authoritative.

**AI gate timeout:** `PENDING_VALIDATION` overdue → retry via `ParkingSpotModerationRetryRequested` (bounded attempts) else `REVIEW_FAILED`.

Jobs: `ModerationTimeoutJob`, `ParkingExpiryJob` under `services/parking-service/.../infrastructure/lifecycle/`.

---

## 8. Current expiration / TTL behavior

Configured in `services/parking-service/src/main/resources/application.yml` (`parkio.parking.moderation.*`):

| Knob | Default | Meaning |
|---|---|---|
| `active-duration` | `10m` | Advertised visible lifetime after publish |
| `validation-timeout` | `2m` | AI gate attempt window |
| `validation-retry-backoff` | (configured) | Per-attempt backoff |
| `max-validation-attempts` | `3` | Then `REVIEW_FAILED` |
| `review-timeout` | `15m` | Human SLA window (breach = metadata) |
| `max-publishable-age` | `30m` | From `createdAt`; late approval → `REVIEW_FAILED` if stale |

Rules:

- Pending statuses keep `expiresAt = null` - TTL does **not** run while waiting.  
- `startLifetime` runs once at publish (`activatedAt` is idempotence key).  
- `ParkingExpiryJob` expires published elapsed spots only.  
- Verification can extend duration (domain constants on `ParkingSpot`).

---

## 9. Kafka event inventory (spot-validation path)

Transport: `com.parkio.platform.messaging.EventEnvelope`; produce via transactional outbox; consume via inbox `eventId` dedupe; consumer DLT `parkio.dlt.{service}` after limited retries.

| Topic | Event | Producer | Consumers (side effects) |
|---|---|---|---|
| `parkio.media.media` | `MediaUploaded` | media-service | ai-validation (optional advisory) |
| `parkio.media.media` | `MediaRejected` | media-service | moderation (MEDIA case) |
| `parkio.parking.spot` | `ParkingSpotCreated` | parking `createSpot` | AI validate; gamification skip-if-pending; notification; analytics |
| `parkio.parking.spot` | `ParkingSpotActivated` | parking on publish | gamification upload reward |
| `parkio.parking.spot` | `ParkingSpotModerationRetryRequested` | parking timeout job | AI re-validate |
| `parkio.parking.spot` | `ParkingSpotReviewFailed` | parking | **no active reward/notify handlers found** |
| `parkio.parking.spot` | `ParkingSpotVerified` / `Claimed` / `Expired` / `MarkedFilled` | parking | gamification/moderation/analytics (subset) |
| `parkio.aivalidation.result` | `AiValidationCompleted` | ai-validation | **parking publication gate**; moderation case open |
| `parkio.moderation.action` | `ParkingSpotApprovedByModerator` / `RejectedByModerator` | moderation | parking status; gamification penalty on reject; notification |
| `parkio.gamification.score` | `PointsEarned` / `TrustScoreUpdated` / … | gamification | notification, user stats, analytics |

**Not present:** `MediaReady` Kafka event; `PointsAwarded` type (actual name `PointsEarned`).

Doc drift: `docs/architecture/event-contracts.md` still describes some AI consumers as "planned"; code is wired.

---

## 10. Database and entity inventory

### parking-service (selected)

| Artifact | Role |
|---|---|
| `V2__create_parking_spots.sql` | Core spot table + PostGIS |
| `V3__create_parking_spot_verifications.sql` | Community verify |
| `V4__create_parking_spot_status_history.sql` | Status history |
| `V14__add_pending_validation_statuses.sql` | Documents pending statuses |
| `V16__add_spot_moderation_lifecycle.sql` | `activated_at`, moderation columns, nullable `expires_at` |
| `V19__review_sla_breach_metadata.sql` | `review_sla_breached_at` + index |
| Entity `ParkingSpotEntity` + JPA repos | Persistence |
| `outbox_events` / `inbox_events` | Messaging durability |

### ai-validation-service

| Artifact | Role |
|---|---|
| `V1__create_ai_validation_results.sql` | Results + scores |
| `V2__create_ai_validation_findings.sql` | Findings |
| `V10__add_moderation_request_identity.sql` | Provenance + `canonical_image_hash` |

### moderation-service / gamification-service

Cases, decisions, user reports; reward rules (`V4__create_reward_rules.sql`), trust rules (`V14__create_trust_rules.sql`).

---

## 11. API / mobile / web contract inventory

| Contract | Location |
|---|---|
| `POST /api/v1/parking/spots` | `ParkingController.createSpot` + `CreateSpotRequest` |
| Spot responses | `SpotResponse`, `PublicSpotResponse` |
| Media upload | `POST /api/v1/media/upload` |
| Moderation resolve | `POST /api/v1/moderation/cases/{id}/resolve` |
| AI read/manual | `AiValidationController` (moderator-gated at gateway) |
| Shared types | `frontend/packages/types/src/parking.ts` (`PARKING_STATUSES`) |
| Shared validation | `frontend/packages/validation/src/contracts/parking.ts` |
| API client | `frontend/packages/api-client/src/parking.ts` |
| mobile-v2 share | `useCreateSpot.ts`, `useDraftUpload.ts` |
| Web upload | `frontend/apps/web/src/pages/UploadPage.tsx` |

`CreateSpotRequest` fields today: `mediaId`, lat/lng, `addressText`, `description`, `manualLocationEdited`, vehicle types, `parkingContext`, `legalStatus`, `violationReasons`.  
**No** GPS accuracy, device attestation, claimedRegion, or client evidence blob on the create contract.

---

## 12. Existing reusable capabilities

| Capability | Exists? | Evidence |
|---|---|---|
| AI confidence / quality / legal risk scores | Yes (persisted + event payload) | `AiValidationResult`, `AiValidationCompletedEvent` |
| Three-way photo policy signals | Yes | `ModerationDecisionPolicy`, `ModerationSignals` |
| Canonical image hash reuse | Yes | `ModerationProvenance.canonicalImageHash`, classifier reuse |
| GPS accuracy gate | Client-only (partial) | Legacy/mobile accuracy helpers; **not** on backend `CreateSpotRequest`; mobile-v2 draft may store accuracy but does not submit it |
| PostGIS location | Yes | parking spots table / nearby search |
| H3 indexing | **No** | No matches under `services/` |
| Device integrity / Play Integrity | **No** | No service matches |
| Trust score | Yes (gamification) | trust rules + `TrustScoreUpdated`; **not** used as create/publish gate |
| Upload reward deferral to publish | Yes | `handleParkingSpotActivated` |
| Spatial near-duplicate blocking | **No** | Only HTTP idempotency fingerprint on create |
| Fraud engine | **No** dedicated engine | Heuristics limited to AI/media/moderation paths |
| Feature flags | Partial | env/config toggles (e.g. Kafka consumer enable, vision provider); no Decision Engine flags |
| Audit / status history | Yes | `parking_spot_status_history`, moderation decisions |
| Scheduled expiry | Yes | `ParkingExpiryJob` |
| Observability | Yes | moderation metrics in parking-service; AI metrics; Prometheus stack |

---

## 13. Gaps against the target architecture

| Target concept | Current gap |
|---|---|
| Decision Engine as sole publication authority | AI status mapping + human approve/reject are the authority |
| Risk / Evidence / Availability / Trust scores | No first-class score model driving publish |
| Publication states `FULL_PUBLISH`, `LIMITED_PUBLISH`, `HOLD`, `SHADOW`, … | Only `ParkingSpotStatus` lifecycle; no exposure band |
| AI as evidence provider only | AI mapped statuses directly publish/reject |
| Pending rewards until outcome | Upload points on activation; no outcome-gated pending ledger |
| Adaptive exposure | Search is binary visible/not |
| Fraud / device integrity inputs | Absent |
| Geospatial cells (H3) | Absent |
| Extractable ports for future decision-service | No Decision ports; logic embedded in `ParkingApplicationService` / `ParkingSpot` |

---

## 14. Risks and migration constraints

1. **Contract compatibility** - mobile/web assume `PENDING_*` / `ACTIVE` / `REJECTED` semantics; new publication states MUST map or version carefully.  
2. **Event schema** - parking currently ignores AI numeric scores; Decision Engine MUST start consuming or extending payloads without breaking inbox dedupe.  
3. **Duplicate rewards** - any change to activation timing risks double-award unless transaction keys remain stable.  
4. **Race conditions** - AI result vs moderator approve; already watermarked via `occurredAt` / `moderationDecidedAt` - Decision Engine MUST preserve ordering rules.  
5. **Stale AI** - `maxPublishableAge` → `REVIEW_FAILED`; Decision Engine MUST define equivalent freshness policy.  
6. **Hidden moderator dependency** - uncertain spots still require human resolve for publication; removing it without LIMITED_PUBLISH/HOLD semantics regresses UX.  
7. **Doc/code drift** - `ModerationPolicy` javadoc vs SLA behavior; `event-contracts.md` "planned" consumers.  
8. **Rollback** - additive score columns + feature-flagged Decision Engine path preferred; reversing V19-style additive migrations is hard.  
9. **Latency** - AI already on critical path; stacking Decision Engine synchronously in the same consumer is acceptable if bounded; avoid new network hops initially.  
10. **Assumption (unverified in this audit):** production vs hosted-beta config values for moderation windows may differ via env overrides - defaults cited from `application.yml`.

### Unresolved questions

- Q1: Should `REVIEW_FAILED` map to target `REJECTED`, `HOLD`, or a new operational state?  
- Q2: Is community `UserReport` in scope for Decision Engine v1, or only spot-create publication?  
- Q3: Will LIMITED_PUBLISH require search ranking changes in the same milestone as Decision Engine v1?  
- Q4: Who owns Trust Score updates long-term - gamification-service or Decision Engine inputs only?

---

## 15. File-and-symbol evidence table

| Claim | Path | Symbol |
|---|---|---|
| Spot statuses | `services/parking-service/.../domain/ParkingSpotStatus.java` | enum values |
| Visibility rule | `.../domain/ParkingSpot.java` | `isVisibleForSearch` |
| Create API | `.../presentation/ParkingController.java` | `createSpot` |
| Create DTO | `.../presentation/dto/CreateSpotRequest.java` | record fields |
| AI publication gate | `.../application/ParkingApplicationService.java` | `applyAiValidationResult` |
| Moderator publish | same | `approveSpotByModerator` |
| SLA breach non-reject | `.../domain/ParkingSpot.java` | `recordReviewSlaBreach` |
| Timeout orchestration | `.../application/ParkingApplicationService.java` | `processModerationTimeouts` |
| AI Kafka consumer | `.../infrastructure/messaging/AiValidationEventsKafkaConsumer.java` | `TOPIC`, `onMessage` |
| Narrow AI payload | same file | record `AiValidationCompleted` |
| Three-way AI policy | `services/ai-validation-service/.../vision/ModerationDecisionPolicy.java` | `evaluate` |
| Policy version | `.../config/VisionProperties.java` | `policyVersion` |
| Upload reward on activate | `services/gamification-service/.../GamificationApplicationService.java` | `handleParkingSpotActivated` |
| Skip reward while pending | same | `handleParkingSpotCreated`, `isPendingPublication` |
| Reward key | `.../RewardRuleKeys.java` | `UPLOAD_OWNER` |
| Moderation windows | `services/parking-service/src/main/resources/application.yml` | `parkio.parking.moderation` |
| Lifecycle migration | `.../db/migration/V16__add_spot_moderation_lifecycle.sql` | columns |
| SLA column | `.../db/migration/V19__review_sla_breach_metadata.sql` | `review_sla_breached_at` |
| Mobile create | `frontend/apps/mobile-v2/src/features/share/useCreateSpot.ts` | create call |
| Shared statuses | `frontend/packages/types/src/parking.ts` | `PARKING_STATUSES` |

---

## Assumptions

- A1: Product language "parking report" = `ParkingSpot` submission (not `UserReport`, not `ParkingSession`).  
- A2: Hosted-beta/production use the same status machine; only durations may be env-overridden.  
- A3: No H3/device-integrity code exists outside searched `services/` trees (repo-wide service scan returned none).
