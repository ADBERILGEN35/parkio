# WEB-MUNI-10A — Municipal Data-Source Presentation

**Program:** WEB-MUNI
**Package:** WEB-MUNI-10A
**Status:** Implementation complete
**Scope:** Frontend presentation only. No backend, API, database, deploy, or occupancy work.

## Goal

Present municipal facility data sources with canonical, human-readable labels. Remove internal ETL/debug provenance from public UI while keeping backend/API fields unchanged.

## Boundary

| Layer | Changed? |
|-------|----------|
| `frontend/packages/geo` presentation helper | Yes |
| `frontend/apps/web` municipal UI surfaces | Yes |
| Backend services, DTOs, API responses | No |
| Database / Flyway / ingest / ETL | No |
| Map legal attribution (`mapConfig`, MapLibre controls) | No |
| Municipal filter URL semantics / raw `sourceLabel` filter values | No |
| Occupancy / freshness business logic | No |

## Canonical public labels

| Raw ingestion key / family | User-facing label |
|----------------------------|-------------------|
| `osm-geofabrik-turkey`, OSM `sourceLabel` heuristics | **OpenStreetMap** |
| `izmir-izum-otoparklar`, İZUM `sourceLabel` heuristics | **İzmir Büyükşehir Belediyesi / İZUM** |
| Unknown / unsupported keys | Omitted |

Multi-source order: İZUM → OpenStreetMap → other supported sources (deduplicated).

## UI surfaces

- Municipal list cards (`formatMunicipalDataSourcesLine`)
- Selected municipal preview / bottom sheet
- Municipal facility detail page (`/facilities/:facilityId`)
- Source filter chip **display text** (`displaySourceLabelForFilter`; filter values remain raw backend `sourceLabel`)

Removed from public UI:

- “Alan kaynağı” / field-provenance block (`selectedFieldProvenanceSummary` is no longer rendered)
- Concatenated `sourceLabel · attribution` distributor strings

`selectedFieldProvenanceSummary` remains on the API/DTO for filters and future packages.

## i18n

- Singular heading: **Veri kaynağı** / **Data source**
- Plural heading: **Veri kaynakları** / **Data sources**

## Out of scope (future packages)

- Occupancy investigation (WEB-MUNI-10B and later)
- Municipality matching / conflation
- Backend provenance publication policy changes
- Filter or URL redesign

## Rollback

Rebuild/redeploy web without this commit, or keep `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=false`. No API or database rollback required.
