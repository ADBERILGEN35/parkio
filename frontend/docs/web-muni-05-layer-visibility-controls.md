# WEB-MUNI-05 — Dual-Inventory Map Layer Visibility Controls

**Program:** WEB-MUNI  
**Package:** WEB-MUNI-05  
**Status:** Implementation complete — **WEB-MUNI-05A not started**  
**Scope:** Frontend presentation only. No backend mutation. No deploy in this package.

## Goal

Let users independently show or hide:

1. **Community spots**
2. **Municipal facilities**

on `/map`, keeping markers and result sections synchronized.

## Feature flag

Reuses **only** `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED`.

| Flag | Behaviour |
|------|-----------|
| `false` (production default) | No layer controls; community-only map (no pointless single-layer toggle) |
| `true` | Layer controls appear; both layers default **on** |

No new flag. Production remains false by default.

## State model

In-memory `MapPage` state:

- `communityLayerVisible` (default `true`)
- `municipalLayerVisible` (default `true`)

Independent of municipal attribute filters and community spot filters.  
Hiding a layer does **not** clear React Query cache or reset filters.

## Control UI

`MapLayerVisibilityControls` — compact `aria-pressed` chips in the discovery panel (desktop sidebar + mobile sheet).

- Group label: Map layers / Harita katmanları
- Community spots / Topluluk park yerleri
- Municipal facilities / Belediye tesisleri

## Behaviour

| Action | Markers | Results | Selection |
|--------|---------|---------|-----------|
| Community OFF | Community markers hidden | `DiscoveryResults` unmounted | Community selection cleared |
| Municipal OFF | Municipal markers hidden | `MunicipalFacilityResults` (+ filters) unmounted | Municipal selection cleared |
| Both OFF | No inventory markers | Dedicated both-hidden EmptyState | Both selections cleared |
| Re-enable | Prior filtered set restored from cache | Section remounts | Selection **not** restored |

Base map always remains. Parked-car marker is unrelated to inventory layers.

### Municipal filters while hidden

Filter controls are **hidden** with the municipal section. Filter state stays in memory and reapplies on re-enable. No API request.

### Marker / list sync

- Community markers use the same filtered/sorted `visibleSpots` as the list.
- Municipal markers use `visibleMunicipalFacilities` (existing WEB-MUNI-03 filter path).

### Detail routes

`/facilities/:id` and `/spots/:id` remain independent of map layer visibility (no route guards).

## Network

Layer toggles must cause **zero** additional `spots/nearby` or `facilities/nearby` requests. Re-enable uses cached query data.

## Both-hidden semantics

Localized title/description distinguish **user-hidden layers** from empty/loading/error result states. Mobile sheet peek summary uses the both-hidden title when applicable.

## Accessibility

- `role="group"` + `aria-label` on the control group
- Each toggle: `aria-pressed`, keyboard operable, visible focus
- State communicated via label text + pressed state (not color alone)
- Both-hidden region: `role="status"`

## Rollback

1. Rebuild without this commit, **or**
2. Keep `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=false`

## WEB-MUNI-05A

Hosted-beta leave-on gate for layer toggles. **Not started** here.

## Confirmations

- WEB-MUNI-05A not started
- No deployment
- Production municipal discovery default unchanged
- No backend / API / DTO / migration change
- No clustering / search / sorting / fusion
- Linking and İZELMAN remain disabled
