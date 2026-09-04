# WEB-MUNI-07 — URL state persistence for municipal filters and layer visibility

**Program:** WEB-MUNI  
**Package:** WEB-MUNI-07  
**Status:** Implementation complete — **WEB-MUNI-07A not started**  
**Scope:** Frontend/web only. No backend mutation. No deploy in this package.

## Goal

Make the stable discovery presentation state on `/map` shareable and restorable via
the URL without changing nearby-query behavior:

- community layer visibility
- municipal layer visibility
- municipal availability filter
- municipal source filter
- municipal facility-type filter
- municipal provenance filter

Transient selection and sheet state remain local.

## Feature flag

Reuses **only** `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED` /
`frontendConfig.features.municipalDiscovery`.

| Flag | Behaviour |
|------|-----------|
| `false` (production default) | municipal URL state is ignored and removed during canonicalization; community-only map contract remains unchanged |
| `true` | municipal URL state is parsed, restored, canonicalized, and written back for share/refresh/history |

No new feature flag.

## Canonical query-parameter contract

Managed keys, in deterministic serialization order:

1. `communityLayer`
2. `municipalLayer`
3. `municipalAvailability`
4. `municipalSources`
5. `municipalTypes`
6. `municipalProvenance`

Existing unrelated params, including `smartReturn=1`, are preserved in their original
relative order ahead of the managed keys.

### `communityLayer`

- Meaning: persisted community-layer visibility
- Allowed values: `0`, `1`
- Default: `1`
- Omitted when default: yes
- Repeated values: last non-blank value wins
- Blank value: ignored, falls back to default
- Unknown value: ignored, falls back to default
- Case sensitivity: exact numeric value only

### `municipalLayer`

- Meaning: persisted municipal-layer visibility
- Allowed values: `0`, `1`
- Default: `1`
- Omitted when default: yes
- Repeated values: last non-blank value wins
- Blank value: ignored, falls back to default
- Unknown value: ignored, falls back to default
- Case sensitivity: exact numeric value only

### `municipalAvailability`

- Meaning: municipal availability chip selection
- Allowed values: `available`, `unavailable`, `unknown`
- Default: `all`
- Omitted when default: yes
- Repeated values: last non-blank value wins
- Blank value: ignored, falls back to default
- Unknown value: ignored, falls back to default
- Case sensitivity: exact lowercase enum values only

### `municipalSources`

- Meaning: selected municipal `sourceLabel` values
- Allowed values: exact payload `sourceLabel` strings
- Default: empty set
- Omitted when default: yes
- Repeated values: all values are merged, split on commas, trimmed, deduped, then sorted
- Blank value: ignored
- Unknown value: preserved until the payload is settled; removed as stale during canonicalization once the available source set is known
- Case sensitivity: exact string match

### `municipalTypes`

- Meaning: selected municipal `facilityType` values
- Allowed values: `ON_STREET`, `OFF_STREET`, `UNKNOWN`
- Default: empty set
- Omitted when default: yes
- Repeated values: all values are merged, split on commas, trimmed, deduped, then sorted
- Blank value: ignored
- Unknown value: removed during canonicalization
- Stale payload value: removed during canonicalization once the available type set is known
- Case sensitivity: exact enum match

### `municipalProvenance`

- Meaning: provenance-only municipal filter
- Allowed values: `1`
- Default: `0` / disabled
- Omitted when default: yes
- Repeated values: last non-blank value wins
- Blank value: ignored, falls back to default
- Unknown value: ignored, falls back to default
- Case sensitivity: exact numeric value only

### `smartReturn`

- Owned by existing Smart Return routing/search-param behavior
- WEB-MUNI-07 never changes its semantics
- `smartReturn=1` is preserved through canonicalization, filter/layer writes, and browser history navigation
- Protected-route login redirects must preserve `smartReturn=1` and safe municipal query
  state on the same internal route; URL fragments remain stripped

## State ownership

`/map` treats the URL as the canonical source for persisted discovery state:

1. Parse stable layer/filter state from `location.search`
2. Initialize `MapPage` layer/filter UI from that parsed state
3. Canonicalize invalid/default/stale state back into the URL
4. Reflect browser Back/Forward URL changes into UI state
5. Reflect user filter/layer changes back into the URL

Not persisted:

- selected spot / selected facility
- preview card visibility
- bottom-sheet position
- loading / error state
- query cache state
- map animation / zoom transitions
- transient text input

No `localStorage` / `sessionStorage` persistence is added.

## Canonicalization rules

Implemented centrally in `apps/web/src/lib/mapDiscoveryUrlState.ts`.

- Defaults are omitted from the URL
- Invalid values are ignored safely
- Stale source/type filters are removed only after the municipal payload settles
- Unknown/unrelated query params are preserved
- `smartReturn=1` is preserved
- Managed params serialize in deterministic order
- CSV filters are deduped and sorted
- Parse/serialize is idempotent:

```ts
serialize(parse(serialize(state))) === serialize(state)
```

When municipal discovery is flag-off, all WEB-MUNI-07 managed params are removed from the
canonical URL.

## History semantics

- Initial cleanup / canonicalization: `replaceState`
- Invalid or stale param repair: `replaceState`
- URL hydration from browser navigation: no history write
- User-visible filter/layer changes: `pushState`

Invariants:

- no render-driven history loop
- no duplicate identical entries
- one user gesture creates at most one intended history transition
- Back restores the older filter/layer state
- Forward restores the newer filter/layer state
- hydration does not immediately overwrite restored history entries

## Refresh, share, and deep links

- Copying the URL preserves the stable layer/filter state
- Refresh restores the same stable layer/filter state
- Opening the copied URL in a new tab restores the same stable layer/filter state
- Protected-route auth redirects preserve safe query state on recognized internal routes
  so a login bounce does not discard municipal layer/filter URL state
- Clearing municipal filters removes the relevant params
- Re-enabling both layers removes default visibility params
- Detail routes stay independent; route-local selection is still transient

## Network safety

WEB-MUNI-07 is presentation-only:

- no new endpoint
- no query-key changes
- no mutation requests
- no polling
- no duplicate nearby fetch caused by URL synchronization alone

Layer/filter/history/refresh URL state changes reuse the existing client-side discovery
data path.

## Accessibility

- URL restoration updates pressed/selected UI state without resetting focus
- invalid-param cleanup is silent
- existing bounded `aria-live="polite"` result-summary behavior remains stable while
  WEB-MUNI-09 adds dedicated map-region selection announcements for marker-driven focus changes
- browser history restoration updates `aria-pressed` states for layer controls

## Locale independence

- Canonical parameter names are locale-independent
- `municipalAvailability` and `municipalTypes` use stable internal enum values
- `municipalSources` uses exact backend `sourceLabel` strings already present in the payload
- Turkish and English UI restore the same URL contract without translating query values

## Rollback

1. Rebuild/redeploy web with `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=false`, or
2. Revert the WEB-MUNI-07 commit

No backend, API, DTO, database, or migration rollback is required.

## WEB-MUNI-07A

Out of scope here. Any hosted-beta leave-on validation should be handled as a separate
gate after this implementation package.

## Confirmations

- WEB-MUNI-07A not started
- No deployment
- Production default unchanged
- No backend / API / DTO / migration change
- No clustering / search / sorting / localStorage persistence
- Linking and İZELMAN remain disabled
