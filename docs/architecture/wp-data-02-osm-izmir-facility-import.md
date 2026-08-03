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
3. `osmium extract` with `izmir-admin-izbb-2024-10-18-v1` polygon (`.poly`); legacy `izmir-bbox-v1` bbox retained for rollback.
4. Export amenity=parking to Parkio GeoJSON interchange.
5. Configure `parkio.municipal.osm.local-input-path`.
6. Admin `POST /api/v1/parking/municipal/sources/osm-geofabrik-turkey/import?dryRun=true|false`.

## İzmir clip

- Current version: `izmir-admin-izbb-2024-10-18-v1` (İZBB district dissolve; see DATA-WP-08)
- Legacy rollback: `izmir-bbox-v1` bounds west 26.20, south 37.85, east 28.45, north 39.05 (WGS84)
- Admin envelope (parser fallback): west 26.2302474, south 37.815253, east 28.4930441, north 39.3854527
- Operator boundary: `/opt/parkio/ops/data-wp-08/boundary/` (see `docs/operations/izmir-admin-boundary-asset-runbook.md`)
- DATA-WP-02A used bbox for hosted-beta validation; DATA-WP-08 replaces extract path with polygon clipping

## External IDs

`node/{id}`, `way/{id}`, `relation/{id}` — numeric IDs alone are forbidden.

## Field provenance on ingest (DATA-WP-10 / DATA-WP-13)

Successful per-facility OSM upsert selects allow-listed provenance for fields OSM
actually supplied (`osm-geofabrik-turkey`). Under **`osm-label-v1`** (DATA-WP-13),
public display names prefer validated `name:tr` → `name` → `official_name` →
`short_name`, then readable operator/brand/type/neutral fallbacks — never
`OSM parking {element}/{id}` as the public label. `NAME` provenance is claimed
**only** for real name-bearing tags; operator/brand/type/neutral fallbacks do not
claim `NAME`. Brand fallback must not invent `OPERATOR`. OSM never claims
`ADDRESS`. Soft-deactivated facilities are not re-selected unless they reappear in
a later import. **DATA-WP-14** reconciles same-source provenance on successful
ingest: when OSM no longer supplies a valid name-bearing selection, stale `NAME`
provenance is withdrawn in the same per-facility transaction. Public provenance
publication is controlled separately (DATA-WP-11).

See [DATA-WP-13 engineering specification](wp-data-13-engineering-specification.md)
and [DATA-WP-14 engineering specification](wp-data-14-engineering-specification.md).

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