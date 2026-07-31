# DATA-WP-02 — OSM İzmir parking facility import and conservative conflation

## Architecture fit (DATA-WP-01 reuse)

DATA-WP-01 `municipal_*` tables and ports are **behaviorally generic** (source_key registry, sync runs with RUNNING lock, facility + source links, PostGIS nearby). Despite municipal-oriented names, OSM reuses them:

- Source key: `osm-geofabrik-turkey`
- Access type: `OPEN_DATA_FILE`
- No occupancy snapshots from OSM (freshness `UNAVAILABLE`, `availableSpaces=null`)
- Naming limitation documented; no destructive rename

## Official source

- Distributor page: https://download.geofabrik.de/europe/turkey.html
- License: ODbL 1.0
- Attribution: © OpenStreetMap contributors
- Geofabrik distributes extracts; does not endorse Parkio
- `production_approved=false` until ops promotion
- Bulk redistribution / derived DB obligations require **legal review** — table separation alone is insufficient

## Import flow

1. Download Geofabrik Turkey PBF (ops, not CI).
2. Verify SHA-256 with local tooling.
3. `osmium extract` with `izmir-bbox-v1` (or future polygon clip).
4. Export amenity=parking to Parkio GeoJSON interchange.
5. Configure `parkio.municipal.osm.local-input-path`.
6. Admin `POST /api/v1/parking/municipal/sources/osm-geofabrik-turkey/import?dryRun=true|false`.

## İzmir clip

- Version: `izmir-bbox-v1`
- Bounds: west 26.20, south 37.85, east 28.45, north 39.05 (WGS84)
- Temporary documented fallback until a licensed admin boundary polygon is checked in
- DATA-WP-02A: bbox is acceptable for hosted-beta validation with known adjacent-province / peninsula contamination; not a production administrative boundary

## External IDs

`node/{id}`, `way/{id}`, `relation/{id}` — numeric IDs alone are forbidden.

## Field provenance on ingest (DATA-WP-10)

Successful per-facility OSM upsert selects allow-listed provenance for fields OSM
actually supplied (`osm-geofabrik-turkey`). Synthetic display names do not claim
`NAME`; OSM never claims `ADDRESS`. Soft-deactivated facilities are not re-selected
unless they reappear in a later import. Public provenance publication remains off.

## Soft deactivation

Missing OSM elements are soft-deactivated **only after a complete successful import**. Failed/partial imports do not mass-deactivate.

## Conflation

Policy version `osm-conflation-v1` (see `ConflationPolicy`).

- Auto-match requires close geometry **and** strong semantic signal **and** no hard conflict
- Unnamed nearby parking never auto-matches by distance alone
- Decisions: AUTO_MATCHED, REVIEW_REQUIRED, REJECTED, NOT_MATCHED, MANUALLY_MATCHED, MANUALLY_REJECTED
- Manual reject/match persisted and survive later imports

## Field precedence

| Field | Preference |
|-------|------------|
| Live occupancy | Municipal only |
| Name / operator / capacity / hours | Municipal when present; OSM fills gaps |
| Access | Prefer known municipal; never drop restrictive OSM signal without review |
| Geometry | Source-specific retained on links; canonical point follows municipal when matched |

## Defaults (safe)

All OSM flags default **false** (import, scheduler, conflation, auto-match, publication). No remote download. No scheduler. Auto-match off. publication-enabled=false hides OSM-attributed facilities from discovery.

## Non-goals (explicit)

No Türkiye-wide publication, Gaziantep, İSPARK, İZELMAN importer, curb/parking:lane generation, Overpass, recommendations, or new microservice.