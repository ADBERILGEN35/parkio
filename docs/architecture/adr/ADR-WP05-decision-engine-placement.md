# ADR-WP05: Decision Engine Placement

- **Status:** Proposed
- **Date:** 2026-07-27
- **Work package:** WP-05.1
- **Related:** [WP-05 current-state audit](../wp-05-parking-validation-current-state.md), [WP-05 implementation plan](../wp-05-implementation-plan.md), [WP-05.2 domain model](../wp-05-decision-domain-model.md)

## Context

Parkio publishes community parking spots after media readiness and an AI photo check. Today the **final publication transition** is made inside parking-service by mapping AI status strings (`PASSED` / `WARNING` / `FAILED`, plus risk `NOT_A_PARKING_SPOT`) in `ParkingApplicationService.applyAiValidationResult`, with a human escape hatch via `approveSpotByModerator` / `rejectSpotByModerator`.

Gamification awards upload points on `ParkingSpotActivated`, not while the spot is pending. Rich AI scores exist on the event payload but are not consumed by the parking consumer DTO.

Product direction requires a **Decision Engine** that combines Risk, Evidence, User Trust, Availability, AI signals, device integrity, geospatial, behavioral/fraud, outcome, pending rewards, and adaptive exposure -- with publication outcomes such as `FULL_PUBLISH`, `LIMITED_PUBLISH`, `HOLD`, `SHADOW`, `EXPIRED`, and `REJECTED`.

Canonical principle:

> The Decision Engine decides. AI is only one evidence provider.

Constraints for this ADR:

- Do not create a new microservice before the domain model is validated.
- Preserve current client contracts until an explicit versioned migration.
- Prefer extractable ports so a future `decision-service` remains possible.

## Decision

1. Implement the Decision Engine as an **isolated logical module inside parking-service** (package boundary under `com.parkio.parking.decision` or equivalent), not as a new deployable.
2. Place the engine **behind ports/interfaces** owned by parking-service application/domain layers so AI-validation-service, moderation-service, and gamification-service remain evidence / side-effect collaborators, not publication authorities.
3. Make the Decision Engine the **sole authority** for publication disposition and exposure band. AI outputs and moderator actions become **inputs** (evidence / overrides with audited policy), never direct writers of searchable visibility except through the engine.
4. Keep **spot aggregate ownership** in parking-service (`ParkingSpot` / status history / TTL). Decision outputs MUST be applied through parking domain transitions (or additive publication fields) with outbox events as the integration surface.
5. Design Trust Score and Evidence Score as **distinct concepts** with separate inputs, owners, and update cadences (see Rejected alternatives).

## Why the Decision Engine is logically centralized

Publication is a single product invariant: a spot is either discoverable under a defined exposure policy or it is not. Spreading final authority across AI-validation, moderation, and ad-hoc parking mappings creates:

- Conflicting transitions (AI vs moderator races -- already partially handled via watermarks).
- Reward timing coupled to the wrong signal (`Activated` as proxy for "decision").
- Inability to introduce LIMITED_PUBLISH / SHADOW without forking logic in multiple services.

Centralizing the disposition decision (even as a module) keeps one policy version, one audit trail, and one place to add scores.

## Why it remains inside parking-service initially

| Reason | Evidence / implication |
|---|---|
| Aggregate already owns visibility | `ParkingSpot.isVisibleForSearch`, TTL (`startLifetime`), moderation lifecycle columns (V16/V19) |
| Critical path already ends in parking | `AiValidationEventsKafkaConsumer` -> `applyAiValidationResult` |
| Avoid premature distributed TX | New service would add sync RPC or dual-write before scores exist |
| Team/scale unknown | Extraction justified only after domain model + metrics prove boundary value |
| Rollback simplicity | Feature-flag module path inside one deployable |

## Extraction boundary (future decision-service)

When (and only when) operational scale or team boundaries justify extraction, the boundary SHOULD be:

| Owned by decision-service (future) | Remains in parking-service |
|---|---|
| Decision policy evaluation | `ParkingSpot` aggregate persistence |
| Score composition / versioning | Search query execution / PostGIS |
| Decision audit log (immutable) | Applying disposition to spot rows |
| Feature-flagged shadow decisions | Outbox emission of spot lifecycle events |

Proposed extraction contract: parking-service calls `DecisionPort.decide(DecisionCommand) -> DecisionResult` (in-process today; HTTP/gRPC later) **or** consumes a `parkio.decision.result` event produced after parking emits `DecisionRequested`. Either way, **parking remains the applicator** of state to the spot row.

## Mandatory ports / interfaces

RFC 2119: the following ports MUST exist before Decision Engine v1 is considered complete:

| Port | Direction | Responsibility |
|---|---|---|
| `DecisionPort` | inbound to decision module | Evaluate disposition from evidence + assessments + risk |
| `EvidenceEvaluationPolicy` | inbound / pure policy | EvidenceVector → AssessmentBundle (WP-05.4) |
| `RiskAssessmentPolicy` | inbound / pure policy | AssessmentBundle → RiskAssessment (WP-05.4) |
| `EvidenceCollectionPort` | inbound / application | Assemble EvidenceVector from supplied inputs (WP-05.3) |
| `EvidenceProvider` | outbound / adapter | Per-category normalized evidence (AI via WP-05.3 normalizers) |
| `TrustSignalPort` | outbound | Read user trust / reputation signals (gamification-owned data) |
| `AvailabilityPort` | outbound / domain | Freshness, TTL remaining, `maxPublishableAge` |
| `ModeratorOverridePort` | outbound | Recorded human decisions as weighted overrides, not silent status writes |
| `DecisionAuditPort` | outbound | Persist decision inputs/outputs/policyVersion |
| `SpotDispositionPort` | outbound to parking domain | Apply `FULL_PUBLISH` / `LIMITED_PUBLISH` / `HOLD` / `SHADOW` / `REJECTED` / `EXPIRED` mappings |

Optional later: `DeviceIntegrityPort`, `FraudSignalPort`, `OutcomeSignalPort`, `GeospatialIndexPort` (H3).

## Data ownership

| Data | Owner service | Notes |
|---|---|---|
| Spot row + visibility + TTL | parking-service | Canonical |
| AI scores / findings / provenance | ai-validation-service | Evidence only |
| Moderation cases / human decisions | moderation-service | Override evidence |
| Points / trust ledger | gamification-service | Trust signals; pending rewards may need new ledger (WP-05.7) |
| Decision audit / policy version | parking-service initially (decision module tables) | Move with extraction |

## Event ownership

- Spot lifecycle events (`ParkingSpot*`) remain owned by parking-service outbox on `parkio.parking.spot`.
- `AiValidationCompleted` remains owned by ai-validation-service; Decision Engine MUST treat it as evidence, not as an implicit publish command.
- New decision events (future), if any, MUST be additive and versioned; consumers MUST ignore unknown fields (`FAIL_ON_UNKNOWN_PROPERTIES = false` convention).
- Reward triggers SHOULD eventually bind to decision/outcome events, not solely to `ParkingSpotActivated` semantics if activation and disposition diverge.

## Failure behavior

| Failure | Expected behavior |
|---|---|
| AI timeout / missing result | Existing retry then `REVIEW_FAILED` path remains until Decision Engine defines HOLD; MUST NOT auto-publish |
| Unknown AI status | Fail-closed (current no-op) MUST be preserved or mapped to HOLD |
| Decision module exception | Fail-closed; spot remains non-searchable; emit metric + alert |
| Stale report (`maxPublishableAge`) | MUST refuse FULL/LIMITED publish (current `REVIEW_FAILED` / reject-stale behavior) |
| Moderator override | Applied as explicit input with audit; MUST NOT bypass freshness rules without policy flag |

## Consistency expectations

- Decision application and outbox append for resulting spot events MUST remain in the **same DB transaction** as today's parking writes.
- Consumers remain **at-least-once** with inbox `eventId` idempotency.
- Shadow mode (WP-05.8 / rollout) SHOULD compute decisions without changing visibility until flag enabled.

## Performance expectations

- Decision evaluation for v1 SHOULD complete in-process on the AI-result consumer path with a hard upper bound compatible with Kafka consumer timeouts (order of tens of milliseconds of CPU; no new remote calls required for v1).
- Remote Trust/Fraud lookups, if added later, MUST be cached or async with fail-closed defaults.

## Security and fraud considerations

- Clients MUST NOT be able to set disposition or trust scores via create/update APIs.
- Device integrity and fraud signals are untrusted until server-verified; absence MUST NOT be treated as "trusted".
- Decision audit MUST retain policyVersion and input hashes for dispute / abuse review.
- Moderator tools remain privileged; overrides MUST be attributed.

## Consequences

### Positive

- Aligns with target principle without a new service.
- Reuses existing outbox/inbox, moderation lifecycle columns, and activation-gated rewards.
- Creates a clean extraction seam.

### Negative / costs

- Temporary concentration of logic in parking-service.
- Requires careful mapping from new dispositions to existing `ParkingSpotStatus` for mobile/web compatibility.
- Risk of dual-path complexity during feature-flag rollout.

## Rejected alternatives

### 1. AI directly approving or rejecting reports

**Rejected.** AI-validation-service already emits advisory-shaped payloads with scores; treating `PASSED`/`FAILED` as final authority conflates evidence with disposition and blocks multi-signal policy. Current parking mapping in `applyAiValidationResult` is the anti-pattern this ADR replaces.

### 2. Manual moderators approving every report

**Rejected.** Does not scale for perishable ~10m inventory; review SLA already records breach without auto-reject. Humans remain an override channel, not the default authority.

### 3. Creating a new decision microservice before validating the domain model

**Rejected.** Adds network failure modes, dual ownership of visibility, and operational cost before Evidence/Trust/Availability scores exist. Extraction is deferred until ports and metrics justify it.

### 4. Granting final rewards immediately on submission

**Rejected.** Current code correctly defers upload points to `ParkingSpotActivated`. Immediate final rewards on create would amplify fraud and conflict with pending-reward / outcome validation (WP-05.6 / WP-05.7).

### 5. Treating Trust Score and Evidence Score as the same concept

**Rejected.** Evidence describes the **report/observation** (photo quality, GPS, AI signals). Trust describes the **actor** over time (reputation). Collapsing them prevents independent calibration and fraud isolation.

## References (code)

- `ParkingApplicationService.applyAiValidationResult`
- `AiValidationEventsKafkaConsumer`
- `ParkingSpot.isVisibleForSearch` / `startLifetime` / `recordReviewSlaBreach`
- `GamificationApplicationService.handleParkingSpotActivated`
- `ModerationDecisionPolicy.evaluate`
- Current-state audit: `docs/architecture/wp-05-parking-validation-current-state.md`