# WP-05.12 Pending Reward Engine - Shadow Mode

## 1. Executive summary

WP-05.12 introduces a standalone shadow-only reward module in
`com.parkio.parking.reward`. It consumes immutable
`OutcomeHistoryRecord` facts, maps them into canonical reporter-side
`RewardContribution` inputs, evaluates them with a deterministic
`RewardEngine`, and appends immutable `PendingRewardIntent` rows into
`pending_reward_ledger`. No points are granted, no gamification balance
is mutated, and no public API or Kafka contract changes are introduced.

## 2. Scope and non-goals

In scope:
- reporter-only pending reward intent
- immutable append-only ledger
- deterministic replay
- internal scheduler, metrics, Prometheus rules, Grafana dashboard
- PostgreSQL-backed migration and concurrency verification

Out of scope:
- settlement, point grants, level updates, achievements
- verifier/claimant/filled-report rewards
- reward-driven Decision, Authority, Availability, Search, Trust, or Fraud behavior
- public/admin reward APIs or Kafka contracts

## 3. Repository-backed gamification/reward audit

- Reward ownership today lives in `services/gamification-service/src/main/java/com/parkio/gamification/application/GamificationApplicationService.java`
  symbol `GamificationApplicationService`.
- Current point history is `services/gamification-service/src/main/java/com/parkio/gamification/domain/PointTransaction.java`
  symbol `PointTransaction`.
- Current balance/level state is
  `services/gamification-service/src/main/java/com/parkio/gamification/domain/UserLevelProgress.java`
  symbol `UserLevelProgress`.
- Current reward rules are seeded through
  `services/gamification-service/src/main/resources/db/migration/V4__create_reward_rules.sql`
  and keyed by
  `services/gamification-service/src/main/java/com/parkio/gamification/application/RewardRuleKeys.java`
  symbol `RewardRuleKeys`.
- Current gamification ingress is Kafka/event-driven through
  `services/gamification-service/src/main/java/com/parkio/gamification/infrastructure/messaging/ParkingEventsKafkaConsumer.java`
  symbol `ParkingEventsKafkaConsumer`.
- Durable validated outcome source-of-truth for the new shadow path is
  `services/parking-service/src/main/java/com/parkio/parking/outcome/history/OutcomeHistoryRecord.java`
  symbol `OutcomeHistoryRecord`.

## 4. Existing legacy reward behavior

- Legacy issuance is event-driven, immediate, and publication/lifecycle-oriented, not outcome-finality-oriented, in
  `GamificationApplicationService.handleParkingSpotActivated`,
  `handleParkingSpotVerified`, and `handleParkingSpotClaimed`.
- Activation rewards are tied to parking lifecycle events copied from parking-service event contracts, not to durable outcome history, through
  `services/gamification-service/src/main/java/com/parkio/gamification/application/event/ParkingSpotActivatedEvent.java`
  and the consumer dispatch in `ParkingEventsKafkaConsumer`.
- Idempotency exists only at the gamification point-transaction boundary through
  `PointTransaction.idempotencyKey` and
  `services/gamification-service/src/main/java/com/parkio/gamification/application/port/PointTransactionRepository.java`
  symbol `PointTransactionRepository`.
- Existing gamification does not expose a repository-backed pending/settlement/cancellation policy model for rewards; it directly records earned or deducted point transactions in `GamificationApplicationService.applyPoints`.

## 5. Reward ownership boundary

- `gamification-service` remains the owner of balances, levels, and legacy reward history through `UserLevelProgress`, `PointTransaction`, and `GamificationApplicationService`.
- `parking-service` now owns only shadow reward calculation and immutable pending reward intent through
  `services/parking-service/src/main/java/com/parkio/parking/application/RewardShadowApplicationService.java`
  symbol `RewardShadowApplicationService` and
  `services/parking-service/src/main/java/com/parkio/parking/application/port/RewardLedgerPort.java`
  symbol `RewardLedgerPort`.
- No settlement boundary is invoked in WP-05.12.

## 6. Supported and unsupported contribution roles

- `REPORTER`: `SUPPORTED_NOW`. Stable durable linkage exists through
  `services/parking-service/src/main/java/com/parkio/parking/domain/ParkingSpot.java`
  symbol `ownerUserId`,
  `OutcomeHistoryRecord.parkingSpotId`, and
  `services/parking-service/src/main/java/com/parkio/parking/infrastructure/persistence/jpa/OutcomeHistoryJpaRepository.java`
  symbol `claimPendingReporterRewards`.
- `VERIFIER`: `PARTIALLY_SUPPORTED`. Verification actor ids exist in
  `services/parking-service/src/main/java/com/parkio/parking/domain/ParkingSpotVerification.java`
  and `.../entity/ParkingSpotVerificationEntity.java`, but the durable reward input path still collapses to
  `services/parking-service/src/main/java/com/parkio/parking/outcome/normalization/OutcomeVerificationSignalData.java`
  symbol `OutcomeVerificationSignalData`, which does not carry `verifierUserId`.
- `CLAIMANT`: `UNSUPPORTED`. Claim actor ids are present on emitted lifecycle events such as
  `services/parking-service/src/main/java/com/parkio/parking/domain/event/ParkingSpotClaimedEvent.java`,
  but the append-only outcome history path does not durably preserve claimant identity for replay-safe reward attribution.
- `FILLED_REPORTER`: `UNSUPPORTED`. Filled-report evidence affects outcome reasoning, but contributor identity is not carried into replay-safe reward input.
- `MODERATOR`: `NOT_REWARDABLE`. Moderator actions are operational authority, not end-user contribution.
- `SYSTEM` / expiration / AI evidence: `NOT_REWARDABLE`. These are system facts or auxiliary evidence sources, not reward subjects.

## 7. Decision / Availability / Outcome / Trust / Reward separation

- Outcome remains the source of reality validation in `com.parkio.parking.outcome`.
- Trust remains future-evidence weighting in `com.parkio.parking.trust`.
- Reward remains outcome-based benefit eligibility in `com.parkio.parking.reward`.
- `RewardEngine` does not read trust snapshots, repositories, or parking state directly.
- `RewardShadowApplicationService` does not write Trust, Decision, Authority, Availability, Outcome, ParkingSpot, or gamification state.

## 8. Reward domain model

Primary symbols:
- `RewardContribution`
- `RewardSubject`
- `RewardAmount`
- `RewardUnit`
- `RewardEvaluationContext`
- `RewardEvaluation`
- `RewardPolicyConfig`
- `RewardEngine`
- `PendingRewardIntent`
- `RewardReplayer`
- `RewardReplayComparison`

All live in `services/parking-service/src/main/java/com/parkio/parking/reward`.

## 9. Outcome-to-reward mapping

`services/parking-service/src/main/java/com/parkio/parking/reward/ValidatedRewardContributionFactory.java`
symbol `reporterContribution` maps durable outcomes as follows:

- `CONFIRMED_CORRECT` with direct/strong reporter attribution -> rewardable pending intent.
- `LIKELY_CORRECT`, `LIKELY_INCORRECT`, and `UNKNOWN` -> deferred finality, not grant-equivalent.
- `CONFIRMED_INCORRECT` -> no reward.
- `EXPIRED_WITHOUT_EVIDENCE` -> no reward, not a penalty.

The engine then converts only `ELIGIBLE + CONFIRMED_CORRECT` into `RewardEvaluation.Disposition.PENDING` in
`RewardEngine.evaluate`.

## 10. Attribution model

`RewardContribution.AttributionQuality` is explicit:
- `DIRECT`
- `STRONG`
- `PARTIAL`
- `AMBIGUOUS`
- `NONE`

Current reporter mapping is chosen by `ValidatedRewardContributionFactory.attributionQuality(...)`.
Direct and strong attribution may earn rewards; ambiguous or none do not.

## 11. Self-confirmation and duplicate protections

- One logical reward contribution identity is created per `(parkingSpotId, reporterUserId)` by
  `ValidatedRewardContributionFactory.reporterContribution`.
- `RewardContribution.evidenceGroupId` equals the deterministic contribution id, so repeated final outcome delivery for the same contribution cannot create multiple full rewards.
- The ledger unique constraint on `source_contribution_id` in
  `services/parking-service/src/main/resources/db/migration/V24__pending_reward_shadow_ledger.sql`
  enforces one first-final-label row per contribution under v1.
- Because verifier/claimant/fill roles are not implemented, one actor cannot currently stack multiple reward roles through the shadow path.

## 12. Cold-start fairness

- Reward v1 does not read trust state at all.
- A new user can earn a reward solely from a confirmed, directly/strongly attributed validated outcome.
- `RewardEngine.evaluate` therefore treats no-trust-history as irrelevant rather than punitive.

## 13. Trust input decision

Trust is intentionally excluded from reward v1. This keeps reward outcome-first, avoids rich-get-richer coupling, and preserves cold-start fairness. Repository integration point:
`RewardShadowApplicationService.process` does not load `TrustSnapshotReadPort` or any trust adapter.

## 14. Reward amount / unit model

- Canonical unit is `RewardUnit.POINTS`, aligned with gamification terminology.
- `RewardAmount` is an immutable non-negative integer.
- Zero reward is explicit and valid for `NO_REWARD` and `DEFERRED`.

## 15. Mathematical policy

`RewardPolicyConfig.referenceV1()` defines immutable integer-only basis-point math:
- base reporter points
- attribution multipliers
- confidence multipliers
- minimum reward threshold
- maximum reward cap per contribution

`RewardEngine` uses rounded integer basis-point multiplication only.

## 16. Finality / revision model

WP-05.12 uses a first-final-label model:
- only final classifications are drained by `OutcomeHistoryJpaRepository.claimPendingReporterRewards`
- first appended ledger row for a logical contribution wins
- no revision or supersession rows are created in v1

This keeps persistence append-only and deterministic while avoiding multi-reward drift from repeated outcome snapshots.

## 17. RewardEngine architecture

`RewardEngine` is pure, deterministic, side-effect free, framework-free, and clock-free. It depends only on `RewardContribution`, `RewardEvaluationContext`, and `RewardPolicyConfig`.

## 18. PendingRewardIntent

`PendingRewardIntent` stores:
- deterministic reward intent id
- deterministic evaluation id
- reward subject and role
- source outcome / contribution / parking spot identities
- policy and attribution versions
- disposition, amount, unit, eligibility, reason
- outcome classification and confidence band
- replay payloads (`RewardContribution` and `RewardEvaluation`)

No emails, usernames, coordinates, raw JPA entities, or Kafka payloads are stored.

## 19. Ledger and persistence

- Port: `RewardLedgerPort`
- Adapter: `PendingRewardLedgerRepositoryAdapter`
- Entity: `PendingRewardLedgerEntity`
- Repository: `PendingRewardLedgerJpaRepository`
- Migration: `V24__pending_reward_shadow_ledger.sql`

Append-only is enforced by API shape and adapter behavior: the adapter uses explicit SQL `INSERT`, not JPA merge semantics.

## 20. Idempotency identity

Deterministic identity is built from:
- source parking spot id
- reporter user id
- contribution role
- `reward-policy-v1`

This is created in `ValidatedRewardContributionFactory.reporterContribution` and persisted uniquely as `source_contribution_id`.

## 21. Transaction and concurrency model

- Scheduler claim happens in `RewardShadowJob.processPendingOutcomes`.
- Each row executes in its own `PROPAGATION_REQUIRES_NEW` transaction in `RewardShadowRowProcessor.process`.
- Duplicate unique-constraint collisions become `DuplicatePendingRewardIntentException` and surface as `RewardShadowProcessingResult.Status.DUPLICATE`.
- Concurrent same-evidence processing produces one logical row; concurrent distinct-spot contributions for the same subject both survive.

## 22. Outcome delivery and scheduler

- Read port: `ValidatedOutcomeForRewardReadPort`
- Adapter: `ValidatedOutcomeForRewardReadRepositoryAdapter`
- Claim query: `OutcomeHistoryJpaRepository.claimPendingReporterRewards`
- Scheduler: `RewardShadowJob`

The worker drains durable outcome history rather than request payloads or legacy parking events.

## 23. Failure isolation

Reward shadow runs asynchronously after outcome history append. Failures therefore do not roll back:
- parking lifecycle writes
- durable outcome history
- trust shadow
- gamification legacy events

## 24. Replay

`RewardReplayer.replay(PendingRewardIntent)` re-evaluates the stored immutable `RewardContribution`
under the stored policy and schema version, then returns `RewardReplayComparison`.
Replay does not append, settle, or mutate gamification.

## 25. Versioning

Separated dimensions:
- policy version: `RewardPolicyConfig.POLICY_VERSION`
- snapshot schema version: `RewardSnapshotSchemaVersion`
- attribution mapping version: `ValidatedRewardContributionFactory.ATTRIBUTION_MAPPING_VERSION`

Unknown policy versions fail explicitly via `UnsupportedRewardPolicyVersionException`.

## 26. Legacy comparison

Legacy reward comparison is explicitly deferred. The repository proves that current gamification reward issuance is event-driven and immediate, but there is no durable reward-intent history keyed to outcome-finality that would let WP-05.12 compare shadow reward and legacy reward without inventing new semantics.

## 27. Settlement boundary

No settlement port is invoked in WP-05.12. Future work can publish or transfer `PendingRewardIntent`
into gamification settlement, but this package does not mutate balances.

## 28. Metrics catalogue

Micrometer component:
`services/parking-service/src/main/java/com/parkio/parking/infrastructure/metrics/RewardShadowMetrics.java`
symbol `RewardShadowMetrics`

Families emitted:
- `parkio.parking.reward.outcome.received`
- `parkio.parking.reward.contribution.produced`
- `parkio.parking.reward.contribution.skipped`
- `parkio.parking.reward.evaluation.success`
- `parkio.parking.reward.evaluation.duplicate`
- `parkio.parking.reward.evaluation.failure`
- `parkio.parking.reward.disposition`
- `parkio.parking.reward.evaluation.duration`
- `parkio.parking.reward.replay.success`
- `parkio.parking.reward.replay.mismatch`
- `parkio.parking.reward.replay.failure`
- scheduler candidate/completed/failed and processing-result metrics

## 29. Prometheus ratio definitions

`docker/prometheus/reward-shadow-recording-rules.yml` defines:
- `parkio:reward_shadow:success_rate5m`
- `parkio:reward_shadow:duplicate_rate5m`
- `parkio:reward_shadow:failure_rate5m`
- `parkio:reward_shadow:eligible_ratio5m`

Duplicates are isolated from failures. Eligible ratio uses produced contributions as denominator.

## 30. Grafana dashboard

`docker/grafana/provisioning/dashboards/parkio-reward-shadow.json` provides:
- success / duplicate / eligible stats
- replay mismatch stat
- disposition distribution
- amount-band distribution
- contribution-role distribution
- attribution-quality distribution
- outcome-classification distribution
- p95 evaluation latency

## 31. Security / privacy / fairness

- no public endpoint
- no user-visible pending amount
- no gamification mutation
- no negative reward or penalty
- no PII fields in the ledger
- no coordinates or device data in metrics
- no subject ids or exact amounts in labels
- no trust-history suppression path

## 32. Retention / account-deletion review

`pending_reward_ledger` references `outcome_history` and `parking_spots`, but not cross-service user tables.
This preserves internal audit linkage while avoiding cross-service cascading deletes.
User erasure/pseudonymization policy for internal UUID-linked audit rows remains a product/legal follow-up.

## 33. PostgreSQL verification

Verification lives in:
- `RewardShadowMigrationPostgresIT`
- `RewardShadowPersistencePostgresIT`

Covered repository-backed proofs:
- fresh schema migrates through V24
- V23 -> V24 upgrade succeeds
- append-only insert semantics work
- duplicate outcome delivery is idempotent
- concurrent same-evidence processing produces one logical intent
- concurrent distinct contributions preserve both intents
- replay matches stored evaluation

## 34. Backward-compatibility proof

No runtime integration was added from reward shadow into:
- Decision Engine
- Authority
- Availability
- Outcome classification
- Trust
- search
- controllers
- Kafka contracts
- gamification balances / levels / achievements

All work remains internal to parking-service and shadow-only.

## 35. Exact files and symbols

Primary reward symbols:
- `RewardEngine.evaluate`
- `ValidatedRewardContributionFactory.reporterContribution`
- `RewardShadowApplicationService.process`
- `RewardShadowJob.processPendingOutcomes`
- `OutcomeHistoryJpaRepository.claimPendingReporterRewards`
- `PendingRewardLedgerRepositoryAdapter.append`
- `RewardReplayer.replay`

## 36. WP-05.13 prerequisites

Adaptive exposure should consume:
- durable validated outcomes
- reward shadow analytics
- separately governed trust/fraud inputs

It should not read pending reward ledger as if it were granted balance.

## 37. Deferred scope and open questions

- verifier/claimant/fill role attribution hardening
- revision/supersession model for stronger later evidence
- explicit settlement contract into gamification
- retention and pseudonymization policy for internal subject UUIDs
- legacy shadow-vs-authority comparison once durable comparable legacy reward intent exists
