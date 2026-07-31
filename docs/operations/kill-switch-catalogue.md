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
| parkio.municipal.osm.import-enabled | false | Admin GeoJSON import |
| parkio.municipal.osm.scheduler-enabled | false | Reserved; unused in WP-02 |
| parkio.municipal.osm.conflation-enabled | false | Candidate generation |
| parkio.municipal.osm.auto-match-enabled | false | Automatic link merge |
| parkio.municipal.osm.publication-enabled | false | Future publication gate |
| parkio.municipal.osm.clip-version | izmir-admin-izbb-2024-10-18-v1 | DATA-WP-08; rollback `izmir-bbox-v1` |
| parkio.municipal.osm.boundary-dir | empty | Operator boundary dir; empty = envelope fallback |

## DATA-WP-03 IZELMAN (default OFF)

| Flag | Default | Effect |
|------|---------|--------|
| parkio.municipal.izelman.enabled | false | Master gate for IZELMAN admin controller |
| parkio.municipal.izelman.facility-import-enabled | false | Facility CSV import |
| parkio.municipal.izelman.roadside-import-enabled | false | Roadside CSV import |
| parkio.municipal.izelman.tariff-import-enabled | false | Tariff CSV import |
| parkio.municipal.izelman.facility-publication-enabled | false | Hide IZELMAN-attributed facilities from public discovery |
| parkio.municipal.izelman.roadside-publication-enabled | false | Disable roadside nearby controller |
| parkio.municipal.izelman.tariff-publication-enabled | false | Do not expose tariffs as public current prices |
| parkio.municipal.izelman.scheduler-enabled | false | No scheduled IZELMAN import |
| parkio.municipal.izelman.candidate-generation-enabled | false | No automatic candidate linking |
| parkio.municipal.izelman.auto-match-enabled | false | Unsupported; must remain false |

Hosted-beta Compose maps PARKIO_MUNICIPAL_IZELMAN_* with defaults false.

## DATA-WP-04 canonical registry (default OFF)

| Flag | Default | Effect |
|------|---------|--------|
| `parkio.municipal.registry.candidate-generation-enabled` | `false` | Generate conservative review candidates |
| `parkio.municipal.registry.review-api-enabled` | `false` | Register admin review endpoints; disabled is HTTP 404 |
| `parkio.municipal.registry.reviewed-linking-enabled` | `false` | Permit explicit ADMIN/SUPER_ADMIN decisions |
| `parkio.municipal.registry.automatic-linking-enabled` | `false` | Prohibited; binding `true` fails startup |
| `parkio.municipal.registry.provenance-publication-enabled` | `true` | Add bounded provenance to public facility responses (DATA-WP-11; kill-switch to `false` restores null fields; production profile pins false) |
| `parkio.municipal.registry.provenance-ingest-write-enabled` | `true` | Kill-switch for DATA-WP-10 ingest provenance writes (does not control publication) |

## DATA-WP-07 / DATA-WP-12 nearby duplicate-presentation

| Flag | Default | Effect |
|------|---------|--------|
| `parkio.municipal.discovery.duplicate-presentation-enabled` | `true` | Nearby-only presentation suppression (DATA-WP-12; kill-switch to `false` restores legacy nearby result set/order; production profile pins false) |

Independent of provenance publication, candidate generation, review API, linking, and
İZELMAN publication. Detail lookup is never suppressed. Zero suppressions is valid;
thresholds stay conservative. Do not enable in production without separate approval.

Disable publication first, then reviewed linking, review API, and candidate generation.
Ingest-write kill-switch is independent: set it false to stop new provenance selections without changing publication.
These switches do not stop IZUM availability. IZELMAN publication stays independently disabled.

DATA-WP-05 adds the bounded ADMIN generation endpoint behind the existing
`candidate-generation-enabled` switch; it adds no scheduler or new enablement
flag. Disabling the switch removes the controller (HTTP 404) after restart.
Generation never applies links. All hosted-beta registry values remain `false`,
and DATA-WP-05A is not started.
