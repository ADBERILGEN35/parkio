# Architecture map (code-verified) — İzmir coverage expansion

See also live SOURCE-INVENTORY.md. Summary of reuse paths (2026-09-22):

| Concern | Ready today | Gap |
| --- | --- | --- |
| IZUM live sync | Adapter + AUTHORITATIVE_FULL_SET; PR #70 incomplete-snapshot skip | Prod still needs #70 image + recovery |
| İZELMAN CSV import | Admin `izelman-import?dryRun=` + named files under allowed-input-dir | No scheduler; publication default false |
| OSM Geofabrik | Operator GeoJSON import + IzmirClip | Need clipped GeoJSON asset; publication false |
| Dedupe | IZUM↔OSM conflation + registry pairs | IZUM↔İZELMAN / OSM↔İZELMAN pairs disabled |
| Occupancy vs inventory | LIVE/AGING only expose spaces; STALE/UNAVAILABLE keep facility | FE already distinguishes stale_live vs static |
| Authenticated nearby limit | Was default 20 / max 100 | Expansion branch raises default to 100 + web always requests 100 |
| Explore | Hard cap limit=6, radius=5000, IZUM/ISPARK families only | Do not widen anonymously without policy change |

Key paths:

- `IzelmanImportApplicationService`, `IzelmanSourceKeys`, `IzelmanCsvReader`
- `OsmImportApplicationService`, `IzmirClip`, `OsmGeofabrikSourceKeys`
- `MunicipalSourcePublicationPolicy`, `PublicExplorePublicationPolicy`
- `MunicipalFacilityQueryService.project` + `OccupancyFreshnessPolicy`
- Runbook: `docs/operations/municipal-parking-source-runbook.md`
