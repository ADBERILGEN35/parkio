# WP-05.6 — Decision Calibration & Shadow Analytics

**Status:** Complete (2026-07-27)  
**Policy version under observation:** `decision-shadow-v1`  
**Authority:** unchanged — `ParkingApplicationService.applyAiValidationResult` remains authoritative.  
**Related:** [WP-05.5 shadow mode](./wp-05-decision-engine-shadow-mode.md), [Implementation plan](./wp-05-implementation-plan.md), [Calibration report template](./wp-05-decision-calibration-report-template.md), [Observability metrics](./observability-metrics.md)

---

## 1. Executive summary

WP-05.6 adds a **production-safe calibration and analytics layer** on top of the non-authoritative Decision Engine shadow path from WP-05.5. Successful shadow evaluations emit an immutable `DecisionCalibrationObservation` with bounded enums only. `DecisionShadowMetrics` records low-cardinality Micrometer counters/timers; Prometheus recording rules and a Grafana dashboard support parity/drift monitoring.

**WP-05.6 provides parity and drift analytics, not correctness calibration.**

It does **not** migrate publication authority, auto-tune thresholds, persist decisions, or claim accuracy/false-hold rates without verified ground truth.

---

## 2. Scope and non-goals

**In scope**

- Structured calibration observation from shadow outputs
- Bounded analytics dimensions (comparison, disposition, risk band, hard-constraint family, decisive rule, evidence profile, assessment level/completeness)
- Exact metric catalogue aligned with `DecisionShadowMetrics`
- Prometheus recording rules and Grafana dashboard
- Ground-truth source audit
- Authority-readiness checklist for WP-05.8 (placeholders, no invented %)
- Offline policy comparison utility (`OfflineDecisionComparison`)
- Calibration report template

**Out of scope / non-goals**

- Authoritative `DecisionPort` execution / status transitions (WP-05.8)
- Automatic threshold or policy-version changes
- Self-learning / adaptive weighting in production
- Decision audit DB store (WP-05.7 — complete)
- Reward, trust, availability, API, Kafka, migration, or SDK changes
- High-cardinality tags (spot/event/user/reason/raw risk score)
- Correctness metrics (precision/recall/false hold) without ground truth

---

## 3. Current shadow pipeline

```text
AiValidationEventsKafkaConsumer
  → applyAiValidationResult (authoritative) → AiValidationApplyOutcome
  → [flag parkio.parking.decision.shadow-enabled]
DecisionShadowOrchestrator.observeAfterApply
  → EvidenceCollectionService → EvidenceVector
  → DecisionEngine → DecisionResult
  → ShadowDecisionComparison
  → DecisionCalibrationObservationFactory.from(...)
  → DecisionShadowObserverPort.recordSuccess(DecisionCalibrationObservation)
       └─ DecisionShadowMetrics (Micrometer)
```

Symbols:

- `com.parkio.parking.application.DecisionShadowOrchestrator`
- `com.parkio.parking.decision.calibration.DecisionCalibrationObservation`
- `com.parkio.parking.decision.calibration.DecisionCalibrationObservationFactory`
- `com.parkio.parking.application.port.DecisionShadowObserverPort`
- `com.parkio.parking.infrastructure.metrics.DecisionShadowMetrics`

Flag default remains **false** (`ParkingProperties.Decision` / `parkio.parking.decision.shadow-enabled`).

---

## 4. Calibration versus parity versus ground truth

| Term | Meaning in WP-05.6 |
|------|--------------------|
| **Agreement** | `ShadowComparisonCategory.EQUIVALENT` — legacy and shadow share an explicit equivalent semantic class |
| **Disagreement** | Comparable but different exposure posture (`SHADOW_MORE_RESTRICTIVE`, `SHADOW_MORE_PERMISSIVE`, `LEGACY_REVIEW_SHADOW_HOLD`) |
| **Not comparable** | `NOT_COMPARABLE` or `NO_SAFE_EQUIVALENCE` — no safe semantic comparison |
| **Parity** | Similarity to legacy behavior. **Parity is not proof of correctness.** |
| **Decision drift** | Distributional change over time in dispositions, comparison categories, risk bands, hard constraints, decisive rules, assessment levels, or evidence profiles |
| **Calibration** | Evaluation against agreed labels / trusted outcomes / explicit proxies |
| **Ground truth** | Later verified outcome or accepted human/product label. **Legacy status is not automatically ground truth.** |

Until ground truth exists, prefer: *shadow more restrictive*, *shadow more permissive*, *legacy/shadow disagreement*, *unresolved disagreement*. Do **not** compute false-hold / false-reject / accuracy from legacy disagreement alone.

**WP-05.6 provides parity and drift analytics, not correctness calibration.**

---

## 5. Calibration observation model

`DecisionCalibrationObservation` is an immutable value object safe for Micrometer tagging. It contains:

| Field | Type | Source |
|-------|------|--------|
| `policyVersion` | `String` (≤64) | `DecisionResult.policyVersion()` |
| `legacyKind` | `LegacyPublicationOutcome.Kind` | comparison legacy |
| `legacyStatus` | `ParkingSpotStatus` | comparison legacy |
| `shadowDisposition` | `PublicationDisposition` | decision |
| `comparisonCategory` | `ShadowComparisonCategory` | comparator |
| `riskBand` | `RiskBand` | `RiskBandClassifier` |
| `hardConstraintFamily` | `HardConstraintFamily` | `HardConstraintFamilyClassifier` |
| `decisiveRule` | `DecisivePolicyRule` | `DecisionResult.decisiveRule()` |
| `evidenceProfile` | `EvidenceAvailabilityProfile` | `EvidenceAvailabilityClassifier` |
| `assessments` | `List<AssessmentCategorySnapshot>` | CONTENT/LEGALITY/LOCATION/INTEGRITY only |
| `orchestrationDuration` | `Duration` | orchestrator wall time |
| `observedAt` | `Instant` | evaluation clock |

`comparable()` is true for EQUIVALENT | SHADOW_MORE_RESTRICTIVE | SHADOW_MORE_PERMISSIVE | LEGACY_REVIEW_SHADOW_HOLD.

Factory: `DecisionCalibrationObservationFactory.from(EvidenceVector, DecisionResult, ShadowDecisionComparison, Duration, Instant)`.

---

## 6. Analytics dimensions

Bounded enums only:

1. Comparison category (`ShadowComparisonCategory`)
2. Shadow disposition (`PublicationDisposition`)
3. Legacy kind / status (`LegacyPublicationOutcome.Kind`, `ParkingSpotStatus`)
4. Risk band (`RiskBand`)
5. Hard-constraint family (`HardConstraintFamily`)
6. Decisive policy rule (`DecisivePolicyRule`)
7. Evidence availability profile (`EvidenceAvailabilityProfile`)
8. Assessment level / completeness by active category
9. Failure stage (`ShadowFailureStage`)
10. Policy version tag (fixed to `decision-shadow-v1` at registration)

---

## 7. Metric catalogue

Registered by `DecisionShadowMetrics`:

| Micrometer name | Type | Tags |
|-----------------|------|------|
| `parkio.parking.decision.shadow.attempt` | counter | `policy_version` |
| `parkio.parking.decision.shadow.success` | counter | `policy_version` |
| `parkio.parking.decision.shadow.failure` | counter | `policy_version`, `stage` |
| `parkio.parking.decision.shadow.duration` | timer | `policy_version` |
| `parkio.parking.decision.shadow.engine.duration` | timer | `policy_version` (helper; optional) |
| `parkio.parking.decision.shadow.disposition` | counter | `policy_version`, `disposition` |
| `parkio.parking.decision.shadow.comparison` | counter | `policy_version`, `category` |
| `parkio.parking.decision.shadow.risk_band` | counter | `policy_version`, `band` |
| `parkio.parking.decision.shadow.hard_constraint_family` | counter | `policy_version`, `family` |
| `parkio.parking.decision.shadow.decisive_rule` | counter | `policy_version`, `rule` |
| `parkio.parking.decision.shadow.evidence_profile` | counter | `policy_version`, `profile` |
| `parkio.parking.decision.shadow.legacy_kind` | counter | `policy_version`, `kind` |
| `parkio.parking.decision.shadow.legacy_status` | counter | `policy_version`, `status` |
| `parkio.parking.decision.shadow.assessment_level` | counter | `policy_version`, `category`, `level` |
| `parkio.parking.decision.shadow.assessment_completeness` | counter | `policy_version`, `category`, `completeness` |

Disabled flag → no metrics. Failures → `failure` + duration; no success/dimension counters.

---

## 8. Exact metric names and bounded tags

Prometheus rendering (dots → underscores; counters → `_total`; timers → `_seconds_*`):

| Prometheus name | Notes |
|-----------------|-------|
| `parkio_parking_decision_shadow_attempt_total` | attempts |
| `parkio_parking_decision_shadow_success_total` | successes |
| `parkio_parking_decision_shadow_failure_total` | `stage=` enum |
| `parkio_parking_decision_shadow_duration_seconds_bucket\|count\|sum` | orchestration timer |
| `parkio_parking_decision_shadow_engine_duration_seconds_*` | pure engine timer (if recorded) |
| `parkio_parking_decision_shadow_disposition_total` | `disposition=` |
| `parkio_parking_decision_shadow_comparison_total` | `category=` |
| `parkio_parking_decision_shadow_risk_band_total` | `band=` |
| `parkio_parking_decision_shadow_hard_constraint_family_total` | `family=` |
| `parkio_parking_decision_shadow_decisive_rule_total` | `rule=` |
| `parkio_parking_decision_shadow_evidence_profile_total` | `profile=` |
| `parkio_parking_decision_shadow_legacy_kind_total` | `kind=` |
| `parkio_parking_decision_shadow_legacy_status_total` | `status=` |
| `parkio_parking_decision_shadow_assessment_level_total` | `category=`, `level=` |
| `parkio_parking_decision_shadow_assessment_completeness_total` | `category=`, `completeness=` |

**Forbidden tags:** spotId, eventId, mediaId, userId, free-form reason codes, exact `RiskScore`.

---

## 9. Ratio definitions and denominators

Recording rules live in `docker/prometheus/decision-shadow-recording-rules.yml`.

| Record | Numerator | Denominator | Notes |
|--------|-----------|-------------|-------|
| `parkio:decision_shadow:success_rate5m` | success rate | attempt rate | failures remain in denom |
| `parkio:decision_shadow:comparable_agreement_rate5m` | EQUIVALENT | EQUIVALENT + RESTRICTIVE + PERMISSIVE + LEGACY_REVIEW_SHADOW_HOLD | excludes NOT_COMPARABLE / NO_SAFE_EQUIVALENCE |
| `parkio:decision_shadow:more_restrictive_rate5m` | SHADOW_MORE_RESTRICTIVE | same comparable set | |
| `parkio:decision_shadow:more_permissive_rate5m` | SHADOW_MORE_PERMISSIVE | same comparable set | |
| `parkio:decision_shadow:not_comparable_rate5m` | NOT_COMPARABLE + NO_SAFE_EQUIVALENCE | all comparisons | |
| `parkio:decision_shadow:hold_rate5m` | disposition HOLD | all dispositions | successful only |
| `parkio:decision_shadow:rejected_rate5m` | disposition REJECTED | all dispositions | |
| `parkio:decision_shadow:hard_constraint_rate5m` | family != NONE | successes | |
| `parkio:decision_shadow:failure_rate5m` | failures | attempts | |

Minimum sample requirement for product decisions: **product-approved threshold required** (do not invent N). Engineering may use provisional alert floors only when labeled provisional.

---

## 10. Decision drift definition

**Decision drift** is a sustained change (week-over-week or cohort-over-cohort) in one or more of:

- comparison category mix
- shadow disposition mix
- risk-band mix
- hard-constraint family mix
- decisive-rule mix
- evidence-profile mix
- assessment level/completeness by category

Drift detection uses distribution rates, not correctness labels. A drift investigation asks whether input evidence quality, traffic mix, or policy code changed — not whether the engine became “more accurate.”

---

## 11. Evidence availability analysis

`EvidenceAvailabilityClassifier` maps `EvidenceVector` types:

| Profile | Condition |
|---------|-----------|
| `UNKNOWN` | empty or no AI/ops/location types |
| `AI_ONLY` | AI only |
| `AI_PLUS_OPERATIONAL` | AI + operational, no location |
| `AI_PLUS_LOCATION` | AI + geospatial, no operational |
| `COMPLETE_CURRENT_V1` | AI + operational + geospatial |
| `PARTIAL` | other non-empty mixes |

Runtime shadow currently passes `ParkingSpotEvidenceContext = null` (`DecisionShadowOrchestrator`), so location/integrity context may be weaker than golden fixtures. Missing TRUST/device/H3 is **not** a runtime defect for v1.

---

## 12. Decisive-rule analytics

`DecisivePolicyRule` is set by `DefaultDecisionPolicy` and carried on `DecisionResult` / observation:

`HARD_MEDIA_MISMATCH`, `HARD_INVALID_COORDINATES`, `CRITICAL_NOT_PARKING`, `INSUFFICIENT_CONTENT`, `LEGALITY_CONCERN`, `UNRESOLVED_CONFLICT`, `HIGH_RISK`, `ELEVATED_RISK`, `LOW_RISK_COMPLETE`, `FALLBACK_HOLD`, `UNKNOWN`.

Metric: `parkio.parking.decision.shadow.decisive_rule{rule}`.

---

## 13. Hard-constraint analytics

`HardConstraintFamilyClassifier` maps `HardConstraintResult`:

| Family | Meaning |
|--------|---------|
| `NONE` | inactive |
| `INTEGRITY` | media/spot mismatch only |
| `LOCATION` | invalid coordinates only |
| `LEGALITY` | reserved (not emitted by v1 hard policy) |
| `OTHER` | both or unrecognized active reasons |

Exact `RiskScore` is never tagged; hard-constraint activation is independent of the numeric score (`RiskBandClassifier` maps hard-active → `CRITICAL` band).

---

## 14. Failure taxonomy

`ShadowFailureStage`: `EVIDENCE_COLLECTION`, `EVIDENCE_EVALUATION`, `RISK_ASSESSMENT`, `DECISION_POLICY`, `LEGACY_COMPARISON`, `OBSERVABILITY`, `CONFIGURATION`, `UNKNOWN`.

Orchestrator isolates all failures; they never rethrow into the Kafka consumer apply path.

---

## 15. Prometheus recording rules or exact PromQL

File: `docker/prometheus/decision-shadow-recording-rules.yml`  
Loaded from: `docker/prometheus/prometheus.yml` (`rule_files`).

Key PromQL (also on dashboard):

```promql
sum(rate(parkio_parking_decision_shadow_attempt_total[5m]))
sum by (category) (rate(parkio_parking_decision_shadow_comparison_total[5m]))
sum by (disposition) (rate(parkio_parking_decision_shadow_disposition_total[5m]))
sum by (band) (rate(parkio_parking_decision_shadow_risk_band_total[5m]))
sum by (family) (rate(parkio_parking_decision_shadow_hard_constraint_family_total[5m]))
sum by (rule) (rate(parkio_parking_decision_shadow_decisive_rule_total[5m]))
sum by (profile) (rate(parkio_parking_decision_shadow_evidence_profile_total[5m]))
histogram_quantile(0.95, sum by (le) (rate(parkio_parking_decision_shadow_duration_seconds_bucket[5m])))
parkio:decision_shadow:comparable_agreement_rate5m
parkio:decision_shadow:more_restrictive_rate5m
parkio:decision_shadow:more_permissive_rate5m
parkio:decision_shadow:failure_rate5m
```

---

## 16. Grafana dashboard/panel design

Dashboard: `docker/grafana/provisioning/dashboards/parkio-decision-shadow.json`  
UID: `parkio-decision-shadow`  
Title: **Parkio - Decision Shadow Calibration**  
Datasource: `parkio-prometheus`

Panels: attempt/success/failure rates; recording-rule stats; comparison; disposition; risk band; hard-constraint family; decisive rule; evidence profile; duration p95; failure by stage.

**No spot/event IDs** in panels or legends.

---

## 17. Ground-truth source audit

| Candidate | Classification | Notes |
|-----------|----------------|-------|
| Legacy `ParkingSpotStatus` from `applyAiValidationResult` | **C** Operational outcome, not correctness label | Used for **parity** only |
| Moderator approve/reject (`ParkingSpot.applyModeratorApproval` / `markRejectedByModerator`) | **B** Useful but biased proxy | Timing/correlation to shadow evaluation not wired in WP-05.6; future label candidate |
| User verify (`ParkingSpot.verify` / `VerificationResult`) | **B** Useful but biased proxy | Post-publish occupancy signal; not decision-time truth |
| Claim / filled reports | **C** Operational outcome | Inventory lifecycle, not publication correctness |
| Expiration without contradiction | **C** Operational outcome | TTL/SLA, not validation correctness |
| Trusted verifier outcomes | **D** Unavailable as structured calibration label | No dedicated calibration label store |
| Manual audit labels | **D** Unavailable | No WP-05.6 label table |
| Golden fixtures A–L | **A** Reliable for **deterministic regression**, not production ground truth | Synthetic / engineered scenarios |

**Conclusion:** No production correctness ground truth is integrated.  
**WP-05.6 provides parity and drift analytics, not correctness calibration.**

---

## 18. Current data limitations

- Shadow default-off → empty series until enabled in a controlled environment
- Runtime `ParkingSpotEvidenceContext` null → weaker LOCATION/INTEGRITY than fixtures
- No persisted decision/audit rows → cannot join moderator later outcomes online
- No correlatable calibration label stream
- Micrometer timer histograms depend on registry percentile histogram config for `_bucket` series
- `legacy_status` cardinality bounded by `ParkingSpotStatus` enum (still keep panels aggregated)

---

## 19. Policy comparison workflow

Offline only: `OfflineDecisionComparison.compare(EvidenceVector, EvaluationContext, DecisionEngine baseline, DecisionEngine candidate)` → `Diff` (disposition/rule/risk-band change flags).

Prefer golden/offline comparison. Do **not** evaluate multiple policy versions per production event in WP-05.6.

---

## 20. Threshold-change governance

Thresholds live in `ShadowDecisionPolicyConfig` (code). Changes require:

1. Explicit reviewed code change + new `AssessmentVersion` / policy version string
2. Golden fixture updates
3. Offline `OfflineDecisionComparison` on representative fixtures
4. Calibration report (template) with product/risk approval
5. No dashboard-driven or auto-generated threshold writes

WP-05.6 emits metrics only; it never mutates policy config.

---

## 21. Authority-readiness criteria

Checklist for **WP-05.8 Controlled Authority Migration**. Do not mark items satisfied without production evidence. Values below are placeholders — **product-approved threshold required** (no invented 99%/97% targets).

| # | Criterion | Target |
|---|-----------|--------|
| 1 | Minimum successful shadow sample count | product-approved threshold required |
| 2 | Minimum observation duration across normal traffic cycles | product-approved threshold required |
| 3 | Maximum shadow failure rate | product-approved threshold required *(provisional eng alert floor may be documented separately)* |
| 4 | Minimum comparable agreement rate | product-approved threshold required |
| 5 | Maximum shadow-more-permissive rate | product-approved threshold required |
| 6 | Maximum unexplained hard-constraint disagreement rate | product-approved threshold required |
| 7 | Maximum NOT_COMPARABLE rate | product-approved threshold required |
| 8 | Minimum runtime evidence-context completeness | product-approved threshold required |
| 9 | Moderator/product review of high-impact disagreement samples | completed & recorded |
| 10 | Approved policy version and threshold table | product sign-off |
| 11 | Rollback procedure tested | drill evidence |
| 12 | Decision audit persistence available or explicitly waived | WP-05.7 complete or waiver |
| 13 | Canary authority mode implemented | WP-05.8 deliverable |
| 14 | Authority flag remains default off | config proof |
| 15 | Monitoring and alerting approved | ops/product sign-off |

---

## 22. Security/privacy

- Metrics: enum tags only; no PII, coordinates, image bytes, or raw payloads
- Logs: DEBUG correlation IDs (`spotId`/`evaluationId`) only; never in metric tags
- No persistence of evidence vectors or calibration observations in WP-05.6

---

## 23. Runtime failure isolation

`DecisionShadowOrchestrator` catches normalization/engine/observer failures, records `ShadowFailureStage`, and never rethrows into the authoritative apply path. Observer failures map to `OBSERVABILITY`.

---

## 24. Backward-compatibility proof

- `ParkingApplicationService.applyAiValidationResult` remains authoritative
- `AiValidationApplyOutcome` semantics unchanged
- Consumer authoritative processing / inbox / DLQ unchanged
- No ParkingSpot transition, visibility, reward, trust, or availability change
- No Flyway migration, Kafka schema, public API, or SDK change
- No extra DB read or remote call on the shadow path
- Flag default false → zero engine work when disabled

---

## 25. WP-05.8 prerequisites

1. Authority-readiness checklist signed with product-approved numeric thresholds
2. Sufficient shadow sample + duration under real traffic mix
3. Reviewed disagreement samples (especially more-permissive)
4. Decision audit persistence available (WP-05.7) or waived
5. Canary authority flag design with rollback to AI-status mapping
6. Calibration report for the approved policy version

---

## 26. Exact file-and-symbol inventory

**Calibration domain** (`com.parkio.parking.decision.calibration`):

- `DecisionCalibrationObservation`, `DecisionCalibrationObservationFactory`
- `AssessmentCategorySnapshot`
- `RiskBand`, `RiskBandClassifier`
- `HardConstraintFamily`, `HardConstraintFamilyClassifier`
- `DecisivePolicyRule`
- `EvidenceAvailabilityProfile`, `EvidenceAvailabilityClassifier`
- `ShadowFailureStage`
- `OfflineDecisionComparison`
- `package-info`

**Decision / policy:** `DecisionResult.decisiveRule()`, `DefaultDecisionPolicy`, `ShadowDecisionPolicyConfig`

**Shadow compare:** `LegacyPublicationOutcome`, `ShadowComparisonCategory`, `ShadowDecisionComparison`, `ShadowDecisionComparator`

**Application:** `DecisionShadowOrchestrator`, `DecisionShadowObserverPort`

**Infrastructure:** `DecisionShadowMetrics`

**Observability assets:**

- `docker/prometheus/decision-shadow-recording-rules.yml`
- `docker/prometheus/prometheus.yml` (rule_files entry)
- `docker/grafana/provisioning/dashboards/parkio-decision-shadow.json`
- `docker/grafana/provisioning/dashboards/dashboards.yml`

**Docs:** this file; `wp-05-decision-calibration-report-template.md`; updates to implementation plan, shadow-mode doc, README, `observability-metrics.md`

**Tests:** `CalibrationAnalyticsTest`, `DecisionShadowMetricsTest`, orchestrator/observer tests

## Related

- [WP-05.8 Controlled Authority Migration](wp-05-controlled-authority-migration.md) — default-off canary authority; shadow remains non-authoritative and independent.
