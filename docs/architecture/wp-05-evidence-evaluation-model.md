# WP-05.4 — Evidence Evaluation Model and Decision Mathematics Specification

**Status:** Complete (2026-07-27)  
**Related:** [WP-05.1 audit](./wp-05-parking-validation-current-state.md), [ADR](./adr/ADR-WP05-decision-engine-placement.md), [Domain model](./wp-05-decision-domain-model.md), [Evidence normalization](./wp-05-evidence-collection-normalization.md), [Implementation plan](./wp-05-implementation-plan.md)

---

## 1. Executive summary

WP-05.4 defines how a normalized `EvidenceVector` becomes typed **domain assessments** and a **risk interpretation**, without selecting `PublicationDisposition` or changing production authority.

Selected design: **hybrid** — one canonical `DomainAssessment` contract (category + level + completeness + evidence references) aggregated in `AssessmentBundle`. Separate concrete classes per category are rejected to avoid class explosion. Decision mathematics is specified as **hard constraints + category assessments + weighted risk aggregation** (non-authoritative thresholds deferred).

Production path remains: `AiValidationEventsKafkaConsumer` → `ParkingApplicationService.applyAiValidationResult`.

---

## 2. Current pipeline and WP-05.4 boundary

```text
ParkingSpot submission
  → Evidence Providers / Normalizers          (WP-05.3 — done)
  → EvidenceVector
  → EvidenceEvaluationPolicy                  (WP-05.4 — port + model)
  → AssessmentBundle (DomainAssessment*)
  → RiskAssessmentPolicy                      (WP-05.4 — port refined)
  → RiskAssessment
  → DecisionPort / DecisionPolicy           (WP-05.5 shadow — non-authoritative)
  → PublicationDisposition
  → SpotDispositionPort / ParkingSpot         (later)
```

| Layer | WP-05.4 |
|-------|---------|
| Evidence normalization | unchanged |
| Assessment types + ports | **in scope** |
| Risk mathematics specification | **in scope** |
| Production RiskAssessmentPolicy impl | **out** (no grounded thresholds) |
| DecisionPort impl / shadow decision | **done** (WP-05.5 shadow; authority unchanged) |
| Publication authority change | **out** |

---

## 3. Ubiquitous language

| Term | Meaning |
|------|---------|
| Evidence | Normalized observation (`EvidenceItem`) |
| EvidenceVector | Immutable snapshot for one evaluation |
| DomainAssessment | Category-level interpretation of evidence |
| AssessmentBundle | Deterministic set of domain assessments for one evaluation |
| RiskAssessment | Derived exposure-risk judgment from assessments |
| Decision | Policy applying assessments + risk → `PublicationDisposition` |
| Hard constraint | Explicit, versioned blocking condition visible on assessment/risk |
| EvaluationContext | Policy version + evaluatedAt (+ optional scenario); no infra |

---

## 4. Evidence versus assessment versus risk versus decision

| Concept | Answers | Type examples |
|---------|---------|---------------|
| Evidence | What was observed? | `EvidenceItem`, polarity, strength |
| Assessment | What does it mean for this category? | `DomainAssessment`, `AssessmentLevel` |
| Risk | How risky is exposure? | `RiskAssessment`, `RiskScore` |
| Decision | What publication disposition? | `DecisionResult`, `PublicationDisposition` |

Rules:
1. AI is an evidence provider, never the decision.
2. A single evidence item must not normally determine disposition (Decision policy + hard constraints).
3. Trust ≠ EvidenceScore; Availability ≠ publication validity.
4. Missing capability ≠ negative evidence ≠ INSUFFICIENT_EVIDENCE.

---

## 5. Selected assessment-model design

**Choice: C — Hybrid**

- Stable common type: `DomainAssessment`
- Discriminator: `AssessmentCategory`
- Qualitative outcome: `AssessmentLevel`
- Aggregate: `AssessmentBundle`
- Traceability: `EvidenceReference` (canonical key + type/source)

No `ContentAssessment` / `LocationAssessment` classes. No stringly-typed maps.

---

## 6. Alternatives considered

| Option | Pros | Cons | Verdict |
|--------|------|------|---------|
| A. Separate concrete types per category | Max type safety | Class explosion; awkward reserved categories | Rejected |
| B. Single DomainAssessment only (no bundle) | Simple | Weak aggregate identity/completeness; harder DecisionPort | Incomplete alone |
| C. Hybrid DomainAssessment + AssessmentBundle | Extensible, explainable, serializable, extractable | Slight indirection | **Selected** |

---

## 7. Initial assessment categories

| Category | Initial? | Evidence sources (WP-05.3) |
|----------|----------|------------------------------|
| CONTENT | Yes | AI status, empty-space, image quality, AI confidence |
| LEGALITY | Yes | legalRiskScore, legal/placement risk types, submitter legal status |
| LOCATION | Yes | coordinate validity, manual location edit |
| INTEGRITY | Yes | event correlation, media mismatch, stale moderation event (integrity + provenance combined) |
| TRUST | Reserved | No local transactional trust |
| BEHAVIOR | Reserved | No velocity/fraud signals |
| AVAILABILITY | Reserved | Owned by TTL / later Availability Engine |

**Integrity + provenance:** one category (`INTEGRITY`). Both concern operational trustworthiness of the evaluation inputs, not parking-claim content.

Reserved categories must **not** appear in a WP-05.4 bundle unless explicitly evaluated as `NOT_APPLICABLE` for a documented scenario. Prefer **absence** over fabricated assessments.

---

## 8. Assessment state semantics (`AssessmentLevel`)

| Level | Meaning |
|-------|---------|
| POSITIVE | Confirmed supportive signal |
| ACCEPTABLE | Acceptable, not strongly positive |
| UNCERTAIN | Ambiguous or conflicting within category |
| CONCERNING | Meaningful concern; elevates risk |
| CRITICAL | Blocking concern; may set `hardConstraint` |
| INSUFFICIENT_EVIDENCE | Category applies; evidence below minimum |
| NOT_APPLICABLE | Category irrelevant to scenario |

Numeric scores alone are insufficient; level is mandatory.

---

## 9. Unknown / insufficient / neutral / not-applicable

| Concept | Representation |
|---------|----------------|
| Unknown (cannot determine) | Prefer `UNCERTAIN` with reason, or omit category if policy did not evaluate |
| Insufficient evidence | `AssessmentLevel.INSUFFICIENT_EVIDENCE` + completeness EMPTY/PARTIAL |
| Neutral evidence item | `EvidencePolarity.NEUTRAL` on evidence; does not alone force assessment level |
| Not applicable | Explicit `NOT_APPLICABLE` (no evidence refs) |
| Absence of capability | Category **missing** from `AssessmentBundle` — do not emit zero/neutral DomainsAssessment |
| Uncomputed score | `Optional.empty()` on score fields — never 0 |

---

## 10. AssessmentBundle design

Fields:
- `parkingSpotId`, `evaluationId`
- `evidenceSchemaVersion` (from `EvidenceVector.schemaVersion()`)
- ordered `DomainAssessment` list (unique category)
- `evaluationPolicyVersion` (`AssessmentVersion`)
- `evaluatedAt`
- `globalReasonCodes`
- optional `aggregateEvidenceScore`

Invariants:
- Duplicate categories rejected
- Deterministic category ordinal order
- Missing category ≠ INSUFFICIENT_EVIDENCE
- Equality deterministic
- No disposition, persistence, network, or clock access

---

## 11. Evidence traceability model

**Choice: hybrid evidence references**

`EvidenceReference` stores:
- `canonicalKey` from `EvidenceItem.canonicalKey()` (identity)
- `EvidenceType`, `EvidenceSource`
- optional `ReasonCode`

Rejected:
1. Full embedded EvidenceItem collections (payload bloat)
2. Reason-code-only (insufficient reproducibility)
3. New opaque evidence UUIDs (not in WP-05.3)

Audit: AssessmentBundle + source EvidenceVector (same evaluationId) reconstructs full items.

---

## 12. Evaluation-policy boundary

```text
EvidenceEvaluationPolicy.evaluate(EvidenceVector, EvaluationContext) → AssessmentBundle
RiskAssessmentPolicy.assess(AssessmentBundle, EvaluationContext) → RiskAssessment
DecisionPort.decide(EvidenceVector, AssessmentBundle, RiskAssessment, ...) → DecisionResult
```

`EvaluationContext`: evaluationPolicyVersion, evaluatedAt, optional scenarioKey.

Structure: **one orchestrating EvidenceEvaluationPolicy** with internal category rules (package-private helpers later). No plugin registry in WP-05.4.

No production implementation — thresholds lack product grounding.

---

## 13. Mathematical model

**Selected: Hard constraints → category assessments → weighted risk aggregation → confidence/completeness adjustment → DecisionPolicy (later)**

Rejected for v1: pure Bayesian, ML, scores-only additive without constraints.

### Category evaluation (conceptual)

For each evaluated category C:
1. Collect relevant `EvidenceItem`s by type/reason mapping (deterministic).
2. Classify hard-constraint candidates (see §14).
3. Aggregate polarity × strength into a provisional category intensity.
4. Assign `AssessmentLevel` from rules (not thresholds alone).
5. Set completeness from expected vs present signals.
6. Emit reason codes + evidence references.

### Risk aggregation (conceptual, non-authoritative)

```text
If any hardConstraint: hardConstraintActive = true; RiskScore MAY still be computed for audit.
Else RiskScore = clamp(0,100, round( Σ w_c * categoryRisk(c) + completenessPenalty ))
```

- Supporting evidence decreases categoryRisk; opposing increases.
- Neutral evidence does not change categoryRisk unless completeness rules say so.
- Duplicate identical items: count once (vector/factory already dedupes equals).
- Correlated items (e.g. legalRiskScore + NO_PARKING_SIGN): apply correlation discount in policy version notes — do not double-count naively.
- Missing reserved categories: no weight contribution (not zero risk).
- Rounding: half-up to integer 0–100; documented in policy version.

**Production weights/thresholds are deferred** to product calibration in WP-05.5+.

---

## 14. Hard-constraint model

| Candidate (WP-05.3 reason) | Classification | Rationale |
|----------------------------|----------------|-----------|
| `MEDIA_SPOT_MISMATCH` | **Definite hard constraint** | Identity integrity failure; evaluation inputs not trustworthy |
| `COORDINATES_INVALID` | **Definite hard constraint** | Cannot publish a geospatially invalid spot |
| `AI_STATUS_FAILED` | Strong risk evidence | AI is advisory; Decision may HOLD/REJECT later — not auto-final solely because AI said FAILED |
| `AI_RISK_NOT_A_PARKING_SPOT` | Strong risk evidence | Content concern; Decision policy decides |
| Legal placement risks (`NO_PARKING_SIGN`, etc.) / high `LEGAL_RISK_SCORE` | Strong risk / ordinary weighted | Elevate LEGALITY; not automatic REJECT without Decision policy |
| `SUBMITTER_LEGAL_RISK` | Strong risk evidence | Submitter claim; corroborate with AI legality |
| `STALE_MODERATION_EVENT` | Neutral operational / ignore-or-hold input | Ordering watermark; does **not** mean the parking location is invalid |
| `AI_STATUS_WARNING` / low image quality | Ordinary weighted / insufficient | Prefer HOLD / INSUFFICIENT over REJECT |
| `AI_EVENT_CORRELATED` | Neutral operational positive | Provenance OK |
| `MANUAL_LOCATION_EDITED` | Neutral / mild concern | Location completeness note |

Hard constraints must be: explicit, versioned (`evaluationPolicyVersion`), reason-coded, and visible via `DomainAssessment.hardConstraint()` / `RiskAssessment.hardConstraintActive()`.

---

## 15. RiskScore semantics

| Aspect | Spec |
|--------|------|
| Question | How risky to expose this ParkingSpot under policy + snapshot? |
| 0 | Minimal modeled exposure risk among computable assessments |
| 100 | Maximum modeled exposure risk |
| Unknown | `Optional.empty()` — never treat as 0 |
| Rounding | Integer 0–100, half-up |
| Monotonicity | Adding opposing evidence must not decrease category risk unless documented counterbalance; adding supporting must not increase unless completeness/correlation rules apply |
| Hard constraints | Remain visible regardless of numeric score; high risk may coexist with COMPLETE assessments |
| Low completeness | Prefer HOLD inputs / risk floor in DecisionPolicy — not silent zero risk |
| Not meaning | AI error P, malice P, occupancy, availability, legality alone, completeness alone |

---

## 16. Conflict-resolution rules

1. Preserve conflicting evidence as distinct `EvidenceItem`s (WP-05.3).
2. Category assessment may be `UNCERTAIN` when supports and opposes both material.
3. Strong vacancy (CONTENT POSITIVE) does **not** erase LEGALITY CONCERNING/CRITICAL.
4. Decision (later) sees both assessments; must not collapse to AI status alone.

---

## 17. Missing-evidence behavior

| Situation | Behavior |
|-----------|----------|
| Optional AI score field absent | Completeness PARTIAL; do not invent score |
| Category expected but no items | INSUFFICIENT_EVIDENCE or omit if category not yet in policy |
| Trust/device/H3 unavailable | Category absent from bundle |
| Aggregate EvidenceScore uncomputed | `Optional.empty()` on bundle |

---

## 18. Correlated and duplicate evidence handling

- Duplicates: `EvidenceVectorFactory` / `EvidenceItem.equals` — count once.
- Correlation groups (documented in policy version): e.g. `{LEGAL_RISK_SCORE, AI_RISK_NO_PARKING_SIGN}` — apply max or discounted sum, never raw double.
- Order independence: category rules must not depend on list iteration order beyond deterministic sorts.

---

## 19. Versioning and reproducibility

| Version | Role |
|---------|------|
| `EvidenceVector.schemaVersion` | Evidence snapshot schema |
| `AssessmentVersion` on DomainAssessment / bundle `evaluationPolicyVersion` | Evaluation model + rule set |
| `DecisionResult.policyVersion` | Decision policy (distinct) |

Reproducibility: same EvidenceVector + EvaluationContext → same AssessmentBundle. No wall clock inside policies.

---

## 20. Explainability and reason-code rules

- Prefer UPPER_SNAKE_CASE machine tokens (`ReasonCode`).
- Assessment reasons describe interpretation (`CONTENT_OK`, `LEGALITY_CONFLICT`); evidence reasons describe observation (`AI_STATUS_PASSED`).
- Do not invent unbounded free text.
- Every CRITICAL / hardConstraint requires ≥1 reason code.
- No localized user messages in domain.

---

## 21. Current source evidence classification

| ReasonCode / signal | Category | Role |
|---------------------|----------|------|
| AI_STATUS_* | CONTENT | Weighted / strong |
| EMPTY_SPACE_CONFIDENCE | CONTENT | Weighted support |
| IMAGE_QUALITY_SCORE / AI_RISK_LOW_IMAGE_QUALITY | CONTENT | Weighted / insufficient driver |
| AI_CONFIDENCE | CONTENT | Neutral completeness |
| LEGAL_RISK_SCORE / AI_RISK_* legal | LEGALITY | Weighted / strong |
| AI_RISK_NOT_A_PARKING_SPOT | CONTENT (+ legality adjacency) | Strong content |
| COORDINATES_* | LOCATION | Hard / support |
| MANUAL_LOCATION_EDITED | LOCATION | Neutral |
| SUBMITTER_LEGAL_* | LEGALITY | Weighted |
| AI_EVENT_CORRELATED | INTEGRITY | Support |
| MEDIA_SPOT_MISMATCH | INTEGRITY | Hard constraint |
| STALE_MODERATION_EVENT | INTEGRITY | Operational (not location invalid) |

---

## 22. Worked examples

### Scenario A — Strong normal case

Evidence: PASSED, high empty-space, good quality, low legal risk, valid coords, correlated, no mismatch.

| Category | Level | Notes |
|----------|-------|-------|
| CONTENT | POSITIVE | Status + scores support |
| LEGALITY | ACCEPTABLE/POSITIVE | Low legal risk |
| LOCATION | POSITIVE | Valid coordinates |
| INTEGRITY | POSITIVE | Correlated |

Risk interpretation: low optional RiskScore; no hard constraint. Future Decision input leans FULL_PUBLISH — **not authoritative here**.

### Scenario B — Conflicting evidence

Evidence: PASSED + high empty-space + NO_PARKING_SIGN + valid coords.

| Category | Level | Notes |
|----------|-------|-------|
| CONTENT | POSITIVE | Vacancy strong |
| LEGALITY | CONCERNING | Legal risk preserved |
| LOCATION | POSITIVE | |
| INTEGRITY | POSITIVE | |

Vacancy does not erase legality. Risk elevated; Decision may HOLD or LIMITED later.

### Scenario C — Poor image quality

Evidence: WARNING or low quality; no critical legal; location valid.

| Category | Level | Notes |
|----------|-------|-------|
| CONTENT | INSUFFICIENT_EVIDENCE or UNCERTAIN | Quality gap ≠ REJECT |
| LEGALITY | ACCEPTABLE | |
| LOCATION | POSITIVE | |

Future Decision input: HOLD / await better evidence — not automatic REJECTED.

### Scenario D — Integrity mismatch

Evidence: MEDIA_SPOT_MISMATCH; otherwise positive AI.

| Category | Level | hardConstraint |
|----------|-------|----------------|
| INTEGRITY | CRITICAL | true |
| CONTENT | may still be POSITIVE | false |

Integrity critical; Decision must not publish on untrusted media binding.

### Scenario E — Stale event

Evidence: strong otherwise; STALE_MODERATION_EVENT.

| Category | Level | Notes |
|----------|-------|-------|
| INTEGRITY | UNCERTAIN or ACCEPTABLE | Stale = ordering/ignore semantics |
| Others | POSITIVE | Spot location not “invalid” |

Do not treat stale as COORDINATES_INVALID or fraud.

### Scenario F — Missing future capability

No trust, device, H3 evidence.

Bundle contains only CONTENT/LEGALITY/LOCATION/INTEGRITY as evaluated. No TRUST/DEVICE assessments. No zero placeholders.

---

## 23. Existing-type migration / cleanup decisions

| Type | Decision |
|------|----------|
| `EvidenceItem` / `EvidenceVector` | Retained; `canonicalKey()` public |
| `EvidenceScore` | Retained (optional aggregate on bundle) |
| `RiskScore` | Retained; javadoc clarified |
| `TrustScore` / `AvailabilityScore` | Retained unchanged |
| `EvidenceAssessment` | **Removed** — replaced by `DomainAssessment` / bundle |
| `RiskAssessment` | **Refined** — hardConstraintActive + contributingEvidence |
| `DerivedAssessment` | **Refined** — carries `AssessmentBundle` not EvidenceAssessment |
| `DecisionResult` | Retained (still wraps DerivedAssessment) |
| `DecisionPort` | **Refined** — AssessmentBundle + RiskAssessment inputs |
| `RiskAssessmentPolicy` | **Refined** — assesses AssessmentBundle |
| `EvidenceEvaluationPolicy` | **Added** |
| `AssessmentVersion` / `ReasonCode` | Retained |
| `EvaluationContext` | **Added** |

---

## 24. Explicitly deferred decisions

- Production weights and thresholds
- Authoritative DecisionPort implementation
- Shadow decision compare (WP-05.5)
- Persisted AssessmentBundle / RiskAssessment
- Trust / Availability assessment implementations
- Correlation discount coefficients
- REVIEW_FAILED ↔ disposition mapping (open from WP-05.1)

---

## 25. Recommended WP-05.5 scope

**Decision Engine v1 Shadow Mode**
1. Package-private or experimental `EvidenceEvaluationPolicy` reference impl (non-authoritative thresholds clearly marked).
2. Shadow compare: engine disposition suggestion vs `applyAiValidationResult` outcome — log only.
3. Do not route production traffic through DecisionPort.
4. Golden fixtures for scenarios A–E.
5. Metrics: shadow mismatch counters.
6. No status transition change.

Then WP-05.6: Controlled Decision Authority Migration.

---

## 26. File-and-symbol inventory

| Symbol | Path |
|--------|------|
| `AssessmentCategory` | `.../decision/assessment/AssessmentCategory.java` |
| `AssessmentLevel` | `.../decision/assessment/AssessmentLevel.java` |
| `AssessmentCompleteness` | `.../decision/assessment/AssessmentCompleteness.java` |
| `EvidenceReference` | `.../decision/assessment/EvidenceReference.java` |
| `DomainAssessment` | `.../decision/assessment/DomainAssessment.java` |
| `AssessmentBundle` | `.../decision/assessment/AssessmentBundle.java` |
| `EvaluationContext` | `.../decision/evaluation/EvaluationContext.java` |
| `EvidenceEvaluationPolicy` | `.../decision/port/EvidenceEvaluationPolicy.java` |
| `RiskAssessmentPolicy` | `.../decision/port/RiskAssessmentPolicy.java` |
| `DecisionPort` | `.../decision/port/DecisionPort.java` |
| `RiskAssessment` | `.../decision/assessment/RiskAssessment.java` |
| `DerivedAssessment` | `.../decision/assessment/DerivedAssessment.java` |

Removed: `EvidenceAssessment.java`

Unchanged runtime: `ParkingApplicationService.applyAiValidationResult`, `AiValidationEventsKafkaConsumer.onMessage`.

## WP-05.5 delivery

See [wp-05-decision-engine-shadow-mode.md](./wp-05-decision-engine-shadow-mode.md) for implemented evaluators, risk math, golden fixtures, and shadow runtime.
