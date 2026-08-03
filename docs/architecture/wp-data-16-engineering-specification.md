# DATA-WP-16 — Source-Mode-Aware Municipal SLA Semantics

> **Naming:** This package is **DATA-WP-16** (municipal/parking data). Document
> filenames follow `wp-data-16-*`.

## 1. Status

**Implementation complete (this package).** DATA-WP-16A (hosted-beta leave-on gate) is
**not started**.

## 2. Executive summary

After DATA-WP-15A, the municipal quality report correctly reused WP-06 SLA evaluation,
but OSM (operator-imported, scheduler intentionally off) appeared `CRITICAL` solely
because last successful import age exceeded the global seconds-since-success threshold
designed for İZUM polling.

**DATA-WP-16** introduces an explicit bounded source operating mode and a kill-switched
mode-aware SLA evaluation path:

| Mode | Source (default) | Seconds-since-success |
|------|------------------|------------------------|
| `SCHEDULED` | `izmir-izum-otoparklar` | Alerting (warning/critical) |
| `OPERATOR_IMPORTED` | `osm-geofabrik-turkey` | Observational only when flag on |

Failure streaks, stale RUNNING, never-run, and recovery semantics remain for both modes.
Occupancy freshness is unchanged and remains separate.

No Flyway migration. Flag defaults **false** (legacy age semantics) until DATA-WP-16A.

## 3. Root cause

`MunicipalSourceSlaPolicy.evaluate` applied `criticalSecondsSinceSuccess` (default 1800)
to every enabled source. OSM leave-on keeps publication=true, import=false, scheduler=false;
last SUCCESS can be hours or days old by design → false `CRITICAL`.

## 4. Source-mode model

Enum: `MunicipalSourceOperatingMode` — `SCHEDULED` | `OPERATOR_IMPORTED`.

Resolution: `MunicipalSourceOperatingModePolicy.resolve(sourceKey, properties)` via
**explicit typed config** on İZUM/OSM (`operating-mode`), not from scheduler flags,
publisher text, labels, or substring heuristics. Unknown keys fail closed to `SCHEDULED`.
İZELMAN keys resolve to `OPERATOR_IMPORTED` while publication remains disabled.

## 5. Feature flag

| Property | Env | Default |
|----------|-----|---------|
| `parkio.municipal.ops.source-mode-sla-enabled` | `PARKIO_MUNICIPAL_OPS_SOURCE_MODE_SLA_ENABLED` | **false** |

When false: legacy WP-06 global age semantics for all modes (kill-switch).
When true: OPERATOR_IMPORTED skips age-driven DEGRADED/CRITICAL; SCHEDULED unchanged.

Independent of quality-report, provenance, duplicate-presentation, linking, İZELMAN.

Also configurable:

| Property | Default |
|----------|---------|
| `parkio.municipal.izum.operating-mode` | `SCHEDULED` |
| `parkio.municipal.osm.operating-mode` | `OPERATOR_IMPORTED` |

## 6. Semantics matrix

### SCHEDULED (İZUM)

Unchanged from WP-06: consecutive failures, seconds-since-success, stale RUNNING, recovery.

### OPERATOR_IMPORTED (OSM) when flag true

| Case | State |
|------|--------|
| Old SUCCESS, consecutive=0, no stale RUNNING | **HEALTHY** (age observational) |
| Latest FAILED / consecutive warning | DEGRADED |
| Consecutive ≥ critical | CRITICAL |
| Stale RUNNING | STALE_OPERATION |
| Never imported | NEVER_RUN |
| Success after failures within recovering window | RECOVERING |
| Publication on, no success | NEVER_RUN / failure state — not HEALTHY |

## 7. WP-15 report

Reuses corrected health snapshot. Additive DTO field `sourceMode` on `SourceQualitySummary`.
No scores. Seconds-since-success remains visible as observation.

## 8. Prometheus / Grafana

- İZUM age alerts gated to `source_mode="SCHEDULED"`.
- OSM consecutive-failure / stale RUNNING / recovery alerts added; **no** OSM age-only alerts.
- Gauges carry bounded `source_mode` label for İZUM and OSM.
- Recording rule `parkio:municipal_source:seconds_since_success_scheduled` for scheduled age.

## 9. DATA-WP-16A procedure (not started)

1. Deploy dark with `PARKIO_MUNICIPAL_OPS_SOURCE_MODE_SLA_ENABLED=false`.
2. Enable flag on hosted-beta only.
3. Confirm OSM quality-report / health not CRITICAL for age-only SUCCESS.
4. Confirm İZUM age CRITICAL still works when last success old.
5. Confirm OSM failure/stale alerts still fire.
6. Leave-on true only after soak; production remains false.

## 10. Rollback

Set `PARKIO_MUNICIPAL_OPS_SOURCE_MODE_SLA_ENABLED=false` and recreate parking-service.
