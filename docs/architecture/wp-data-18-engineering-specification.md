# DATA-WP-18 — Municipal District Coverage Quality Report

> **Naming:** DATA-WP-18 (municipal/parking data). Filenames follow `wp-data-18-*`.

## 1. Status

**Implementation complete (this package).** DATA-WP-18A (hosted-beta leave-on gate) is
**not started**. No deploy in this package.

## 2. Executive summary

After DATA-WP-08, İzmir inventory is clipped to the official administrative polygon, but
operators still could not see **where** active facilities sit across the 30 İzBB districts.
DATA-WP-15 reports city-level coverage only.

**DATA-WP-18** extends the existing read-only ADMIN quality-report API with a bounded
`districtCoverage` section built from the approved WP-08 district FeatureCollection
(`izmir-ilceler-source.geojson`, property `adi`, SHA-256
`6f4f43e4ce8139ddca4606582d903f047cb7c73810f8b876541a1ec3994ffd89`).

## 3. Feature flag

| Property | Env | Default |
|----------|-----|---------|
| `parkio.municipal.ops.district-coverage-enabled` | `PARKIO_MUNICIPAL_OPS_DISTRICT_COVERAGE_ENABLED` | `false` |

Independent of the main WP-15 quality-report flag. When quality-report is enabled and
district coverage is disabled, the overall report includes
`districtCoverage.status=DISABLED`. Hosted-beta Compose and production pin `false` until
DATA-WP-18A.

## 4. Asset configuration

| Property | Purpose |
|----------|---------|
| `parkio.municipal.ops.district-coverage.asset-path` | Operator GeoJSON path |
| `...expected-sha256` | Required checksum (official default) |
| `...name-property` | Default `adi` |
| `...expected-count` | Default `30` |
| `...max-facilities` | Hard cap (default `10000`) |
| `...cache-ttl-seconds` | In-process TTL (default `45`) |

Hosted-beta intended path (18A):

`/opt/parkio/ops/data-wp-08/boundary/izmir-ilceler-source.geojson`

Asset validation failures never crash startup or the main WP-15 sections. District section
returns `UNAVAILABLE` with a bounded reason (`asset_unavailable`, `asset_invalid`,
`facility_limit_exceeded`). No filesystem paths or raw validation text in the API.

## 5. Assignment policy

- Point-in-polygon **covers** (interior + boundary).
- Island rings incorrectly nested as holes are promoted (WP-08 repair semantics).
- Multi-match overlap → `overlapAnomalyCount++` and deterministic folded-name tie-break.
- No match → `UNASSIGNED` (never nearest-district guess).
- Missing/non-finite coordinates → `INVALID_COORDINATES`.
- Never infer district from address, name, operator, attribution, tags or labels.

## 6. Report contract

Additive field on overall quality report: `districtCoverage`.

Status: `AVAILABLE` | `DISABLED` | `UNAVAILABLE`.

Per district (deterministic folded Turkish name order): active totals, OSM/İZUM splits,
İZUM availability-exposed, OSM real-name / neutral-fallback label counts, provenance-covered
count.

**Empty district ≠ no parking / demand shortage / source failure** — only zero currently
active imported facilities assigned to that geometry.

Never exposes: polygons, GeoJSON, coordinates, facility IDs/names, external IDs, paths,
scores, rankings or readiness verdicts.

## 7. Query / cache / read-only

- One bounded facility projection query (`LIMIT max+1` for overflow detection).
- In-memory assignment over ≤ max facilities × 30 prepared geometries.
- 45s in-process cache; flag=false bypasses and clears cache.
- Reads never mutate facilities, links, provenance, occupancy, sync/import runs, candidates,
  reviews, aliases, tariffs or asset files.

## 8. Metrics

`parkio.municipal.ops.district_coverage.{requests,duration,facilities,anomalies}` with
bounded tags: `outcome`, `asset_status`, `policy_version`, `anomaly_type`. District names
are not metric labels.

## 9. Database

**No migration.** No district columns or materialized geometry tables.

## 10. DATA-WP-18A

Hosted-beta leave-on: enable flag + asset path, reconcile 30 districts to SQL, prove
no mutation, leave linking/İZELMAN/publication policies unchanged.

## 11. Rollback

Set `PARKIO_MUNICIPAL_OPS_DISTRICT_COVERAGE_ENABLED=false` (district section DISABLED) or
disable the main quality-report flag (404). Code rollback: previous parking-service image.
