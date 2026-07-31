# DATA-WP-08 — İzmir Administrative Boundary Asset Validation and OSM Clip Upgrade

**Status:** COMPLETE (implementation + asset acceptance; hosted-beta reimport is DATA-WP-08A)  
**Clip version:** `izmir-admin-izbb-2024-10-18-v1`  
**Legacy rollback clip:** `izmir-bbox-v1`  
**Flyway:** none (configuration/metadata only)

## Goal

Replace the temporary metropolitan bbox clip (`izmir-bbox-v1`) with a verified İzmir
administrative MultiPolygon derived from the official İZBB district boundaries
(`ilceler.geojson`), and upgrade the osmium extraction path to polygon clipping.

## Official source (verified)

| Field | Value |
|-------|-------|
| Publisher | İzmir Büyükşehir Belediyesi |
| Publisher unit | İzBB Coğrafi Bilgi Sistemleri Şube Müdürlüğü |
| Dataset | İzmir Şehir Haritası |
| Package id | `9292e9ab-3832-45a7-99e6-b1c5c6e35264` |
| Resource id | `c4b1da96-c547-4cca-a9a7-4053d0fee54f` |
| Source URL | https://acikveri.bizizmir.com/dataset/9292e9ab-3832-45a7-99e6-b1c5c6e35264/resource/c4b1da96-c547-4cca-a9a7-4053d0fee54f/download/ilceler.geojson |
| Updated | 2024-10-18 |
| License | CC BY 4.0 (İzmir Büyükşehir Belediyesi Açık Veri Lisansı) |
| Source SHA-256 | `6f4f43e4ce8139ddca4606582d903f047cb7c73810f8b876541a1ec3994ffd89` |
| Source bytes | 10560519 |
| Features / districts | 30 / 30 |
| Name field | `adi` |
| Source CRS | EPSG:4326 (inferred; no CRS member; lon/lat in İzmir range) |

### District validation

Official 30 İzmir districts present; no missing/extra/duplicate/foreign names.
Name folding is comparison-only (Turkish uppercase → ASCII fold) and does not mutate source properties.

### Geometry validation

28/30 features were OGC-valid Polygons. `URLA` and `DİKİLİ` encoded island rings as
Polygon holes outside the shell (`Hole lies outside shell`). Transparent repair:

- promote exterior island rings to MultiPolygon components
- preserve source bytes untouched
- write repaired geometry only into derived dissolve output
- area delta vs ring-sum = 0

Derived dissolve: valid `MultiPolygon`, 3 components, ~11665 km², bbox
`(26.2302474, 37.815253)–(28.4930441, 39.3854527)`.

Derived checksums:

- GeoJSON `ddd5664064a6bad22920d64a9f83c5c11c3ba85e4fb0e55a17bd3a26c31d2b61`
- `.poly` `5b20558b28e93c1fb7f2bcda2e36142b186846e63a7151285dae76bb19f5d7b1`

## Operator asset layout

Windows prep: `D:/parkio-ops/data-wp-08/boundary/`  
Hosted-beta: `/opt/parkio/ops/data-wp-08/boundary/`

Files: source GeoJSON, derived GeoJSON, `.poly`, `MANIFEST.yml`, `CHECKSUMS.sha256`,
`validation-report.json`.

Large geometry remains operator-managed (CC BY allows redistribution; repo policy keeps
PBF/generated extracts and full source out of Git). Repository stores contracts,
checksums, validators, scripts, and documentation.

See `docs/contracts/data-wp-08/boundary-asset-contract.md`.

## Runtime behavior

1. Osmium extract uses `-p izmir-admin-boundary.poly` (`scripts/data-wp-08/extract-izmir-osm-polygon.sh`).
2. Failed extract writes only under a temp dir and does not promote; previous known-good
   outputs (including `izmir-bbox-v1.osm.pbf`) are preserved.
3. Import records `clipVersion` on `municipal_osm_import_runs`.
4. Soft-deactivation still runs only after complete successful non-dry-run import.
5. OSM `availableSpaces` remains null; freshness `UNAVAILABLE`; no OSM occupancy snapshots.
6. İZUM / İZELMAN / registry linking / provenance / duplicate-presentation policies unchanged.
7. No Flyway migration.

### Configuration

| Key | Default | Notes |
|-----|---------|-------|
| `parkio.municipal.osm.clip-version` | `izmir-admin-izbb-2024-10-18-v1` | Rollback: `izmir-bbox-v1` |
| `parkio.municipal.osm.boundary-dir` | empty | Operator path; CI-safe when empty |
| `parkio.municipal.osm.boundary-geojson-sha256` | empty | Optional enforce |
| `parkio.municipal.osm.boundary-source-sha256` | official source SHA | Documented contract |

When `boundary-dir` is empty, `IzmirClip` uses the verified admin envelope as a secondary
parser safety filter. Polygon membership loads when the derived GeoJSON is present.

## Non-goals / still off

- DATA-WP-08A hosted-beta deploy + real reimport
- Automatic / reviewed linking
- İZELMAN publication
- OSM availability semantics changes
- Registry linking enablement
- Committing Türkiye PBF / clipped PBF / full parking GeoJSON

## Rollback (no schema rollback)

1. Set `PARKIO_MUNICIPAL_OSM_CLIP_VERSION=izmir-bbox-v1`.
2. Point `local-input-path` at the preserved bbox-derived Parkio GeoJSON.
3. Dry-run then mutating import.
4. Keep admin polygon assets on disk for later retry.

## Related docs

- `docs/operations/izmir-admin-boundary-asset-runbook.md`
- `docs/operations/municipal-parking-source-runbook.md`
- `docs/architecture/wp-data-02-osm-izmir-facility-import.md`
- `docs/contracts/data-wp-08/boundary-asset-contract.md`
