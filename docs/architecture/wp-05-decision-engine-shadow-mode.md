# WP-05.5 Decision Engine v1 — Non-Authoritative Shadow Mode

**Status:** Complete (2026-07-27)  
**Policy version:** `decision-shadow-v1`  
**Authority:** unchanged — `ParkingApplicationService.applyAiValidationResult` remains authoritative.  
**Calibration:** [WP-05.6 Decision Calibration & Shadow Analytics](./wp-05-decision-calibration-shadow-analytics.md)  
**Audit store:** [WP-05.7 Decision Audit Store](./wp-05-decision-audit-store.md)

## 1. Executive summary

WP-05.5 lands a pure, deterministic Decision Engine pipeline:

`EvidenceVector → AssessmentBundle → RiskAssessment → DecisionResult`

Runtime execution is optional, default-off, and non-authoritative. When
`parkio.parking.decision.shadow-enabled=true`, parking-service evaluates the
shadow decision **after** the legacy AI apply path, compares dispositions, and
records low-cardinality metrics. Shadow failures never mutate publication,
rewards, trust, availability, or Kafka/DB outcomes.

Thresholds are **conservative engineering baselines**, not product-calibrated truth.

## 2. Scope and non-goals

**In scope:** category evaluators (CONTENT/LEGALITY/LOCATION/INTEGRITY), hard
constraints, risk math, decision composition, golden fixtures, legacy comparison
matrix, default-off shadow orchestration, observability.

**Out of scope (WP-05.5):** controlled authority migration (WP-05.8), LIMITED_PUBLISH UX,
trust/device/H3 evaluation, Kafka schema changes, threshold calibration.
Decision audit persistence is WP-05.7.

## 3. End-to-end pure decision pipeline

```text
EvidenceVector + EvaluationContext
  → DefaultEvidenceEvaluationPolicy
      → Content / Legality / Location / Integrity evaluators
  → AssessmentBundle
  → DefaultRiskAssessmentPolicy (+ HardConstraintPolicy)
  → RiskAssessment
  → DefaultDecisionPolicy
  → DecisionResult
```

Facade: `com.parkio.parking.decision.policy.DecisionEngine`.

## 4. Component responsibility diagram

```text
[Kafka AI result]
      │
      ▼
applyAiValidationResult  ──► AiValidationApplyOutcome (in-memory)
      │
      ▼ (flag off: stop)
DecisionShadowOrchestrator
      │ collect EvidenceVector (no extra DB read; spot context null)
      ▼
DecisionEngine (pure)
      ▼
ShadowDecisionComparator → metrics (bounded tags)
```

## 5. Assessment evaluation policies

| Category | Source evidence | Notes |
|----------|-----------------|-------|
| CONTENT | `AI_CONTENT_ANALYSIS` | AI FAILED → CONCERNING (not hard reject); NOT_A_PARKING_SPOT → CRITICAL; weak image quality → INSUFFICIENT |
| LEGALITY | legal risk reasons + submitter legal | Vacancy evidence cannot erase legality concern |
| LOCATION | `GEOSPATIAL_CONSISTENCY` | Invalid coordinates → CRITICAL + hardConstraint |
| INTEGRITY | `OPERATIONAL_PROVENANCE` | Media mismatch → CRITICAL + hardConstraint; stale event → UNCERTAIN (not EXPIRED) |

Reserved TRUST / BEHAVIOR / AVAILABILITY remain **absent** unless explicitly evaluated.

## 6. Hard-constraint policy

`HardConstraintPolicy` reads `AssessmentBundle` only:

| Constraint | Detection | Shadow disposition |
|------------|-----------|--------------------|
| MEDIA_SPOT_MISMATCH | INTEGRITY hard / `INTEGRITY_MEDIA_MISMATCH` | `SHADOW` |
| COORDINATES_INVALID | LOCATION hard / `LOCATION_COORDINATES_INVALID` | `HOLD` |

AI_STATUS_FAILED and low image quality are **not** hard constraints.

## 7. Risk mathematics

Integer weighted average (half-up), clamped to `[0, 100]`:

`RiskScore = clamp(round_half_up(Σ weight(c) × levelRisk(level(c)) / Σ weight(c)))`

Weights (`decision-shadow-v1`): CONTENT 30, LEGALITY 35, LOCATION 15, INTEGRITY 20.

Level contributions: POSITIVE 0, ACCEPTABLE 15, UNCERTAIN 40, CONCERNING 65,
CRITICAL 100, INSUFFICIENT_EVIDENCE 50, NOT_APPLICABLE 0.

Hard-constraint activation is stored independently of `RiskScore`.

Missing categories are skipped (not treated as zero risk). Absent TRUST/device/H3
does not invent assessments.

## 8. Reference policy configuration

Class: `ShadowDecisionPolicyConfig.referenceV1()`  
Version: `AssessmentVersion.of("decision-shadow-v1")`

| Constant | Value | Rationale |
|----------|-------|-----------|
| RISK_FULL_PUBLISH_MAX | 25 | Conservative low-risk band for FULL_PUBLISH |
| RISK_ELEVATED_MIN | 26 | Prefer HOLD above this |
| RISK_HIGH_MIN | 71 | High non-final risk → HOLD |
| EMPTY_SPACE_STRONG_MIN | 70 | Strong vacancy for POSITIVE content |
| IMAGE_QUALITY_WEAK_MAX | 40 | Weak quality → insufficient content |
| LEGAL_RISK_CONCERNING_MIN | 40 | Legal concern band |
| LEGAL_RISK_CRITICAL_MIN | 80 | Critical legality score |

No Spring config, env defaults, or remote fetch inside the domain.

## 9. Decision composition order

`DefaultDecisionPolicy`:

1. Hard MEDIA mismatch → `SHADOW`
2. Hard invalid coordinates → `HOLD`
3. Final content invalidity (`CONTENT_NOT_PARKING`) → `REJECTED`
4. Insufficient required evidence → `HOLD`
5. Unresolved content/legality conflict or uncertain content → `HOLD`
6. High risk (≥71) → `HOLD`
7. Elevated risk (≥26) → `HOLD`
8. Complete required categories + risk ≤25 → `FULL_PUBLISH`
9. Else → conservative `HOLD`

Prefer HOLD over REJECTED when finality is not justified.

## 10. Disposition semantics used by v1

| Disposition | v1 meaning |
|-------------|------------|
| FULL_PUBLISH | Low risk, complete required assessments |
| HOLD | Needs more evidence/review; non-final |
| REJECTED | Final non-publication only for explicit not-parking |
| SHADOW | Integrity hard constraint (media mismatch) |
| LIMITED_PUBLISH | Not emitted by v1 |
| EXPIRED | Not emitted for stale moderation events |

## 11. Explainability and reason-code propagation

`DecisionResult` carries parkingSpotId, evaluationId, disposition, `DerivedAssessment`
(bundle + risk), policy version, decidedAt, and machine-readable reason codes.
No localized user messages; no raw payloads.

## 12. Golden fixture catalogue

| ID | Scenario | Expected highlight |
|----|----------|--------------------|
| A | Strong normal | FULL_PUBLISH, low risk |
| B | Conflicting legality | HOLD; legality CONCERNING preserved |
| C | Poor image quality | HOLD; content INSUFFICIENT; not REJECTED |
| D | AI FAILED | HOLD; not automatic REJECTED; no hard constraint |
| E | Media mismatch | SHADOW; hard constraint active |
| F | Invalid coordinates | HOLD; hard constraint active |
| G | Legal risk critical | Restrictive HOLD (not FULL) |
| H | Stale event | Not EXPIRED; location not invalid |
| I | Missing trust/device/H3 | Reserved categories absent |
| J | Duplicate evidence | No double-count vs strong normal |
| K | Order independence | Identical assessments/risk/decision |
| L | Reserved absence | TRUST/BEHAVIOR/AVAILABILITY absent |

Tests: `DecisionEngineGoldenFixtureTest`.

## 13. Legacy/shadow comparison model

`ShadowDecisionComparator` exhaustively maps every `ParkingSpotStatus` ×
`PublicationDisposition` to `ShadowComparisonCategory`:

EQUIVALENT, SHADOW_MORE_PERMISSIVE, SHADOW_MORE_RESTRICTIVE,
LEGACY_REVIEW_SHADOW_HOLD, NO_SAFE_EQUIVALENCE, NOT_COMPARABLE.

Stale apply kind → NOT_COMPARABLE. REVIEW_FAILED / SUSPICIOUS / FILLED → NOT_COMPARABLE.

## 14. Shadow feature flag

```yaml
parkio.parking.decision.shadow-enabled: ${PARKIO_PARKING_DECISION_SHADOW_ENABLED:false}
```

Bound by `ParkingProperties.Decision`. Default **false** in all environments.
Must not be enabled in production config files.

## 15. Runtime integration point

`AiValidationEventsKafkaConsumer.onMessage`:

1. Authoritative `applyAiValidationResult` (returns `AiValidationApplyOutcome`)
2. Existing evidence DEBUG shadow
3. If flag enabled → `DecisionShadowOrchestrator.observeAfterApply`

No extra ParkingSpot DB read for location context (documented limitation:
runtime LOCATION may be INSUFFICIENT without spot context).

## 16. Failure isolation

Orchestrator catches normalization and runtime exceptions, records failure
metrics, DEBUG-logs correlation IDs only, and never rethrows. Technical failures
do not map to business dispositions.

## 17. Observability and metric cardinality

`DecisionShadowMetrics` (Micrometer) records attempts/failures/duration plus success dimensions from `DecisionCalibrationObservation` (WP-05.6):

- `parkio.parking.decision.shadow.attempt`
- `parkio.parking.decision.shadow.success`
- `parkio.parking.decision.shadow.failure{stage}`
- `parkio.parking.decision.shadow.duration`
- `parkio.parking.decision.shadow.engine.duration`
- `parkio.parking.decision.shadow.disposition{disposition}`
- `parkio.parking.decision.shadow.comparison{category}`
- `parkio.parking.decision.shadow.risk_band{band}`
- `parkio.parking.decision.shadow.hard_constraint_family{family}`
- `parkio.parking.decision.shadow.decisive_rule{rule}`
- `parkio.parking.decision.shadow.evidence_profile{profile}`
- `parkio.parking.decision.shadow.legacy_kind{kind}` / `legacy_status{status}`
- `parkio.parking.decision.shadow.assessment_level{category,level}`
- `parkio.parking.decision.shadow.assessment_completeness{category,completeness}`

Tags are enum-bounded only — never spot/event IDs or exact RiskScore. Full catalogue: [wp-05-decision-calibration-shadow-analytics.md](./wp-05-decision-calibration-shadow-analytics.md).

## 18. Determinism and thread safety

Immutable config singleton; pure policies; no system clock in engine;
`EvaluationContext.evaluatedAt` supplied by caller; EvidenceVector canonical
ordering; concurrent repeated evaluation covered by tests.

## 19. Performance expectations

Pure in-memory evaluation; no I/O/JSON/reflection/network. Local budget:
500 repeated strong-normal evaluations under 2s (`DecisionEngineGoldenFixtureTest`).

## 20. Security/privacy considerations

Logs: spotId/evaluationId/eventId only at DEBUG. No coordinates, image bytes,
or raw payloads. No high-cardinality metric tags.

## 21. Backward-compatibility proof

- Authoritative apply path semantics unchanged (status transitions identical)
- Inbox dedupe / retries / DLQ unchanged
- No Flyway migration, Kafka topic/schema, public API, or SDK change
- Flag default off → zero Decision Engine work when disabled
- Return type `AiValidationApplyOutcome` exposes already-computed status only

## 22. Explicitly deferred decisions

- Product calibration of weights/thresholds
- Spot-context load for runtime location/integrity mismatch detection
- LIMITED_PUBLISH semantics
- Persisted decision audit (delivered in WP-05.7)
- Authority flip

## 23. WP-05.8 authority-migration prerequisites

See also the WP-05.6 authority-readiness checklist in
[wp-05-decision-calibration-shadow-analytics.md](./wp-05-decision-calibration-shadow-analytics.md).

1. Shadow parity evidence across golden + production-like fixtures
2. Calibrated or explicitly accepted reference thresholds (product-approved)
3. Decision audit persistence available (WP-05.7) or explicit waiver
4. Feature flag for authoritative DecisionPort path
5. Parity tests vs legacy `applyAiValidationResult` outcomes
6. Rollback plan restoring AI-status mapping

## 24. Exact file-and-symbol inventory

**Policy:** `DecisionEngine`, `DefaultEvidenceEvaluationPolicy`,
`ContentAssessmentEvaluator`, `LegalityAssessmentEvaluator`,
`LocationAssessmentEvaluator`, `IntegrityAssessmentEvaluator`,
`HardConstraintPolicy`, `HardConstraintResult`, `DefaultRiskAssessmentPolicy`,
`DecisionPolicy`, `DefaultDecisionPolicy`, `ShadowDecisionPolicyConfig`,
`EvidenceSelectors`

**Shadow compare:** `LegacyPublicationOutcome`, `ShadowComparisonCategory`,
`ShadowDecisionComparison`, `ShadowDecisionComparator`

**Application:** `DecisionShadowOrchestrator`, `AiValidationApplyOutcome`,
`DecisionShadowObserverPort`

**Calibration (WP-05.6):** `DecisionCalibrationObservation`,
`DecisionCalibrationObservationFactory`, classifiers, `OfflineDecisionComparison`

**Infra:** `DecisionShadowMetrics`, `ParkingProperties.Decision`,
`ParkingInfrastructureConfig.decisionShadowOrchestrator`,
`AiValidationEventsKafkaConsumer` (shadow hook)

**Tests:** `DecisionEngineGoldenFixtureTest`, `DecisionGoldenFixtures`,
`CategoryAssessmentEvaluatorTest`, `ShadowDecisionComparatorTest`,
`DecisionShadowOrchestratorTest`, updated consumer tests

## Related

- [WP-05.8 Controlled Authority Migration](wp-05-controlled-authority-migration.md) — default-off canary authority; shadow remains non-authoritative and independent.
