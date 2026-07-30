# Observability — Metrics (Micrometer + Prometheus)

Every backend service exports Micrometer metrics in Prometheus format at
`GET /actuator/prometheus`. The endpoint is exposed alongside `health` and `info`
only — no sensitive actuator endpoints (`env`, `beans`, `heapdump`, ...) are ever
exposed. The per-service `GatewayAuthFilter` deliberately skips `/actuator/**`, so
Prometheus can scrape services directly without the gateway secret.

All metrics additionally carry an `application` tag
(`management.metrics.tags.application = ${spring.application.name}`), matching the
`service` label applied by the local Prometheus scrape config.

## Local stack

`docker/prometheus/prometheus.yml` scrapes `/actuator/prometheus` from all ten
services over the `parkio-backend` Docker network every 15s.

- Prometheus UI: <http://localhost:9090> (Status → Targets shows per-service health;
  `/alerts` shows alert-rule state)
- Alertmanager UI: <http://localhost:9093> (bound to loopback by default; hosted beta uses
  an SSH tunnel)
- Loki API: <http://localhost:3100> (bound to loopback; normal access is through Grafana)
- Tempo API: <http://localhost:3200> (`TEMPO_PORT`, default 3200; bound to loopback — the
  OTLP receivers on 4317/4318 are never published, access traces through Grafana)
- Grafana: <http://localhost:3000> (`GRAFANA_PORT`, default 3000; Prometheus is the
  provisioned default datasource, Loki is provisioned for logs, Tempo is provisioned for
  traces, and the "Parkio — Hosted Beta Overview" dashboard is bundled — see Alerting &
  dashboards below)
- Ad-hoc check: `curl http://localhost:8081/actuator/prometheus | grep parkio_`

Spring Boot's built-in Micrometer bindings (JVM memory/GC, process CPU,
`http.server.requests`, HikariCP, Kafka client metrics where applicable) are exported
automatically in addition to the custom metrics below.

## Custom metric catalogue

Prometheus rendering replaces dots with underscores and suffixes counters with
`_total` (e.g. `parkio.auth.login.success.count` → `parkio_auth_login_success_count_total`).

### Outbox / inbox (every service with an outbox and/or inbox)

| Metric | Type | Services | Meaning |
|---|---|---|---|
| `parkio.outbox.unpublished.count` | gauge | auth, user, parking, media, gamification, notification, moderation, ai-validation | Relayable outbox rows (`published = false AND dead_lettered = false`). Sustained growth ⇒ the outbox relay is not draining to Kafka. Dead-lettered rows are **excluded** so a poison row doesn't masquerade as backlog. |
| `parkio.outbox.oldest.unpublished.age.seconds` | gauge | same | Age of the oldest relayable row (0 when empty). Alert when it exceeds a few relay intervals. |
| `parkio.outbox.deadlettered.count` | gauge | auth, user, parking, media, gamification, moderation, ai-validation | Open dead-lettered (poison) outbox rows retained in-table for inspection/redrive. Acknowledged/suppressed rows are excluded so alerts stop after deliberate operator action. |
| `parkio.outbox.deadlettered.acknowledged.count` | gauge | same | Dead-lettered rows intentionally acknowledged/suppressed by an operator and retained for audit. |
| `parkio.outbox.deadlettered.oldest.age.seconds` | gauge | same | Age of the oldest open dead-lettered row. A high value means recovery did not happen or did not work. |
| `parkio.outbox.publish.failed` | counter | same as dead-lettered | Per-row publish attempts that failed (all causes: broker error, unreadable payload, no topic mapping). A rising rate signals broker/contract trouble before rows dead-letter. |
| `parkio.outbox.deadlettered` | counter | same | Rows that crossed into the dead-lettered state. Pairs with the gauge: the counter is the rate of new poison rows, the gauge is the current depth. |
| `parkio.outbox.publish.success` | counter | same as dead-lettered | Per-row publishes that were broker-acked. Together with `parkio.outbox.publish.failed` gives the relay's publish success ratio. |
| `parkio.outbox.publish.duration` | timer | same | Latency from relay dispatch to broker ack, per published row. Because sends are pipelined within a poll, this approximates per-row broker round-trip, not the serial sum. |
| `parkio.outbox.batch.size` | summary | same | Outbox rows claimed per relay poll (`FOR UPDATE SKIP LOCKED` batch). Sustained values at the configured batch ceiling ⇒ the relay is saturated and backlog will grow. |
| `parkio.outbox.recovery.retry.count` | gauge | same as dead-lettered | Durable count of operator retry actions recorded in `outbox_recovery_audit`. Use with open dead-letter count to detect failed redrive attempts. |
| `parkio.outbox.recovery.acknowledged.count` | gauge | same as dead-lettered | Durable count of operator acknowledge/suppress actions recorded in `outbox_recovery_audit`. |
| `parkio.inbox.processed.count` | gauge | auth, user, parking, gamification, notification, moderation, ai-validation, analytics | Inbox dedup rows currently retained. A flat line while events flow ⇒ the consumer stopped processing. Retention cleanup makes this dip periodically — that is expected. |

Gauges are backed by one cheap COUNT/MIN repository query per scrape; they never run
on a request path. The two outbox counters are incremented in the relay (no extra
query). The `parkio.outbox.publish.success` / `parkio.outbox.publish.failed` /
`parkio.outbox.deadlettered` counters render in Prometheus as `..._total` per the
suffix rule above; `parkio.outbox.publish.duration` and `parkio.outbox.batch.size`
are recorded once per published row / per poll in the relay (no extra query).

> **notification-service** exports the outbox backlog gauges but has **no relay yet**,
> so it does not emit the dead-letter gauge/counters. **analytics-service** has no
> producer outbox, so it exports neither.

### Notification delivery (notification-service)

| Metric | Type | Meaning |
|---|---|---|
| `parkio.notification.delivery.pending.count` | gauge | Attempts queued or awaiting a retry. Growth ⇒ worker not draining. |
| `parkio.notification.delivery.sent.count` | gauge | Attempts successfully handed to the provider (lifetime rows). |
| `parkio.notification.delivery.failed.count` | gauge | Terminally failed attempts (retries exhausted). |
| `parkio.notification.delivery.skipped.count` | gauge | Attempts intentionally skipped (e.g. no active device token). |
| `parkio.notification.delivery.worker.success.count` | counter | Sends handed off by this worker instance since start. |
| `parkio.notification.delivery.worker.failure.count` | counter | Failed sends (retried or terminal) by this worker instance. |

### Parking (parking-service)

| Metric | Type | Meaning |
|---|---|---|
| `parkio.parking.active.count` | gauge | Spots currently `ACTIVE`. |
| `parkio.parking.verified.count` | gauge | Spots currently `VERIFIED`. |
| `parkio.parking.suspicious.count` | gauge | Spots currently `SUSPICIOUS`. |
| `parkio.parking.expired.count` | gauge | Spots in the terminal `EXPIRED` state. |
| `parkio.parking.expiry.job.expired.count` | counter | Spots transitioned to `EXPIRED` by the expiry job since start. |

### Municipal parking sources (parking-service)

Micrometer component: `MunicipalSourceMetrics`. Labels bounded to `source_key`,
`status`, `error_category`, and `outcome` — never facility/external IDs or URLs.

| Metric | Type | Meaning |
|---|---|---|
| `parkio.municipal.sync.runs` | counter | Sync runs by status/error category. |
| `parkio.municipal.sync.duration` | timer | Sync wall time. |
| `parkio.municipal.sync.records` | counter | Records received/accepted/rejected. |
| `parkio.municipal.sync.facilities` | counter | Facilities inserted/updated/unchanged. |
| `parkio.municipal.sync.occupancy` | counter | Occupancy snapshots inserted. |
| `parkio.municipal.sync.schema_mismatch` | counter | Contract/schema failures. |

Health indicator `municipalSources` always contributes **UP** (municipal data is
non-critical). Details include enablement and `izumStatus` (`disabled`, `healthy`, `aging`, `stale`, `never_synced`, `failing`, `schema_mismatch`, …).

Ops: [`docs/operations/municipal-parking-source-runbook.md`](../operations/municipal-parking-source-runbook.md).

### Parking sessions — stale lifecycle (parking-service)

Micrometer component: `ParkingSessionLifecycleMetrics`. Never tagged by
`userId` / `sessionId`. Prometheus names below (dots → underscores; counters
gain `_total`).

| Metric (Prometheus) | Type | Meaning |
|---|---|---|
| `parking_sessions_active` | gauge | Approximate ACTIVE parking sessions (refreshed each scheduler tick). |
| `parking_sessions_auto_completed_total` | counter | Sessions auto-completed by the stale scheduler (`AUTO` / `AUTO_TIMEOUT`). |
| `parking_sessions_confirmation_total` | counter | Owner confirm-active heartbeats. |
| `parking_sessions_reminder_sent_total{stage}` | counter | Reminder outbox publishes; `stage=FIRST\|SECOND` only. |
| `parking_sessions_scheduler_processed_total` | counter | Candidate rows examined by the stale scheduler. |
| `parking_sessions_scheduler_failed_total` | counter | Scheduler tick failures. |
| `parking_sessions_scheduler_duration_seconds_*` | timer | Wall time of a full stale-session scheduler tick. |
| `parking_sessions_retention_deleted_total` | counter | Terminal rows deleted by retention (when enabled). |

Grafana dashboard: **Parkio — Parking Sessions (stale lifecycle)**  
(`docker/grafana/provisioning/dashboards/parkio-parking-sessions.json`).

Alerts (`docker/prometheus/alerts.yml` group `parkio-parking-session-stale`):

- `ParkingSessionSchedulerFailures`
- `ParkingSessionAutoCompleteSpike`
- `ParkingSessionSchedulerFailuresSustained` (repeated failures over 6h; not
  “reminder idle”)

Ops: [`docs/operations/parking-session-stale-runbook.md`](../operations/parking-session-stale-runbook.md).

### Decision shadow calibration (parking-service)

Micrometer component: `DecisionShadowMetrics` (`com.parkio.parking.infrastructure.metrics`).
Emits only when `parkio.parking.decision.shadow-enabled=true`. Success dimensions come from
`DecisionCalibrationObservation` — bounded enums only (never spot/event/user IDs or exact
`RiskScore`). See [wp-05-decision-calibration-shadow-analytics.md](./wp-05-decision-calibration-shadow-analytics.md).

Prometheus names (dots → underscores; counters gain `_total`; timers → `_seconds_*`):

| Metric (Prometheus) | Type | Tags | Meaning |
|---|---|---|---|
| `parkio_parking_decision_shadow_attempt_total` | counter | `policy_version` | Shadow evaluations attempted |
| `parkio_parking_decision_shadow_success_total` | counter | `policy_version` | Shadow evaluations completed |
| `parkio_parking_decision_shadow_failure_total` | counter | `policy_version`, `stage` | Failures by `ShadowFailureStage` |
| `parkio_parking_decision_shadow_duration_seconds_*` | timer | `policy_version` | Orchestration duration |
| `parkio_parking_decision_shadow_engine_duration_seconds_*` | timer | `policy_version` | Pure engine duration (optional helper) |
| `parkio_parking_decision_shadow_disposition_total` | counter | `policy_version`, `disposition` | Shadow `PublicationDisposition` |
| `parkio_parking_decision_shadow_comparison_total` | counter | `policy_version`, `category` | `ShadowComparisonCategory` |
| `parkio_parking_decision_shadow_risk_band_total` | counter | `policy_version`, `band` | `RiskBand` |
| `parkio_parking_decision_shadow_hard_constraint_family_total` | counter | `policy_version`, `family` | `HardConstraintFamily` |
| `parkio_parking_decision_shadow_decisive_rule_total` | counter | `policy_version`, `rule` | `DecisivePolicyRule` |
| `parkio_parking_decision_shadow_evidence_profile_total` | counter | `policy_version`, `profile` | `EvidenceAvailabilityProfile` |
| `parkio_parking_decision_shadow_legacy_kind_total` | counter | `policy_version`, `kind` | Legacy outcome kind |
| `parkio_parking_decision_shadow_legacy_status_total` | counter | `policy_version`, `status` | Legacy `ParkingSpotStatus` |
| `parkio_parking_decision_shadow_assessment_level_total` | counter | `policy_version`, `category`, `level` | Active category levels |
| `parkio_parking_decision_shadow_assessment_completeness_total` | counter | `policy_version`, `category`, `completeness` | Active category completeness |

Recording rules: `docker/prometheus/decision-shadow-recording-rules.yml`
(`parkio:decision_shadow:comparable_agreement_rate5m`, `more_restrictive_rate5m`,
`more_permissive_rate5m`, `failure_rate5m`, and related ratios).

Grafana: **Parkio - Decision Shadow Calibration**
(`docker/grafana/provisioning/dashboards/parkio-decision-shadow.json`).

**WP-05.6 provides parity and drift analytics, not correctness calibration.**

### Availability engine (parking-service, WP-05.9)

Micrometer component: `AvailabilityMetrics` (`com.parkio.parking.infrastructure.metrics`).
Bounded enum tags only — never spot IDs or exact scores. See
[wp-05-availability-engine.md](./wp-05-availability-engine.md).

| Metric (Prometheus) | Type | Tags | Meaning |
|---|---|---|---|
| `parkio_parking_availability_evaluation_total` | counter | `policy_version` | Evaluations recorded |
| `parkio_parking_availability_state_total` | counter | `policy_version`, `state` | `AvailabilityState` |
| `parkio_parking_availability_freshness_total` | counter | `policy_version`, `freshness` | Freshness band |
| `parkio_parking_availability_expiration_total` | counter | `policy_version`, `expired` | TTL expired at evaluation |
| `parkio_parking_availability_aging_total` | counter | `policy_version` | Aging or stale freshness |
| `parkio_parking_availability_evaluation_duration_seconds_*` | timer | `policy_version` | Evaluation duration |

Hot-path orchestration is not wired in WP-05.9; metrics activate when an observer records evaluations.

### Outcome validation (parking-service, WP-05.10)

Micrometer component: `OutcomeValidationMetrics` (`com.parkio.parking.infrastructure.metrics`).
Bounded enum tags only — never spot IDs or user IDs. See
[wp-05-outcome-validation.md](./wp-05-outcome-validation.md).

| Metric (Prometheus) | Type | Tags | Meaning |
|---|---|---|---|
| `parkio_parking_outcome_evaluation_total` | counter | `policy_version` | Evaluations recorded |
| `parkio_parking_outcome_classification_total` | counter | `policy_version`, `classification` | `OutcomeClassification` |
| `parkio_parking_outcome_validation_window_total` | counter | `policy_version`, `open` | Evaluations while window open |
| `parkio_parking_outcome_expired_without_evidence_total` | counter | `policy_version` | Expired with no post-publish evidence |
| `parkio_parking_outcome_evaluation_duration_seconds_*` | timer | `policy_version` | Evaluation duration |

Hot-path orchestration is not wired in WP-05.10; metrics activate when an observer records evaluations.

### Trust shadow (parking-service, WP-05.11)

Micrometer component: `TrustShadowMetrics` (`com.parkio.parking.infrastructure.metrics`).
Bounded tags only: `policy_version`, `snapshot_schema_version`, `subject_type`,
`trust_domain`, `evidence_type`, `contribution_role`, `attribution_quality`,
`eligibility_result`, `update_direction`, `trust_level`, `score_band`,
`confidence_band`, `failure_stage`. Never subject ids, outcome ids, spot ids, or
exact scores.

| Metric (Prometheus) | Type | Tags | Meaning |
|---|---|---|---|
| `parkio_parking_trust_outcome_received_total` | counter | none | Durable outcome candidates received by trust shadow |
| `parkio_parking_trust_evidence_produced_total` | counter | bounded evidence tags | Canonical trust evidence items produced |
| `parkio_parking_trust_evidence_skipped_total` | counter | bounded evidence tags + `eligibility_result` | Ineligible or ambiguous evidence skipped |
| `parkio_parking_trust_update_success_total` | counter | bounded evidence tags + level/direction bands | Successful shadow trust updates |
| `parkio_parking_trust_update_duplicate_total` | counter | bounded evidence tags | Idempotent duplicate deliveries |
| `parkio_parking_trust_update_failure_total` | counter | bounded evidence tags + `failure_stage` | Failed trust updates |
| `parkio_parking_trust_evaluation_duration_seconds_*` | timer | none | Trust evaluation/orchestration duration |
| `parkio_parking_trust_replay_success_total` | counter | none | Single-entry replay matched stored evaluation |
| `parkio_parking_trust_replay_mismatch_total` | counter | none | Replay mismatch detected |
| `parkio_parking_trust_replay_failure_total` | counter | none | Replay failed before comparison |

Recording rules: `docker/prometheus/trust-shadow-recording-rules.yml`

Grafana: `docker/grafana/provisioning/dashboards/parkio-trust-shadow.json`

Validation closure:
- `promtool check config docker/prometheus/prometheus.yml`
- `promtool check rules docker/prometheus/trust-shadow-recording-rules.yml`
- compose mount path: `docker/docker-compose.yml` `prometheus.volumes`
- Grafana provider path: `docker/grafana/provisioning/dashboards/dashboards.yml`

### Reward shadow (parking-service, WP-05.12)

Micrometer component: `RewardShadowMetrics` (`com.parkio.parking.infrastructure.metrics`).
Bounded tags only: `policy_version`, `snapshot_schema_version`, `attribution_version`,
`subject_type`, `contribution_role`, `attribution_quality`, `outcome_classification`,
`outcome_confidence_band`, `eligibility`, `disposition`, `reward_unit`, `amount_band`,
`failure_stage`, `status`. Never subject ids, outcome ids, spot ids, or exact reward amounts.

| Metric (Prometheus) | Type | Tags | Meaning |
|---|---|---|---|
| `parkio_parking_reward_outcome_received_total` | counter | none | Durable outcome candidates received by reward shadow |
| `parkio_parking_reward_contribution_produced_total` | counter | bounded contribution tags | Canonical reward contributions produced |
| `parkio_parking_reward_contribution_skipped_total` | counter | bounded contribution tags + `eligibility` | Contributions skipped as non-rewardable |
| `parkio_parking_reward_evaluation_success_total` | counter | bounded contribution tags + `disposition`, `reward_unit`, `amount_band` | Successful reward-shadow evaluations appended |
| `parkio_parking_reward_evaluation_duplicate_total` | counter | bounded contribution tags | Idempotent duplicate deliveries |
| `parkio_parking_reward_evaluation_failure_total` | counter | bounded contribution tags + `failure_stage` | Failed reward-shadow evaluations |
| `parkio_parking_reward_disposition_total` | counter | `disposition` | Distribution of pending vs no-reward outcomes |
| `parkio_parking_reward_evaluation_duration_seconds_*` | timer | none | Reward evaluation/orchestration duration |
| `parkio_parking_reward_replay_success_total` | counter | none | Replay matched stored evaluation |
| `parkio_parking_reward_replay_mismatch_total` | counter | none | Replay mismatch detected |
| `parkio_parking_reward_replay_failure_total` | counter | none | Replay failed before comparison |

Recording rules: `docker/prometheus/reward-shadow-recording-rules.yml`

Grafana: `docker/grafana/provisioning/dashboards/parkio-reward-shadow.json`

Validation:
- `promtool check config docker/prometheus/prometheus.yml`
- `promtool check rules docker/prometheus/reward-shadow-recording-rules.yml`
- compose mount path: `docker/docker-compose.yml` `prometheus.volumes`
- Grafana provider path: `docker/grafana/provisioning/dashboards/dashboards.yml`

### Exposure shadow (parking-service, WP-05.13)

Micrometer component: `ExposureShadowMetrics` (`com.parkio.parking.infrastructure.metrics`).
Bounded tags only: `policy_version`, `search_type`, `skip_reason`, `eligibility`, `disposition`,
`score_band`, `availability_state`, `freshness_band`, `distance_band`, `vehicle_match`, `trust_level`,
`movement_band`, `candidate_count_band`, `same_top1`, `failure_stage`. Never user ids, spot ids,
candidate ids, exact coordinates, exact scores, or exact ranks.

| Metric (Prometheus) | Type | Tags | Meaning |
|---|---|---|---|
| `parkio_parking_exposure_request_received_total` | counter | none | Nearby searches observed by exposure shadow hook |
| `parkio_parking_exposure_request_sampled_total` | counter | none | Deterministic sample selected for evaluation |
| `parkio_parking_exposure_request_skipped_total` | counter | `skip_reason` | Disabled or not sampled (not a failure) |
| `parkio_parking_exposure_candidate_evaluated_total` | counter | bounded evaluation tags | Per-candidate shadow evaluation |
| `parkio_parking_exposure_evaluation_success_total` | counter | `policy_version`, `search_type`, `same_top1`, `movement_band`, `candidate_count_band` | Completed shadow comparison for a sampled request |
| `parkio_parking_exposure_evaluation_failure_total` | counter | `failure_stage` | Shadow evaluation failure (search still succeeds) |
| `parkio_parking_exposure_time_budget_exceeded_total` | counter | none | Time budget exceeded before finishing all candidates |
| `parkio_parking_exposure_evaluation_duration_seconds_*` | timer | none | Request-path shadow evaluation duration |
| `parkio_parking_exposure_replay_success_total` | counter | none | Snapshot replay matched stored evaluation |
| `parkio_parking_exposure_replay_mismatch_total` | counter | none | Snapshot replay mismatch detected |

Recording rules: `docker/prometheus/exposure-shadow-recording-rules.yml`

Grafana: `docker/grafana/provisioning/dashboards/parkio-exposure-shadow.json`

Validation:
- `promtool check config docker/prometheus/prometheus.yml`
- `promtool check rules docker/prometheus/exposure-shadow-recording-rules.yml`
- compose mount path: `docker/docker-compose.yml` `prometheus.volumes`
- Grafana provider path: `docker/grafana/provisioning/dashboards/dashboards.yml`

### Fraud shadow (parking-service, WP-05.14)

Micrometer component: `FraudShadowMetrics` (`com.parkio.parking.infrastructure.metrics`).
Bounded tags only: `policy_version`, `schema_version`, `mapping_version`,
`aggregation_version`, `subject_type`, `fraud_domain`, `risk_band`, `confidence_band`,
`disposition`, `evidence_volume_band`, `failure_stage`, `processing_result`. Never
subject ids, outcome ids, spot ids, exact scores, or exact evidence counts.

| Metric (Prometheus) | Type | Tags | Meaning |
|---|---|---|---|
| `parkio_parking_fraud_candidate_received_total` | counter | none | Outcome candidates received by fraud shadow |
| `parkio_parking_fraud_feature_produced_total` | counter | bounded feature tags | Feature vectors produced from aggregates |
| `parkio_parking_fraud_candidate_skipped_total` | counter | bounded feature tags | Candidates skipped (not a failure) |
| `parkio_parking_fraud_evaluation_success_total` | counter | bounded evaluation tags | Successful shadow evaluations appended |
| `parkio_parking_fraud_evaluation_duplicate_total` | counter | bounded feature tags | Idempotent duplicate deliveries |
| `parkio_parking_fraud_evaluation_failure_total` | counter | bounded tags + `failure_stage` | Failed evaluations (source domains unaffected) |
| `parkio_parking_fraud_evaluation_duration_seconds_*` | timer | none | Fraud evaluation/orchestration duration |
| `parkio_parking_fraud_replay_success_total` | counter | none | Snapshot replay matched stored evaluation |
| `parkio_parking_fraud_replay_mismatch_total` | counter | none | Replay mismatch detected |
| `parkio_parking_fraud_replay_failure_total` | counter | none | Replay failed before comparison |
| `parkio_parking_fraud_scheduler_candidates_total` | counter | none | Candidates claimed per scheduler tick |
| `parkio_parking_fraud_scheduler_completed_total` | counter | none | Non-failed rows processed per tick |
| `parkio_parking_fraud_scheduler_failed_total` | counter | none | Scheduler tick failures |

Recording rules: `docker/prometheus/fraud-shadow-recording-rules.yml`

Grafana: `docker/grafana/provisioning/dashboards/parkio-fraud-shadow.json`

Validation:
- `promtool check config docker/prometheus/prometheus.yml`
- `promtool check rules docker/prometheus/fraud-shadow-recording-rules.yml`
- compose mount path: `docker/docker-compose.yml` `prometheus.volumes`
- Grafana provider path: `docker/grafana/provisioning/dashboards/dashboards.yml`

### Continuous calibration (parking-service, WP-05.15)

Micrometer component: `ContinuousCalibrationMetrics` (`com.parkio.parking.infrastructure.metrics`).
Bounded tags only: `engine_type`, `failure_stage`, `report_status`, `readiness_status`,
`status` (processing result). Never spot/evaluation/cohort/user ids or exact scores.

| Metric (Prometheus) | Type | Tags | Meaning |
|---|---|---|---|
| `parkio_parking_calibration_scheduler_candidates` | summary | `engine_type` | Candidates claimed per scheduler tick |
| `parkio_parking_calibration_candidate_received_total` | counter | `engine_type` | Calibration candidate received |
| `parkio_parking_calibration_observation_success_total` | counter | `engine_type` | Observation successfully appended |
| `parkio_parking_calibration_observation_appended_total` | counter | `engine_type` | Explicit observation append |
| `parkio_parking_calibration_observation_duplicate_total` | counter | `engine_type` | Idempotent duplicate observation |
| `parkio_parking_calibration_observation_failure_total` | counter | `engine_type`, `failure_stage` | Observation build/append failure |
| `parkio_parking_calibration_report_generated_total` | counter | `engine_type`, `report_status` | Calibration report generated |
| `parkio_parking_calibration_report_duplicate_total` | counter | `engine_type` | Idempotent duplicate report |
| `parkio_parking_calibration_report_failure_total` | counter | `engine_type`, `failure_stage` | Report generation failure |
| `parkio_parking_calibration_report_duration_seconds_*` | timer | none | Report generation duration |
| `parkio_parking_calibration_readiness_assessed_total` | counter | `engine_type`, `readiness_status` | Readiness assessment written |
| `parkio_parking_calibration_replay_success_total` | counter | `engine_type` | Snapshot replay matched |
| `parkio_parking_calibration_replay_mismatch_total` | counter | `engine_type` | Snapshot replay mismatch |
| `parkio_parking_calibration_replay_failure_total` | counter | `engine_type` | Replay failed before comparison |
| `parkio_parking_calibration_scheduler_completed_total` | counter | `engine_type` | Non-failed rows completed per tick |
| `parkio_parking_calibration_scheduler_failed_total` | counter | none | Scheduler tick failure |
| `parkio_parking_calibration_processing_result_total` | counter | `engine_type`, `status` | Batch processing outcome |

Recording rules: `docker/prometheus/continuous-calibration-recording-rules.yml`

Grafana: `docker/grafana/provisioning/dashboards/parkio-continuous-calibration.json`

Validation:
- `promtool check config docker/prometheus/prometheus.yml`
- `promtool check rules docker/prometheus/continuous-calibration-recording-rules.yml`
- compose mount path: `docker/docker-compose.yml` `prometheus.volumes`
- Grafana provider path: `docker/grafana/provisioning/dashboards/dashboards.yml`

### Auth (auth-service)

| Metric | Type | Meaning |
|---|---|---|
| `parkio.auth.login.success.count` | counter | Successful logins. |
| `parkio.auth.login.failure.count` | counter | Failed logins (bad credentials, suspended account, ...). A spike suggests credential stuffing. |

No email, user id, or other PII is ever used as a tag.

### Media (media-service)

| Metric | Type | Meaning |
|---|---|---|
| `parkio.media.upload.count` | counter | Uploads accepted and stored (idempotent replays are not re-counted). |
| `parkio.media.rejected.count` | counter | Uploads rejected by validation. |

### Gateway (gateway-service)

| Metric | Type | Meaning |
|---|---|---|
| `parkio.gateway.rate.limit.rejected.count` | counter | Requests answered `429` at the edge (the Redis `RequestRateLimiter`). Backend services never return 429 themselves. |

Spring Cloud Gateway's built-in `spring.cloud.gateway.requests` timer (per route/status)
is also exported.

## Kafka DLT and consumer lag — Kafka exporter

There is intentionally **no app-level `parkio.kafka.consumer.dlt.count`**: services publish
poison messages to `parkio.dlt.<service>` and move on; consuming the DLTs from the apps just
to count them would couple triage to runtime. Hosted beta monitors this at the broker via
`parkio-kafka-exporter` (`danielqsj/kafka-exporter`), scraped by Prometheus as
`job="kafka-exporter"`.

The exporter connects to the private broker at `kafka:9092`, filters to Parkio topics
(`parkio.*`) and service consumer groups (`*-service`), and exposes:

| Metric | Meaning |
|---|---|
| `kafka_consumergroup_lag` | Lag by consumer group, topic, and partition. |
| `kafka_topic_partition_current_offset` | Topic partition high-water offset. |
| `kafka_topic_partition_oldest_offset` | Oldest retained topic partition offset. |
| `kafka_brokers` | Number of Kafka brokers visible to the exporter. |

DLT topics follow the convention documented in `kafka-transport.md`: `parkio.dlt.<service>`.
For delete-retention DLT topics, retained offset depth is calculated as
`current_offset - oldest_offset`; this is the broker-side proxy for poison messages awaiting
inspection/redrive. It is not an application business counter.

Useful Grafana/Prometheus queries:

```promql
max by (consumergroup, topic) (kafka_consumergroup_lag{consumergroup=~".*-service",topic=~"parkio\\..+"})
sum by (topic) (kafka_topic_partition_current_offset{topic=~"parkio\\.dlt\\..+"} - kafka_topic_partition_oldest_offset{topic=~"parkio\\.dlt\\..+"})
up{job="kafka-exporter"}
kafka_brokers
```

Spring's Kafka client metrics (consumer fetch/commit rates) are exported per service via the
automatic Micrometer binding and complement the broker-side view.

## Host metrics — Node exporter

The hosted beta runs on a **single VPS**, so host-level saturation (disk, inodes, memory, CPU)
is a direct production risk: a full disk stalls Postgres, the outbox relay, Loki and MinIO at
once. `parkio-node-exporter` (`prom/node-exporter`) collects host metrics, scraped by
Prometheus as `job="node-exporter"`.

The exporter mounts host `/proc`, `/sys` and `/` **read-only** and reports filesystem paths
through `--path.rootfs`, so mountpoints read as the host sees them (e.g. `mountpoint="/"`).
Pseudo/overlay/bind mounts are dropped by the collector excludes so filesystem alerts track
real host filesystems only. It is **never publicly exposed** — bound to `127.0.0.1:9100` for
admin/debug and scraped in-network as `node-exporter:9100`.

| Metric | Meaning |
|---|---|
| `node_filesystem_avail_bytes` / `node_filesystem_size_bytes` | Disk free / total per filesystem. |
| `node_filesystem_files_free` / `node_filesystem_files` | Inodes free / total per filesystem. |
| `node_filesystem_readonly` | `1` when a filesystem is mounted read-only (disk/IO failure). |
| `node_memory_MemAvailable_bytes` / `node_memory_MemTotal_bytes` | Available (reclaim-aware) / total memory. |
| `node_cpu_seconds_total` | Per-mode CPU seconds; non-idle gives usage. |

Useful Grafana/Prometheus queries:

```promql
100 * node_filesystem_avail_bytes{fstype!~"tmpfs|overlay|squashfs"} / node_filesystem_size_bytes{fstype!~"tmpfs|overlay|squashfs"}
100 * node_filesystem_files_free{fstype!~"tmpfs|overlay|squashfs"} / node_filesystem_files{fstype!~"tmpfs|overlay|squashfs"}
100 * (1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)
100 * (1 - avg by (instance) (rate(node_cpu_seconds_total{mode="idle"}[5m])))
up{job="node-exporter"}
```

## Alerting & dashboards

### Alert rules

`docker/prometheus/alerts.yml` defines the hosted-beta alert baseline (wired into
`prometheus.yml` via `rule_files` and mounted at `/etc/prometheus/alerts.yml`). Thresholds
are sized for a small single-VPS beta — tune as traffic grows.

| Alert | Severity | Expr (summary) | Fires when |
|---|---|---|---|
| `ServiceDown` | critical | `up{job="parkio-services"} == 0` | a service is unscrapeable for 2m |
| `GatewayHigh5xxRate` | critical | gateway 5xx / total > 0.05 | >5% edge 5xx over 5m, sustained 10m |
| `OutboxDeadlettered` | critical | `parkio_outbox_deadlettered_count > 0` | any poison row persists ≥5m |
| `OutboxDeadletterAgeHigh` | critical | `parkio_outbox_deadlettered_oldest_age_seconds > 21600` | oldest open poison row older than 6h |
| `OutboxOldestUnpublishedTooOld` | critical | `parkio_outbox_oldest_unpublished_age_seconds > 300` | oldest relayable row >5m old, for 10m |
| `OutboxPublishFailuresElevated` | warning | `rate(parkio_outbox_publish_failed_total[5m]) > 0.1` per service | publish failures sustained >0.1/s for 10m (broker/contract trouble before rows dead-letter) |
| `OutboxRedriveStillDeadlettered` | warning | retry audit increased and open dead-letter count remains >0 | retry action recorded but dead-letter rows remain |
| `DatabaseConnectionPoolExhausted` | critical | `hikaricp_connections_pending > 0` | threads block on a DB connection for 5m |
| `KafkaExporterDown` | critical | `up{job="kafka-exporter"} == 0` | Kafka exporter scrape is down for 2m |
| `KafkaBrokerUnavailable` | critical | `kafka_brokers < 1` | exporter can scrape but sees no broker |
| `KafkaDltMessagesPresent` | critical | DLT current offset - oldest offset > 0 | any `parkio.dlt.*` retained offset depth persists ≥5m |
| `KafkaConsumerLagSustained` | critical | 30m avg lag > 1000 | sustained service consumer lag |
| `NodeExporterDown` | critical | `up{job="node-exporter"} == 0` | host metrics exporter unscrapeable for 2m |
| `HostFilesystemReadOnly` | critical | `node_filesystem_readonly == 1` | a host filesystem flipped read-only for 2m |
| `HostDiskSpaceCritical` | critical | disk free % < 10 | a real host filesystem under 10% free for 5m |
| `HostDiskSpaceLow` | warning | disk free % < 20 | a real host filesystem under 20% free for 10m |
| `HostDiskWillFillSoon` | warning | `predict_linear(...24h) < 0` | disk trending to full within 24h, for 30m |
| `HostInodesCritical` | critical | inodes free % < 10 | a host filesystem under 10% inodes free for 5m |
| `HostInodesLow` | warning | inodes free % < 20 | a host filesystem under 20% inodes free for 10m |
| `HostHighMemoryUsageCritical` | critical | mem used % > 95 | host memory >95% for 5m (OOM-kill risk) |
| `HostHighMemoryUsage` | warning | mem used % > 85 | host memory >85% for 10m |
| `HostHighCpuUsageCritical` | critical | CPU used % > 95 | host CPU averaged >95% for 10m |
| `HostHighCpuUsage` | warning | CPU used % > 85 | host CPU averaged >85% for 10m |
| `HighJvmMemoryUsage` | warning | heap used/max > 0.9 | heap >90% for 10m |
| `MediaUploadFailuresHigh` | warning | rejects / attempts > 0.5 | >50% uploads rejected over 15m |
| `AuthLoginFailuresHigh` | warning | `rate(login_failure) > 0.2/s` | ~>12 failed logins/min for 10m |
| `GatewayRateLimitSpike` | warning | `rate(rate_limit_rejected) > 1/s` | >60 × 429/min for 10m |
| `NotificationDeliveryFailuresHigh` | warning | worker failures / attempts > 0.5 | >50% deliveries fail over 15m |
| `KafkaConsumerLagHigh` | warning | lag > 100 | beta-sized early warning for consumer lag |

### Alertmanager routing

Prometheus sends firing alerts to Alertmanager at `alertmanager:9093`. Alertmanager groups
by `alertname`, `service`, and `severity`, waits 30s before the first notification, batches
updates every 5m, and inhibits warning notifications when a matching critical alert is
already firing for the same alert/service.

Default local/dev behavior is a safe no-op receiver: alerts are visible in the Alertmanager
UI but no outbound notification is sent. Hosted beta should set Slack delivery in
`docker/.env`:

```dotenv
PARKIO_ALERT_SLACK_WEBHOOK_URL=https://hooks.slack.com/services/...
PARKIO_ALERT_SLACK_CHANNEL="#parkio-alerts"
PARKIO_ALERT_REPEAT_CRITICAL=1h
PARKIO_ALERT_REPEAT_WARNING=4h
```

The Slack webhook is read from environment and rendered into a container-local runtime
config by `docker/alertmanager/render-config.sh`; it is never committed to YAML. Critical
alerts repeat every hour by default, warnings every four hours. In hosted beta Prometheus
(`:9090`), Alertmanager (`:9093`), and Grafana (`:3000`) are bound to **loopback only** —
reach them via SSH tunnel:

```bash
ssh -L 3000:localhost:3000 -L 9090:localhost:9090 -L 9093:localhost:9093 user@vps
```

To test routing without creating an outage, temporarily lower a warning threshold in a local
branch or use Alertmanager's UI/API against the loopback tunnel. Do not expose Alertmanager
through Caddy or a public firewall rule.

### Grafana dashboard

`docker/grafana/provisioning/dashboards/parkio-hosted-beta.json`
("**Parkio — Hosted Beta Overview**", uid `parkio-hosted-beta`) is auto-provisioned and
references the Prometheus datasource by stable uid `parkio-prometheus`. Panels: services
down / outbox DLQ depth / oldest-unpublished age / gateway 429 (stat row), service health
(`up`), gateway 5xx ratio, outbox dead-lettered + oldest-age + backlog by service, auth
login success vs failure, media uploads vs rejects, JVM heap %, and DB pool active/pending.
A **Host (node-exporter)** row adds node-exporter up, host CPU/memory/disk-used stats, and
per-mountpoint filesystem-free / disk-used / inode-usage time series.

## Centralized logs — Loki + Promtail

Hosted beta collects container stdout/stderr through Promtail and stores it in Loki. Promtail
uses Docker service discovery against `/var/run/docker.sock`, filters to the `parkio` Compose
project, and labels log streams with:

| Label | Source |
|---|---|
| `service` | Docker Compose service label |
| `container` | Docker container name |
| `compose_project` | Docker Compose project label (`parkio`) |
| `environment` | `PARKIO_ENVIRONMENT` (`local` or `hosted-beta`) |

Promtail does **not** mount host log directories and does **not** scrape `.env` files or other
filesystem paths. Loki retention is controlled by `PARKIO_LOKI_RETENTION_PERIOD` and defaults
to `168h` (7 days), which is intentionally beta-sized for a single VPS.

Use Grafana **Explore -> Loki**. Sample LogQL:

```logql
# All gateway logs
{service="gateway-service"}

# Error-looking logs across Parkio
{compose_project="parkio"} |~ "(?i)error|exception|failed"

# Search by OpenTelemetry trace id (links across to Tempo via the derived field)
{compose_project="parkio"} |= "traceId=4bf92f3577b34da6a3ce929d0e0e4736"

# Search by the user-facing correlation id (echoed in API errors / X-Correlation-Id)
{compose_project="parkio"} |= "correlationId=abc-123"

# Service-specific logs
{environment="hosted-beta", service="parking-service"}

# Auth failures
{service="auth-service"} |~ "(?i)login|authentication" |~ "(?i)fail|denied|locked"

# Media upload failures
{service="media-service"} |~ "(?i)upload|reject|malware|normalize|storage"
```

Every service log line carries two distinct identifiers (see
`logging.pattern.level` in each `application.yml`):
`[<service>,traceId=<otel-hex>,spanId=<otel-hex>,correlationId=<uuid>,eventId=<uuid>]`.

- **`traceId`/`spanId`** are the OpenTelemetry ids owned by Micrometer tracing. The `traceId=`
  token is what Grafana's Loki **derived field** matches to link a log line straight to its
  trace in Tempo (Loki ↔ Tempo correlation).
- **`correlationId`** is the user-facing request id the gateway generates/forwards as
  `X-Correlation-Id`, echoes on responses, and surfaces in `ApiError.traceId`. It is the id a
  user or support ticket will quote, and it also rides Kafka events for async flows.
- **`eventId`** is present while Kafka listener code handles an event. It is the inbox/outbox
  deduplication key and lets logs line up with the Kafka envelope without replacing the OTel ids.

Full structured JSON logging is not complete yet; Promtail currently treats logs as Docker log
lines with labels rather than parsing application fields. Follow-up: standardize JSON logs with
safe fields (`traceId`, `spanId`, `correlationId`, `service`, request method/path/status, error
code) and explicit redaction for tokens, passwords, API keys, MinIO credentials, DB passwords.

## Distributed tracing — OpenTelemetry + Tempo

Hosted beta runs end-to-end distributed tracing so a single request can be followed across
every hop (Gateway → Auth/User/Parking/Media/Moderation/AI-Validation/Analytics/Notification/
Gamification), with per-span latency, errors and retries.

### Architecture

```
Spring services ──OTLP/HTTP(4318)──▶ Tempo ──remote_write──▶ Prometheus (service-graph metrics)
   │  (Micrometer Observation                │
   │   → micrometer-tracing-bridge-otel       └──────────────▶ trace blocks (local volume)
   │   → opentelemetry-exporter-otlp)
   └─ logs (traceId=…) ──▶ Promtail ──▶ Loki ◀──derived field──▶ Tempo (logs ↔ traces)
                                          Grafana reads all three datasources
```

- **Instrumentation:** the smallest correct path on Spring Boot 3.4 — Spring already records
  Micrometer **Observations** for incoming HTTP, `WebClient`/`RestClient`, etc. We add
  `micrometer-tracing-bridge-otel` (turns those Observations into OpenTelemetry spans) and
  `opentelemetry-exporter-otlp` (ships them). No custom spans, no second tracing stack. Both
  versions come from the Spring Boot BOM, so there is exactly one, aligned tracer.
- **Sampling:** `PARKIO_TRACING_SAMPLING_PROBABILITY` (default `1.0` = 100% for beta). Lower to
  `0.05`–`0.20` in production. `PARKIO_TRACING_ENABLED` toggles the whole feature.
- **Tempo:** single-binary, local block storage on the `tempo-data` volume, retention
  `PARKIO_TEMPO_RETENTION` (default `48h`). Receives OTLP on 4317 (gRPC) / 4318 (HTTP),
  serves queries on 3200. The metrics-generator (`service-graphs` + `span-metrics`)
  remote-writes into Prometheus to power the service map.

### Propagation model

Trace context propagates over the wire as the **W3C `traceparent`** header. It is injected
automatically for internal HTTP clients because they are built from the auto-configured
`WebClient.Builder` / `RestClient.Builder` bean (gateway → user-status/session-epoch/JWKS,
parking → media). So the same OTel `traceId` is shared by Gateway, Auth, User, Parking, Media,
Moderation, AI-Validation, Analytics, Notification and Gamification on the synchronous path.

Kafka propagation uses the same W3C model. Spring Kafka observation is enabled for producer
templates and the service-local listener container factories, so producer and consumer spans are
created by Micrometer Observation rather than custom code. Transactional outbox appenders capture
the current OpenTelemetry carrier into the existing nullable `outbox_events.trace_id` storage slot;
relays re-enter that context before `KafkaTemplate.send(...)` and publish `traceparent`,
`tracestate` and `baggage` Kafka headers. The event envelope's `traceId` and the legacy `traceId`
Kafka header continue to carry the support-facing `correlationId`, preserving the wire contract.

This is **separate from** the existing `X-Correlation-Id` channel: the correlation UUID remains
the user-facing request id (response header + `ApiError.traceId` + Kafka event envelope), now
logged under `correlationId=`. The two coexist with no contract change.

### Grafana workflows

Use **Explore → Tempo**:

- **Trace search:** query by service, span name, duration or status; or paste a `traceId`.
- **Trace view:** the waterfall shows each service's span, latency contribution and errors.
- **Service map / node graph:** the **Service Graph** tab renders the live dependency graph and
  RED metrics from the generator's Prometheus metrics.
- **Latency investigation:** sort spans by duration / filter `duration > Xms` to find the slow hop.
- **Error investigation:** filter `status = error`; jump **trace → logs** (Tempo
  `tracesToLogsV2` → Loki) to read that service's log lines for the trace.
- **Logs → trace:** in **Explore → Loki**, the `traceId=` token in any log line is a clickable
  link that opens the trace in Tempo (derived field).

### Relationship between Prometheus, Loki and Tempo

- **Prometheus** — aggregate metrics ("how many / how slow overall"); also stores Tempo's
  service-graph/span metrics.
- **Loki** — log lines ("what exactly happened"), labelled per service.
- **Tempo** — per-request traces ("where did *this* request spend its time / fail").
- They are stitched by shared ids: `traceId` links Loki ↔ Tempo; exemplars + the service-graph
  metrics link Prometheus ↔ Tempo; `correlationId` ties async/Kafka flows and user reports.

### Troubleshooting

- **No traces in Tempo:** confirm `PARKIO_TRACING_ENABLED=true` and the service can reach
  `tempo:4318`; check the service log for OTLP exporter errors; verify sampling probability > 0.
- **Trace missing a hop:** that call likely doesn't use the auto-configured client builder, or a
  custom thread/executor dropped the context — check the client construction. For Kafka, verify
  that the record has a `traceparent` header and that `parkio.kafka.trace.propagation.missing`
  is not increasing unexpectedly.
- **Service map empty:** the generator needs traffic *and* Prometheus' remote-write receiver
  (`--web.enable-remote-write-receiver`); allow a few minutes after first traffic.
- **Log line won't link to a trace:** confirm the log shows `traceId=<hex>` (not `unknown`) — an
  unsampled or pre-context log line has no trace id.

### Not yet scraped (known gaps)

Host-level metrics are now covered by `node-exporter` (see **Host metrics** above and the
`Host*` / `NodeExporterDown` alerts). No alert in this baseline is currently disabled for a
missing exporter. Remaining observability follow-ups (managed-broker metrics, async/Kafka
trace propagation, full structured JSON logs) are tracked in `production-readiness.md` §8.

### Runbook — `ServiceDown`

1. Confirm the target is down in Prometheus (`Status -> Targets`) and identify the
   `service` label.
2. On the VPS, run `docker compose ... ps` and inspect the container logs for that service.
3. Check dependent infrastructure first: database, Redis, Kafka, MinIO or ClamAV health can
   keep an app from becoming scrapeable.
4. Restart only after capturing logs if the cause is not obvious.

### Runbook — `GatewayHigh5xxRate`

1. Check whether `ServiceDown` is firing; if yes, restore the unavailable downstream first.
2. Inspect gateway logs for route/status patterns and correlate with per-service logs.
3. Verify Caddy and gateway health, then check recent deploys or configuration changes.
4. If the error is isolated to one route, roll back or disable the failing integration path.

### Runbook — `OutboxDeadlettered`

A dead-lettered row means the relay tried to publish an event
`parkio.kafka.relay.max-attempts` times and gave up; the row is retained (`dead_lettered =
true`) for inspection and is **excluded** from both the backlog gauge and the relay's claim
query, so it will never publish on its own. Events are being lost for that aggregate until
you act.

1. **Identify the service** from the alert's `service` label, then inspect open rows with the
   private operator tool:

   ```bash
   PARKIO_ENV_FILE=docker/.env PARKIO_OPERATOR=<name> \
     scripts/outbox-deadletter-recovery.sh list --service parking
   ```

   Direct SQL equivalent:

   ```sql
   SELECT id, aggregate_type, event_type, failure_count, last_failure_reason, last_failed_at
   FROM outbox_events
   WHERE dead_lettered = true AND acknowledged_deadletter = false
   ORDER BY last_failed_at DESC;
   ```

2. **Diagnose `last_failure_reason`.** Common cases:
   - *Broker unreachable / timeout* → Kafka was down; the fix is infrastructure, the payload
     is fine → safe to redrive.
   - *No topic mapping / serialization error* → a contract/code bug → fix the mapping or
     payload handling and deploy **before** redriving, or the row will just re-fail.
   - *Genuinely malformed/obsolete event* → may be correct to leave dead-lettered.

3. **Redrive after the root cause is fixed** (resets the row so the relay re-claims it):

   ```bash
   PARKIO_OPERATOR=<name> scripts/outbox-deadletter-recovery.sh retry \
     --service parking --id <row-id> --reason "broker fixed" --yes
   ```

   The scheduled relay picks it up within one poll interval; the alert clears once
   `parkio_outbox_deadlettered_count` returns to 0. Consumers are idempotent (inbox dedup),
   so a redrive that double-delivers is safe.

4. **When NOT to redrive:** the event is obsolete/superseded, the payload is irreparably
   malformed, or the downstream contract no longer accepts it. Acknowledge it so it remains
   retained but stops paging:

   ```bash
   PARKIO_OPERATOR=<name> scripts/outbox-deadletter-recovery.sh acknowledge \
     --service parking --id <row-id> \
     --reason "obsolete; superseded by incident INC-123 correction" --yes
   ```

   The audit table records the operator, reason and previous state.

### Runbook — `DatabaseConnectionPoolExhausted`

1. Identify `service` and `pool` from the alert labels.
2. Check service logs for slow queries, transaction timeouts, or connection leak warnings.
3. Inspect PostgreSQL activity for long-running queries and blocked sessions.
4. Treat pool-size increases as a temporary mitigation; fix the slow/leaking path first.

### Runbook — `MediaUploadFailuresHigh`

1. Confirm whether rejects are validation/malware/normalization failures or storage errors
   from media-service logs.
2. Check ClamAV health, MinIO health, bucket existence, and presigned endpoint configuration.
3. If failures started after a frontend release, verify accepted MIME types and image limits.

### Runbook — `AuthLoginFailuresHigh`

1. Check auth-service logs and gateway rate-limit metrics for credential-stuffing patterns.
2. Verify Redis is healthy; account/IP brute-force protection depends on it.
3. Confirm Resend/email issues are not causing users to repeatedly fail verification/login.
4. If traffic is abusive, tighten edge rate limits or temporarily block source ranges at the
   VPS firewall/proxy.

### Runbook — `KafkaExporterDown`

1. Check `docker compose ... ps kafka-exporter` and `docker logs parkio-kafka-exporter`.
2. Confirm the exporter can reach the in-network broker address `kafka:9092`.
3. Check the Kafka container health and the `parkio-backend` / `parkio-observability` networks.
4. Until this clears, consumer-lag and DLT-depth alerts are blind.

### Runbook — `KafkaBrokerUnavailable`

1. Kafka exporter is scrapeable but reports `kafka_brokers < 1`; inspect `parkio-kafka` health.
2. Verify Kafka listeners still advertise `PLAINTEXT://kafka:9092` for in-network clients.
3. Check broker logs for startup, storage, or KRaft controller failures.
4. If the broker was rebuilt, verify topics were recreated by service topic provisioning.

### Runbook — `KafkaDltMessagesPresent`

1. Identify the DLT topic from the alert label, e.g. `parkio.dlt.notification`.
2. Inspect the owning consumer service logs around the first DLT timestamp and look for poison
   record / deserialization / handler errors.
3. Dry-run the DLT redrive tool before any replay:

   ```bash
   scripts/kafka-dlt-redrive.sh \
     --bootstrap-servers localhost:29092 \
     --source-topic parkio.dlt.notification \
     --target-topic <original-topic> \
     --max-records 10
   ```

4. Redrive only after the consumer bug or data-contract issue is fixed:

   ```bash
   PARKIO_OPERATOR=<name> scripts/kafka-dlt-redrive.sh \
     --execute \
     --bootstrap-servers localhost:29092 \
     --source-topic parkio.dlt.notification \
     --target-topic <original-topic> \
     --max-records 10 \
     --reason "consumer fixed"
   ```

   The tool preserves original headers and adds `parkio-redrive-*` audit headers. Otherwise leave
   the DLT retained and escalate; do not blindly replay poison records.

### Runbook — `KafkaConsumerLagHigh`

1. Identify `consumergroup` and `topic`; inspect that service's logs for handler failures,
   retries, slow DB writes, or downstream outages.
2. Compare with `KafkaDltMessagesPresent`: DLT records often explain lag clearing or poison flow.
3. Check Kafka broker health and CPU/disk pressure on the VPS.
4. If lag is transient and falling, watch; if it keeps growing, restart or scale the consumer
   service after capturing logs.

### Runbook — `KafkaConsumerLagSustained`

1. Treat this as user-visible stale projections: the group has averaged >1000 lag for 30m.
2. Follow `KafkaConsumerLagHigh`, then check whether the service is underprovisioned or blocked
   on database/HTTP dependencies.
3. If the consumer is healthy but traffic exceeds one instance's capacity, increase replicas in
   the target platform. For single-VPS beta, consider temporarily reducing producers or disabling
   non-critical write paths.

### Runbook — `NodeExporterDown`

1. While this fires, every host disk/memory/CPU/inode alert is blind — treat as urgent.
2. `docker compose ... ps node-exporter` and `docker logs parkio-node-exporter`. Common causes:
   the container was stopped, or `/proc`//`/sys`//`/` bind mounts are unavailable.
3. Verify the scrape: Prometheus `Status → Targets`, job `node-exporter` (`node-exporter:9100`).
4. Until restored, inspect the host directly over SSH: `df -h`, `df -ih`, `free -h`, `docker stats`.

### Runbook — `HostDiskSpaceLow`

(also covers `HostDiskSpaceCritical` and `HostDiskWillFillSoon`) — a full disk stalls Postgres,
the outbox relay, Loki and MinIO simultaneously, so act before the critical (<10%) threshold.

1. Identify the filesystem from the `mountpoint` label, then on the VPS run
   `df -h` and `du -xh --max-depth=1 /var/lib/docker | sort -h | tail`.
2. Follow the **disk-cleanup runbook** in `docker/README.md` (Docker container logs → dangling
   images/build cache → old images → Loki/Tempo retention → old backups already pushed off-box).
3. **Do not** delete `*-data` volumes or run `docker compose down -v` to reclaim space — that
   destroys databases, Kafka, MinIO objects and issued TLS certs.
4. If the data is legitimately large, resize the VPS disk; tune `PARKIO_LOKI_RETENTION_PERIOD`
   and `BACKUP_RETENTION_DAYS` down for a smaller steady-state footprint.

### Runbook — `HostInodesLow`

1. Bytes may be free but writes still fail — the filesystem is out of inodes (many small files).
2. Find the offender: `df -ih`, then `for d in /var/log /tmp /var/lib/docker; do echo $d; find $d -xdev -type f | wc -l; done`.
3. Usual causes: rotated/uncapped logs or a runaway temp dir. Apply the same Docker-log and image
   prune steps as the disk runbook; cap container logs in `daemon.json` if not already.

### Runbook — `HostHighMemoryUsage`

(also `HostHighMemoryUsageCritical`) — 10 JVMs + Kafka + 9 Postgres on one VPS are memory-hungry.

1. `docker stats --no-stream` and `free -h` to find the heaviest container(s).
2. Check for a leak vs. legitimate load; correlate with `HighJvmMemoryUsage` per service.
3. Lower per-JVM heap via `JAVA_TOOL_OPTIONS` `MaxRAMPercentage` and set container memory limits
   so one service cannot starve the box. If it is sustained load, scale the VPS RAM.
4. At critical (>95%) the kernel OOM killer may reap containers — `dmesg | grep -i oom` confirms.

### Runbook — `HostHighCpuUsage`

(also `HostHighCpuUsageCritical`) — sustained saturation risks request latency and probe failures.

1. `docker stats --no-stream` to find the hot container; `top`/`htop` for host processes.
2. Distinguish a burst (ClamAV scan, JVM warm-up, build) from sustained load. The alert already
   requires 10m, so transient spikes are filtered out.
3. If a single service is hot, check for a busy loop or runaway query; otherwise shed load or scale
   the VPS vCPUs.

### Runbook — `HostFilesystemReadOnly`

1. A filesystem flipped read-only almost always means disk/IO failure or corruption — host writes
   (DB, outbox, uploads, logs) are failing now. Treat as urgent.
2. `dmesg | tail` for IO errors; check the VPS provider's disk health/console.
3. Remount read-write only if the underlying error is understood (`mount -o remount,rw <mountpoint>`);
   otherwise snapshot/restore on healthy storage. Restore from backups if data is corrupt.

## Adding a new metric

- Keep metric components in `infrastructure/metrics` per service; never in `domain`.
- Names: `parkio.<area>.<thing>.<unit-ish>` (dots; Prometheus rewrites them).
- Gauges must be backed by cheap (indexed COUNT) queries — they run on every scrape.
- Never tag with unbounded or personal values (user ids, emails, file names, tokens).
- Add a representative unit test with `SimpleMeterRegistry`.
