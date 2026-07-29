# WP-05.10A Outcome Operationalization

**Status:** Implemented in code, pending full-suite / observability validation before completion is claimed  
**Date:** 2026-07-28  
**Related:** [WP-05.10 Outcome Validation](./wp-05-outcome-validation.md), [WP-05 implementation plan](./wp-05-implementation-plan.md), [Observability metrics](./observability-metrics.md)

## 1. Executive summary

WP-05.10A operationalizes the pure `com.parkio.parking.outcome` engine by introducing a durable append-only history store, deterministic trigger identity, repository-backed evidence reads, failure-isolated orchestration, and bounded metrics. The hot path still does **not** mutate trust, decision authority, search visibility, rewards, or `ParkingSpot` lifecycle semantics.

## 2. Scope and non-goals

In scope:
- Durable `OutcomeHistoryPort` persistence via `outcome_history`
- Deterministic `OutcomeEvaluationTriggerRequest` delivery via `outcome_evaluation_triggers`
- Trigger enqueueing from existing parking lifecycle writes in `com.parkio.parking.application.ParkingApplicationService`
- Internal replayable `OutcomeHistoryRecord` snapshots
- Bounded Micrometer / Prometheus / Grafana observability

Still out of scope:
- Trust mutation through `com.parkio.parking.outcome.port.OutcomeTrustConsumerPort`
- Search ranking changes
- Reward settlement
- Kafka/public API contracts for outcome labels
- Scheduled validation-window closure beyond existing explicit lifecycle evidence

## 3. Pre-implementation repository audit

### Lifecycle facts already present

The repository already persisted most outcome-relevant facts as append-only or durable lifecycle data:
- `ParkingApplicationService.recordHistory()` writes `ParkingSpotStatusHistory` rows with reason strings such as `CLAIMED`, `VERIFICATION_*`, moderation outcomes, and `EXPIRED` in `services/parking-service/src/main/java/com/parkio/parking/application/ParkingApplicationService.java` `recordHistory()`.
- Verification rows are persisted through `ParkingSpotVerificationRepository.save(...)` from `ParkingApplicationService.verifySpot()` in `services/parking-service/src/main/java/com/parkio/parking/application/ParkingApplicationService.java` `verifySpot()`.
- Outbox writes already exist for claim/verify/expiry/review-failure events via `OutboxEventAppender.append(...)` in `ParkingApplicationService`.

### Action inventory

| Evidence action | Application symbol | Aggregate mutation | History reason / persisted fact | Outbox event | Transaction / duplicate model |
|---|---|---|---|---|---|
| Initial successful publication | `ParkingApplicationService.applyAiValidationResult()` / `applyModeratorDecision()` | `ParkingSpot.activate(...)` | `AI_PASSED` or moderator approval path through `recordHistory()` | `ParkingSpotActivatedEvent.of(...)` | same transaction; consumer inbox / aggregate guards already bound duplicates |
| Community claim | `ParkingApplicationService.claimSpot()` | `ParkingSpot.claim(...)` | `CLAIMED` history row | `ParkingSpotClaimedEvent.of(...)` | same transaction; aggregate guard prevents repeated claims |
| Verification available / invalid / filled | `ParkingApplicationService.verifySpot()` | `ParkingSpot.verify(...)` | `VERIFICATION_<result>` history row + verification table row | `ParkingSpotVerifiedEvent.of(...)` or `ParkingSpotMarkedFilledEvent.of(...)` | same transaction; aggregate/user checks reject illegal duplicates |
| Moderator rejection / approval | `ParkingApplicationService.rejectSpot()` / `approveSpot()` -> `applyModeratorDecision()` | `ParkingSpot.reject(...)` / `activate(...)` | moderation reason via `recordHistory()` | `ParkingSpotReviewFailedEvent.of(...)` or `ParkingSpotActivatedEvent.of(...)` | same transaction; inbox on moderation consumer handles duplicate Kafka delivery |
| Review failure | `ParkingApplicationService.failOverdueSpot(...)` / `failModeration(...)` | `ParkingSpot.failReview(...)` | `ParkingSpotReviewFailedEvent.REASON_*` via `recordHistory()` | `ParkingSpotReviewFailedEvent.of(...)` | bounded retries driven by `ModerationTimeoutJob` |
| Expiration | `ParkingApplicationService.expireIfElapsed()` | `ParkingSpot.expire(...)` | `EXPIRED` history row | `ParkingSpotExpiredEvent.of(...)` | invoked directly and from `ParkingExpiryJob`; aggregate status guard makes repeats no-ops |

### Scheduler / retry patterns already in repository

Repository-supported reliable background processing already exists and influenced the outcome design:
- Bounded scheduler loops: `ParkingExpiryJob.expireElapsedSpots()` and `ModerationTimeoutJob.resolveOverdueModeration()` in `services/parking-service/src/main/java/com/parkio/parking/infrastructure/lifecycle`.
- Lock-safe polling: `OutboxEventJpaRepository.findUnpublishedBatchForUpdate(...)` uses `FOR UPDATE SKIP LOCKED` in `services/parking-service/src/main/java/com/parkio/parking/infrastructure/persistence/jpa/OutboxEventJpaRepository.java`.
- Per-row isolation: `ParkingSessionStaleRowProcessor` uses `@Transactional(propagation = Propagation.REQUIRES_NEW)` in `services/parking-service/src/main/java/com/parkio/parking/application/ParkingSessionStaleRowProcessor.java`.

### Conclusion

Outcome evaluation should not run synchronously inside the critical publication / claim / verification transaction because outcome is observational and history append failure must not roll back the lifecycle mutation. The repository already prefers “commit primary fact first, then drain a durable queue independently” patterns, so WP-05.10A uses a dedicated durable trigger table plus a scheduled consumer.

## 4. Outcome trigger inventory

`services/parking-service/src/main/java/com/parkio/parking/application/outcome/OutcomeEvaluationTrigger.java` defines the bounded trigger taxonomy actually supported by repository events:
- `PUBLICATION`
- `CLAIM`
- `VERIFICATION_AVAILABLE`
- `NEGATIVE_VERIFICATION`
- `FILLED_REPORT`
- `MODERATOR_REJECTION`
- `REVIEW_FAILURE`
- `EXPIRATION`

Not implemented in WP-05.10A because repository delivery was not yet proven for them:
- `SCHEDULED_WINDOW_CHECK`
- `MANUAL_REPLAY`

Trigger enqueueing is wired in `ParkingApplicationService.enqueueOutcomeHistoryTrigger(...)`, `enqueueOutcomeVerificationTrigger(...)`, and `enqueueOutcomeTrigger(...)` in `services/parking-service/src/main/java/com/parkio/parking/application/ParkingApplicationService.java`.

## 5. Evidence-source inventory

Outcome evidence is reconstructed from canonical repository facts instead of mutable live projections:
- Baseline spot timestamps from `OutcomeSpotSnapshotReadPort.findSpotSnapshot(...)`
- Append-only status rows from `OutcomeStatusHistoryReadPort.findStatusHistoryForOutcome(...)`
- Verification rows from `OutcomeVerificationReadPort.findVerificationsForOutcome(...)`

The concrete adapters are:
- `OutcomeSpotSnapshotReadRepositoryAdapter` in `services/parking-service/src/main/java/com/parkio/parking/infrastructure/persistence/OutcomeSpotSnapshotReadRepositoryAdapter.java`
- `OutcomeStatusHistoryReadRepositoryAdapter` in `services/parking-service/src/main/java/com/parkio/parking/infrastructure/persistence/OutcomeStatusHistoryReadRepositoryAdapter.java`
- `OutcomeVerificationReadRepositoryAdapter` in `services/parking-service/src/main/java/com/parkio/parking/infrastructure/persistence/OutcomeVerificationReadRepositoryAdapter.java`

Claims, filled reports, moderator outcomes, and expiration are represented through `ParkingSpotStatusHistory.reason` and `ParkingSpotStatusHistory.newStatus`, so separate claim/report read ports were not required for this repository revision.

## 6. Operational architecture

```text
ParkingApplicationService lifecycle write
    -> enqueueOutcomeTrigger(...)
    -> outcome_evaluation_triggers
    -> OutcomeValidationTriggerJob.processPendingTriggers()
    -> OutcomeValidationApplicationService.process(...)
    -> OutcomeHistoricalEvidenceFactory.create(...)
    -> OutcomeValidationEngine.evaluate(...)
    -> OutcomeHistoryPort.append(...)
    -> outcome_history
```

Core symbols:
- `OutcomeValidationApplicationService.process(...)`
- `OutcomeValidationTriggerJob.processPendingTriggers()`
- `OutcomeHistoricalEvidenceFactory.create(...)`
- `OutcomeHistoryRepositoryAdapter.append(...)`

## 7. Transaction and delivery model

The chosen delivery model is “transaction-local durable trigger enqueue, then independent scheduled evaluation”:
- The lifecycle write and trigger row insert happen in the existing application transaction inside `ParkingApplicationService.enqueueOutcomeTrigger(...)`.
- Evaluation occurs later in `OutcomeValidationTriggerJob.processPendingTriggers()` after the lifecycle transaction has committed.
- Trigger claiming uses `OutcomeEvaluationTriggerJpaRepository.claimPendingBatch(...)` with `FOR UPDATE SKIP LOCKED`, so multiple replicas should not process the same pending row concurrently.

This keeps outcome failures observational and retriable without inventing new Kafka contracts.

## 8. Failure-isolation model

Bounded failure stages are defined in `services/parking-service/src/main/java/com/parkio/parking/application/outcome/OutcomeProcessingFailureStage.java`.

`OutcomeValidationApplicationService.process(...)` catches duplicate appends as `DuplicateOutcomeHistoryException` and converts unexpected runtime failures into bounded processing results. `OutcomeValidationTriggerJob.processPendingTriggers()` then marks the durable trigger row processed or failed via `OutcomeEvaluationTriggerPort.markProcessed(...)` / `recordFailure(...)`.

Result: publication, claim, verification, moderation, and expiration succeed independently of outcome append success.

## 9. OutcomeHistoryRecord

The immutable persisted representation is `services/parking-service/src/main/java/com/parkio/parking/outcome/history/OutcomeHistoryRecord.java`.

Fields persisted for replay / audit:
- record id
- deterministic evaluation id
- parking spot id
- policy version
- snapshot schema version
- bounded trigger type / trigger reference
- evaluated at / evidence cutoff
- serialized `OutcomeSnapshot`
- classification / confidence / primary reason / validation-window flag
- created at

No JPA entities, stack traces, request DTOs, or raw Kafka payloads are embedded.

## 10. Persistence schema

Flyway migration: `services/parking-service/src/main/resources/db/migration/V22__outcome_operationalization.sql`.

Tables:
- `outcome_history`: append-only durable outcome snapshots
- `outcome_evaluation_triggers`: durable trigger queue with failure counters and dead-letter marker

JPA entities:
- `OutcomeHistoryEntity`
- `OutcomeEvaluationTriggerEntity`

Repository adapters:
- `OutcomeHistoryRepositoryAdapter`
- `OutcomeEvaluationTriggerRepositoryAdapter`

Retention / FK choice: the implementation currently mirrors `decision_audit` in `V20__create_decision_audit.sql` by keeping a foreign key from `outcome_history.parking_spot_id` to `parking_spots(id)` without cascade delete. That gives RESTRICT-like protection against deleting spots that still have audit history. Repository policy for long-term spot deletion retention remains open.

## 11. Snapshot schema

`OutcomeSnapshot` remains the replay payload, now wrapped by `OutcomeHistoryRecord` and serialized by `OutcomeHistorySnapshotMapper` in `services/parking-service/src/main/java/com/parkio/parking/infrastructure/persistence/outcome/OutcomeHistorySnapshotMapper.java`.

The stored payload includes only canonical outcome-domain values:
- `OutcomeEvidence`
- `OutcomeEvaluationContext`
- `OutcomeEvaluation`

## 12. Policy versus schema versioning

Policy and serialization versions are now explicitly separate:
- policy version: `OutcomePolicyConfig.POLICY_VERSION` (`outcome-validation-v1`)
- snapshot schema version: `OutcomeSnapshotSchemaVersion.V1` (`outcome-snapshot-v1`)

Unknown policy versions still fail through the pure outcome replay/policy boundary. Unknown snapshot schema versions are intended to fail during mapper/replay interpretation rather than being inferred from ad hoc JSON structure.

## 13. Idempotency model

Deterministic trigger/evaluation identity is generated by `OutcomeEvaluationIdentity.forTrigger(...)` in `services/parking-service/src/main/java/com/parkio/parking/application/outcome/OutcomeEvaluationIdentity.java` from:
- `parkingSpotId`
- bounded `OutcomeEvaluationTrigger`
- `triggerReference`
- `evidenceCutoffAt`

This distinguishes later legitimate evidence from exact duplicate delivery. `outcome_evaluation_triggers.evaluation_id` and `outcome_history.evaluation_id` are unique, so duplicates converge on one logical evaluation.

## 14. Evidence cutoff semantics

The durable trigger carries `evidenceCutoffAt` in `OutcomeEvaluationTriggerRequest`. Read ports query `<= cutoff`, and `OutcomeValidationApplicationService.process(...)` builds evidence exclusively from rows at or before that cutoff.

Consistency guarantee: explicit cutoff ordering across independently persisted tables. The repository does **not** claim strict cross-table snapshot isolation beyond the transaction that inserted the trigger.

## 15. Trigger taxonomy

Bounded enum only; no free-form strings in business logic. See `OutcomeEvaluationTrigger` above and the enqueue mapping in `ParkingApplicationService.enqueueOutcomeHistoryTrigger(...)` / `enqueueOutcomeVerificationTrigger(...)`.

## 16. Read ports

Application-facing ports introduced in `services/parking-service/src/main/java/com/parkio/parking/application/port`:
- `OutcomeSpotSnapshotReadPort`
- `OutcomeStatusHistoryReadPort`
- `OutcomeVerificationReadPort`
- `OutcomeEvaluationTriggerPort`
- `OutcomeOperationalizationObserverPort`

The pure `com.parkio.parking.outcome` package remains free of Spring/JPA/Micrometer dependencies.

## 17. Replay flow

Replay continues to use the pure domain replayer from WP-05.10:
- durable `OutcomeHistoryRecord.snapshot()`
- `OutcomeSnapshot`
- `OutcomeReplayer.replay(...)`
- `OutcomeReplayComparison`

WP-05.10A does not append or mutate during replay.

## 18. Latest-outcome read semantics

`OutcomeHistoryPort` now exposes:
- `findLatest(UUID)`
- `findLatestAtOrBefore(UUID, Instant)`
- `findByEvaluationId(UUID)`
- `findAll(UUID)`

The JPA repository orders latest rows by `evaluated_at DESC, id DESC` in `OutcomeHistoryJpaRepository.findTopByParkingSpotIdOrderByEvaluatedAtDescIdDesc(...)`, giving deterministic tie-breaking without denormalizing a mutable “current outcome” column onto `ParkingSpot`.

## 19. Concurrency model

Concurrency protections now exist at two layers:
- trigger claiming: `OutcomeEvaluationTriggerJpaRepository.claimPendingBatch(...)` uses `FOR UPDATE SKIP LOCKED`
- append idempotency: `outcome_history.evaluation_id` unique constraint plus `OutcomeHistoryRepositoryAdapter.append(...)` translating unique-conflict writes into `DuplicateOutcomeHistoryException`

Late evidence produces a new evaluation only when it produces a distinct deterministic evaluation id (new trigger reference and/or later cutoff).

## 20. Scheduler design or deferral

Implemented scheduler: `OutcomeValidationTriggerJob` drains explicit trigger rows only.

Deferred scheduler: validation-window closure without a new lifecycle fact. `SCHEDULED_WINDOW_CHECK` was intentionally **not** wired because the repository work in this change set does not yet provide a proven, bounded due-candidate query for “window just closed” without broad active-spot scans.

## 21. Metrics catalogue

`OutcomeValidationMetrics` in `services/parking-service/src/main/java/com/parkio/parking/infrastructure/metrics/OutcomeValidationMetrics.java` emits:
- `parkio.parking.outcome.trigger.received`
- `parkio.parking.outcome.trigger.received.by_type{trigger_type}`
- `parkio.parking.outcome.trigger.eligible{trigger_type}`
- `parkio.parking.outcome.trigger.skipped{trigger_type}`
- `parkio.parking.outcome.trigger.duplicate{trigger_type}`
- `parkio.parking.outcome.evaluation.failure{failure_stage}`
- `parkio.parking.outcome.history.append.success`
- `parkio.parking.outcome.history.append.duplicate`
- `parkio.parking.outcome.history.append.failure`
- `parkio.parking.outcome.classification{classification}`
- `parkio.parking.outcome.confidence_band{confidence_band}`
- `parkio.parking.outcome.validation_window{open}`
- `parkio.parking.outcome.expired_without_evidence`
- `parkio.parking.outcome.processing.duration`
- `parkio.parking.outcome.scheduler.candidates`
- `parkio.parking.outcome.scheduler.completed`
- `parkio.parking.outcome.scheduler.failed`
- `parkio.parking.outcome.replay.success|mismatch|failure`

All tags are bounded enums/booleans only.

## 22. Prometheus ratio definitions

Recording rules added in `docker/prometheus/outcome-validation-recording-rules.yml` define:
- evaluation success rate = eligible triggers / received triggers
- history append success rate = append success / (append success + append failure)
- duplicate-trigger rate = duplicate triggers / received triggers
- expired-without-evidence rate = expired-without-evidence / total classifications
- validation-window-open ratio = open window evaluations / (open + closed)
- scheduler success rate = completed / candidates

Idempotent duplicates are excluded from append-failure ratios.

## 23. Grafana panels

Provisioned dashboard: `docker/grafana/provisioning/dashboards/parkio-outcome-validation.json`.

Panels cover:
- evaluation success rate
- append failure rate
- duplicate trigger rate
- trigger distribution
- classification distribution
- confidence-band distribution
- validation-window ratio
- expired-without-evidence ratio
- processing latency p50/p95/p99
- replay mismatch/failure totals

## 24. Security/privacy

Repository guardrails preserved:
- no public API/controller added for outcome history or replay
- no IDs in metric tags
- no raw entity serialization stored; mapper writes canonical snapshot JSON only
- no caller-supplied policy version or cutoff accepted from HTTP/Kafka inputs
- no trust mutation: `OutcomeTrustConsumerPort` remains a future boundary

## 25. Retention open question

The repository now stores durable outcome history but does not yet define a business retention TTL beyond DB retention. Because the FK mirrors `decision_audit`, deleting a spot with retained outcome history is intentionally blocked today.

## 26. Backward-compatibility proof

No code in this change set alters:
- decision authority selection (`com.parkio.parking.decision.authority.*`)
- decision audit append semantics (`decision_audit`)
- availability engine packages
- public controllers / DTO contracts
- Kafka envelope/contracts
- trust/reward/search integration

Outcome enqueueing is additive and failure-isolated; the primary lifecycle methods still call the same aggregate methods and outbox appenders in `ParkingApplicationService`.

## 27. Exact files and symbols

Primary symbols introduced/changed:
- `OutcomeValidationApplicationService.process(...)`
- `OutcomeEvaluationIdentity.forTrigger(...)`
- `OutcomeHistoryRecord`
- `OutcomeHistoryRepositoryAdapter.append(...)`
- `OutcomeEvaluationTriggerRepositoryAdapter.enqueue(...)`
- `OutcomeValidationTriggerJob.processPendingTriggers()`
- `ParkingApplicationService.enqueueOutcomeTrigger(...)`
- `OutcomeHistoricalEvidenceFactory.create(...)`
- `OutcomeValidationMetrics`
- `V22__outcome_operationalization.sql`

## 28. WP-05.11 Trust Engine prerequisites

WP-05.10A now provides the durable history boundary WP-05.11 needs:
- append-only validated outcome history
- latest-at-or-before reads
- deterministic replay inputs
- explicit trigger taxonomy
- no direct trust mutation coupling

What still remains before Trust work can safely start:
- explicit consumer/read contract for trust pull ingestion
- full-suite validation of operationalization
- decision on scheduled window-closure evaluation

## 29. Deferred scope

Deferred from WP-05.10A despite the new pipeline:
- scheduled `SCHEDULED_WINDOW_CHECK` trigger generation
- manual replay operational tooling
- public/internal API exposure for latest outcome queries
- trust/reward/search/authority feedback loops