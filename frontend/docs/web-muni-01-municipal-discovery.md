# WEB-MUNI-01 — Surface municipal facilities on hosted-beta web map

**Program:** WEB-MUNI (productization)  
**Package:** WEB-MUNI-01  
**Status:** Implementation + hosted-beta leave-on complete (**WEB-MUNI-01 / 01A CLOSED**)  
**Scope:** Web discovery only. No backend mutation. No mobile.

## Goal

Expose existing municipal parking facilities (`GET /api/v1/parking/facilities/nearby` and `GET /api/v1/parking/facilities/{id}`) on the hosted-beta web `/map` experience as a **separate inventory** from community spots.

## Feature flag

| Name (product) | Build env | Config path | Default |
|----------------|-----------|-------------|---------|
| `WEB_MUNICIPAL_DISCOVERY_ENABLED` | `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED` | `frontendConfig.features.municipalDiscovery` | **false** |

- Local demo: set `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=true` in `apps/web/.env`
- Hosted-beta: enable **only** during WEB-MUNI-01A build/deploy
- Production: must remain **false**
- Detail route `/facilities/:facilityId` (WEB-MUNI-02) reuses this flag — when off, the route shows a disabled empty state and does not call facilities APIs
- Detail location map + open-in-maps (WEB-MUNI-04) also reuse this flag — no additional flag

## Behaviour when enabled

1. Same search center as community nearby also requests municipal facilities.
2. Map shows rounded-square **secondary/green** municipal markers (garage glyph) vs circular primary **P** community spots.
3. Discovery sidebar/sheet shows a **Municipal parking facilities** section above community results.
4. Selecting a municipal marker/list row opens a read-only detail panel (source, availability, freshness, provenance when present).
5. No claim / edit / report / linking / İZELMAN UI.
6. Clustering: **not implemented** — web map does not cluster community spots today; municipal markers follow the same per-pin pattern.
7. **WEB-MUNI-05:** Dual-inventory layer visibility controls (community / municipal) when discovery is enabled. See `web-muni-05-layer-visibility-controls.md`.

## Rollback

1. Rebuild/redeploy web with `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=false` (or unset).
2. No database rollback, no Flyway, no API revert required.
3. Optional: remove municipal UI in a follow-up if permanently abandoned (flag alone is sufficient kill-switch).

## Deployment notes (for WEB-MUNI-01A — do not run in WEB-MUNI-01)

```bash
# Hosted-beta web image build only (example)
VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=true \
VITE_APP_ENV=hosted-beta \
# ...existing hosted-beta Vite vars...
pnpm --filter @parkio/web build
```

Production builds must omit the flag or set it to `false`.

## Acceptance checklist (implementation)

- [x] Client flag defaults false
- [x] Types mirror backend DTO (no contract reshape)
- [x] API client methods for nearby + detail
- [x] Separate query keys from community spots
- [x] Municipal markers visually distinct
- [x] Detail panel: attribution, availability, freshness, provenance
- [x] Loading / empty / error states
- [x] Community inventory unchanged when flag off
- [x] No backend / migration / linking / İZELMAN changes
- [x] Unit + MapPage integration tests
- [x] Playwright smoke for flag-off default
- [x] WEB-MUNI-01A hosted-beta leave-on (`VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=true`)

## Remaining limitations

- No marker clustering on web (parity with community layer)
- Detail route `/facilities/:id` is WEB-MUNI-02 (complete)
- Client-side municipal filters are WEB-MUNI-03 (complete)
- Mobile not in scope
- Availability often `UNAVAILABLE` for OSM-published inventory (backend semantics)

## Confirmations

- WEB-MUNI-01A not started
- No deployment
- No production change
- No backend mutation
- No API contract change
- No migration
- No linking
- No İZELMAN
