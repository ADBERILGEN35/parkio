# WP-05.14 Fraud Intelligence Engine — Shadow Mode

## 1. Executive summary

WP-05.14 introduces a standalone shadow-only fraud intelligence module in
`com.parkio.parking.fraud`. It consumes durable, attributable reporter outcome
facts, aggregates them into bounded feature vectors, evaluates deterministic
fraud risk with `FraudEngine`, and appends immutable evaluations to
`fraud_evaluation_ledger`. No Decision, Availability, Authority, Outcome,
Trust, Reward, Exposure, Search, Moderation, Enforcement, API, or Kafka
behavior is changed.

## 2. Scope and non-goals

In scope (v1):
- reporter (`USER`) subject only
- one fraud domain: `CONTRIBUTION_INTEGRITY`
- outcome-history-driven pattern detection (direct confirmed-incorrect repetition)
- immutable append-only ledger (no projection table)
- replayable deterministic engine
- internal metrics, recording rules, Grafana dashboard
- default-disabled scheduler

Out of scope:
- account ban/suspend/restrict, publication mutation, search suppression
- reward cancellation, trust mutation, exposure mutation
- self-confirmation, coordination graphs, spatial fraud, IP/device fingerprinting
- public APIs, public Kafka contracts, user-visible fraud labels
- ML models, external fraud vendors

## 3. Repository-backed fraud-signal audit

| Signal | Status | Repository basis |
|---|---|---|
| Reporter identity | SUPPORTED_NOW | `ParkingSpot.ownerUserId` in `com.parkio.parking.domain.ParkingSpot` |
| Outcome classification/reason | SUPPORTED_NOW | `OutcomeHistoryRecord` via `outcome_history` |
| Direct confirmed-incorrect attribution | SUPPORTED_NOW | SQL filter in `FraudReporterOutcomeAggregateReadRepositoryAdapter` |
| Verifier/claimant/filled-reporter durable actor IDs | PARTIALLY_SUPPORTED | verification/claim tables exist; safe cross-role attribution deferred |
| Self-confirmation (reporter==verifier) | DEFERRED | `ParkingSpot.ensureNotOwner` blocks owner verification |
| Duplicate contribution (semantic) | DEFERRED_FOR_CALIBRATION | dedup keys exist but v1 uses outcome aggregates only |
| Reward farming | NOT_APPROPRIATE_FOR_V1 | `PendingRewardIntent` is calculation-only; excluded |
| Trust level as fraud input | NOT_APPROPRIATE_FOR_FRAUD | low trust ≠ fraud; excluded from v1 scoring |
| Exposure disagreement | NOT_APPROPRIATE_FOR_FRAUD | shadow rank disagreement excluded |
| IP/device/session | PRIVACY_REVIEW_REQUIRED | no privacy-reviewed durable store for v1 |
| Spatial/impossible travel | DEFERRED | insufficient durable actor-timestamp trail for safe v1 |
| Moderator override as fraud proof | NOT_APPROPRIATE_FOR_FRAUD | operational authority, not user intent |

## 4. Supported/unsupported/privacy-restricted signals

V1 implements only signals with stable reporter identity, direct outcome
attribution, deterministic SQL aggregation, and explainable false-positive
boundaries. All other categories are explicitly deferred in code paths and
documentation.

## 5. Fraud ownership boundary

Fraud answers: "Given durable attributable evidence, what manipulation or abuse
risk is observable?" It does not own publication, availability, outcome, trust,
reward, exposure, moderation, account status, or search visibility.

## 6. Incorrectness versus fraud

`FraudEngine` treats one `CONFIRMED_INCORRECT` outcome as capped low risk
(`FraudPolicyConfig.singleIncorrectRiskCap`). Natural occupancy change,
expiration, unknown outcomes, and low trust do not imply fraud.

## 7. Subject and fraud-domain model

- `FraudSubjectType.USER` — reporter user id (`FraudSubject`)
- `FraudDomain.CONTRIBUTION_INTEGRITY` — reporter contribution integrity only

## 8. Fact/evidence/assessment/risk/disposition separation

Pipeline:
1. Durable outcome facts (`OutcomeHistoryRecord`)
2. Bounded aggregate features (`FraudFeatureVector` via `ReporterFraudFeatureFactory`)
3. Category assessments (`FraudAssessment`)
4. Weighted risk (`FraudRiskScore`, `FraudRiskBand`)
5. Analytical disposition (`FraudDisposition`) — never enforcement

## 9. Evidence model

V1 uses aggregate features rather than per-event `FraudEvidence` rows. Features
are frozen in `FraudSnapshot` for replay. No PII, coordinates, IPs, or raw JPA
entities are persisted in the ledger JSON.

## 10. Attribution model

Reporter attribution is direct via `parking_spots.owner_user_id`. Direct
confirmed-incorrect counts require explicit negative reasons in SQL
(`NEGATIVE_VERIFICATION`, `MODERATOR_REJECTION`, `AI_REJECTION`, `REVIEW_FAILED`).

## 11. Evidence eligibility

Cold start (`eligibleContributionCount < minimumEvidenceVolume`) yields
`FraudDisposition.INSUFFICIENT_EVIDENCE`. Ineligible transport duplicates are
excluded by ledger uniqueness on `(source_outcome_record_id, subject, domain, policy)`.

## 12. Duplicate taxonomy

1. Transport duplicate — scheduler/Kafka retry; idempotent ledger unique constraint
2. Logical duplicate — same outcome trigger reprocessed; `DuplicateFraudLedgerEntryException`
3. Semantic duplicate — deferred for calibration
4. Coordinated duplicate — deferred (no graph analytics in v1)

## 13. Self-confirmation model

Deferred. Owner cannot verify own spot (`ParkingSpot.ensureNotOwner`). No
self-role overlap scoring in v1.

## 14. Coordination decision

Deferred. No actor-pair or cluster analytics without complete durable attribution.

## 15. Spatial anomaly decision

Deferred. No coordinate-based fraud scoring in v1.

## 16. Temporal window model

Rolling 7-day window: `FraudPolicyConfig.ROLLING_WINDOW`. Window boundaries
computed in `ReporterFraudFeatureFactory.windowStartFor` using injected
evaluation time from the trigger outcome record.

## 17. Feature aggregation

`FraudReporterOutcomeAggregateReadRepositoryAdapter.aggregateReporterContributions`
produces bounded counts: eligible contributions, direct confirmed-incorrect,
likely incorrect, confirmed correct, unknown, expired-without-evidence.

## 18. Minimum-evidence safeguards

`FraudPolicyConfig.minimumEvidenceVolume` = 1 for evaluation, but elevated
dispositions require `minimumEvidenceForElevated` = 2. No history → insufficient
evidence, not suspicion.

## 19. Risk/confidence/evidence-volume separation

Separate types: `FraudRiskScore`, `FraudConfidenceBand`, `FraudEvidenceVolume`.
Same risk with different evidence volumes yields different confidence bands.

## 20. Assessment categories

V1: `FraudAssessmentCategory.OUTCOME_INCONSISTENCY` only.

## 21. Hard anomaly policy

`FraudHardAnomalyType.REPEATED_DIRECT_CONFIRMED_INCORRECT` when
`directConfirmedIncorrectCount >= hardAnomalyConfirmedIncorrectThreshold` (4).
Hard anomaly is analytical only; no enforcement branch exists.

## 22. Fraud policy and mathematical model

Policy version: `fraud-policy-v1` (`FraudPolicyConfig.referenceV1`).
Integer basis points (max 10_000). Category caps, single-event caps, mitigation
from confirmed-correct history, monotonic risk/confidence thresholds.

## 23. Trust input decision

Trust is **absent** from v1 fraud scoring. Low trust cannot create fraud disposition.

## 24. Outcome input decision

Direct confirmed-incorrect outcomes contribute with full weight; likely incorrect
with lower weight; unknown and expired neutral; confirmed correct mitigates risk.

## 25. Reward input decision

Pending reward excluded from v1 fraud features.

## 26. Exposure input exclusion

Exposure shadow disagreement is not fraud evidence in v1.

## 27. FraudEngine architecture

Pure class `FraudEngine.evaluate(FraudFeatureVector, FraudEvaluationContext)`.
No repository access, no `Instant.now()`.

## 28. FraudSnapshot

Immutable replay bundle: subject, versions, context, features, evaluation,
evaluatedAt. Serialized in `evaluation_snapshot_json`.

## 29. Fraud ledger/projection decision

Ledger-only. No `fraud_subject_snapshot` projection (no enforcement consumer).

## 30. Persistence and Flyway design

Migration `V25__fraud_evaluation_ledger.sql`:
- PK `id`, unique `evaluation_id`
- unique trigger `(source_outcome_record_id, subject_type, subject_id, fraud_domain, policy_version)`
- FK to `outcome_history`
- bounded risk CHECK constraints
- indexes on subject/domain and outcome trigger

## 31. Deterministic idempotency

`FraudShadowApplicationService.deterministicEvaluationId` uses
`UUID.nameUUIDFromBytes` over subject, domain, watermark outcome record,
policy, aggregation version.

## 32. Source-fact watermark

`source_outcome_record_id` on ledger entry; aggregate SQL bounded by window and
reporter join. Unchanged trigger outcome → duplicate evaluation.

## 33. Candidate discovery

Outcome-driven: `OutcomeHistoryJpaRepository.claimPendingReporterFraudCandidates`
selects outcomes without matching ledger row for reporter/domain/policy.

## 34. Scheduler/orchestration

`FraudShadowJob` — `@ConditionalOnProperty(parkio.lifecycle.fraud-shadow.enabled=true)`,
default **disabled**. `FraudShadowRowProcessor` uses `REQUIRES_NEW` per row.

## 35. Transaction/concurrency model

Independent transactions per candidate. Unique constraints ensure one logical
evaluation per trigger outcome.

## 36. Failure isolation

Fraud failures return `FraudShadowProcessingResult.failed` without rolling back
outcome/trust/reward/exposure source domains.

## 37. Replay/versioning

`FraudReplayer.replay(FraudLedgerEntry)` re-evaluates frozen snapshot; unknown
policy/schema versions throw explicitly.

## 38. Metrics catalogue

See `FraudShadowMetrics` and `docs/architecture/observability-metrics.md`
(Fraud shadow section).

## 39. Prometheus ratio definitions

Recording rules in `docker/prometheus/fraud-shadow-recording-rules.yml`.

## 40. Grafana dashboard

`docker/grafana/provisioning/dashboards/parkio-fraud-shadow.json`

## 41. Security/privacy review

No public API, no PII in ledger, no ID/score metric tags, no fingerprinting,
no enforcement side effects.

## 42. False-positive review

- One incorrect report: capped by `singleIncorrectRiskCap`
- Urban density / family shared area: not scored as duplicate in v1
- Scheduler retry: idempotent duplicate, not user fraud evidence
- New users: insufficient evidence disposition
- Moderator correction: only counts when classification is directly incorrect with explicit reason

## 43. Fairness/feedback-loop review

No trust/reward/exposure write-back. Shadow-only. Calibration required before
any authority consumer (WP-05.15+).

## 44. Retention/erasure review

Ledger stores subject UUID and outcome record FK. Account-deletion cascade
behavior follows existing outcome/parking retention policies — legal/product
retention period not invented here.

## 45. PostgreSQL verification

`FraudShadowMigrationPostgresIT` verifies fresh V25 migrate and V24→V25 upgrade.

## 46. Backward-compatibility proof

Fraud shadow default disabled. No imports from fraud into decision/trust/reward/
exposure domains. Full `:services:parking-service:test` suite green.

## 47. Exact files and symbols

Domain: `com.parkio.parking.fraud.*` — `FraudEngine`, `FraudPolicyConfig`, `FraudReplayer`
Application: `FraudShadowApplicationService`, `ReporterFraudFeatureFactory`
Ports: `FraudLedgerPort`, `FraudReporterOutcomeAggregateReadPort`, `ValidatedOutcomeForFraudReadPort`
Infrastructure: `FraudLedgerRepositoryAdapter`, `FraudReporterOutcomeAggregateReadRepositoryAdapter`, `FraudShadowJob`, `FraudShadowMetrics`
Migration: `V25__fraud_evaluation_ledger.sql`

## 48. WP-05.15 prerequisites

- Shadow calibration dashboards populated with real traffic
- Expanded signal audit (self-confirmation, semantic duplicates) after attribution closure
- Privacy review for any new identity signals
- Explicit authority ADR before fraud influences moderation or enforcement

## 49. Deferred scope and open questions

- Self-confirmation when verifier attribution is durable
- Reward duplication patterns with logical identity
- Coordination/pair features
- Spatial fraud
- Trust as bounded contextual feature (not score replacement)
- Fraud snapshot projection if operational lookup needed