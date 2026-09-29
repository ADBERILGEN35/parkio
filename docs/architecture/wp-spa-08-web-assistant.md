# WP-SPA-08 — Web Smart Parking Assistant Shell

## Purpose

Destination-first Smart Parking Assistant on Parkio **web** `/map`, layered on the
existing map discovery experience. Mobile-v2 belongs to WP-SPA-09.

## Ownership

| Concern | Owner |
|---------|-------|
| Web assistant UI | `frontend/apps/web` (`MapPage` + `features/smart-parking-assistant`) |
| Search composition | Client `composeDestinationSearch` (WP-SPA-07) |
| Recents confirmation | `placesApi.confirmRecentDestination` |
| Recommendations / ranking | Existing `parkingApi.recommendParking` (SPA-05/06) |
| Map markers / previews | Existing community + municipal pipelines |

## Feature flag

`VITE_SMART_PARKING_ASSISTANT_ENABLED` → `frontendConfig.features.smartParkingAssistant`.

- Explicit `'true'` only; missing/malformed → `false`.
- Independent of `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED`.
- When false: no entry control, no assistant queries, no confirm writes, discovery unchanged.

## Assistant state

Logical phases: IDLE → SEARCHING → DESTINATION_CONFIRMED → LOADING_RECOMMENDATIONS →
RESULTS | DEGRADED_RESULTS | ERROR | CANDIDATE_SELECTED.

Persisted (URL + React state): confirmed Destination fields + optional candidate id.
Transient loading owned by React Query / mutation status — not Zustand.

## URL contract

Assistant params coexist with map discovery params:

| Param | Meaning |
|-------|---------|
| `destLat` / `destLng` | Destination coordinates |
| `destLabel` | Display label (URI-encoded) |
| `destSource` | Optional `GEOCODING` \| `MAP_PIN` \| `SYSTEM` |
| `destProvider` / `destPlaceId` | Optional PlaceIdentity |
| `destSubtitle` | Optional subtitle |
| `candidate` | Optional selected ParkingCandidate.id |

Rules:

- No full Destination JSON, no recommendation payloads, no private recent row ids.
- Malformed coords → ignore assistant restore.
- Clear destination removes all `dest*` + `candidate`.
- URL rehydrate restores destination for recommendations but does **not** re-call
  `confirmRecentDestination` (confirm only on explicit suggestion selection).

## Confirmation / recents

Selecting a suggestion:

1. Canonical Destination → URL + assistant state.
2. `confirmRecentDestination` once per selection (non-blocking).
3. Recommendations query enabled separately.
4. Map nearby search recentered on destination (radius matches recommend default).

Navigation / Open in Maps does **not** call `recordRecentParking` (SPA-07 deferred
policy; parked-car wiring in WP-SPA-11).

## Recommendations

Request defaults (SPA-05): radius **1500** m, limit **10**, community + municipal on
(municipal channel still respects municipal discovery flag for includeMunicipal).

Server order authoritative. Score / breakdown / rankingVersion not shown to users.
Reason codes mapped to short i18n strings.

## Map integration

- Distinct destination marker (not a parking pin).
- Candidate `refId` highlights existing community/municipal markers when present.
- Card ↔ marker selection sync; municipal → existing preview; community → SpotPreview.
- Clearing destination clears assistant highlights and returns to discovery mode.

## Non-goals

Mobile assistant · ranking changes · AI · price/traffic · analytics funnel ·
quick-action chrome · parked-car unification · WP-SPA-09.
