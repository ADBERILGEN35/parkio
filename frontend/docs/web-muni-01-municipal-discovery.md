# WEB-MUNI-01 — Surface municipal facilities on hosted-beta web map

**Program:** WEB-MUNI (productization)  
**Package:** WEB-MUNI-01  
**Status:** Implementation complete — **WEB-MUNI-01A not started**  
**Scope:** Web discovery only. No backend mutation. No mobile. No deploy in this package.

## Goal

Expose existing municipal parking facilities (`GET /api/v1/parking/facilities/nearby` and `GET /api/v1/parking/facilities/{id}`) on the hosted-beta web `/map` experience as a **separate inventory** from community spots.

## Feature flag

| Name (product) | Build env | Config path | Default |
|----------------|-----------|-------------|---------|
| `WEB_MUNICIPAL_DISCOVERY_ENABLED` | `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED` | `frontendConfig.features.municipalDiscovery` | **false** |

- Local demo: set `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=true` in `apps/web/.env`
- Hosted-beta: enable **only** during WEB-MUNI-01A build/deploy
- Production: must remain **false**

## Behaviour when enabled

1. Same search center as community nearby also requests municipal facilities.
2. Map shows rounded-square **secondary/green** municipal markers (garage glyph) vs circular primary **P** community spots.
3. Discovery sidebar/sheet shows a **Municipal parking facilities** section above community results.
4. Selecting a municipal marker/list row opens a read-only detail panel (source, availability, freshness, provenance when present).
5. No claim / edit / report / linking / İZELMAN UI.
6. Clustering: **not implemented** — web map does not cluster community spots today; municipal markers follow the same per-pin pattern.

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
- [ ] WEB-MUNI-01A hosted-beta gate (not started)

## Remaining limitations

- No marker clustering on web (parity with community layer)
- No dedicated `/facilities/:id` route page (in-map detail panel only)
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
