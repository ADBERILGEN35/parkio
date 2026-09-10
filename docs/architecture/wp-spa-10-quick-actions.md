# WP-SPA-10 — Quick Actions and Daily Shortcuts

## Purpose

Compact shortcuts into existing Smart Parking Assistant / ParkingSession flows
on **web** and **mobile-v2**. No new persistence; no parked-car architecture.

## Feature flag

Same as SPA-08/09:

- Web: `VITE_SMART_PARKING_ASSISTANT_ENABLED`
- Mobile: `EXPO_PUBLIC_SMART_PARKING_ASSISTANT_ENABLED`

When OFF: no Quick Actions UI and no Quick Action–only fetches.

## Shared model

`@parkio/types` `QuickActionKind` / availability + `@parkio/validation`
`buildQuickActionDescriptors`, destination converters, `selectAssistantDestination`
boundary on both assistant hooks.

## Parity matrix

| Action | Web | Mobile-v2 | Source of truth | Empty / unconfigured |
|--------|-----|-----------|-----------------|----------------------|
| HOME | AVAILABLE → assistant Destination | same | SavedPlace HOME | “Ev ekle” → open search |
| WORK | AVAILABLE → assistant Destination | same | SavedPlace WORK | “İş ekle” → open search |
| Favourite destinations | picker / single → assistant | same | FavouriteDestination | EMPTY chip disabled |
| Favourite parking | picker → municipal facility flow | same | FavouriteParking + facility hydrate | EMPTY disabled; not a Destination |
| Recent destinations | picker / single → assistant | same | RecentDestination | EMPTY disabled |
| Parked car | focus / sheet (existing session) | OS navigate (existing actions) | ACTIVE ParkingSession | hidden when absent |

## Recent-write policy

Destination-producing shortcuts call `selectAssistantDestination` once per
identity (same as search). Opening a picker does not write. Favourite parking
and Parked car do **not** write RecentDestination / RecentParking.

## Future analytics (WP-SPA-12)

`quick_action_selected(kind)` · `quick_action_unavailable(kind)` — no
labels/coords/IDs.
