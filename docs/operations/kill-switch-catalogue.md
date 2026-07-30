# Kill Switch Catalogue

Exact configuration keys, defaults, and disable behavior. Source:
`services/parking-service/src/main/resources/application.yml` and
`ParkingProperties` / `@ConditionalOnProperty` job classes.

## Decision & Authority (WP-05.5 / WP-05.8)

| Key | Env | Default | Scope | Restart | Disable behavior |
|-----|-----|---------|-------|---------|------------------|
| `parkio.parking.decision.shadow-enabled` | `PARKIO_PARKING_DECISION_SHADOW_ENABLED` | `false` | Post-AI validation path | Yes | No shadow metrics/comparisons |
| `parkio.parking.decision.authority.enabled` | `PARKIO_PARKING_DECISION_AUTHORITY_ENABLED` | `false` | Controlled apply | Yes | Legacy path only |
| `parkio.parking.decision.authority.canary-percentage` | `PARKIO_PARKING_DECISION_AUTHORITY_CANARY_PERCENTAGE` | `0` | Cohort % | Yes | Zero authority apply |
| `parkio.parking.exposure-shadow.enabled` | `PARKIO_PARKING_EXPOSURE_SHADOW_ENABLED` | `false` | Nearby search request path | Yes | No shadow rerank metrics |
| `parkio.parking.exposure-shadow.sample-percent` | `PARKIO_PARKING_EXPOSURE_SHADOW_SAMPLE_PERCENT` | `10` | Sample rate when enabled | Yes | N/A when off |
| `parkio.parking.exposure-shadow.time-budget-millis` | `PARKIO_PARKING_EXPOSURE_SHADOW_TIME_BUDGET_MS` | `25` | Max shadow work per search | Yes | Hard cap when enabled |

**Verification:** `DecisionAuthoritySettings`, metrics
`parkio_parking_decision_authority_*`. Dashboard: `parkio-decision-authority.json`.

## Shadow Schedulers (WP-05.11–05.15)

| Key | Env | Default | Job class | In-flight |
|-----|-----|---------|-----------|-----------|
| `parkio.lifecycle.trust-shadow.enabled` | `PARKIO_TRUST_SHADOW_ENABLED` | `true` | Trust shadow scheduler | Completes current batch |
| `parkio.lifecycle.reward-shadow.enabled` | `PARKIO_REWARD_SHADOW_ENABLED` | `false` | Reward shadow scheduler | Completes current batch |
| `parkio.lifecycle.fraud-shadow.enabled` | `PARKIO_FRAUD_SHADOW_ENABLED` | `false` | Fraud shadow scheduler | Completes current batch |
| `parkio.lifecycle.calibration.enabled` | `PARKIO_CALIBRATION_ENABLED` | `false` | `ContinuousCalibrationJob` | REQUIRES_NEW per row |

## Operational Pipelines

| Key | Env | Default | Notes |
|-----|-----|---------|-------|
| `parkio.lifecycle.outcome-validation.enabled` | `PARKIO_OUTCOME_VALIDATION_ENABLED` | `true` | Ground-truth; not authority |
| `parkio.kafka.moderation-consumer.enabled` | `PARKIO_MODERATION_CONSUMER_ENABLED` | `true` | Disable pauses moderation ingest |
| `parkio.kafka.ai-validation-consumer.enabled` | `PARKIO_AI_VALIDATION_CONSUMER_ENABLED` | `true` | Disable pauses AI result ingest |

## Lifecycle / Retention

| Key | Default | Risk if disabled |
|-----|---------|------------------|
| `parkio.lifecycle.parking-expiry.enabled` | `true` | Stale spots remain visible |
| `parkio.lifecycle.moderation-timeout.enabled` | `true` | Spots stuck in moderation |
| `parkio.lifecycle.retention.outbox-enabled` | `true` | Outbox table growth |

## Municipal parking sources (DATA-WP-01)

| Key | Default | Notes |
|-----|---------|-------|
| `parkio.municipal.enabled` | `false` | Master gate for municipal integration |
| `parkio.municipal.izum.enabled` | `false` | Live IZUM HTTP fetch path |
| `parkio.municipal.izum.scheduler-enabled` | `false` | Scheduled sync job |
| `parkio.municipal.manual-sync-enabled` | `false` | Admin manual sync endpoint |

Disabling IZUM leaves facility inventory intact; occupancy ages to STALE and
`availableSpaces` is withheld from facility read APIs.

## Gateway / Edge

| Key | Location | Default |
|-----|----------|---------|
| `PARKIO_GATEWAY_INTERNAL_SECRET` | parking + all services | **Required** (no default) |
| Rate limiting | gateway Redis | Fail-open at library level — treat Redis as required |

## Verification Procedure

1. Confirm property in deployed env / `application.yml` overlay.
2. Restart service (all switches above are restart-required unless noted).
3. Check metrics: shadow counters should stop increasing (`parkio_parking_*_shadow_*`).
4. For authority: confirm `parkio_parking_decision_authority_apply_total` not incrementing.

## Rollback Interaction

- **Code rollback** does not change env overrides — re-verify kill switches after deploy.
- **Config rollback** takes precedence over in-repo defaults.
- **Never** set `canary-percentage` > 0 without PRR approval and calibration evidence.

See [rollback-runbook.md](rollback-runbook.md).

## OSM import (DATA-WP-02)

| Key | Default | Scope |
|-----|---------|-------|
| parkio.municipal.osm.import-enabled | alse | Admin GeoJSON import |
| parkio.municipal.osm.scheduler-enabled | alse | Reserved; unused in WP-02 |
| parkio.municipal.osm.conflation-enabled | alse | Candidate generation |
| parkio.municipal.osm.auto-match-enabled | alse | Automatic link merge |
| parkio.municipal.osm.publication-enabled | alse | Future publication gate |
