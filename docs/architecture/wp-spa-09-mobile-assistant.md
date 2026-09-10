# WP-SPA-09 — Mobile-v2 Smart Parking Assistant UX

## Purpose

Destination-first Smart Parking Assistant on Parkio **mobile-v2** MapScreen,
layered on the existing MapSurface / WebView map. Web assistant remains WP-SPA-08.

## Feature flag

`EXPO_PUBLIC_SMART_PARKING_ASSISTANT_ENABLED` → `appConfig.features.smartParkingAssistant`.

- Explicit `'true'` only; missing/malformed → `false`.
- Independent of `EXPO_PUBLIC_MUNICIPAL_DISCOVERY_ENABLED`.
- EAS profiles default `false`.

## Persistence

Confirmed Destination is persisted via jsonStore key `spa-assistant-destination`
(schema version 1). Candidate selection and search query are session-only.
Hydration does **not** re-call `confirmRecentDestination`.

## Recent / navigation policy

- Explicit destination selection → `confirmRecentDestination` once per identity.
- Candidate view / detail / Open in Maps → **no** `recordRecentParking`
  (deferred to WP-SPA-11 parked-car flow).

## Map bridge

Additive ops (do not overload community/municipal inventories):

- `setDestinationMarker`
- `setRecommendedHighlights` (highlight existing marker ids)

## Future analytics (WP-SPA-12)

assistant_opened · destination_confirmed · recommendations_shown ·
recommendation_selected · navigation_started — no coordinates/labels.
