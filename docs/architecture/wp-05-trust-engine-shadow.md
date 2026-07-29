# WP-05.11 Trust Engine - Shadow Mode

## 1. Executive summary

WP-05.11 introduces a standalone shadow-only trust module in `com.parkio.parking.trust`.
It consumes immutable `OutcomeHistoryRecord` facts, maps them into canonical
`TrustEvidence`, calculates deterministic reporter trust updates, appends them to an
immutable `trust_ledger`, and maintains a rebuildable `trust_snapshot` projection.
No Decision, Availability, Authority, Search, Reward, Fraud, API, or Kafka behavior is changed.

## 2. Scope and non-goals

In scope:
- reporter trust only
- one contextual domain: `PARKING_REPORT_ACCURACY`
- immutable ledger + derived snapshot
- replayable deterministic engine
- internal metrics, recording rules, Grafana dashboard

Out of scope:
- trust-based publication authority
- trust-based search, reward, gamification, fraud, or user-visible labels
- device/session trust
- public APIs or Kafka contracts

## 3. Repository-backed trust-input audit

- Durable outcome facts exist in `services/parking-service/src/main/java/com/parkio/parking/outcome/history/OutcomeHistoryRecord.java` via `OutcomeHistoryRecord`.
- Outcome history is persisted append-only through `services/parking-service/src/main/java/com/parkio/parking/infrastructure/persistence/OutcomeHistoryRepositoryAdapter.java` and `services/parking-service/src/main/java/com/parkio/parking/infrastructure/persistence/entity/OutcomeHistoryEntity.java`.
- Durable outcome draining already exists as the repository pattern in `services/parking-service/src/main/java/com/parkio/parking/infrastructure/lifecycle/OutcomeValidationTriggerJob.java` and `services/parking-service/src/main/java/com/parkio/parking/infrastructure/persistence/jpa/OutcomeEvaluationTriggerJpaRepository.java`.
- Reporter identity is durable on the parking aggregate through `services/parking-service/src/main/java/com/parkio/parking/domain/ParkingSpot.java` symbol `ownerUserId`.
- Current trust linkage can safely join `outcome_history.parking_spot_id` to `parking_spots.id` and read `owner_user_id`; see `services/parking-service/src/main/java/com/parkio/parking/infrastructure/persistence/jpa/OutcomeHistoryJpaRepository.java` symbol `claimPendingReporterOutcomes`.
- A trust extension point already exists but was intentionally noop in WP-05.10 through `services/parking-service/src/main/java/com/parkio/parking/outcome/port/OutcomeTrustConsumerPort.java`.

## 4. Supported and unsupported trust subjects

- `REPORTER`: `SUPPORTED_NOW`. Stable identity and deterministic durable linkage through `ParkingSpot.ownerUserId` and `OutcomeHistoryRecord.parkingSpotId`.
- `VERIFIER`: `PARTIALLY_SUPPORTED`. Verification rows are durable, but actor attribution is not yet wired into trust processing and self-confirmation rules are not fully repository-proven for production scope.
- `CLAIMANT`: `PARTIALLY_SUPPORTED`. Claims exist in the lifecycle, but current durable outcome-to-claimant attribution remains too coarse for safe v1 penalties.
- `FILLED_REPORTER`: `PARTIALLY_SUPPORTED`. Filled signals exist, but their correctness semantics are often ambiguous relative to original publication-time truth.
- `MODERATOR`: `UNSUPPORTED` for trust v1. Moderator actions are operational authority signals, not end-user contribution trust.
- `DEVICE`: `PRIVACY_REVIEW_REQUIRED`. No privacy-reviewed stable internal device identifier was established in repository-backed outcome history.
- `SESSION`: `PRIVACY_REVIEW_REQUIRED`. No stable cross-outcome session identity is stored for trust learning.
- `REGION` / location: `PRIVACY_REVIEW_REQUIRED`. Coordinates and region derivations would introduce additional privacy and attribution questions.
- `PARKING_SPOT`: `SUPPORTED_NOW` as an internal concept but not selected for v1 because the objective is subject trust, not spot trust.
- `EVIDENCE_SOURCE_TYPE`: `SUPPORTED_NOW` as bounded metadata in the evidence model, not as the primary trust subject.

The initial production scope is therefore `TrustSubjectType.REPORTER` only in `TrustDomain.PARKING_REPORT_ACCURACY`.

## 5. Trust domain boundaries

The trust module is pure and framework-free in `services/parking-service/src/main/java/com/parkio/parking/trust`.
It does not import Spring, JPA, Kafka, or Micrometer. The application boundary lives in
`TrustShadowApplicationService`; persistence lives under `infrastructure.persistence`; metrics live under `infrastructure.metrics`.

## 6. Trust-domain taxonomy

Current bounded trust taxonomy:
- subject types in `TrustSubjectType`
- domain `PARKING_REPORT_ACCURACY` in `TrustDomain`
- levels in `TrustSnapshot.Level`
- direction in `TrustEvaluation.Direction`

No global cross-domain trust score is created.

## 7. Outcome-to-trust mapping

`ValidatedTrustEvidenceFactory.reporterEvidence()` maps `OutcomeHistoryRecord` to canonical reporter trust evidence.

- `CONFIRMED_CORRECT` and `LIKELY_CORRECT` can produce positive evidence.
- `UNKNOWN` and `EXPIRED_WITHOUT_EVIDENCE` are neutral.
- `LIKELY_INCORRECT` is only eligible when the reason is a direct negative verification.
- `CONFIRMED_INCORRECT` is only eligible when the reason is `NEGATIVE_VERIFICATION` or `MODERATOR_REJECTION`.
- `TIME_EXPIRED`, `TIME_EXPIRED_NO_EVIDENCE`, and `COMMUNITY_FILLED_REPORTS` stay ambiguous and do not penalize the reporter.

## 8. Attribution model

Reporter attribution quality is explicit in `TrustEvidence.AttributionQuality` and chosen by `ValidatedTrustEvidenceFactory.attributionQuality()`.

- `MULTIPLE_AVAILABLE_VERIFICATIONS`, `COMMUNITY_CLAIM_CONFIRMED`: `DIRECT`
- `SINGLE_AVAILABLE_VERIFICATION`: `STRONG`
- `NEGATIVE_VERIFICATION`, `MODERATOR_REJECTION`: `DIRECT`
- `COMMUNITY_FILLED_REPORTS`, `TIME_EXPIRED`, `TIME_EXPIRED_NO_EVIDENCE`, `VALIDATION_WINDOW_OPEN`, `INSUFFICIENT_EVIDENCE`, `TERMINAL_STATUS`: `AMBIGUOUS`
- `AI_REJECTION`, `REVIEW_FAILED`: `PARTIAL` or `NONE` depending on outcome classification

## 9. Circularity and self-confirmation protections

- Exactly one trust evidence item is created per durable `OutcomeHistoryRecord` and reporter subject through `ValidatedTrustEvidenceFactory.reporterEvidence()`.
- `TrustEvidence.evidenceGroupId` is the durable `OutcomeHistoryRecord.recordId`, which prevents one real-world outcome from creating multiple full-weight reporter updates.
- Trust processing queries only reporter candidates today; it does not also award verifier or claimant trust from the same outcome.
- Ambiguous sources such as filled reports and passive expiration are marked ineligible.

## 10. Cold-start model

Cold start is implemented in `TrustEngine.initialSnapshot()` and `TrustPolicyConfig.referenceV1()`:
- neutral prior score from symmetric prior mass
- zero confidence
- zero effective evidence count
- level `UNKNOWN`

No-history is therefore uncertainty, not low trust.

## 11. Score, confidence and evidence-volume model

`TrustSnapshot` stores:
- `score`
- `confidence`
- `positiveEvidenceMass`
- `negativeEvidenceMass`
- `effectiveEvidenceCount`

These remain distinct. Confidence saturates separately from score, and trust levels also depend on support volume.

## 12. Mathematical/update model

`TrustEngine.evaluate()` applies:
- classification weight
- attribution multiplier
- confidence multiplier
- per-event max impact cap

All math is integer / basis-point based via `TrustPolicyConfig.BASIS_POINTS`. No floating-point trust math is used.

## 13. Policy configuration

`TrustPolicyConfig` defines immutable `trust-policy-v1` values:
- symmetric prior mass
- confidence saturation mass
- positive/negative weights
- attribution multipliers
- confidence multipliers
- negative-learning minimum confidence
- trust level thresholds
- per-event max impact

Invalid monotonic or out-of-range configurations are rejected in the constructor.

## 14. Time-decay decision

No active temporal decay is implemented in v1. `TrustEvaluationContext.evaluatedAt` is injected and replayable, leaving a clean extension point for future decay without rewriting history.

## 15. TrustEngine architecture

- Pure engine: `TrustEngine`
- Deterministic input context: `TrustEvaluationContext`
- Immutable update result: `TrustEvaluation`
- Immutable current state: `TrustSnapshot`
- Immutable provenance row: `TrustLedgerEntry`
- Offline replay helper: `TrustReplayer`

## 16. TrustEvidence identity

`ValidatedTrustEvidenceFactory.deterministicId()` builds `TrustEvidence.evidenceId` from:
- outcome record id
- reporter subject id
- domain
- policy version

`TrustEvidence.evidenceGroupId` is the source outcome record id.

## 17. Ledger model

The append-only ledger is represented by:
- domain: `TrustLedgerEntry`
- entity: `TrustLedgerEntity`
- port: `TrustLedgerPort`
- adapter: `TrustLedgerRepositoryAdapter`

It stores the canonical evidence, previous snapshot, evaluation result, versions, and audit indexes required for replay.

## 18. Projection decision

WP-05.11 uses ledger + derived transactional projection:
- authoritative source: `trust_ledger`
- current derived read model: `trust_snapshot`

`TrustSnapshotEntity.version` provides optimistic-lock based rebuildable projection safety.

## 19. Persistence schema

Flyway migration `V23__trust_shadow_ledger.sql` adds:
- `trust_ledger`
- `trust_snapshot`
- subject/domain ordering indexes
- deterministic uniqueness on `evaluation_id` and `source_evidence_id`

No FK is created from trust rows to user-service identities.

## 20. Idempotency semantics

Logical duplicate suppression is keyed by deterministic `source_evidence_id` and surfaced via `DuplicateTrustLedgerEntryException`.
Exact outcome redelivery therefore results in one logical trust update.

## 21. Ordering and concurrency

Ordering is canonical by durable outcome evaluation time and id:
- `OutcomeHistoryJpaRepository.claimPendingReporterOutcomes()` orders by `outcome_history.evaluated_at`, then `outcome_history.id`
- ledger history reads order by `evaluated_at`, then `id`

Projection updates use optimistic locking in `TrustSnapshotEntity.version`, surfaced as `TrustShadowProjectionConflictException`.

## 22. Trigger/delivery model

Shadow trust drains from durable outcome history, not request payloads:
- claim port: `ValidatedOutcomeForTrustReadPort`
- repository claim query: `OutcomeHistoryJpaRepository.claimPendingReporterOutcomes`
- scheduler: `TrustShadowJob`
- row transaction boundary: `TrustShadowRowProcessor.process`

The checkpoint is trust-side idempotency in `trust_ledger`, not mutation of outcome history.

## 23. Failure isolation

Trust processing is isolated from parking lifecycle writes:
- parking lifecycle still commits outcome facts first
- trust scheduler runs later
- single-row trust processing uses `@Transactional(propagation = Propagation.REQUIRES_NEW)` in `TrustShadowRowProcessor`
- duplicate and projection failures are converted into bounded internal results

## 24. Replay and rebuild

Single-entry replay is supported by `TrustReplayer.replay(TrustLedgerEntry)`.
Projection rebuild is supported by replaying subject ledger rows in canonical order using `TrustLedgerPort.findBySubject(...)`.

## 25. Policy and schema versioning

Separated version dimensions:
- trust policy: `TrustPolicyConfig.POLICY_VERSION`
- trust snapshot schema: `TrustSnapshotSchemaVersion`
- trust attribution mapping: `ValidatedTrustEvidenceFactory.ATTRIBUTION_MAPPING_VERSION`

Unknown policy and schema versions fail explicitly.

## 26. Metrics catalogue

Micrometer component: `TrustShadowMetrics`

Families emitted:
- `parkio.parking.trust.outcome.received`
- `parkio.parking.trust.evidence.produced`
- `parkio.parking.trust.evidence.skipped`
- `parkio.parking.trust.update.success`
- `parkio.parking.trust.update.duplicate`
- `parkio.parking.trust.update.failure`
- `parkio.parking.trust.evaluation.duration`
- `parkio.parking.trust.replay.success`
- `parkio.parking.trust.replay.mismatch`
- `parkio.parking.trust.replay.failure`
- scheduler candidate/completed/failed metrics

Only bounded labels are used.

## 27. Prometheus ratio definitions

`docker/prometheus/trust-shadow-recording-rules.yml` defines:
- `parkio:trust_shadow:success_rate5m`
- `parkio:trust_shadow:duplicate_rate5m`
- `parkio:trust_shadow:failure_rate5m`
- `parkio:trust_shadow:eligible_ratio5m`

Duplicates are intentionally separate from failures.

## 28. Grafana dashboard

`docker/grafana/provisioning/dashboards/parkio-trust-shadow.json` provides:
- success / duplicate / failure rate stats
- update-direction distribution
- trust-level distribution
- attribution-quality distribution
- p95 evaluation latency

## 29. Privacy/fairness review

- no public endpoints
- no trust labels in API responses
- no PII fields in trust storage
- no device fingerprinting
- no coordinates in trust ledger columns
- no subject ids in metric labels
- no protected-class signals
- ambiguous outcomes remain neutral
- no automatic reward or enforcement path

## 30. Retention/account-deletion review

Current design keeps trust rows as internal historical audit keyed by internal UUIDs without foreign keys to user-service tables. This avoids cross-service cascading deletes but leaves final erasure/pseudonymization policy as a product/legal follow-up.

## 31. Backward-compatibility proof

No runtime integration was added from trust into:
- Decision Engine
- authority selection
- availability engine
- search
- reward / gamification
- public API controllers
- Kafka contracts

All trust work is internal to parking-service and scheduler-driven.

## 32. Exact files and symbols

Primary trust symbols:
- `TrustEngine`
- `TrustPolicyConfig`
- `TrustSnapshot`
- `TrustLedgerEntry`
- `ValidatedTrustEvidenceFactory.reporterEvidence`
- `TrustShadowApplicationService.process`
- `TrustShadowJob.processPendingOutcomes`
- `OutcomeHistoryJpaRepository.claimPendingReporterOutcomes`
- `TrustLedgerRepositoryAdapter.append`
- `TrustSnapshotRepositoryAdapter.upsert`

## 33. WP-05.12 prerequisites

Reward work should consume validated outcomes and/or stable trust reads through a dedicated read boundary. WP-05.12 now uses `OutcomeHistoryRecord`-driven reporter reward attribution in `ValidatedRewardContributionFactory` and intentionally keeps `RewardEngine` trust-independent. It must not mutate or derive business rewards directly from raw trust score without separate calibration and product rules.

## 34. Deferred scope and open questions

- verifier / claimant / filled-report trust attribution
- explicit projection rebuild command path
- retention and erasure policy for user-linked internal ids
- future trust reads by reward/fraud modules
- any future decay policy

## 35. Verification closure

Operational proof for WP-05.11A lives in `docs/architecture/wp-05-trust-verification-closure.md`.
That closure verifies:
- PostgreSQL migration execution through `V23__trust_shadow_ledger.sql`
- real transaction semantics for `TrustShadowRowProcessor.process`
- bounded retry on snapshot conflicts
- replay/rebuild parity from `TrustLedgerPort.findBySubject(...)`
- Prometheus/Grafana wiring for trust shadow observability
