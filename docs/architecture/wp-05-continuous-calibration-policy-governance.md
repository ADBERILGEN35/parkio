# WP-05.15 Continuous Calibration & Policy Governance

**Status:** Complete (2026-07-28)  
**Calibration policy version:** `calibration-policy-v1`  
**Authority:** unchanged — no engine policy activation, no automatic tuning, schedulers default disabled.  
**Related:** [WP-05.6 Decision Calibration](./wp-05-decision-calibration-shadow-analytics.md), [WP-05.11 Trust Shadow](./wp-05-trust-engine-shadow.md), [WP-05.14 Fraud Shadow](./wp-05-fraud-intelligence-engine-shadow.md), [Implementation plan](./wp-05-implementation-plan.md), [Observability metrics](./observability-metrics.md)

---

## Executive summary

WP-05.15 introduces a framework-free continuous calibration domain in
`com.parkio.parking.calibration` and wires batch observation ingestion for **Trust**
and **Fraud** shadow engines inside `parking-service`. The module aggregates
labeled prediction–outcome pairs into cohort-scoped reports, assesses readiness
against `calibration-policy-v1`, verifies deterministic replay, and persists
append-only artifacts in Flyway `V26__continuous_calibration.sql`.

Decision calibration remains in the separate WP-05.6 path
(`com.parkio.parking.decision.calibration`). Availability, Outcome, Reward, and
Exposure engines contribute labels or metrics-only shadow signals but are not yet
on the continuous-calibration batch pipeline.

No policy activation, no automatic threshold tuning, and no authority migration
are performed. The scheduler (`ContinuousCalibrationJob`) is gated by
`parkio.lifecycle.calibration.enabled=false` by default.

---

## Scope and non-goals

**In scope (v1)**

- Pure domain package `com.parkio.parking.calibration` (no Spring/JPA/Kafka)
- Trust + Fraud batch calibration via `ContinuousCalibrationApplicationService`
- Deterministic UUID identity (`UUID.nameUUIDFromBytes`)
- Cohort-scoped reports, readiness assessment, snapshot replay verification
- Append-only persistence (`calibration_observation`, `calibration_report`, `calibration_readiness_assessment`)
- Micrometer metrics, Prometheus recording rules, Grafana dashboard
- Policy governance metadata types (`PolicyGovernanceDescriptor`)
- Integration tests: unit suite, `CalibrationShadowMigrationPostgresIT`, `FraudShadowPersistencePostgresIT`

**Out of scope / non-goals**

- New calibration microservice
- Policy activation or automatic tuning in production
- Reward, Exposure, Availability, Outcome, or Decision batch ingestion (Decision uses WP-05.6)
- Public APIs, Kafka contract changes, or user-visible calibration labels
- ML model training or external vendor integration
- Fairness/privacy review automation (flags only; manual review required)
- Canary or authority rollout execution

---

## Repository state

| Area | State |
|---|---|
| Domain | `services/parking-service/src/main/java/com/parkio/parking/calibration/*` — 33 types, framework-free |
| Application | `ContinuousCalibrationApplicationService`, `ContinuousCalibrationRowProcessor`, ports under `application/port` |
| Infrastructure | JPA adapters, `ContinuousCalibrationMetrics`, `ContinuousCalibrationJob` |
| Migration | `V26__continuous_calibration.sql` (depends on V25 fraud ledger) |
| Decision calibration | Separate package `com.parkio.parking.decision.calibration` (WP-05.6) |
| Tests | `CalibrationReportGeneratorTest`, `ContinuousCalibrationApplicationServiceTest`, `CalibrationPackageIndependenceTest`, `CalibrationShadowMigrationPostgresIT`, `FraudShadowPersistencePostgresIT` |
| Observability | `ContinuousCalibrationMetrics`, recording rules, Grafana dashboard |
| Scheduler | Default disabled (`parkio.lifecycle.calibration.enabled=false`) |

---

## Cross-engine calibration readiness matrix

| Engine | Classification | Batch pipeline | Ground-truth source | Notes |
|---|---|---|---|---|
| **Decision** | `METRICS_ONLY` | WP-05.6 shadow analytics only | No verified outcome labels in v1 | Parity/drift via `DecisionCalibrationObservation`; not on continuous batch |
| **Availability** | `METRICS_ONLY` | Not wired | N/A | Shadow metrics only; no labeled observations |
| **Outcome** | `PARTIALLY_READY` | Label provider only | Self-validated outcome history | Supplies labels for Trust/Fraud; not a calibration consumer |
| **Trust** | `CALIBRATION_READY` | `processTrustBatch` | Outcome history via `TrustOutcomeCalibrationReadPort` | MEDIUM_TERM horizon; band calibration supported |
| **Reward** | `METRICS_ONLY` | Not wired | N/A | Shadow ledger only; reward intent not outcome-labeled |
| **Exposure** | `METRICS_ONLY` | Not wired | N/A | Rank-movement shadow comparison; no classification labels |
| **Fraud** | `CALIBRATION_READY` | `processFraudBatch` | Outcome history + operational dispositions | SHORT_TERM horizon; reviewed dispositions → `NOT_APPLICABLE` |

Classification legend:

- `CALIBRATION_READY` — batch observation, report, readiness, replay, persistence
- `PARTIALLY_READY` — contributes labels or partial signals; not a full consumer
- `METRICS_ONLY` — shadow observability without continuous-calibration artifacts
- `DEFERRED` — intentionally excluded from v1 (none of the seven engines use this in v1)

---

## Calibration ownership boundary

Continuous calibration lives **inside parking-service** at
`com.parkio.parking.calibration`. It is not a new microservice.

Responsibilities:

- Normalize prediction + label pairs into `CalibrationObservation`
- Generate cohort reports and readiness assessments
- Replay snapshots for determinism verification
- Emit bounded metrics

It does **not** own:

- Engine evaluation logic (Trust, Fraud, Decision, etc.)
- Policy activation or authority migration (WP-05.8)
- Outcome validation execution (WP-05.10)
- Enforcement, moderation, or publication mutation

Application orchestration sits in `com.parkio.parking.application` with
read/write ports; infrastructure adapters implement persistence and metrics.

---

## Calibration versus authority

| Concern | Calibration (WP-05.15) | Authority (WP-05.8+) |
|---|---|---|
| Purpose | Measure prediction quality vs labels | Execute production decisions |
| Mutates user-visible state | Never | When enabled |
| Policy changes | Read-only observation of engine policy versions | Requires controlled migration |
| Scheduler default | Disabled | Per-engine shadow/authority flags |
| Readiness output | Advisory `CalibrationReadinessAssessment` | Operational rollout gates (manual) |

Calibration observes shadow and ledger outputs; it never replaces
`ParkingApplicationService`, `DecisionPort`, or any authoritative write path.

---

## Ground-truth and label policy

Labels are sourced only from durable, attributable records:

| Source | Used by | Category mapping |
|---|---|---|
| `OutcomeHistoryRecord` | Trust, Fraud | `CONFIRMED_CORRECT`/`LIKELY_CORRECT` → POSITIVE; direct `CONFIRMED_INCORRECT` → NEGATIVE; ambiguous → NEUTRAL |
| Operational fraud disposition | Fraud | `REVIEW_CANDIDATE`/`ELEVATED_RISK` → `NOT_APPLICABLE` (does not count toward classification) |
| Not available | — | `CalibrationLabelSource.NOT_AVAILABLE` → unlabeled |

Label quality follows attribution:

- `DIRECT` — strong causal link (e.g., direct confirmed-incorrect)
- `STRONG`, `PARTIAL`, `AMBIGUOUS`, `NONE` — degraded confidence

Finality:

- POSITIVE/NEGATIVE with direct attribution → `FINAL`
- NEUTRAL/UNKNOWN → `PROVISIONAL`

Labels with `NOT_APPLICABLE` or `NOT_AVAILABLE` do not count toward precision/recall denominators.

---

## Engine-specific calibration semantics

### Decision

Handled by WP-05.6 (`com.parkio.parking.decision.calibration`). Emits
`DecisionCalibrationObservation` for parity analytics. No precision/recall
without verified ground truth. Not ingested by `ContinuousCalibrationApplicationService`.

### Availability

Shadow-only availability signals. No labeled observations in continuous calibration v1.

### Outcome

Produces validated outcome classifications consumed as labels by Trust and Fraud.
Does not produce its own calibration reports in v1.

### Trust

- Pairs trust evaluations with outcome labels via `TrustOutcomeCalibrationPair`
- Horizon: `MEDIUM_TERM`
- Cohort key: engine + `trustPolicyVersion` + trust level band + label category + horizon
- Predicted category derived from trust level band (`HIGH_CONFIDENCE`/`ESTABLISHED` → POSITIVE, etc.)
- Band calibration metrics (`OBSERVED_POSITIVE_RATE_BY_BAND`) enabled

### Reward

Shadow pending-reward intents only. No outcome-linked reward fulfillment labels in v1.

### Exposure

Search rank shadow comparison metrics. No classification labels; not on batch pipeline.

### Fraud

- Pairs fraud ledger evaluations with outcome labels via `FraudLedgerCalibrationCandidate`
- Horizon: `SHORT_TERM`
- Reviewed fraud dispositions excluded from classification (`NOT_APPLICABLE`)
- Provisional NEUTRAL labels for non-reviewed rows
- Band calibration by `FraudRiskBand`

---

## Calibration domain model

Key types in `com.parkio.parking.calibration`:

| Type | Role |
|---|---|
| `CalibrationEngineType` | Enum: DECISION, AVAILABILITY, OUTCOME, TRUST, REWARD, EXPOSURE, FRAUD |
| `CalibrationObservation` | Immutable prediction + label pair with cohort and timestamps |
| `CalibrationPrediction` | Engine policy/schema/mapping/aggregation versions + predicted band/category |
| `CalibrationLabel` | Category, source, quality, finality, source record id, labeled-at |
| `CalibrationLabelCategory` | POSITIVE, NEGATIVE, NEUTRAL, UNKNOWN, NOT_APPLICABLE |
| `CalibrationLabelSource` | OUTCOME_HISTORY, OPERATIONAL_METRIC, NOT_AVAILABLE |
| `CalibrationLabelQuality` | DIRECT, STRONG, PARTIAL, AMBIGUOUS, NONE |
| `CalibrationLabelFinality` | FINAL, PROVISIONAL |
| `CalibrationAttributionQuality` | Attribution strength for label mapping |
| `CalibrationCohortKey` | Canonical five-part cohort identity |
| `CalibrationObservationHorizon` | AT_EVALUATION, SHORT_TERM, MEDIUM_TERM |
| `CalibrationWindow` | Batch time window (start/end instants) |
| `CalibrationReport` | Generated metrics for a cohort window |
| `CalibrationReportStatus` | GENERATED, INSUFFICIENT_DATA, FAILED |
| `CalibrationMetricType` | PRECISION, RECALL, LABEL_COVERAGE, DRIFT_STATUS, etc. |
| `CalibrationMetricValue` | Ratio in basis points with applicability |
| `CalibrationMetricApplicability` | APPLICABLE, NOT_APPLICABLE, INSUFFICIENT_DATA |
| `CalibrationPolicyConfig` | Threshold policy (`calibration-policy-v1`) |
| `CalibrationReadinessAssessment` | Advisory readiness outcome |
| `CalibrationReadinessStatus` | INSUFFICIENT_DATA through READY_FOR_CONTROLLED_CANARY_REVIEW |
| `CalibrationReadinessReason` | Blocking/pass reasons |
| `CalibrationSnapshot` | Replay bundle (policy + report + observations) |
| `CalibrationSnapshotSchemaVersion` | V1 |
| `CalibrationMappingVersion` | V1 |
| `CalibrationReplayComparison` | Original vs replayed report diff |
| `CalibrationComparisonResult` | IMPROVED, REGRESSED, INCONCLUSIVE, NOT_APPLICABLE |
| `PolicyGovernanceDescriptor` | Engine policy lifecycle metadata |
| `PolicyLifecycleStatus` | EXPERIMENTAL through REPLAY_ONLY |
| `CalibrationReportGenerator` | Pure report builder |
| `CalibrationReadinessAssessor` | Pure readiness evaluator |
| `CalibrationReplayer` | Deterministic replay and baseline/candidate compare |

---

## Observation model

Each `CalibrationObservation` contains:

- `observationId` — deterministic UUID
- `engineType` — TRUST or FRAUD in v1 batch
- `prediction` — versioned prediction snapshot
- `label` — ground-truth or provisional label
- `horizon` — observation maturity window
- `cohortKey` — canonical string for aggregation
- `attributionQuality` — label trust level
- `completenessBasisPoints` — 10_000 (full) in v1
- `predictedAt` — engine evaluation timestamp
- `labeledAt` — label availability timestamp
- `createdAt` — observation append time

Observations are immutable once appended. Updates are expressed as new rows with
new deterministic ids (duplicate logical keys are rejected).

---

## Deterministic identity

All primary keys are derived via `UUID.nameUUIDFromBytes` in
`ContinuousCalibrationApplicationService`:

| Entity | Material string |
|---|---|
| Observation | `calibration-observation-v1\|{engine}\|{evaluationId}\|{sourceOutcomeId}` |
| Report | `calibration-report-v1\|{engine}\|{policyVersion}\|{windowEnd}\|{watermark}\|{calibrationPolicyVersion}\|{cohortKey}` |
| Readiness assessment | `calibration-readiness-v1\|{reportId}` |

Properties:

- Idempotent reprocessing produces identical UUIDs
- Duplicate appends hit unique constraints and increment duplicate metrics
- No random UUID generation in the calibration path

---

## Observation horizons

| Horizon | Duration name | Used by |
|---|---|---|
| `AT_EVALUATION` | (none) | Fallback cohort parsing |
| `SHORT_TERM` | `short-term` | Fraud |
| `MEDIUM_TERM` | `medium-term` | Trust |

Horizons encode how long after prediction a label is considered valid for
calibration. Trust uses medium-term outcome maturity; fraud uses short-term
ledger-to-outcome linkage.

---

## Cohort model and cardinality control

Cohort key format (canonical):

```text
{engineType}|{policyVersion}|{predictionBand}|{labelCategory or *}|{horizon}
```

Example:

```text
TRUST|trust-policy-v1|HIGH_CONFIDENCE|POSITIVE|MEDIUM_TERM
```

Cardinality controls:

- Five bounded dimensions; no spot/user/id in cohort keys
- Reports generated per distinct cohort in a batch window (not global cross-cohort)
- Band calibration split by `predictionBand` within engine policy version
- Wildcard `*` for label category when aggregating across categories

Micrometer tags never include cohort key values — engine type and bounded enums only.

---

## Metric applicability model

`CalibrationMetricApplicability` governs each metric slot:

| Value | Meaning |
|---|---|
| `APPLICABLE` | Metric computed with valid numerator/denominator |
| `NOT_APPLICABLE` | Metric does not apply to this engine/cohort (e.g., band calibration on Decision) |
| `INSUFFICIENT_DATA` | Applicable but denominator below policy minimum |

Examples:

- Classification metrics when no POSITIVE/NEGATIVE labels → insufficient or N/A
- `OBSERVED_POSITIVE_RATE_BY_BAND` on non-Trust/Fraud engines → `NOT_APPLICABLE`
- Report with `< minimumObservations` → report status `INSUFFICIENT_DATA`; metrics marked accordingly

---

## Confusion-matrix semantics

Classification metrics derive from a standard confusion matrix over observations
where `label.countsTowardClassificationMetrics()` is true (POSITIVE or NEGATIVE only):

| Cell | Condition |
|---|---|
| True positive | actual POSITIVE and predicted positive |
| False negative | actual POSITIVE and not predicted positive |
| False positive | actual not POSITIVE and predicted positive |
| True negative | actual not POSITIVE and not predicted positive |

Predicted positive categories: `POSITIVE`, `HIGH`, `ELEVATED`, `CRITICAL`.

Derived metrics:

- **Precision** — TP / (TP + FP)
- **Recall** — TP / (TP + FN)
- **Specificity** — TN / (TN + FP)
- **False positive rate** — FP / (FP + TN)

Denominators below `minimumLabeledObservations` yield `INSUFFICIENT_DATA`.

---

## Calibration curves

Band calibration (`OBSERVED_POSITIVE_RATE_BY_BAND`) approximates calibration
curves per prediction band:

- For Trust and Fraud only
- Computes observed positive rate per `predictedBand`
- Requires `bandCalibrationMinimumObservations` (5) per band
- Bands with insufficient counts → `INSUFFICIENT_DATA`
- No bands with labeled observations → `NOT_APPLICABLE`

Full continuous calibration curves (reliability diagrams across score bins) are
deferred; v1 uses discrete band buckets only.

---

## Drift model

`DRIFT_STATUS` metric in `CalibrationReportGenerator`:

| Condition | Value |
|---|---|
| No baseline/candidate policy versions | `NOT_APPLICABLE` |
| Baseline equals candidate | `stable` (ratio 0/1) |
| Baseline differs from candidate | `candidate_differs` (ratio 1/1) |
| Insufficient report data | `INSUFFICIENT_DATA` |

v1 batch reports pass `Optional.empty()` for baseline and candidate — drift is
`NOT_APPLICABLE` until explicit A/B policy comparison is wired.

Temporal drift (metric degradation over sliding windows) is deferred to future
policy registry integration.

---

## Baseline/candidate version comparison

`CalibrationReplayer.compareReports(baseline, candidate, policyConfig)` compares
precision basis points:

| Result | Condition |
|---|---|
| `IMPROVED` | candidate precision > baseline |
| `REGRESSED` | candidate precision < baseline |
| `INCONCLUSIVE` | equal precision or insufficient data |
| `NOT_APPLICABLE` | different engine types |

Report generation accepts optional `baselinePolicyVersion` and
`candidatePolicyVersion` for future canary analysis. Current Trust/Fraud batch
path generates single-policy reports (candidate empty).

---

## Policy registry/governance metadata

`PolicyGovernanceDescriptor` records governance metadata per engine policy:

```java
record PolicyGovernanceDescriptor(
    CalibrationEngineType engineType,
    String policyVersion,
    PolicyLifecycleStatus lifecycleStatus,
    String authorityScope,
    String documentationRef)
```

`PolicyLifecycleStatus` values: EXPERIMENTAL, SHADOW, CANARY, AUTHORITATIVE_LIMITED,
AUTHORITATIVE, RETIRED, REPLAY_ONLY.

v1 defines the type for documentation and future registry wiring; no runtime
policy registry service is deployed. Engine policy versions are read from shadow
ledger rows at observation build time.

---

## Readiness assessment

`CalibrationReadinessAssessor.assess` evaluates a `CalibrationReport` against
`CalibrationPolicyConfig` and `OperationalFlags`:

Checks include:

- Minimum observations and labeled observations
- Label coverage basis points
- Replay match rate vs mismatch threshold
- Classification metric sufficiency
- Operational verification, privacy review, fairness review flags
- Regression detection flag

Output statuses include:

- `INSUFFICIENT_DATA`, `NOT_READY`, `OPERATIONALLY_UNVERIFIED`
- `CALIBRATION_INCOMPLETE`, `REGRESSION_DETECTED`
- `FAIRNESS_REVIEW_REQUIRED`, `PRIVACY_REVIEW_REQUIRED`
- `READY_FOR_SHADOW_EXPANSION`, `READY_FOR_CONTROLLED_CANARY_REVIEW`
- `STABLE`, `INCONCLUSIVE`

v1 passes all-false operational flags — assessments remain advisory and blocked
on operational/privacy/fairness gates.

---

## Readiness policy

`CalibrationPolicyConfig.referenceV1()` (`calibration-policy-v1`):

| Threshold | Value |
|---|---|
| `minimumObservations` | 10 |
| `minimumLabeledObservations` | 5 |
| `minimumLabelCoverageBasisPoints` | 5_000 (50%) |
| `minimumReplayMatchRateBasisPoints` | 9_900 (99%) |
| `maximumReplayMismatchBasisPoints` | 100 (1%) |
| `minimumPrecisionBasisPoints` | 7_000 (70%) |
| `minimumRecallBasisPoints` | 7_000 (70%) |
| `bandCalibrationMinimumObservations` | 5 |

Unsupported policy versions throw `UnsupportedCalibrationPolicyVersionException`.

---

## Statistical safety

Safeguards:

- Minimum sample sizes before classification metrics are `APPLICABLE`
- Basis-point ratios (10_000 denominator) avoid floating-point drift
- Monotonic threshold validation in `CalibrationPolicyConfig` constructor
- Provisional labels excluded from classification denominators
- `NOT_APPLICABLE` fraud dispositions excluded from false-positive inflation
- Report status `INSUFFICIENT_DATA` when cohort below minimum observations
- No extrapolation or smoothing across sparse cohorts

Statistical significance testing (chi-square, confidence intervals) is deferred.

---

## Fairness/bias review

`CalibrationReadinessAssessor` requires `fairnessReviewComplete` and
`privacyReviewComplete` operational flags before canary readiness.

v1 does not compute demographic parity or geographic bias metrics. Cohort keys
exclude user/geo dimensions intentionally. Fairness review is a manual gate
documented here; automation is deferred.

Privacy-sensitive signals (IP/device) remain excluded per WP-05.14 audit.

---

## Architecture deployment decision

**Deploy inside parking-service** as a domain module + application service.

Rationale:

- Calibration reads existing trust/fraud ledger tables — no cross-service latency
- Shared Flyway migration lifecycle with shadow engines
- Consistent with WP-05.x shadow-engine placement ADR
- Avoids operational overhead of a dedicated calibration microservice before volume justifies split

Future extraction boundary: if calibration consumers multiply beyond parking-service,
ports already isolate read/write boundaries for later service extraction.

---

## Source read ports

| Port | Adapter | Purpose |
|---|---|---|
| `TrustOutcomeCalibrationReadPort` | `TrustOutcomeCalibrationReadRepositoryAdapter` | Unobserved trust evaluation + outcome pairs |
| `FraudLedgerCalibrationReadPort` | `FraudLedgerCalibrationReadRepositoryAdapter` | Unobserved fraud ledger evaluations |
| `CalibrationObservationPort` | JPA adapter | Append/find observations |
| `CalibrationReportPort` | JPA adapter | Append reports |
| `CalibrationReadinessPort` | JPA adapter | Append readiness assessments |

Read queries exclude rows already linked via `(engine_type, source_evaluation_id, label_source_id)` unique constraint.

---

## Orchestration

**`ContinuousCalibrationApplicationService`**

- `processTrustBatch(limit)` / `processFraudBatch(limit)`
- Builds observations, appends, generates per-cohort reports + readiness + replay verify
- Emits metrics via `ContinuousCalibrationObserverPort`

**`ContinuousCalibrationRowProcessor`**

- Wraps each batch in `PROPAGATION_REQUIRES_NEW` transaction
- Configurable `parkio.lifecycle.calibration.max-attempts` (default 3)

**`ContinuousCalibrationJob`**

- `@ConditionalOnProperty(name = "parkio.lifecycle.calibration.enabled", havingValue = "true", matchIfMissing = false)`
- `@Scheduled(fixedDelayString = "${parkio.lifecycle.calibration.fixed-delay-ms:60000}")`
- Processes trust then fraud batches per tick
- **Default disabled** — must explicitly enable for non-production experimentation

---

## Persistence decision

Flyway `V26__continuous_calibration.sql` introduces three append-only tables.
No update or delete paths in application code. Payload JSON columns store full
domain snapshots for audit and replay.

Primary storage remains PostgreSQL in the parking-service database.

---

## Flyway/database design

**`calibration_observation`**

- PK `id`, unique `observation_id`
- Unique logical key: `(engine_type, source_evaluation_id, label_source_id)`
- Indexes on `(engine_type, predicted_at)` and `(cohort_key, predicted_at)`

**`calibration_report`**

- Unique `report_id`
- Unique logical key: `(engine_type, window_end, cohort_key, calibration_policy_version)`
- Check constraints: non-negative counts, labeled ≤ observation count
- FK from readiness assessment → report

**`calibration_readiness_assessment`**

- Unique `assessment_id`
- FK to `calibration_report.report_id`

Migration IT verifies V25→V26 upgrade path and constraint presence.

---

## Append-only guarantees

- Application ports expose `append` only (no update/delete methods)
- Unique constraints enforce idempotent inserts
- Duplicate observations increment `observation.duplicate` metric; processing continues
- Duplicate reports increment `report.duplicate` metric
- Database triggers for immutability not required — enforced by application layer and ops policy

---

## Source watermarks

Each report stores `sourceWatermark` — the latest `labeledAt` timestamp among
cohort observations in the window. Used for:

- Incremental read-port cursoring (future)
- Audit trail of label freshness at report generation time
- Deterministic report id material includes watermark

---

## Idempotency

| Layer | Mechanism |
|---|---|
| Observation | Deterministic UUID + unique `(engine, evaluation, label source)` |
| Report | Deterministic UUID + unique `(engine, window_end, cohort, calibration policy)` |
| Assessment | Deterministic UUID derived from report id |
| Scheduler retry | Safe — duplicates counted, not double-applied |

---

## Scheduler

Configuration properties:

| Property | Default | Meaning |
|---|---|---|
| `parkio.lifecycle.calibration.enabled` | `false` | Master switch |
| `parkio.lifecycle.calibration.batch-size` | `100` | Candidates per engine per tick |
| `parkio.lifecycle.calibration.fixed-delay-ms` | `60000` | Delay between ticks |
| `parkio.lifecycle.calibration.max-attempts` | `3` | Transaction retry budget |

Scheduler failures increment `parkio.parking.calibration.scheduler.failed` without
affecting source shadow engines.

---

## Transactions/concurrency

- Each batch runs in `REQUIRES_NEW` transaction via `ContinuousCalibrationRowProcessor`
- Observation append and report generation share the batch transaction scope in the service method chain
- Concurrent scheduler instances may race on same candidates — unique constraints resolve to duplicate metrics
- `FraudShadowPersistencePostgresIT` validates concurrent fraud ledger appends independently

---

## Replay/versioning

**`CalibrationReplayer`**

- `replayReport(snapshot, replayedAt)` — regenerates report from snapshot
- `replayAndCompare(snapshot, replayedAt)` — structural equality check
- Mismatch increments `replay.mismatch` metric; failure aborts batch finalization

Snapshot includes:

- `CalibrationSnapshotSchemaVersion.V1`
- `CalibrationMappingVersion.V1`
- Policy config, report, full observation list

Replay verification runs on every successful report generation before readiness append.

---

## Fraud persistence verification closure

`FraudShadowPersistencePostgresIT` closes the WP-05.14 fraud ledger verification gap:

- End-to-end fraud shadow row processing against real PostgreSQL
- Concurrent append idempotency
- `FraudReplayer` snapshot parity
- Confirms fraud ledger rows are durable inputs for `FraudLedgerCalibrationReadPort`

This IT plus `CalibrationShadowMigrationPostgresIT` validates the V25→V26
persistence stack required for continuous calibration.

---

## Metrics catalogue

Micrometer component: `ContinuousCalibrationMetrics`
(`com.parkio.parking.infrastructure.metrics`).

Bounded tags: `engine_type`, `failure_stage`, `report_status`, `readiness_status`,
`status` (processing result). Never spot/evaluation/cohort/user ids.

| Metric | Type | Meaning |
|---|---|---|
| `parkio.parking.calibration.scheduler.candidates` | summary | Candidates claimed per tick |
| `parkio.parking.calibration.candidate.received` | counter | Candidate received |
| `parkio.parking.calibration.observation.success` | counter | Observation appended |
| `parkio.parking.calibration.observation.appended` | counter | Same (explicit append) |
| `parkio.parking.calibration.observation.duplicate` | counter | Idempotent duplicate |
| `parkio.parking.calibration.observation.failure` | counter | Append/build failure |
| `parkio.parking.calibration.report.generated` | counter | Report created |
| `parkio.parking.calibration.report.duplicate` | counter | Idempotent report duplicate |
| `parkio.parking.calibration.report.failure` | counter | Report generation failure |
| `parkio.parking.calibration.report.duration` | timer | Report generation latency |
| `parkio.parking.calibration.readiness.assessed` | counter | Readiness assessment written |
| `parkio.parking.calibration.replay.success` | counter | Replay matched |
| `parkio.parking.calibration.replay.mismatch` | counter | Replay differed |
| `parkio.parking.calibration.replay.failure` | counter | Replay exception |
| `parkio.parking.calibration.scheduler.completed` | counter | Rows completed per tick |
| `parkio.parking.calibration.scheduler.failed` | counter | Scheduler tick failure |
| `parkio.parking.calibration.processing.result` | counter | Batch outcome by status |

---

## Prometheus ratio definitions

File: `docker/prometheus/continuous-calibration-recording-rules.yml`

| Recording rule | Expression |
|---|---|
| `parkio:continuous_calibration:success_rate5m` | observation_success / candidate_received |
| `parkio:continuous_calibration:duplicate_rate5m` | observation_duplicate / candidate_received |
| `parkio:continuous_calibration:failure_rate5m` | observation_failure / candidate_received |
| `parkio:continuous_calibration:replay_mismatch_rate5m` | replay_mismatch / observation_success |

Mounted in `docker/prometheus/prometheus.yml` and `docker/docker-compose.yml`.

---

## Grafana dashboards

Dashboard: `docker/grafana/provisioning/dashboards/parkio-continuous-calibration.json`

- UID: `parkio-continuous-calibration`
- Panels: success rate, duplicate rate, failure rate, replay mismatch rate (5m recording rules)
- Refresh: 30s
- Provisioned via `docker/grafana/provisioning/dashboards/dashboards.yml`

---

## Security/privacy

- No PII in metrics tags or cohort keys
- Observation payload JSON stored internally; not exposed via public API
- Label source ids are internal UUIDs only
- Privacy review flag required before canary readiness
- Calibration scheduler disabled by default in all environments unless explicitly enabled

---

## Failure isolation

- Observation build/append failures increment failure metrics; other candidates in batch continue
- Report failure returns `CalibrationProcessingResult.failed` for the batch
- Scheduler try/catch prevents tick failure from crashing the service
- Trust batch failure does not block fraud batch in same tick (separate processor calls)
- Source shadow engines unaffected by calibration failures

---

## Backward-compatibility proof

- V26 migration additive only — no alterations to pre-V26 tables
- `CalibrationShadowMigrationPostgresIT` proves V25→V26 clean upgrade
- `CalibrationPackageIndependenceTest` forbids Spring/JPA imports in domain package
- Default-disabled scheduler — zero behavior change when flag unset
- Decision calibration (WP-05.6) unchanged — separate package and metrics
- Unit and integration test suites pass

---

## Exact files and symbols

**Domain**

- `services/parking-service/src/main/java/com/parkio/parking/calibration/*`
- `CalibrationReportGenerator`, `CalibrationReadinessAssessor`, `CalibrationReplayer`
- `CalibrationPolicyConfig.POLICY_VERSION` = `calibration-policy-v1`

**Application**

- `ContinuousCalibrationApplicationService`
- `ContinuousCalibrationRowProcessor`
- `application/port/CalibrationObservationPort`, `CalibrationReportPort`, `CalibrationReadinessPort`
- `application/port/TrustOutcomeCalibrationReadPort`, `FraudLedgerCalibrationReadPort`
- `application/port/ContinuousCalibrationObserverPort`
- `application/calibration/TrustOutcomeCalibrationPair`, `FraudLedgerCalibrationCandidate`
- `application/calibration/CalibrationProcessingResult`, `CalibrationFailureStage`
- `DuplicateCalibrationObservationException`, `DuplicateCalibrationReportException`

**Infrastructure**

- `infrastructure/lifecycle/ContinuousCalibrationJob`
- `infrastructure/metrics/ContinuousCalibrationMetrics`
- `infrastructure/persistence/calibration/CalibrationPersistenceMapper`
- `infrastructure/persistence/TrustOutcomeCalibrationReadRepositoryAdapter`
- `infrastructure/persistence/FraudLedgerCalibrationReadRepositoryAdapter`

**Migration**

- `src/main/resources/db/migration/V26__continuous_calibration.sql`

**Decision calibration (separate)**

- `com/parkio/parking/decision/calibration/*` (WP-05.6)

**Observability**

- `docker/prometheus/continuous-calibration-recording-rules.yml`
- `docker/grafana/provisioning/dashboards/parkio-continuous-calibration.json`

**Tests**

- `CalibrationReportGeneratorTest`
- `ContinuousCalibrationApplicationServiceTest`
- `calibration/architecture/CalibrationPackageIndependenceTest`
- `infrastructure/persistence/calibration/CalibrationShadowMigrationPostgresIT`
- `infrastructure/persistence/fraud/FraudShadowPersistencePostgresIT`

---

## Policy-authority prerequisites

Before any engine policy promotion from calibration readiness:

1. WP-05.8 controlled authority migration path available for target engine
2. Operational verification flag set manually after runbook review
3. Privacy and fairness reviews completed for label sources used
4. Sustained `READY_FOR_CONTROLLED_CANARY_REVIEW` assessments across cohorts
5. Replay mismatch rate below policy threshold in production metrics
6. Decision engine remains on separate WP-05.6 + WP-05.8 track

WP-05.15 does not execute these steps — it produces advisory artifacts only.

---

## Deferred scope and unresolved questions

- Batch ingestion for Reward, Exposure, Availability engines
- Unified Decision engine path into `com.parkio.parking.calibration`
- Runtime policy registry service backing `PolicyGovernanceDescriptor`
- Temporal drift detection across sliding windows
- Statistical significance and confidence intervals
- Automated fairness/bias metrics by geography or user segment
- Public operator API for report/readiness queries
- Canary automation wiring baseline/candidate policy versions into reports
- Replay match metric binding to live replay comparison (currently placeholder in generator)

---

## WP-05 completion statement

WP-05.15 completes the **Continuous Calibration & Policy Governance** work package
for parking-service. Together with WP-05.1–WP-05.14, the WP-05 decision-intelligence
shadow stack now includes:

- Shadow engines for Decision, Availability, Outcome, Trust, Reward, Exposure, Fraud
- Decision-specific calibration analytics (WP-05.6)
- Cross-engine continuous calibration domain with Trust + Fraud batch pipeline
- Append-only calibration persistence, metrics, dashboards, and advisory readiness

**WP-05.15 is complete.** Policy activation, automatic tuning, and authority
rollout remain explicitly out of scope and require separate operational runbooks
beyond this work package.