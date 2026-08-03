# WEB-MUNI-04 — Municipal Facility Detail Location Experience

**Program:** WEB-MUNI  
**Package:** WEB-MUNI-04  
**Status:** Implementation complete — **WEB-MUNI-04A not started**  
**Scope:** Frontend only. No backend mutation. No deploy in this package.

## Goal

Improve `/facilities/:facilityId` with:

1. A read-only embedded location map (reused `SpotMap`)
2. An **Open in maps** action (reused `openParkingLocationInMaps`)

Coordinates come only from the existing facility detail payload:

`GET /api/v1/parking/facilities/{id}`

## Map component reuse

- Canonical component: `frontend/apps/web/src/components/map/SpotMap.tsx`
- Same MapLibre + `getMapStyle()` tile path as community spot detail
- Detail zoom / non-draggable / no scroll-zoom (existing SpotMap behaviour)
- Optional props added without forking the stack:
  - `ariaLabel` — accessible map region name
  - `markerPresentation: 'municipal'` — secondary/garage pin (non-interactive)
  - `onError` — MapLibre error → local unavailable UI (no retry loop)
- Local `MunicipalMapErrorBoundary` isolates optional map failures from the page

**Marker limitation:** Detail map uses SpotMap’s municipal presentation (same visual language as discovery pins). It does **not** invent a new backend category. Community SpotDetail continues to use the default Marker.

## Coordinate source

- `facility.latitude` / `facility.longitude` from the detail DTO only
- Validated with `isUsableParkedCoordinate` (−90…90 / −180…180, finite)
- Invalid/missing → no map mount, no open-in-maps, localized location-unavailable status
- No fabricated fallback coordinates
- No geocoding / reverse geocoding

## Open in maps

- Helper: `openParkingLocationInMaps(lat, lng)` → HTTPS maps URL + `window.open(..., 'noopener,noreferrer')`
- No Parkio routing / ETA / traffic / directions API
- Button label does not expose raw coordinates
- External-tab hint via accessible text (`openInMapsExternal`)
- Remains available when the embedded map fails but coordinates are valid

## Feature flag

Reuses **only** `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED` / `frontendConfig.features.municipalDiscovery`.

| Flag | Behaviour |
|------|-----------|
| `false` (production default) | Disabled empty state; no facilities API; no map / open-in-maps |
| `true` | Detail loads once; map mounts when coordinates are valid |

No new flag.

## Request boundary

**Allowed Parkio API:** single `GET /parking/facilities/{id}` (existing query).

Map tile requests from the existing style provider are expected and are **not** Parkio API calls.

Forbidden from this page/map: nearby, spots list, mutations, geocoding, polling, duplicate facility fetches.

## Map failure degradation

- Facility summary/text remains visible
- Bounded localized `mapUnavailable` message
- Open-in-maps may stay enabled for valid coordinates
- No infinite retries / request loops
- No global app ErrorBoundary takeover for the optional map section

## Accessibility

- Location section semantic heading
- Map `role="region"` + `aria-label` from facility display name
- Municipal marker `role="img"` + same label when custom presentation is used
- Open-in-maps keyboard accessible; external navigation described
- MapLibre keyboard limitations: surrounding controls remain accessible; map does not trap focus for page chrome

## i18n

EN/TR under `map.municipal.detail.*`:

- `locationTitle`, `openInMaps`, `openInMapsA11y`, `openInMapsExternal`
- `locationUnavailable`, `mapUnavailable`, `mapAria`

## Rollback

1. Rebuild web without this commit, **or**
2. Keep `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=false`

No database / API rollback.

## WEB-MUNI-04A (out of scope here)

Hosted-beta leave-on gate for detail map + open-in-maps. **Not started** in this package.

## Confirmations

- WEB-MUNI-04A not started
- No deployment
- Production municipal discovery remains false by default
- No backend / API / DTO / migration change
- No routing / ETA / traffic service
- Automatic and reviewed linking remain disabled
- İZELMAN publication remains disabled
- No clustering / search / sorting / community–municipal fusion
