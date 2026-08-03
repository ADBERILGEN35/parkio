# WEB-MUNI-02 — Municipal Facility Detail Route

**Program:** WEB-MUNI  
**Package:** WEB-MUNI-02  
**Status:** Implementation complete — **WEB-MUNI-02A not started**  
**Scope:** Frontend only. No backend mutation. No deploy in this package.

## Goal

Add a shareable, refreshable detail route for municipal parking facilities:

`/facilities/:facilityId`

In-map preview from WEB-MUNI-01 remains unchanged; the preview gains a **View facility details** link.

## Feature flag

Reuses `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED` / `frontendConfig.features.municipalDiscovery`.

| Flag | Detail route behavior |
|------|------------------------|
| `false` (default) | Route exists but renders disabled empty state; **no** facilities API call |
| `true` | Loads `GET /parking/facilities/{id}` and shows read-only detail |

No new feature flag.

## Data

- Only `GET /api/v1/parking/facilities/{id}` via existing `useMunicipalFacilityDetailQuery` / `municipalFacilityDetailQueryOptions`.
- Invalid UUID path params skip the query.
- No polling; React Query default fetch once per mount/key.

## Map integration

- Marker / list selection still opens the in-map preview (WEB-MUNI-01 behavior).
- Preview CTA navigates to `/facilities/{id}` (same pattern as community `/spots/{id}`).
- **WEB-MUNI-04:** Detail page embeds a read-only `SpotMap` + **Open in maps** using facility DTO coordinates only. See `web-muni-04-municipal-detail-location.md`.

## Rollback

1. Rebuild web without this commit, **or**
2. Keep flag `false` so the detail route never exposes municipal UI.

No database / API rollback.

## Acceptance checklist (implementation)

- [x] `/facilities/:facilityId` registered in route manifest
- [x] Document title `titles.facilityDetails`
- [x] Detail fields from backend only
- [x] Loading / 404 / network / invalid-id / flag-off states
- [x] Preview link without changing preview selection behavior
- [x] Unit + routing + Playwright smoke tests
- [x] Docs

## Remaining limitations

- Flag-off still matches the route URL (shows disabled UI, not global 404)
- Embedded detail map styling is SpotMap municipal presentation (not a separate map stack)
- Mobile out of scope
- WEB-MUNI-04A leave-on gate not started here

## Confirmations

- WEB-MUNI-02A not started
- No deployment
- No production change
- No backend mutation
- No API contract change
- No migration
- No linking
- No İZELMAN
