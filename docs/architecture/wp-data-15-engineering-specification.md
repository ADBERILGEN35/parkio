# DATA-WP-15 — Municipal Quality & Coverage Report (Read-Only ADMIN API)

> **Naming:** This package is **DATA-WP-15** (municipal/parking data). Document
> filenames follow `wp-data-15-*`. Unrelated to repository WP-15 if any.

## 1. Status

**Implementation complete (this package).** DATA-WP-15A (hosted-beta leave-on gate) is
**not started**.

## 2. Executive summary

Operators need a single, read-only view of municipal registry coverage and source health
without triggering sync, import, conflation, linking, or publication changes.

**DATA-WP-15** registers two ADMIN-only GET endpoints in parking-service that assemble
aggregate coverage and freshness facts from persisted registry state:

- overall report across OSM and İZUM
- per-source detail with bounded recent sync-run history

The report reuses **DATA-WP-06** source-health/SLA evaluation (`MunicipalSourceHealthService`)
and **DATA-WP-13** label outcomes stored in `source_metadata_json`. With **DATA-WP-16**
enabled, OSM `OPERATOR_IMPORTED` mode no longer maps old successful imports to CRITICAL
from age alone; additive `sourceMode` appears on each source summary. **DATA-WP-17**
aligns source-summary `occupancyFreshness` with occupancy authority (OSM always
`UNAVAILABLE`; İZUM from latest occupancy observation). It emits **no** global
quality score, trust score, linking readiness, or production-readiness verdict.

No Flyway migration. No frontend. No İZELMAN publication change. Disabled by default; HTTP
404 when the kill-switch is off.

## 3. Goals

- Read-only ADMIN quality/coverage report API for operators and on-call.
- Bounded DTO: allow-listed provenance fields, known label outcomes, normalized import
  quality JSON only.
- Coverage metrics with explicit numerators/denominators; `null` percentage when
  denominator is zero.
- Reuse WP-06 operational SLA snapshot (consecutive failures, seconds since success,
  stale RUNNING) without duplicating evaluation logic. Occupancy freshness on the
  snapshot follows **DATA-WP-17** authority (not sync/import age).
- OSM label-outcome histogram from `source_metadata_json.labelOutcome` on active links.
- İZUM occupancy freshness buckets from latest snapshot per active facility.
- Integrity guardrail counters (duplicate links/provenance, link-candidate depth, etc.).
- Bounded Micrometer counters/timers; no facility IDs, external IDs, or raw payloads on
  the wire or as metric tags.
- Kill-switch: controller absent unless flag is exactly `true`; disabled path is HTTP 404.

## 4. Non-goals

- No aggregate **quality score**, trust score, readiness score, or linking/production
  verdict (forbidden on the wire).
- No linking trigger, candidate generation, review mutation, or İZELMAN publication.
- No sync/import/conflation scheduler or manual-sync side effects.
- No Flyway migration or schema change.
- No frontend/admin UI in this package.
- **DATA-WP-15A** (hosted-beta leave-on gate) is out of scope here.
- Public nearby/detail DTO shape unchanged.

## 5. Feature flags

| Property | Env | Default | Effect |
|----------|-----|---------|--------|
| `parkio.municipal.ops.quality-report-enabled` | `PARKIO_MUNICIPAL_OPS_QUALITY_REPORT_ENABLED` | **false** | Registers `MunicipalQualityReportController`; any other value (including unset) ⇒ bean absent, path 404 |
| `parkio.municipal.ops.recent-run-limit-default` | `PARKIO_MUNICIPAL_OPS_QUALITY_REPORT_RECENT_RUN_LIMIT_DEFAULT` | `20` | Default `limit` on source detail |
| `parkio.municipal.ops.recent-run-limit-max` | `PARKIO_MUNICIPAL_OPS_QUALITY_REPORT_RECENT_RUN_LIMIT_MAX` | `100` | Hard cap on `limit` query param |

Profile defaults:

- Canonical `application.yml`: **false**
- `application-prod.yml`: **false**
- Azure hosted-beta Compose (`PARKIO_MUNICIPAL_OPS_QUALITY_REPORT_ENABLED:-false`): **false**
  until DATA-WP-15A leave-on approval

Independent of provenance publication, duplicate-presentation, candidate generation,
linking, İZELMAN publication, and ingest-write flags.

## 6. Endpoints

Base path (parking-service, via gateway when routed):

```text
GET /api/v1/parking/admin/municipal/quality-report
GET /api/v1/parking/admin/municipal/quality-report/sources/{sourceKey}
    ?limit=<1..max>   # optional; default from ops.recent-run-limit-default
```

Supported `{sourceKey}` values only:

- `osm-geofabrik-turkey` (OSM)
- `izmir-izum-otoparklar` (İZUM)

Any other key ⇒ **404** `NOT_FOUND`.

### 6.1 Auth matrix

Gateway forwards `X-User-Roles` (comma-separated). Controller accepts `ADMIN` or
`SUPER_ADMIN` (case-insensitive, trimmed).

| Condition | HTTP | `code` |
|-----------|------|--------|
| Missing / blank `X-User-Roles` | 401 | `UNAUTHORIZED` |
| Authenticated, no ADMIN/SUPER_ADMIN | 403 | `FORBIDDEN` |
| Flag off (controller not registered) | 404 | `NOT_FOUND` |
| Unsupported `{sourceKey}` | 404 | `NOT_FOUND` |
| `limit` out of range or non-numeric | 400 | `BAD_REQUEST` / `MALFORMED_REQUEST` |
| GET with ADMIN role, flag on | 200 | — |
| POST/PUT/DELETE/PATCH on path | 405 | `METHOD_NOT_ALLOWED` |
| Unexpected server failure | 500 | `INTERNAL_ERROR` (no stack/SQL/token leak) |

Metrics are **not** recorded for 401/403/405 (service never invoked).

## 7. Response DTO (allow-list)

Policy version: `municipal-quality-report-v1` (`MunicipalQualityReportPolicy.POLICY_VERSION`).

### 7.1 Overall (`MunicipalQualityReport`)

| Field | Meaning |
|-------|---------|
| `policyVersion` | Report policy id |
| `generatedAt` | Assembly timestamp (UTC) |
| `activeFacilities` | Count of `municipal_parking_facilities` where `active = true` |
| `sources` | Two `SourceQualitySummary` rows (OSM, İZUM) |
| `osm` | `OsmQualitySection` |
| `izum` | `IzumQualitySection` |
| `integrity` | `IntegrityGuardrails` |
| `districtCoverage` | Additive DATA-WP-18 section (`AVAILABLE` / `DISABLED` / `UNAVAILABLE`); see [wp-data-18](wp-data-18-engineering-specification.md) |

### 7.2 Per-source summary (`SourceQualitySummary`)

Enablement flags, WP-06 SLA fields (`operationalState`, `lastRunStatus`, `lastRunAt`,
`lastSuccessAt`, `secondsSinceSuccess`, `consecutiveFailures`, `failuresInWindow`,
`staleRunningOperations`, `lastFailureCategory`, `occupancyFreshness`), facility/link
counts, `shareOfActiveFacilities` (`CoverageMetric`), and `provenanceCoverage` (seven
allow-listed fields).

Provenance allow-list (same as public publication): `NAME`, `COORDINATES`, `ADDRESS`,
`OPERATOR`, `FACILITY_TYPE`, `STATIC_CAPACITY`, `ATTRIBUTION`.

### 7.3 OSM section (`OsmQualitySection`)

Import/scheduler/publication flags, `clipVersion`, `labelPolicyVersion`, active facility
count, `nameBearingLabelCoverage`, `technicalLabelCount`, `staleNameMismatchCount`
(fallback label outcome **and** OSM `NAME` provenance still selected), occupancy snapshot
count, `nullAvailabilityCoverage`, `labelOutcomes` map, `latestImportReport`
(`NormalizedQualityReport`).

### 7.4 İZUM section (`IzumQualitySection`)

Enablement, scheduler, `agingAfterSeconds` / `staleAfterSeconds` from source row,
occupancy bucket counts and coverage metrics (`live`, `aging`, `stale`,
`availabilityExposed`).

### 7.5 Source detail (`SourceQualityDetail`)

Same header fields as overall plus exactly one of `osm` or `izum`, `recentRunLimit`, and
`recentRuns` (`RecentSyncRunSummary`: status, errorCategory, startedAt, completedAt only).

### 7.6 Forbidden fields (must never appear)

`qualityScore`, `trustScore`, `readinessScore`, `linkingReadiness`, `productionReady`,
run UUID, `correlationId`, `payloadHash`, `schemaFingerprint`, `rawRecordHash`, facility
external IDs, display names, coordinates, operator text, raw `source_metadata_json`,
exception text, or stack traces.

`latestImportReport` copies only allow-listed keys from persisted OSM import
`quality_report_json`: `named`, `unnamed`, `capacityKnown`, `rejectReasons` (max 50 keys),
`clipVersion`, `labelPolicyVersion`, `labelOutcomes` (known outcomes only). Text values
truncated at 128 chars.

## 8. Coverage denominators

| Metric | Numerator | Denominator |
|--------|-----------|-------------|
| `shareOfActiveFacilities` | Active facilities with ≥1 active link for source | All active facilities |
| Provenance field coverage | Distinct active facilities with selected provenance for field + source | Active facilities for that source |
| `nameBearingLabelCoverage` | OSM links whose `labelOutcome` ∈ {`real_name_selected`, `localized_name_selected`} | Active OSM-linked facilities |
| `nullAvailabilityCoverage` | Active OSM facilities with no non-null `available_spaces` snapshot | Active OSM-linked facilities |
| İZUM freshness buckets | Facilities in LIVE / AGING / STALE / availability-exposed bucket | Active İZUM-linked facilities with occupancy history (bucket totals) |

When denominator is **0**, `CoverageMetric.percentage` is **`null`** (not 0 or 100).

## 9. Label outcomes (DATA-WP-13)

Histogram reads `source_metadata_json ->> 'labelOutcome'` on active OSM facility links
(JSON object prefix filter + optimisation fence). Known outcomes match
`OsmDisplayLabelOutcome.metricOutcome()`:

`localized_name_selected`, `real_name_selected`, `operator_fallback`, `brand_fallback`,
`type_fallback`, `neutral_fallback`, `legacy_technical`, `invalid_name_rejected`,
`technical_id_removed`, `unchanged`.

Unknown/missing values roll into `unknown`. `staleNameMismatchCount` counts facilities
where outcome ∈ fallback set **and** OSM still owns `NAME` provenance (DATA-WP-14
cleanup signal).

## 10. Source-health reuse (DATA-WP-06)

`MunicipalQualityReportService` calls `MunicipalSourceHealthService.snapshot(sourceKey,
sourceEnabled, schedulerEnabled)` for each supported source. OSM `sourceEnabled` is true
when import **or** publication is enabled; İZUM uses `izum.enabled`. Evaluation fields
mirror the health indicator and Prometheus SLA gauges — no second copy of consecutive-failure
or stale-RUNNING logic.

Occupancy freshness on the summary row is the WP-06 label (`LIVE`, `AGING`, `STALE`, …),
distinct from İZUM bucket counts in the İZUM section.

## 11. Query bounds and indexes

All queries are aggregate-only (`COUNT`, `GROUP BY`, `DISTINCT ON`, `LIMIT 1`). No full
table scans of occupancy history without source/facility filters.

| Query | Index / bound |
|-------|----------------|
| Active facility counts | `idx_municipal_parking_facilities_active` |
| Per-source facility/link counts | `uq_municipal_facility_source_links_source_ext` + facility indexes |
| Provenance coverage | `municipal_facility_field_provenance` by `source_key`, field IN allow-list |
| Label outcome histogram | Active OSM links CTE; JSON cast behind `{` prefix filter |
| Latest OSM import quality JSON | `idx_municipal_source_sync_runs_source_started`, `LIMIT 1` |
| İZUM freshness buckets | `idx_municipal_occupancy_snapshots_facility_fetched`, `DISTINCT ON (facility_id)` |
| Recent sync runs | `findRecentCompleted(sourceId, limit)` with bounded `limit` ≤ max |
| Integrity duplicate groups | Small-table `GROUP BY … HAVING count(*) > 1` on links/provenance |

Service method: `@Transactional(readOnly = true)`.

**Caching:** none. Aggregate queries are cheap at current hosted-beta volumes; responses
always carry a fresh `generatedAt`. Do not introduce Redis solely for this report.

## 12. Read-only guarantee

- Controller exposes **GET only**; unsupported methods ⇒ 405 without reaching the service.
- Public service API: `overallReport()` and `sourceReport()` only.
- No repository write, sync trigger, outbox append, or link/candidate mutation.
- Report assembly is safe to run during incidents; it does not alter İZELMAN publication or
  public discovery behaviour.

## 13. Metrics

Micrometer component: `MunicipalQualityReportMetrics`.

| Metric | Type | Tags |
|--------|------|------|
| `parkio.municipal.ops.quality_report` | counter | `report_type` (`overall` \| `source`), `outcome` (`success` \| `client_error` \| `not_found` \| `error`), `source_family` (`none` \| `osm` \| `izum` \| `izelman` \| `unknown`), `policy_version` |
| `parkio.municipal.ops.quality_report.duration` | timer | same tags |

Full source keys are **never** metric tags. See
[observability-metrics.md](./observability-metrics.md).

## 14. DATA-WP-15A hosted-beta procedure (outline)

1. Deploy this commit with flag still **false**; verify path 404.
2. Set `PARKIO_MUNICIPAL_OPS_QUALITY_REPORT_ENABLED=true` on hosted-beta only.
3. Call overall + OSM + İZUM detail as ADMIN; confirm coverage denominators, label
   outcomes, and integrity counters match SQL spot-checks.
4. Confirm no sync/import side effects (sync-run count unchanged by reads alone).
5. Leave flag **on** only after explicit leave-on approval; production remains **false**
   until separate rollout.

## 15. Rollback / kill-switch

1. Set `PARKIO_MUNICIPAL_OPS_QUALITY_REPORT_ENABLED=false` (or unset) and restart
   parking-service ⇒ controller unregistered, endpoints 404.
2. Code rollback: redeploy previous parking-service image.
3. No data migration or cleanup required — report is read-only.

Catalogue: [kill-switch-catalogue.md](../operations/kill-switch-catalogue.md) (DATA-WP-15).

## 16. Out of scope

DATA-WP-15A leave-on gate, linking, İZELMAN publication, Flyway migrations, frontend,
aggregate scoring, production deploy without separate approval.

District geographic coverage is **DATA-WP-18** (additive `districtCoverage` section;
independent kill-switch; no migration).

## 17. Related packages

- [DATA-WP-06](wp-data-06-engineering-specification.md) — source health / SLA reuse
- [DATA-WP-13](wp-data-13-engineering-specification.md) — OSM label outcomes in link metadata
- [DATA-WP-14](wp-data-14-engineering-specification.md) — stale NAME mismatch signal in OSM section
- [DATA-WP-18](wp-data-18-engineering-specification.md) — İzmir district coverage section
