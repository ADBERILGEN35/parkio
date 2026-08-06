# WP-SPA-07 — Destination Search and Recents

## Purpose

Provide the full-stack foundation for authenticated destination search composition,
destination confirmation, recent destinations, and recently used parking — without
building the final web/mobile assistant UI (WP-SPA-08 / WP-SPA-09).

## Ownership

| Concern | Owner |
|---------|-------|
| Recent destinations persistence | `user-service` |
| Recently used parking persistence | `user-service` |
| Geocoding | `parking-service` (unchanged) |
| Recommendations / ranking | `parking-service` (unchanged) |
| Search composition | **Client-side** pure utility (`composeDestinationSearch`) |

### Strategy

**Strategy A** — user-service owns recents; clients compose Saved Places + favourite
destinations + recents + geocoding via shared pure logic. No new cross-service
aggregation endpoint (avoids cyclic user↔parking coupling).

## Recent destination semantics

- Written **only** after explicit confirmation (`POST /api/v1/places/recents/destinations`).
- Not raw search history, keystrokes, geocoder impressions, SavedPlace, or favourites.
- Repeat confirmation updates `lastUsedAt` / `useCount` and refreshes **label/subtitle**
  only (coordinates, source, PlaceIdentity, duplicateKey stay identity-stable).

## Recently-used parking trigger policy

| Trigger | v1 status |
|---------|-----------|
| Explicit `POST /api/v1/places/recents/parking` | Supported contract |
| Successful `ParkingSession` create / Park here | **Deferred** — wire in WP-SPA-08/09/11 |
| Open in Maps / Navigate deep-link | Not a history write |
| Candidate impression / detail open | Never |

Clients must call `recordRecentParking` only from an explicit user action.
No speculative auto-wiring in this package.

## Target-kind policy

v1 supports `MUNICIPAL_FACILITY` only. Community spots deferred until spot
lifecycle IDs are durable enough for history references.

Inactive/deleted facilities: history rows remain until user delete or retention
prune; clients hydrate live facility state separately.

## Schema

Flyway `V18__create_recents.sql`:

- `recent_destinations` — Destination snapshot + `duplicate_key` + use counters
- `recent_parking` — reference-only (`target_kind`, `target_id`) + use counters

Unique: `(user_profile_id, duplicate_key)` / `(user_profile_id, target_kind, target_id)`.

## Retention

Configurable count-based limits (default **20** each):

```yaml
parkio.spa.recents.enabled: false
parkio.spa.recents.destination-limit: 20
parkio.spa.recents.parking-limit: 20
```

After successful upsert, oldest-by-`lastUsedAt` rows beyond the limit are pruned.
Clear-all endpoints delete real rows. Saved Places / favourites are never pruned.

## APIs

### Recent destinations

- `GET    /api/v1/places/recents/destinations`
- `POST   /api/v1/places/recents/destinations` — confirm/upsert
- `DELETE /api/v1/places/recents/destinations/{id}`
- `DELETE /api/v1/places/recents/destinations` — clear all (idempotent)

### Recently used parking

- `GET    /api/v1/places/recents/parking`
- `POST   /api/v1/places/recents/parking` — record use
- `DELETE /api/v1/places/recents/parking/{id}`
- `DELETE /api/v1/places/recents/parking` — clear all

Authenticated via gateway `X-User-Id`. Flag off → `RECENTS_DISABLED` (404).
Cross-user id access → `*_NOT_FOUND` (404).

## Confirmation boundary

API-client: `placesApi.confirmRecentDestination(destination)`.

- Sends canonical Destination fields only (no raw query).
- Does **not** call Recommendations inside the client.
- Clients call `parkingApi.recommendParking` separately after confirmation.

## Search composition

Sources / groups: `SAVED_PLACE` | `FAVOURITE_DESTINATION` | `RECENT_DESTINATION` | `GEOCODING`.

Blank / short query (&lt; geocode min, default 3): HOME → WORK → CUSTOM → favourites →
recents (no geocoding section).

Active query: matching saved → favourites → recents → geocoding.

Cross-source dedupe: PlaceIdentity, else 5-decimal coordinate key.
Priority: SavedPlace &gt; favourite &gt; recent &gt; geocoding.
Label-only matches do **not** merge. Turkish matching via `tr-TR` case fold.

## Geocoding behavior

Unchanged server contract (`GET /api/v1/geocoding/search`, min 3, limit ≤10).
Client foundations reuse existing debounce patterns (web ~350ms, mobile-v2 ~300ms)
and RQ cancellation/`signal` / stale guards. No geocode call for blank query.

## Privacy / security

- Private user history; no public recents APIs.
- No raw query persistence by default.
- No labels/coordinates in routine application logs for confirm/record.
- Individual delete + clear-all.
- User-session query roots include `placesKeys.all` (cleared on logout/switch).

## Feature flag

`parkio.spa.recents.enabled=false` (dark deploy). Data retained when disabled.
Does not affect Saved Places, favourites, geocoding, recommendations, or ranking.

## Client foundation

- Shared types/Zod/api-client (`createPlacesApi`) including saved/favourites/recents.
- `composeDestinationSearch` pure utility in `@parkio/validation`.
- Thin query options + `placesKeys` on web and mobile-v2.
- **No** full assistant UI, search overlay chrome, or quick-action row.

## Boundaries

| Package | Owns |
|---------|------|
| WP-SPA-08 | Web assistant shell / visual search |
| WP-SPA-09 | Mobile-v2 assistant shell |
| WP-SPA-10 | Quick actions |
| WP-SPA-11 | Parked-car unification / session→recent parking wiring |
| WP-SPA-12 | Analytics funnel |

## Explicit non-goals

- Full assistant UI
- Ranking boost from recents
- AI / learned suggestions
- Recommendation redesign
- Community-spot favourites / recent parking
- Navigation-provider integration
- Raw search-query history
