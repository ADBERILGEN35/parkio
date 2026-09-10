# WEB-MUNI-09 — Municipal discovery accessibility hardening

**Program:** WEB-MUNI  
**Package:** WEB-MUNI-09  
**Status:** Implementation complete — **WEB-MUNI-09A not started**  
**Scope:** Frontend/web only. No backend, API, or deployment work in this package.

## Goal

Harden the municipal discovery map and results experience for keyboard and assistive-technology
users without replacing MapLibre or changing the existing nearby-query contract.

## What changed

- `NearbySpotsMap` now exposes the discovery surface as an explicit accessible region.
- The map region includes non-visual keyboard instructions for markers and floating controls.
- Marker-driven selection changes announce the currently selected community spot, municipal
  facility, or parked-car location through a bounded polite live region.
- Community and municipal list scrolling now happens only for map-origin selection, not for
  list-origin selection.
- Municipal result cards now expose more descriptive accessible names and keep the selected
  facility card in view when selection is driven from the map.
- The mobile bottom sheet keeps discovery content mounted but removes collapsed content from the
  accessibility tree and returns focus to the resize handle if collapse would otherwise strand
  focus inside hidden content.
- Layer visibility controls now have focused component-level accessibility coverage in addition
  to the existing page-level keyboard regression coverage.

## Accessibility contract

### Interactive discovery map

- The map is exposed as a `role="region"` landmark with a stable accessible name.
- Screen-reader help explains the supported keyboard path:
  - Tab to reach controls and markers
  - Enter/Space to activate a marker
  - clicking/tapping the background updates the search center
- Decorative center markers stay `aria-hidden`.
- Both-hidden state does not remove the map region itself; it adds separate guidance through the
  existing localized empty-state messaging.

### Selection feedback

- Selection announcements are bounded to the currently active target only.
- Community spot, municipal facility, and parked-car selections each expose distinct copy.
- Only map-origin selection triggers these announcements.
- The package does not add noisy repeated announcements for loading, filtering, URL cleanup, or
  browser history hydration.

### Municipal results

- Municipal result buttons now expose a descriptive accessible name with:
  - inventory type
  - facility name
  - distance
- Result labels intentionally exclude raw ids, coordinates, and provenance implementation detail.
- When a facility is selected from the map, the corresponding list item scrolls into view so
  keyboard and screen-reader users do not lose context between the map and the results list.

### Focus model

- Map-origin selection may scroll the related result into view, but does not forcibly move focus
  away from the current control.
- List-origin selection keeps focus on the activated list control and only updates marker
  highlight/preview state.
- Hiding a selected layer clears that selection without moving focus off the activated toggle.
- Filtering a selected municipal facility out of the visible list clears the preview/selection so
  focus is never left on detached municipal content.
- On mobile, collapsed sheet content is inert and `aria-hidden`; collapse returns focus to the
  sheet handle when necessary.

### Live-region rules

- Discovery summary remains the existing bounded `aria-live="polite"` path for result-count and
  dual-inventory chrome updates.
- Map selection announcements remain separate and bounded to marker-driven selection only.
- No technical state changes, cache activity, or URL canonicalization work is announced.

## Known limitations

- MapLibre canvas interaction remains limited compared with a fully semantic SVG/DOM map.
- This package hardens the surrounding discovery contract rather than replacing the renderer.
- Pointer-only map background interactions remain supplemental; the primary keyboard path is
  through the existing controls and focusable markers.
- Full spatial exploration, geospatial narration, and universal screen-reader support for every
  MapLibre interaction are not claimed by this package.

## Feature flag

WEB-MUNI-09 reuses only `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED`.

- `false`: community-only accessibility contract remains unchanged; no municipal labels, results,
  or live announcements appear
- `true`: municipal discovery accessibility semantics are enabled as part of the existing map
  experience

## Validation

Focused component and page coverage now verifies:

- map-region semantics and live-selection announcements
- municipal result accessible naming and selected-item visibility
- community list scroll behavior remains bounded to map-origin selection
- layer visibility control semantics
- collapsed mobile sheet content is not keyboard reachable
- existing `MapPage` keyboard and municipal regression behavior

Browser-level WEB-MUNI accessibility smoke additionally verifies:

- dedicated flag-on Vite (`VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=true` on port 5193)
- map region label/instructions and absence of decorative-center naming
- marker activation, one bounded selection announcement, and map-to-list surfacing
- keyboard layer toggles, both-hidden messaging, and filter/filtered-empty flow
- mobile collapsed sheet inert/focus restoration
- keyboard detail navigation and return to map
- separate flag-off safety spec proves no municipal accessibility leak

Pixel-level scroll offsets are not a cross-device acceptance criterion; Playwright
proves the selected municipal result is visible/`toBeInViewport` after the sheet
is open, while Vitest covers map-origin-only `scrollIntoView` invocation.

## Rollback

1. Revert the WEB-MUNI-09 commit, or
2. Restore the prior frontend build artifact

No backend, API, DTO, database, or migration rollback is required.

## WEB-MUNI-09A

Hosted-beta validation remains a separate gate. It should prove:

- keyboard traversal reaches discovery controls, markers, filters, and results at hosted-beta
  widths
- municipal-only, dual-result, filtered-empty, and both-hidden states remain understandable to
  screen readers
- selection announcements stay bounded and do not loop
- the current MapLibre limitation remains non-blocking because surrounding controls stay usable
- flag-off builds remain community-only with no municipal accessibility leakage
