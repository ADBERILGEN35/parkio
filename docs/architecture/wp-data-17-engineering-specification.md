# DATA-WP-17 — Source-Level Availability Semantics Alignment

> **Naming:** This package is **DATA-WP-17** (municipal/parking data). Document
> filenames follow `wp-data-17-*`.

## 1. Status

**Implementation complete (this package).** DATA-WP-17A (hosted-beta leave-on gate) is
**not started**. No deploy in this package.

## 2. Executive summary

After DATA-WP-16A, OSM operational state correctly stayed `HEALTHY` under
operator-imported SLA, and facility APIs correctly returned
`availableSpaces=null` / `freshness=UNAVAILABLE`. The WP-15 quality-report
**source summary** and municipal source metrics could still report
`occupancyFreshness=LIVE` for OSM because source-level freshness was derived from
**last successful sync/import age**, not from occupancy authority.

**DATA-WP-17** separates the concepts permanently:

| Concept | Question |
|---------|----------|
| `operationalState` | Is this source operationally functioning for its mode? |
| `occupancyFreshness` | Does this source currently supply valid live occupancy? |
| `sourceMode` | SCHEDULED vs OPERATOR_IMPORTED (WP-16) |
| `secondsSinceSuccess` | Import/sync age (observational for OSM when mode-aware) |

## 3. Root cause

`MunicipalSourceHealthService` called `MunicipalSourceSlaPolicy.occupancyFreshness(lastSuccessAt, …)`.
For OSM, `lastSuccessfulSyncAt` is the last successful **import**, so a recent or
aging import produced LIVE/AGING/STALE even with **zero** occupancy snapshots.

Facility projection already used
`MunicipalSourcePublicationPolicy.mayContributeLiveOccupancy` (İZUM only).

## 4. Availability authority

`MunicipalSourceOccupancyAuthorityPolicy`:

| Source | May contribute occupancy | Source-level freshness |
|--------|--------------------------|------------------------|
| `izmir-izum-otoparklar` | yes | classify latest occupancy observation |
| `osm-geofabrik-turkey` | no | always `UNAVAILABLE` |
| all İZELMAN keys | no | always `UNAVAILABLE` |

Authority is **not** inferred from operational health, publication, scheduler,
attribution, facility count, or import success.

Facility APIs continue to use `MunicipalSourcePublicationPolicy.mayContributeLiveOccupancy`.

## 5. Valid combined states

- OSM: `operationalState=HEALTHY` + `occupancyFreshness=UNAVAILABLE` (canonical leave-on)
- İZUM fresh: ops HEALTHY/RECOVERING + freshness LIVE
- İZUM aging/stale: ops may stay HEALTHY/DEGRADED while freshness AGING/STALE
- İZUM missing observation: freshness UNAVAILABLE even if last sync SUCCESS

## 6. WP-15 / metrics / health

- Source summary `occupancyFreshness` comes from the health snapshot (now authority-aware).
- OSM section already exposes `occupancySnapshotCount` and `nullAvailabilityCoverage`
  (expected 100% under current model).
- Metrics `parkio_municipal_source_occupancy_freshness` inherit the corrected snapshot.
- Actuator municipal details for İZUM use observation-based freshness.

No DTO shape change. No migration. No score/readiness field.

## 7. Flag / SLA interaction

DATA-WP-16 source-mode SLA is **unchanged**. This package does not alter
operational evaluation thresholds.

## 8. Rollback

Semantic rollback is a code revert of the authority wiring (restore sync-age
freshness). No data rollback. Prefer forward-fix; do not toggle WP-16 SLA to
“fix” freshness.

## 9. DATA-WP-17A gate

Hosted-beta leave-on proof that WP-15 OSM `occupancyFreshness=UNAVAILABLE` while
ops remain HEALTHY, İZUM observation freshness matches facility masking, metrics
agree, and no mutation occurs. **Not started in this package.**
