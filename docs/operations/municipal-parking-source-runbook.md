# Municipal parking source runbook (IZUM)

## Manual sync (hosted-beta / staging)

1. Ensure Flyway V28 applied.
2. Set:
   - `parkio.municipal.enabled=true`
   - `parkio.municipal.izum.enabled=true`
   - `parkio.municipal.manual-sync-enabled=true`
3. Trigger sync via municipal manual sync endpoint (source key `izmir-izum-otoparklar`).
4. Confirm `municipal_source_sync_runs.status` SUCCESS/PARTIAL_SUCCESS.
5. Confirm nearby facilities return `freshness=LIVE|AGING` with non-null `availableSpaces` only then.

## Scheduler

Enable only after soak:
`parkio.municipal.izum.scheduler-enabled=true`

## Rollback

1. Disable `parkio.municipal.izum.enabled` and scheduler.
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

### 2. Clip to `izmir-bbox-v1` (temporary bbox, not admin boundary)

Bounds: west 26.20, south 37.85, east 28.45, north 39.05 (WGS84).

Validated with osmium 1.19.0 via Docker image `iboates/osmium:latest` (entrypoint is `osmium`):

```bash
docker run --rm -v "$OPS_DIR:/data" iboates/osmium:latest extract \
  -b 26.20,37.85,28.45,39.05 -s complete_ways --set-bounds \
  -o /data/izmir-bbox-v1.osm.pbf /data/turkey-YYYYMMDD.osm.pbf
```

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

Also set `local-input-path` / `allowed-input-dir` (`PARKIO_MUNICIPAL_OSM_LOCAL_INPUT_PATH`, `PARKIO_MUNICIPAL_OSM_ALLOWED_INPUT_DIR`).

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