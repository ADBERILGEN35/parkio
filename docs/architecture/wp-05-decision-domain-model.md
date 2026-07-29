# WP-05 Decision Domain Model

**Status:** WP-05.2 vocabulary + WP-05.3 evidence pipeline + WP-05.4 assessment model  
**Date:** 2026-07-27  
**Related:** [Current-state audit](./wp-05-parking-validation-current-state.md), [ADR placement](./adr/ADR-WP05-decision-engine-placement.md), [Evidence normalization](./wp-05-evidence-collection-normalization.md), [Evidence evaluation](./wp-05-evidence-evaluation-model.md), [Implementation plan](./wp-05-implementation-plan.md)

## 1. Purpose and scope

Canonical Decision Engine domain vocabulary inside `parking-service` under
`com.parkio.parking.decision`.

| WP | Delivered |
|----|-----------|
| 05.2 | Evidence/score/disposition types + ports |
| 05.3 | Evidence normalization → `EvidenceVector` |
| 05.4 | Domain assessments + evaluation ports + mathematics spec |

Out of scope still: production DecisionPort authority, migrations, API/Kafka changes.

## 2. Ubiquitous language

| Term | Meaning |
|---|---|
| ParkingSpot | Existing submission aggregate (`com.parkio.parking.domain.ParkingSpot`) |
| Evidence | Normalized observation about one submission |
| Evidence Vector | Immutable collection of evidence for one evaluation |
| Domain Assessment | Category-level interpretation of evidence |
| Assessment Bundle | Deterministic set of domain assessments for one evaluation |
| Risk Assessment | Derived exposure-risk judgment from assessments |
| Decision | Policy application producing a `PublicationDisposition` |
| Trust | Actor-level signal (Decision `TrustScore` 0.00-1.00) |
| Availability | Opportunity freshness / remaining publishability |
| Outcome | Post-publish observation about a ParkingSpot |
| PublicationDisposition | Decision output vocabulary (not `ParkingSpotStatus`) |

Principle: **The Decision Engine decides. AI is only one evidence provider.**

## 3. Evidence versus assessment versus risk versus decision

```text
EvidenceItem(s)  ->  EvidenceVector
                         |
                         v
              EvidenceEvaluationPolicy
                         |
                         v
                   AssessmentBundle (DomainAssessment*)
                         |
                         v
                 RiskAssessmentPolicy
                         |
                         v
                   RiskAssessment
                         |
                         v
              DecisionPort -> DecisionResult(PublicationDisposition)
```

- Evidence describes what is known.
- Assessment interprets evidence per category (level, not disposition).
- Risk is derived from assessments; it is not raw evidence.
- Decision applies versioned policy to assessments + risk.
- No production path currently computes RiskAssessment or Disposition.

## 4. Trust versus Evidence Score

| Type | Package | Scale | Owns |
|---|---|---|---|
| `EvidenceScore` | `decision.score` | int 0-100 | Optional aggregate observation quality |
| `TrustScore` | `decision.score` | BigDecimal 0.00-1.00 | Actor trust **input** |

Unknown scores MUST be `Optional.empty()`, never zero.

## 5. Risk as a derived assessment

`RiskAssessment` carries optional `RiskScore` (0-100), reason codes, assessment
version, evaluation timestamp, `hardConstraintActive`, and contributing
`EvidenceReference`s. `RiskAssessmentPolicy` derives it from `AssessmentBundle`.
See [evaluation model](./wp-05-evidence-evaluation-model.md) for semantics.

## 6. Availability as time-varying state

`AvailabilityScore` (0-100) represents opportunity freshness. Existing runtime
TTL remains owned by `ParkingSpot` / `ModerationPolicy`. Not evaluated in WP-05.4 bundles.

## 7. PublicationDisposition semantics

| Disposition | Semantics |
|---|---|
| `FULL_PUBLISH` | Eligible for normal visibility under active policy |
| `LIMITED_PUBLISH` | Restricted / adaptive exposure only (not implemented) |
| `HOLD` | Not published; awaiting evidence / async validation |
| `SHADOW` | Not publicly visible; security/fraud observation |
| `EXPIRED` | No longer publishable |
| `REJECTED` | Final non-publication under evaluated policy |

## 8. Assessment model (WP-05.4)

Hybrid design: `DomainAssessment` + `AssessmentBundle`.

- Categories: CONTENT, LEGALITY, LOCATION, INTEGRITY (+ reserved TRUST, BEHAVIOR, AVAILABILITY)
- Levels: POSITIVE, ACCEPTABLE, UNCERTAIN, CONCERNING, CRITICAL, INSUFFICIENT_EVIDENCE, NOT_APPLICABLE
- Completeness: COMPLETE, PARTIAL, EMPTY
- Traceability: `EvidenceReference` via `EvidenceItem.canonicalKey()`

Missing category ≠ INSUFFICIENT_EVIDENCE ≠ fabricated neutral assessment.

## 9. Outcome vocabulary

`SpotOutcomeType`: `PARKED_SUCCESSFULLY`, `ARRIVED_BUT_OCCUPIED`,
`LOCATION_NOT_FOUND`, `INVALID_OR_ILLEGAL`, `REPORT_ALREADY_STALE`.

## 10. Package and dependency boundaries

```text
com.parkio.parking.decision
  evidence/      EvidenceType, EvidenceSource, EvidencePolarity, EvidenceItem, EvidenceVector
  normalization/ WP-05.3 input records + normalizers
  application/   EvidenceCollectionService, EvidenceVectorFactory
  score/         EvidenceScore, RiskScore, TrustScore, AvailabilityScore
  assessment/    DomainAssessment, AssessmentBundle, RiskAssessment, ReasonCode, ...
  evaluation/    EvaluationContext
  outcome/       SpotOutcomeType, SpotOutcome
  port/          Decision + evidence + evaluation + ADR ports
  compatibility/ PublicationDispositionCompatibility (non-runtime)
```

Core decision sources MUST NOT depend on Spring, JPA, Kafka, presentation DTOs,
or outer `application` / `infrastructure` packages (normalization/application
under `decision` are allowed). Verified by `DecisionPackageIndependenceTest`.

## 11. Port responsibilities

| Port | Why necessary |
|---|---|
| `EvidenceProvider` | Category-scoped evidence acquisition |
| `EvidenceCollectionPort` | Assemble EvidenceVector (`EvidenceCollectionService`) |
| `EvidenceEvaluationPolicy` | EvidenceVector → AssessmentBundle |
| `RiskAssessmentPolicy` | AssessmentBundle → RiskAssessment |
| `DecisionPort` | Disposition from assessments + risk |
| `DecisionAuditPort` | Persist decision audit trail |
| `TrustSignalPort` | Actor trust read boundary |
| `AvailabilityPort` | Freshness input boundary |
| `ModeratorOverridePort` | Human override as evidence |
| `SpotDispositionPort` | Apply decision to ParkingSpot later |

No Spring beans for evaluation/decision policies in WP-05.4.

## 12. Current ParkingSpotStatus compatibility analysis

`PublicationDispositionCompatibility.suggestedLegacyStatus` — **not wired**.

## 13. Invariants

- Score types reject out-of-range values; unknown = absent.
- Evidence items require type/source/polarity/observedAt; strength in 0-100.
- `EvidencePolarity.ABSENT` != `OPPOSES_PUBLISH`.
- `EvidenceVector` / `AssessmentBundle` canonicalize order; no silent conflict merge.
- Duplicate assessment categories rejected.
- Hard constraint requires `AssessmentLevel.CRITICAL`.
- `DecisionResult` requires disposition, policyVersion, ≥1 reason code.
- Reason codes are UPPER_SNAKE_CASE machine tokens.

## 14. Explicitly deferred behavior

- Production evaluation/decision thresholds (WP-05.5+)
- Wiring consumer → DecisionPort
- Changing `applyAiValidationResult` authority
- Trust/device/H3/fraud assessments
- Persistence / migrations / Kafka schema changes

## 15. Extension rules

1. Add evidence categories to `EvidenceType`; do not add AI DTO field names.
2. Add assessment categories sparingly; prefer reason codes for nuance.
3. Keep score types separate; never reuse EvidenceScore for Trust.
4. New ports need ADR + extraction justification.
5. Adapters belong in `infrastructure`, not `decision`.

## 16. File-and-symbol inventory

See also [wp-05-evidence-evaluation-model.md](./wp-05-evidence-evaluation-model.md) §26 and
[wp-05-evidence-collection-normalization.md](./wp-05-evidence-collection-normalization.md) §16.

Current runtime (unchanged):

| Symbol | Path |
|---|---|
| `applyAiValidationResult` | `.../application/ParkingApplicationService.java` |
| `AiValidationEventsKafkaConsumer` | `.../infrastructure/messaging/AiValidationEventsKafkaConsumer.java` |
| `isVisibleForSearch` | `.../domain/ParkingSpot.java` |

## WP-05.5 follow-up

Decision Engine v1 shadow mode is documented in [wp-05-decision-engine-shadow-mode.md](./wp-05-decision-engine-shadow-mode.md). Production authority remains pplyAiValidationResult. Reference policy version: `decision-shadow-v1`.
