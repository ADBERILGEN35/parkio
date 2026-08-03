# Municipal parking source runbook (IZUM)

## Hosted-beta Compose env bindings (DATA-WP-02C)

Azure overlay `docker/docker-compose.azure-hosted-beta.yml` must map these into
`parking-service` (env-file alone is insufficient):

| Flag | Env | Default |
|------|-----|---------|
| `parkio.municipal.enabled` | `PARKIO_MUNICIPAL_ENABLED` | false |
| `parkio.municipal.manual-sync-enabled` | `PARKIO_MUNICIPAL_MANUAL_SYNC_ENABLED` | false |
| `parkio.municipal.izum.enabled` | `PARKIO_MUNICIPAL_IZUM_ENABLED` | false |
| `parkio.municipal.izum.scheduler-enabled` | `PARKIO_MUNICIPAL_IZUM_SCHEDULER_ENABLED` | false |
| `parkio.municipal.izum.fixed-delay-ms` | `PARKIO_MUNICIPAL_IZUM_FIXED_DELAY_MS` | 120000 |
| `parkio.municipal.izum.connect-timeout` | `PARKIO_MUNICIPAL_IZUM_CONNECT_TIMEOUT` | 2s |
| `parkio.municipal.izum.read-timeout` | `PARKIO_MUNICIPAL_IZUM_READ_TIMEOUT` | 5s |
| `parkio.municipal.izum.max-retries` | `PARKIO_MUNICIPAL_IZUM_MAX_RETRIES` | 2 |
| `parkio.municipal.registry.provenance-ingest-write-enabled` | `PARKIO_MUNICIPAL_REGISTRY_PROVENANCE_INGEST_WRITE_ENABLED` | true |
| `parkio.municipal.registry.provenance-publication-enabled` | `PARKIO_MUNICIPAL_REGISTRY_PROVENANCE_PUBLICATION_ENABLED` | true (prod profile: false) |
| `parkio.municipal.discovery.duplicate-presentation-enabled` | `PARKIO_MUNICIPAL_DISCOVERY_DUPLICATE_PRESENTATION_ENABLED` | true (prod profile: false) |
| `parkio.municipal.ops.quality-report-enabled` | `PARKIO_MUNICIPAL_OPS_QUALITY_REPORT_ENABLED` | false |

Source key: `izmir-izum-otoparklar`.  
Admin: `POST /api/v1/parking/municipal/sources/{sourceKey}/sync` (requires `municipal.enabled` + `manual-sync-enabled`; İZUM also requires `izum.enabled`).  
Freshness thresholds live on the seeded source row (aging 300s, stale 900s), not Compose.

## Manual sync (hosted-beta / staging)

1. Ensure Flyway V28 applied.
2. Set:
   - `parkio.municipal.enabled=true`
   - `parkio.municipal.izum.enabled=true`
   - `parkio.municipal.manual-sync-enabled=true`
3. Trigger sync via municipal manual sync endpoint (source key `izmir-izum-otoparklar`).
4. Confirm `municipal_source_sync_runs.status` SUCCESS/PARTIAL_SUCCESS.
5. Confirm nearby facilities return `freshness=LIVE|AGING` with non-null `availableSpaces` only then.
6. Confirm `municipal_facility_field_provenance` gained allow-listed rows for synced facilities
   (DATA-WP-10). Nearby/detail provenance DTO fields are published when publication is
   true (DATA-WP-11 canonical default); set publication false to restore null fields.
7. Historical provenance backfill is **not** provided — re-run sync/import instead of guessing
   field ownership from `primary_source_key`. Successful re-ingest also withdraws same-source
   stale selections (DATA-WP-14).

## Scheduler

Enable only after soak:
`parkio.municipal.izum.scheduler-enabled=true`  
Cadence: `fixed-delay-ms` default **120000** (2 minutes). Job is gated by municipal.enabled + izum.enabled + izum.scheduler-enabled. Unique RUNNING lock prevents overlap.

## Source health / SLA (DATA-WP-06)

Operational SLA is **not** the same as occupancy freshness:

| Concern | Meaning | Typical signal |
|---------|---------|----------------|
| Occupancy freshness | Whether public `availableSpaces` may be shown | LIVE / AGING / STALE on facility DTO |
| Operational SLA | Whether the İZUM integration is healthy | consecutive failures, seconds since success, Prometheus alerts |

Public STALE masking remains authoritative for availability. Do **not** raise aging/stale thresholds to hide upstream outages.

### Defaults (non-secret)

| Setting | Default |
|---------|---------|
| `parkio.municipal.sla.warning-consecutive-failures` | 3 |
| `parkio.municipal.sla.critical-consecutive-failures` | 5 |
| `parkio.municipal.sla.warning-seconds-since-success` | 600 |
| `parkio.municipal.sla.critical-seconds-since-success` | 1800 |
| `parkio.municipal.sla.stale-running-after-seconds` | 600 |

### Alert meanings

- **ConsecutiveFailuresWarning/Critical** — trailing FAILED sync runs (SKIPPED ignored).
- **SecondsSinceSuccessWarning/Critical** — no SUCCESS/PARTIAL_SUCCESS within SLA window.
- **StaleRunningOperation** — a RUNNING row older than the stale-running threshold.
- **MunicipalSourceRecovered** — info signal after a success resets a failure streak.

Disabled sources must not page. Scheduler kill switch remains `izum.scheduler-enabled=false` (and/or `izum.enabled=false`).

## Quality & coverage report (DATA-WP-15)

Read-only ADMIN report for registry coverage and source health. **Default off** everywhere
(canonical, prod profile, hosted-beta Compose). Does not trigger sync, import, linking, or
İZELMAN publication.

1. Set `PARKIO_MUNICIPAL_OPS_QUALITY_REPORT_ENABLED=true` and restart parking-service (or
   enable only for DATA-WP-15A on hosted-beta).
2. Call with gateway JWT and ADMIN role (`X-User-Roles` includes `ADMIN` or `SUPER_ADMIN`):
   - Overall: `GET /api/v1/parking/admin/municipal/quality-report`
   - OSM detail: `GET /api/v1/parking/admin/municipal/quality-report/sources/osm-geofabrik-turkey`
   - İZUM detail: `GET /api/v1/parking/admin/municipal/quality-report/sources/izmir-izum-otoparklar`
   - Optional `?limit=N` (1–100, default 20) on source detail for recent sync runs.
3. When the flag is false, the path returns **404** (controller not registered).
4. Use `integrity.*` and OSM `staleNameMismatchCount` alongside WP-06 SLA alerts; the report
   carries no aggregate quality score or readiness verdict.
5. Rollback: set flag false and restart. Spec:
   [`wp-data-15-engineering-specification.md`](../architecture/wp-data-15-engineering-specification.md).

### Timeout vs schema_contract

New runs classify I/O timeouts as `read_timeout` / `connect_timeout`. Schema/validation breaks are `schema_contract`. Historical rows may still show legacy `contract` for timeouts — do not rewrite them heuristically.

### Diagnostic checklist (bounded)

1. `izumLastRunStatus` / `izumLastRunTimestamp`
2. `izumLastSuccessTimestamp` / `izumSecondsSinceSuccess`
3. `izumLastErrorCategory` (bounded taxonomy only)
4. `izumConsecutiveFailures`
5. Public facility `freshness` + null `availableSpaces` when STALE
6. Scheduler flags (`municipal.enabled`, `izum.enabled`, `izum.scheduler-enabled`)
7. Upstream reachability outside the app (without dumping payloads or tokens)

Actuator: `/actuator/health` detail contributor `municipalSources` (always UP for liveness). Grafana: `parkio-municipal-source-health`.

### Recovery confirmation

1. Observe a SUCCESS/PARTIAL_SUCCESS sync run.
2. Confirm consecutive failures reset to 0 and recovery counter/alert resolves.
3. Confirm public freshness returns to LIVE/AGING only from the **new** observation timestamp (do not expect old STALE snapshots to rewrite).

### Escalation

- Prolonged `read_timeout` / `connect_timeout` → treat as upstream/network incident; keep scheduler enabled unless disk/CPU risk.
- Repeated `schema_contract` → halt publication assumptions; investigate adapter contract; do not fabricate occupancy.
- Never enable registry linking or İZELMAN publication as an incident workaround.

## Rollback

1. Disable `parkio.municipal.izum.scheduler-enabled` (and/or `izum.enabled`).
2. Facilities remain; occupancy ages to STALE and free spaces disappear from API.
3. Do not drop V28 tables in production without a dedicated migration plan.

## Local fixture development

Use `src/test/resources/fixtures/municipal/izum/otoparklar-sample.json`.
Do not point CI at `openapi.izmir.bel.tr`.

## OSM / Geofabrik (DATA-WP-02 / DATA-WP-02A)

Store extracts **outside** Git (ops directory). Do not commit PBF or generated GeoJSON.

### 1. Download official Geofabrik Türkiye extract

Source page: https://download.geofabrik.de/europe/turkey.html

```bash
curl -L --fail -o turkey-YYYYMMDD.osm.pbf \
  https://download.geofabrik.de/europe/turkey-latest.osm.pbf
# Record Content-Length / Last-Modified from HEAD, then:
sha256sum turkey-YYYYMMDD.osm.pbf
```

License: ODbL 1.0. Attribution: © OpenStreetMap contributors. Geofabrik distributes extracts; does not endorse Parkio.

### 2. Clip to `izmir-admin-izbb-2024-10-18-v1` (administrative polygon)

Preferred path (DATA-WP-08):

```bash
export PARKIO_OSM_OPS_DIR=/opt/parkio/ops/data-wp-02b
export PARKIO_OSM_BOUNDARY_DIR=/opt/parkio/ops/data-wp-08/boundary
./scripts/data-wp-08/extract-izmir-osm-polygon.sh /data/turkey-YYYYMMDD.osm.pbf
```

Boundary preparation: `docs/operations/izmir-admin-boundary-asset-runbook.md`.

#### Legacy rollback: `izmir-bbox-v1` (temporary bbox)

Bounds: west 26.20, south 37.85, east 28.45, north 39.05 (WGS84).

Validated with osmium 1.19.0 via Docker image `iboates/osmium:latest` (entrypoint is `osmium`):

```bash
docker run --rm -v "$OPS_DIR:/data" iboates/osmium:latest extract \
  -b 26.20,37.85,28.45,39.05 -s complete_ways --set-bounds \
  -o /data/izmir-bbox-v1.osm.pbf /data/turkey-YYYYMMDD.osm.pbf
```

Do not delete the previous bbox extract when adopting the admin polygon.
### 3. Filter parking + export GeoJSON with IDs/polygons

```bash
docker run --rm -v "$OPS_DIR:/data" iboates/osmium:latest tags-filter \
  -o /data/izmir-parking-objects.osm.pbf /data/izmir-bbox-v1.osm.pbf nwr/amenity=parking

docker run --rm -v "$OPS_DIR:/data" iboates/osmium:latest getid -r \
  -I /data/izmir-parking-objects.osm.pbf \
  -o /data/izmir-parking-complete.osm.pbf /data/izmir-bbox-v1.osm.pbf

# export config: attributes type+id true; area_tags true; include allowlisted tags
docker run --rm -v "$OPS_DIR:/data" iboates/osmium:latest export \
  -c /data/osmium-export-parkio.json -a type,id -u type_id \
  --geometry-types=point,polygon -e -O -f geojson \
  -o /data/izmir-parking-osmium.geojson /data/izmir-parking-complete.osm.pbf
```

Convert osmium GeoJSON → Parkio `osm-parking-geojson-v1` (preserve `osmType`/`osmId`, amenity=parking only). Keep interchange under `parkio.municipal.osm.max-input-bytes` (50 MiB).

### 4. Import flags (defaults remain false)

| Flag | Env (Compose → parking-service) | Default | Notes |
|------|----------------------------------|---------|--------|
| `parkio.municipal.enabled` | `PARKIO_MUNICIPAL_ENABLED` | false | Master gate; required with OSM import |
| `parkio.municipal.osm.import-enabled` | `PARKIO_MUNICIPAL_OSM_IMPORT_ENABLED` | false | Required for admin import |
| `parkio.municipal.osm.scheduler-enabled` | `PARKIO_MUNICIPAL_OSM_SCHEDULER_ENABLED` | false | Keep off |
| `parkio.municipal.osm.conflation-enabled` | `PARKIO_MUNICIPAL_OSM_CONFLATION_ENABLED` | false | Optional offline/review |
| `parkio.municipal.osm.auto-match-enabled` | `PARKIO_MUNICIPAL_OSM_AUTO_MATCH_ENABLED` | false | Separate gate; keep off until review passes |
| `parkio.municipal.osm.publication-enabled` | `PARKIO_MUNICIPAL_OSM_PUBLICATION_ENABLED` | false | Hides OSM-attributed rows from nearby/detail when false |
| `parkio.municipal.osm.label-policy` | `PARKIO_MUNICIPAL_OSM_LABEL_POLICY` | `osm-label-v1` | DATA-WP-13; `legacy` restores technical `OSM parking …` labels |

Also set `local-input-path` / `allowed-input-dir` (`PARKIO_MUNICIPAL_OSM_LOCAL_INPUT_PATH`, `PARKIO_MUNICIPAL_OSM_ALLOWED_INPUT_DIR`).

### Display labels (DATA-WP-13) and provenance reconciliation (DATA-WP-14)

Under `osm-label-v1`, public `displayName` prefers validated OSM name tags, then
readable operator/brand/type/neutral Turkish fallbacks. External IDs and source
links are unchanged. Roll back with `label-policy=legacy` + reimport. Does **not**
change ranking, linking, availability, or duplicate-presentation. See
`docs/architecture/wp-data-13-engineering-specification.md`.

Complete successful OSM reimport also reconciles same-source provenance
(DATA-WP-14): fallback-only labels withdraw stale `NAME` rows. Use reimport for
hosted-beta cleanup of known stale selections — no mass-delete job. See
`docs/architecture/wp-data-14-engineering-specification.md`.

Azure hosted-beta: these env keys are mapped in `docker/docker-compose.azure-hosted-beta.yml` and must be present in `docker/.env.azure-hosted-beta`. Values in the env file alone do not reach the container without that Compose mapping. Ops GeoJSON is mounted read-only from `/opt/parkio/ops/data-wp-02b`.

### 5. Dry-run then mutating import

```http
POST /api/v1/parking/municipal/sources/osm-geofabrik-turkey/import?dryRun=true
POST /api/v1/parking/municipal/sources/osm-geofabrik-turkey/import?dryRun=false
```

Dry-run must not create OSM facilities. Mutating import must not create occupancy snapshots. Soft-deactivation of missing OSM elements runs only after a **complete successful** import; concurrent RUNNING sync skips; re-import reactivates previously soft-deactivated facilities.

### 6. Kill switches / rollback

1. `publication-enabled=false` (hide OSM from discovery immediately).
2. `import-enabled=false` / `scheduler-enabled=false`.
3. `auto-match-enabled=false` (never merge links automatically).
4. Do not drop V29 tables in production without a dedicated migration plan.

Attribution on OSM-derived facilities: © OpenStreetMap contributors (ODbL). Legal review required before bulk redistribution.

### Opt-in real-data IT (operators)

```bash
export PARKIO_OSM_REAL_IZMIR_VALIDATION=true
export PARKIO_OSM_REAL_IZMIR_GEOJSON=/path/to/izmir-parking-parkio.geojson
./gradlew :services:parking-service:integrationTest \
  --tests com.parkio.parking.infrastructure.osm.OsmRealIzmirImportValidationIT
```

## Nearby duplicate-presentation (DATA-WP-07 / DATA-WP-12)

Canonical and Azure hosted-beta default **true** for
`parkio.municipal.discovery.duplicate-presentation-enabled`. Production `prod`
profile pins **false** until separate approval.

- Nearby may suppress strong IZUM↔OSM presentation duplicates only.
- Detail `GET /api/v1/parking/facilities/{id}` is never suppressed.
- Kill-switch: set env/property `false` to restore legacy nearby result set/order immediately.
- Zero suppressions is an acceptable safe result; false positives are avoided conservatively.
- Independent of provenance publication, linking, and İZELMAN publication.
- Matching radius/overfetch/thresholds are unchanged by DATA-WP-12.

### Hosted-beta leave-on (DATA-WP-12A — not started here)

1. Deploy with Compose `:-true` left on (do not force false after gate).
2. Verify representative nearby queries; confirm detail for any suppressed IDs.
3. Confirm linking + İZELMAN publication remain false; no DB mutation from reads.
4. Canonical smoke; retain presentation enabled unless incident requires kill-switch.

### Production warning

Do not flip production to true without a dedicated rollout approval. `application-prod.yml`
keeps the env default false under the `prod` profile.