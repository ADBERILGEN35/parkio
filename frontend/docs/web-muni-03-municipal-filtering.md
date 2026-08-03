# WEB-MUNI-03 — Municipal Discovery Filtering

**Program:** WEB-MUNI  
**Package:** WEB-MUNI-03  
**Status:** Implementation complete — **WEB-MUNI-03A not started**  
**Scope:** Frontend only. No backend mutation. No deploy in this package.

## Goal

Allow users to filter the municipal facility layer on `/map` using fields already present on `GET /parking/facilities/nearby` responses.

## Feature flag

Reuses `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED` / `frontendConfig.features.municipalDiscovery`.

No additional feature flag.

## Filters (client-side only)

| Filter | Source field(s) | Values |
|--------|-----------------|--------|
| Availability | `availableSpaces` only | All / Available (`> 0`) / Unavailable (`=== 0`) / Unknown (`null`) |
| Source | exact `sourceLabel` from the payload | Distinct labels present in results (e.g. IZUM / OSM strings as published) |
| Facility type | `facilityType` | `ON_STREET` / `OFF_STREET` / `UNKNOWN` when present |
| Provenance | non-empty `selectedFieldProvenanceSummary` | Optional “Has provenance” chip |

- Nearby query params unchanged (`lat` / `lng` / `radius` / `limit`).
- No extra requests, no polling, no backend faceting.
- Source chips use **exact** backend labels — no fabricated İZUM/OSM groups.
- Availability does **not** infer from freshness or capacity alone.

## Map behaviour

Filtering narrows:

- municipal markers
- municipal sidebar list
- which facilities can be selected from the filtered layer

Community spots / filters / sort remain unchanged.

Selection resolves from the **unfiltered** municipal set so a selected facility can survive filter changes. Direct `/facilities/{id}` navigation is independent of map filter state.

## URL persistence

Not applicable — same as community `SpotFilters` (in-memory presentation state only).

## Rollback

1. Rebuild/redeploy web without this commit, **or**
2. Keep `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=false` so municipal UI (including filters) never appears.

No database / API rollback.

## Acceptance checklist (implementation)

- [x] Pure filter helpers in `@parkio/geo` (`municipalDiscovery.ts`)
- [x] Filter chips in `MunicipalFacilityResults`
- [x] Map markers + sidebar use filtered list; community unaffected
- [x] Detail route unaffected by filter state
- [x] Accessibility: keyboard chips (`aria-pressed`), filter group label, live region for count
- [x] Unit tests (geo + results + MapPage)
- [x] Playwright smoke (flag-off: no municipal filter UI leak)
- [x] Docs

## Remaining limitations

- No clustering / search / sorting for municipal layer
- No URL-persisted filter state
- Source chip labels can be long (truncated with native `title`)
- Mobile out of scope
- Live availability often `unknown` for OSM-published inventory (backend semantics)
- Detail location map is WEB-MUNI-04 (`web-muni-04-municipal-detail-location.md`)

## Confirmations

- WEB-MUNI-03A not started
- No deployment
- No production change
- No backend mutation
- No API contract change
- No migration
- No linking
- No İZELMAN
