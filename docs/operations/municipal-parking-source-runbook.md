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

## OSM / Geofabrik (DATA-WP-02)

1. Download Turkey extract from Geofabrik (ops machine).
2. Record filename, bytes, SHA-256, extract timestamp.
3. Clip with osmium using `izmir-bbox-v1` (see architecture doc).
4. Export `amenity=parking` to Parkio GeoJSON `osm-parking-geojson-v1`.
5. Set `parkio.municipal.enabled=true`, `parkio.municipal.osm.import-enabled=true`, `local-input-path`, optional `allowed-input-dir`.
6. Dry-run: `POST /api/v1/parking/municipal/sources/osm-geofabrik-turkey/import?dryRun=true` (admin).
7. Keep `auto-match-enabled=false` until sample review passes.
8. Kill switches listed in kill-switch catalogue.

Attribution on OSM-derived facilities: © OpenStreetMap contributors (ODbL). Legal review required before bulk redistribution.