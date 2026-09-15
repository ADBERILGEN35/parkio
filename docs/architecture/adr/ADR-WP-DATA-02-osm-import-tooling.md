# ADR-WP-DATA-02 — OSM import tooling for İzmir parking facilities

## Status

Accepted — 2026-07-30

## Context

DATA-WP-02 must import OSM `amenity=parking` facilities for İzmir without depending on public Overpass or downloading the Geofabrik Türkiye extract in CI.

## Decision

1. **Production input**: Official Geofabrik Türkiye PBF (`https://download.geofabrik.de/europe/turkey.html`), clipped offline.
2. **In-app interchange**: Parkio normalized GeoJSON FeatureCollection (`osm-parking-geojson-v1`) read from a configured local path.
3. **Offline prep tool**: `osmium` (extract + export) documented for operators. The JVM does **not** parse PBF.
4. **Clip**: Versioned `izmir-admin-izbb-2024-10-18-v1` administrative polygon (DATA-WP-08). Legacy `izmir-bbox-v1` metropolitan bbox retained for rollback. Clip id is recorded on every import run.
5. **Registry**: Reuse DATA-WP-01 `municipal_data_sources` / links / sync runs with source key `osm-geofabrik-turkey` (`OPEN_DATA_FILE`). Municipal naming is retained as a known limitation.

## Rejected alternatives

| Option | Why rejected |
|--------|----------------|
| Public Overpass in production | Availability, ToS, non-reproducible, rate limits |
| Custom PBF parser | High complexity / maintenance |
| osm2pgsql staging schema in v1 | Extra operational surface for hosted-beta |
| Multiple overlapping toolchains | Operational confusion |
| Committing Türkiye PBF to Git | Size / license / repo health |

## Consequences

- CI uses tiny deterministic GeoJSON fixtures only.
- Operators must run osmium (or equivalent) before enabling import.
- ODbL attribution and legal-review flags are mandatory in docs and source registry text.