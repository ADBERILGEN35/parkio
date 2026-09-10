# WEB-MUNI-06 — Dual-inventory empty states and mobile sheet chrome

## Status

Implementation package. Hosted-beta leave-on gate: **WEB-MUNI-06A** (not started here).

## Problem

With municipal discovery enabled, map peek summary and the mobile advanced-sheet CTA
were still community-centric. Live hosted-beta often has **0 community spots** and
**many municipal facilities**, yet chrome could still say “No spots nearby” and treat
the map as empty for CTA purposes.

## Scope

Frontend presentation only:

- Canonical dual-inventory discovery chrome resolver
- Peek / summary copy
- Advanced-sheet CTA / hints
- Community empty hierarchy when municipal results exist
- i18n (EN/TR)
- Tests + docs

Out of scope: URL/localStorage persistence, search, sorting, clustering, backend,
inventory fusion, mobile app, production rollout.

## Canonical model

`frontend/apps/web/src/lib/mapDiscoveryChrome.ts`

`resolveMapDiscoveryChrome` considers:

- municipal feature flag
- layer visibility
- pending / error / total / visible counts per inventory

Kinds include: `idle`, `both_hidden`, `loading`, `error_no_results`, `has_results`,
`no_visible_results`.

Formatting helpers:

- `formatDiscoveryChromeSummary`
- `formatDiscoveryChromeCtaLabel`

## Count semantics

Inventories stay separate in copy:

- community spots
- municipal facilities
- dual: both counts named (no fused “21 parking places”)

Hidden layers contribute **0** to chrome counts.

Municipal chrome counts use **filtered** visible facilities; filtered-empty is distinct
from fetched-empty.

## Precedence

1. Both layers hidden (WEB-MUNI-05)
2. Idle (no nearby search yet)
3. Visible inventory has settled results (including when sibling still loading/error)
4. Loading with no settled visible results
5. Full error with no usable results
6. Municipal filtered-empty (unfiltered > 0)
7. True no-visible-results

## Mobile peek / CTA

- Peek uses the resolver summary (not community-only `resolveSummary`)
- CTA opens results when **either** visible inventory has results (or municipal
  filtered-empty so filters remain reachable)
- Both-hidden hint guides re-enable, not a fruitless search
- Opening the sheet causes **zero** nearby requests
- WEB-MUNI-07 later makes the stable layer/filter inputs URL-restorable without
  persisting transient sheet state; see `web-muni-07-url-state-persistence.md`

## Feature flag

Reuses `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED` only.

Flag off: legacy community-only empty / summary / CTA contract unchanged.

## Accessibility

- Peek summary: `aria-live="polite"` + `aria-atomic` (bounded announcements)
- CTA labels name the inventory explicitly
- Filtered-empty / true-empty / both-hidden remain distinguishable
- WEB-MUNI-09 keeps the collapsed mobile sheet content out of the accessibility tree until the
  sheet is opened, so the peek summary and CTA remain the only reachable controls in the collapsed
  state

## Network

Presentation-only. No new endpoints. Layer/filter/summary recomputation must not
refetch nearby.

## Rollback

1. Revert this commit, or
2. Keep `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=false`

## WEB-MUNI-06A

Hosted-beta leave-on gate for dual-inventory empty chrome. **Not started** here.

## Confirmations

- No backend / API / DTO / migration changes
- No clustering / search / sorting / persistence
- No inventory fusion
- Linking and İZELMAN publication unchanged (out of scope)
