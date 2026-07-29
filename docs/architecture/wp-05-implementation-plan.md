# WP-05 Implementation Plan — Parking Validation & Decision Architecture

**Work package baseline:** WP-05.1 (this document)  
**Related:** [Current-state audit](./wp-05-parking-validation-current-state.md), [ADR placement](./adr/ADR-WP05-decision-engine-placement.md)  
**Constraint:** Decision Engine lives as a logical module inside parking-service initially (see ADR). No new microservice in WP-05.2–05.11 unless a later ADR revises placement.

Legend: **Current** = as of WP-05.1 audit. **Target** = end-state for that task.

---

## WP-05.2 Canonical Domain Model

**Status:** Complete (2026-07-27) — see [wp-05-decision-domain-model.md](./wp-05-decision-domain-model.md).

### Objective
Define and land the canonical decision domain types (publication dispositions, score concepts, evidence snapshot) without changing runtime publication behavior.

### In-scope
- Domain enums/records for dispositions: `FULL_PUBLISH`, `LIMITED_PUBLISH`, `HOLD`, `SHADOW`, `EXPIRED`, `REJECTED`
- Explicit separation of Evidence Score vs Trust Score vs Risk Score vs Availability Score (types + ownership docs)
- Mapping table from dispositions <-> current `ParkingSpotStatus` (compatibility shim)
- Package skeleton `com.parkio.parking.decision` with ports only (no scoring logic)
- Update architecture docs / event registry notes for proposed fields

### Out-of-scope
- Scoring algorithms, Kafka consumer behavior changes, migrations that alter visibility, frontend UX for new states

### Affected services/modules
- parking-service (domain + docs); types package docs only if contracts previewed

### Database impact
- Prefer none, or additive nullable columns behind unused feature flag. MUST NOT change search visibility semantics.

### Event/API impact
- None required for runtime. Document proposed optional response fields as additive.

### Compatibility strategy
- Keep emitting/accepting existing `ParkingSpotStatus` strings. New dispositions map 1:1 or many:1 onto current statuses until clients migrate.

### Tests
- Unit tests for disposition <-> status mapping; compile-only port interfaces

### Observability
- None required beyond doc of future metric names

### Rollback strategy
- Delete unused types/package; no data migration reverse needed if no columns written

### Acceptance criteria
- [x] Mapping table reviewed and checked into docs
- [x] Ports listed in ADR exist as interfaces (no runtime impl)
- [x] Production behavior unchanged (no runtime wiring)

### Dependencies
- WP-05.1 complete

---

## WP-05.3 Evidence Collection and Normalization

**Status:** Complete (2026-07-27) — see [wp-05-evidence-collection-normalization.md](./wp-05-evidence-collection-normalization.md).

### Objective
Map existing AI validation and parking-spot signals into canonical `EvidenceItem` / `EvidenceVector` values without changing publication authority.

### In-scope
- `AiValidationEvidenceNormalizer`, location/operational normalizers, `EvidenceVectorFactory`, `EvidenceCollectionService`
- Extended consumer parsing of score fields already on `AiValidationCompleted` v1 payload (read-only for normalization)
- Fail-safe shadow observation in `AiValidationEventsKafkaConsumer` (debug logging only)
- Port review: removed duplicate `AiEvidencePort`

### Out-of-scope
- `EvidenceScore` / `RiskScore` calculation, `DecisionPort` wiring, disposition selection
- Trust/device/H3 evidence, DB persistence, migrations, API/event schema changes

### Affected services/modules
- `parking-service` `com.parkio.parking.decision.normalization` + `decision.application`
- `AiValidationEventsKafkaConsumer` (shadow only)

### Database impact
- None

### Event/API impact
- None (consumer reads additional optional JSON fields already emitted by producer; `applyAiValidationResult` signature unchanged)

### Compatibility strategy
- Publication path identical; shadow failures swallowed

### Tests
- `com.parkio.parking.decision.normalization.*`, `com.parkio.parking.decision.application.*`
- Existing `AiValidationEventsKafkaConsumerTest`, `ParkingApplicationServiceTest` unchanged

### Observability
- DEBUG shadow log: spot id, evaluation id, item count only

### Rollback strategy
- Remove shadow call and normalization package; no data to revert

### Acceptance criteria
- [x] AI fields mapped to `EvidenceItem` with deterministic rules
- [x] `EvidenceVector` assembly tested
- [x] No scores/decisions/dispositions produced
- [x] Publication and reward behavior unchanged
- [x] No migration/API/event break
- [x] Port inventory pruned (`AiEvidencePort` removed)
- [x] Full `parking-service` tests green

### Dependencies
- WP-05.2 complete

---

## WP-05.4 Evidence Evaluation Model

**Status:** Complete (2026-07-27) — see [wp-05-evidence-evaluation-model.md](./wp-05-evidence-evaluation-model.md).

### Objective
Define the domain model and mathematics that turn `EvidenceVector` into typed category assessments and a risk interpretation — without selecting `PublicationDisposition` or changing publication authority.

### In-scope
- Hybrid `DomainAssessment` + `AssessmentBundle` model
- `AssessmentCategory` / `AssessmentLevel` / `AssessmentCompleteness` / `EvidenceReference`
- `EvidenceEvaluationPolicy` and refined `RiskAssessmentPolicy` / `DecisionPort` signatures
- Decision mathematics + hard-constraint specification
- Cleanup: remove premature `EvidenceAssessment`
- Focused domain tests; documentation with worked scenarios A–F

### Out-of-scope
- Production scoring thresholds / evaluator Spring beans
- DecisionPort implementation or shadow decision execution
- Runtime consumer / `applyAiValidationResult` changes
- Migrations, API/Kafka/SDK changes

### Acceptance criteria
- [x] Assessment model selected and justified
- [x] Unknown / insufficient / neutral / N/A distinct
- [x] RiskScore semantics documented
- [x] Hard constraints classified from repository evidence
- [x] No PublicationDisposition selected by evaluation layer
- [x] No production runtime path changed
- [x] parking-service tests green

### Dependencies
- WP-05.3 complete

---

## WP-05.5 Decision Engine v1 Shadow Mode

**Status:** Complete (2026-07-27) — see [wp-05-decision-engine-shadow-mode.md](./wp-05-decision-engine-shadow-mode.md).

### Objective
Introduce a non-authoritative Decision Engine path that consumes `AssessmentBundle` + `RiskAssessment` and shadow-compares suggested dispositions to current `applyAiValidationResult` outcomes.

### In-scope
- Pure `DecisionEngine` + category/hard-constraint/risk/decision policies (`decision-shadow-v1`)
- Golden fixtures A–L; exhaustive legacy/shadow comparison matrix
- Default-off flag `parkio.parking.decision.shadow-enabled`
- Fail-safe `DecisionShadowOrchestrator` + Micrometer observer
- `applyAiValidationResult` returns `AiValidationApplyOutcome` (already-computed status; no extra DB read)

### Out-of-scope
- Routing production traffic through DecisionPort; status transition authority changes (WP-05.8)

### Acceptance criteria
- [x] Pure deterministic EvidenceVector → DecisionResult pipeline
- [x] Hard constraints independent of RiskScore
- [x] Golden fixtures + comparison matrix
- [x] Shadow flag default off; failure isolation
- [x] Publication/reward/trust/availability unchanged
- [x] parking-service tests green

### Dependencies
- WP-05.4 complete

---

## WP-05.6 Decision Calibration & Shadow Analytics

**Status:** Complete (2026-07-27) — see [wp-05-decision-calibration-shadow-analytics.md](./wp-05-decision-calibration-shadow-analytics.md), [report template](./wp-05-decision-calibration-report-template.md).

### Objective
Measure and analyze the non-authoritative shadow Decision Engine (parity and drift) without migrating publication authority or auto-tuning thresholds.

### In-scope
- `DecisionCalibrationObservation` + classifiers (risk band, hard-constraint family, evidence profile, decisive rule, failure stage)
- Extended `DecisionShadowMetrics` / observer contract
- Prometheus recording rules + Grafana decision-shadow dashboard
- Offline `OfflineDecisionComparison`
- Authority-readiness checklist (product-approved placeholders)
- Calibration report template

### Out-of-scope
- Controlled authority migration (WP-05.8)
- Automatic threshold / policy-version changes
- Decision audit persistence (delivered in WP-05.7)
- Correctness metrics without ground truth

### Acceptance criteria
- [x] Immutable calibration observation with bounded enums
- [x] Exact metric names match `DecisionShadowMetrics`
- [x] Recording rules + dashboard without spot/event IDs
- [x] Ground-truth audit states parity/drift only (not correctness)
- [x] Authority-readiness checklist without invented % targets
- [x] Publication/reward/trust/availability unchanged

### Dependencies
- WP-05.5 complete

---

## WP-05.7 Decision Audit Store

**Status:** Complete (2026-07-28) — see [wp-05-decision-audit-store.md](./wp-05-decision-audit-store.md).

### Objective
Persist immutable, append-only canonical snapshots of **completed shadow** Decision Engine evaluations for offline replay, explainability, and version comparison — without granting publication authority.

### In-scope
- `DecisionAuditRecord` + factory / replay / version resolution
- Flyway `decision_audit` (`V20`) + JPA adapter
- Shadow orchestrator append after successful evaluation (failure-isolated)
- Bounded audit write/replay metrics
- Offline identical-policy replay producing identical `DecisionResult`

### Out-of-scope
- Controlled authority migration (WP-05.8)
- Authoritative decision persistence
- Public API / Kafka audit events
- Automatic threshold tuning
- Runtime multi-policy A/B

### Acceptance criteria
- [x] Immutable `DecisionAuditRecord`
- [x] Successful shadow evaluations append canonical snapshots
- [x] No raw AI/image/Kafka payloads
- [x] Offline replay with identical policy reproduces `DecisionResult`
- [x] Policy + engine versions persisted
- [x] Append-only; audit failure isolated
- [x] No authority / API / Kafka / reward / trust / availability change
- [x] parking-service tests green

### Dependencies
- WP-05.5 / WP-05.6 complete

---

## WP-05.8 Controlled Decision Authority Migration

### Objective
Introduce default-off, deterministic canary authority for Decision Engine FULL_PUBLISH while preserving legacy AI mapping for all other traffic.

### Status
Implemented in code with defaults **disabled / 0% canary**. Operational activation requires human-controlled config after calibration approval. See [wp-05-controlled-authority-migration.md](wp-05-controlled-authority-migration.md).

### In-scope
- [x] Authority configuration (default off)
- [x] Deterministic canary cohort
- [x] Eligibility + disposition matrix (FULL_PUBLISH only)
- [x] Transactional AUTHORITATIVE audit
- [x] Legacy fallback + rollback controls
- [x] Bounded metrics + PromQL + Grafana
- [x] Focused tests

### Out-of-scope
- Full cutover; LIMITED_PUBLISH UX; auto rollout; trust/availability/fraud

### Dependencies
- WP-05.6 / WP-05.7 complete

---

## WP-05.9 Availability Engine

### Objective
Encode occupancy-oriented availability explicitly, separate from publication decisions,
aligned with `ModerationPolicy.activeDuration` windows.

### Delivered (WP-05.9)
- Pure domain package `com.parkio.parking.availability` with `AvailabilityEngine`
- Configurable decay policy (`availability-v1`, basis-point thresholds)
- Clock-injected evaluation + offline replay via `AvailabilitySnapshot`
- `AvailabilityHistoryPort` boundary (noop persistence)
- Bounded Micrometer metrics via `AvailabilityMetrics`
- Documentation: [wp-05-availability-engine.md](./wp-05-availability-engine.md)

### Out-of-scope
- Search ranking/filter changes; hot-path orchestration; DB history; decision/authority changes

### Dependencies
- WP-05.8 complete

---

## WP-05.10 Outcome Validation

**Status:** Complete (2026-07-28) — see [wp-05-outcome-validation.md](./wp-05-outcome-validation.md).

### Objective
Answer “how did reality evolve after publication?” with a standalone read-only outcome domain — without deciding, publishing, calculating trust, or changing search/authority behavior.

### In-scope
- Pure domain package `com.parkio.parking.outcome` with `OutcomeValidationEngine`
- Configurable validation policy (`outcome-validation-v1`)
- Clock-injected evaluation + offline replay via `OutcomeSnapshot`
- `OutcomeHistoryPort` boundary (noop persistence)
- `OutcomeTrustConsumerPort` extension point (noop — WP-05.11)
- Bounded Micrometer metrics via `OutcomeValidationMetrics`
- Documentation: [wp-05-outcome-validation.md](./wp-05-outcome-validation.md)

### Out-of-scope
- Trust score calculation; decision/authority feedback; reward finalization
- Hot-path orchestration; DB history; search ranking changes

### Dependencies
- WP-05.9 complete

---

## WP-05.10A Outcome Operationalization

**Status:** Current (2026-07-28) — see [wp-05-outcome-operationalization.md](./wp-05-outcome-operationalization.md).

### Objective
Operationalize the read-only outcome engine with durable append-only history, deterministic trigger delivery, repository-backed evidence reads, and bounded observability without mutating trust, authority, search, or rewards.

### In-scope
- Durable `OutcomeHistoryPort` adapter + Flyway schema
- Deterministic trigger queue + scheduler drain
- Read ports for status history / verifications / spot snapshot
- Append-only replayable `OutcomeHistoryRecord`
- Prometheus rules + Grafana outcome dashboard

### Out-of-scope
- Trust Engine / rewards / search feedback
- Public APIs or Kafka contracts for outcomes
- Full scheduled window-closure sweeps

### Dependencies
- WP-05.10 complete

---

## WP-05.11 Trust Engine

**Status:** Complete (2026-07-28) - see [wp-05-trust-engine-shadow.md](./wp-05-trust-engine-shadow.md) and [wp-05-trust-verification-closure.md](./wp-05-trust-verification-closure.md).

### Objective
Introduce a shadow-only trust engine that learns evidential weight from durable validated outcomes without changing Decision, Authority, Availability, Search, Reward, Fraud, API, or Kafka behavior.

### Delivered (WP-05.11)
- Pure domain package `com.parkio.parking.trust` with deterministic `TrustEngine`
- Repository-backed reporter trust audit and canonical `ValidatedTrustEvidenceFactory`
- Durable `trust_ledger` + rebuildable `trust_snapshot`
- Outcome-history driven scheduler with trust-side idempotency
- Replay support, bounded Micrometer metrics, Prometheus rules, Grafana dashboard

### Out-of-scope
- Trust-based decision weighting or publication authority
- Reward/gamification mutations
- Public trust APIs, device trust, fraud classification

### Verification closure (WP-05.11A)
- PostgreSQL-backed migration proof for fresh and V22→V23 paths
- Real transaction/concurrency tests for `TrustShadowRowProcessor`
- Prometheus `promtool` validation and Grafana provisioning structural checks
- File-integrity / encoding checks for trust assets

---

## WP-05.12 Pending Reward Engine

**Status:** Complete (2026-07-28) - see [wp-05-pending-reward-engine-shadow.md](./wp-05-pending-reward-engine-shadow.md).

### Objective
Introduce a shadow-only pending reward ledger so validated contribution value is calculated from durable outcome history rather than paid on raw submission.

### Delivered (WP-05.12)
- Pure domain package `com.parkio.parking.reward` with deterministic `RewardEngine`
- Reporter-only repository-backed attribution via `ValidatedRewardContributionFactory`
- Durable `pending_reward_ledger` (`V24`) with append-only adapter semantics
- Outcome-history driven scheduler with reward-side idempotency
- Replay support, bounded Micrometer metrics, Prometheus rules, Grafana dashboard
- PostgreSQL-backed migration and concurrency verification

### Out-of-scope
- Real point grants, balance updates, level changes, achievement unlocks
- Verifier/claimant/filled-report rewards
- Trust-weighted reward math
- Public reward APIs, Kafka contracts, settlement, fraud

---

## WP-05.13 Adaptive Exposure Engine — Shadow Mode

### Objective
Given published nearby-search candidates and bounded request context, compute deterministic shadow exposure priority and compare against legacy PostGIS distance order without changing search output.

### Delivered (WP-05.13)
- Pure domain package `com.parkio.parking.exposure` with policy `exposure-policy-v1`
- Request-path orchestration after `ParkingApplicationService.searchNearby`
- Canonical evidence mapping from returned spots only (no N+1)
- Shadow comparison metrics (`sameTop1`, movement bands, disposition/score bands)
- Replay verification via `ExposureSnapshot` / `ExposureReplayer`
- Metrics-only persistence (no exposure audit migration in v1)
- Prometheus recording rules + Grafana dashboard + CI validation
- Architecture doc: `wp-05-adaptive-exposure-engine-shadow.md`

### Non-goals (unchanged)
- Real search reordering/filtering/suppression
- Reward/trust/gamification ranking inputs
- Public exposure APIs or Kafka changes

---

## WP-05.14 Fraud Intelligence Engine Shadow

### Objective
Standalone shadow-only fraud risk intelligence for attributable reporter
contribution patterns. Analytical only — no enforcement, moderation, or cross-domain mutation.

### Status
Complete (shadow-only, default disabled).

### Deliverables
- Pure domain `com.parkio.parking.fraud` (`FraudEngine`, policy, replay)
- Append-only `fraud_evaluation_ledger` (Flyway V25)
- Outcome-driven candidate discovery and scheduler (`FraudShadowJob`)
- Metrics, Prometheus recording rules, Grafana dashboard
- Documentation: [wp-05-fraud-intelligence-engine-shadow.md](wp-05-fraud-intelligence-engine-shadow.md)

### V1 scope
- Subject: reporter (`USER`) only
- Domain: `CONTRIBUTION_INTEGRITY` only
- Signal: repeated directly attributable confirmed-incorrect outcomes (outcome history)

### Non-goals (unchanged)
- No ban/suspend/restrict, no search/exposure/reward/trust mutation
- No IP/device fingerprinting, no public API/Kafka contract

---

## WP-05.15 Continuous Calibration & Policy Governance

### Objective
Cross-engine continuous calibration domain, Trust + Fraud batch pipeline, advisory
readiness assessment, and policy governance metadata — without policy activation
or automatic tuning.

### Status
Complete (shadow-only, default disabled).

### Deliverables
- Pure domain `com.parkio.parking.calibration` (`CalibrationReportGenerator`, `CalibrationReadinessAssessor`, `CalibrationReplayer`)
- Batch orchestration: `ContinuousCalibrationApplicationService`, `ContinuousCalibrationRowProcessor`, `ContinuousCalibrationJob`
- Append-only Flyway V26 tables (`calibration_observation`, `calibration_report`, `calibration_readiness_assessment`)
- Trust + Fraud batch calibration; Decision remains on WP-05.6 `decision.calibration`
- Metrics (`ContinuousCalibrationMetrics`), Prometheus recording rules, Grafana dashboard
- Integration tests: `CalibrationShadowMigrationPostgresIT`, `FraudShadowPersistencePostgresIT`
- Documentation: [wp-05-continuous-calibration-policy-governance.md](wp-05-continuous-calibration-policy-governance.md)

### Non-goals (unchanged)
- No policy activation, no automatic threshold tuning
- No new calibration microservice
- Schedulers default disabled (`parkio.lifecycle.calibration.enabled=false`)

---

## Roadmap summary

| WP | Title | Status |
|----|-------|--------|
| 05.1 | Current-State Audit & ADR | Complete |
| 05.2 | Canonical Decision Domain Model | Complete |
| 05.3 | Evidence Collection & Normalization | Complete |
| 05.4 | Evidence Evaluation Model | Complete |
| 05.5 | Decision Engine v1 Shadow Mode | Complete |
| 05.6 | Decision Calibration & Shadow Analytics | Complete |
| 05.7 | Decision Audit Store | Complete |
| 05.8 | Controlled Decision Authority Migration | Complete |
| 05.9 | Availability Engine | Complete |
| 05.10 | Outcome Validation | Complete |
| 05.11 | Trust Engine | Complete |
| 05.12 | Pending Reward Engine | Complete |
| 05.13 | Adaptive Exposure | Complete |
| 05.14 | Fraud Intelligence Engine Shadow | Complete |
| 05.15 | Continuous Calibration & Policy Governance | Complete |

---

## WP-06 — Operational Platform

### WP-06.1 Operational Readiness & Production Governance

**Status:** Current (governance evidence; does not claim production launch).

**Deliverables:**
- Operations hub: [docs/operations/wp-06-01-operational-readiness-production-governance.md](../../operations/wp-06-01-operational-readiness-production-governance.md)
- Service criticality, journeys, SLO catalogue, error budget, kill switches, rollback, incident model
- 20 incident runbooks under `docs/operations/runbooks/`
- Prometheus `operational-readiness-*-rules.yml`, Grafana `parkio-operational-readiness.json`
- `OperationalReadinessGovernanceTest` structural validation

**Non-goals:** No authority expansion, no scheduler default enablement, no operations microservice.

### WP-06.2 Staging Verification, Backup/Restore Automation & Runtime Baseline

**Status:** PARTIALLY_CLOSED (see [WP-06.2A closure](../operations/wp-06-02a-staging-verification-closure.md); staging evidence only — does not claim production launch).

**Deliverables:**
- Hub: [docs/operations/wp-06-02-staging-verification-backup-restore-runtime-baseline.md](../../operations/wp-06-02-staging-verification-backup-restore-runtime-baseline.md)
- Closure: [docs/operations/wp-06-02a-staging-verification-closure.md](../../operations/wp-06-02a-staging-verification-closure.md)
- Staging safety guards and pipeline under `scripts/staging/`
- Evidence schema: `docs/operations/evidence/operational-evidence-schema.json`
- Compose overlays: `docker/docker-compose.staging-verification.yml`, `docker-compose.restore-drill.yml`
- CI: `.github/workflows/staging-verification.yml`, `runtime-baseline.yml`
- Gateway global downstream timeouts in `gateway-service` `application.yml`
- `StagingVerificationGovernanceTest`, `GatewayDownstreamTimeoutGovernanceTest`

**Non-goals:** No production deployment, no SLO/RPO/RTO approval, no authority expansion.

### WP-06.2A Staging Verification Closure

**Status:** Current (verification closure pass; PARTIALLY_CLOSED — see closure doc).

**Deliverables:** Executed regression matrix, isolated pipeline evidence, MinIO round-trip automation fixes, semantic integrity fixes, UTF-8 workflow encoding fix, closure matrix in `wp-06-02a-staging-verification-closure.md`.


### WP-06.2A.1 Application-Level Staging Verification Closure Patch

**Status:** Current closure patch (application journeys + JWKS/parser; does not claim production launch).

**Deliverables:**
- Closure: [docs/operations/wp-06-02a-1-application-verification-closure-patch.md](../../operations/wp-06-02a-1-application-verification-closure-patch.md)
- scripts/staging/lib/json-helper.sh, hardened run-critical-journeys.sh, verify-restored-application-apis.sh
- Smoke no longer depends on undeclared `jq`
- Evidence status `APPLICATION_VERIFICATION_SUCCEEDED`

### WP-06.2B Shared Staging Sign-off & Restored-Database Application Verification

**Status:** TECHNICALLY_VERIFIED_SIGNOFF_REQUIRED (final-state evidence `wp062b2-20260729073440`; historical `wp062b-20260728211226` preserved).
See [WP-06.2B](../operations/wp-06-02b-shared-staging-signoff-restored-database-verification.md), [WP-06.2B.1](../operations/wp-06-02b-1-evidence-finalization-signoff-preparation.md), and [WP-06.2B.2](../operations/wp-06-02b-2-final-state-reexecution-signoff-gate.md).
Does **not** claim production readiness.

### WP-06.2B.1 Verification Evidence Finalization & Human Sign-off Preparation

**Status:** Complete (evidence finalization; not a substitute for final-state re-execution).
See [WP-06.2B.1](../operations/wp-06-02b-1-evidence-finalization-signoff-preparation.md).

### WP-06.2B.2 Final-State Re-execution & Human Sign-off Gate

**Status:** Current (final-state LOCAL_REPRESENTATIVE re-execution; human sign-off still NOT_REVIEWED).
See [WP-06.2B.2](../operations/wp-06-02b-2-final-state-reexecution-signoff-gate.md).

**Deliverables:**
- Post-historical change audit vs `wp062b-20260728211226`
- Defensible ExposureShadow time-budget test resolution (no unjustified 5000 ms)
- Docker-backed integrationTest + full regression
- Fresh run `wp062b2-20260729073440` with live cleanup revalidation
- Historical vs final comparison; human sign-off package defaulting to `NOT_REVIEWED`

Does **not** approve WP-06.3. Does **not** claim production readiness or shared-staging execution.

**WP-06.3:** NOT_ELIGIBLE until human `APPROVED_FOR_WP_06_3` or `APPROVED_WITH_WAIVER` — not started.
Future WP-06 packages (WP-06.3 deployment automation, WP-06.4 secrets hardening, WP-06.5 production launch PRR) — **not started**.

