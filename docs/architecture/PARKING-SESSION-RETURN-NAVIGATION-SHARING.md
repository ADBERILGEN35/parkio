# ParkingSession return navigation and location sharing

**Decision / task:** S1-P0-10  
**Status:** Implemented in canonical mobile-v2  
**Date:** 2026-07-24  

## Placement

Extends `ActiveParkingSessionBanner` on the Map (no new screen/route/tab).

## Coordinate source

Backend ACTIVE `ParkingSessionResponse.latitude` / `longitude` only.
Validated before URL/share construction (`-90..90` / `-180..180`, finite).
Invalid destination 뿯↽ fail closed (controls disabled + toast on press attempts).

## Non-persistence

Coordinates, maps URLs, and share messages stay in memory for the action only.
No AsyncStorage / SecureStore / SQLite / share-draft store / React Query write of coords.

## Navigation strategy

| Platform | Primary URL | Fallback |
|---|---|---|
| iOS | `maps://?daddr={lat},{lng}&q={label}` (Apple Maps) | HTTPS OpenStreetMap |
| Android | `geo:{lat},{lng}?q={lat},{lng}({label})` | HTTPS OpenStreetMap |
| default | HTTPS OpenStreetMap | — |

Opened via `expo-linking` `openURL`. Hand-off success ≠ route completed.
No location permission request. No auto-retry. Duplicate presses ignored while busy.

## Share strategy

`Share.share` with localized lead + HTTPS OSM maps link.
iOS may also pass `url`. Dismiss (`Share.dismissedAction`) is not failure and not success.
No share drafts. No screenshots/files.

## Client interaction events

Seam: `frontend/apps/mobile-v2/src/services/productAnalytics.ts`

| Event | When |
|---|---|
| `return_to_car_clicked` | After Linking hand-off succeeds |
| `parking_location_shared` | After `Share.sharedAction` |
| `parking_action_failed` | Bounded failure (`action` + `reason`) |

Allowed params: `platform`, `action` (`navigation`\|`share`), `reason` (allowlist).

**Never:** latitude/longitude, maps URL, share message, sessionId, userId, tokens,
idempotency keys, raw errors, lifecycle names (`parking_session_*`).

Transport: `__DEV__` 뿯↽ console; release 뿯↽ in-memory queue drained by
`setProductAnalyticsTransport` (vendor SDK). Authoritative lifecycle Kafka events remain
backend-only (S1-P0-08/09).

## Accessibility / i18n

IconButtons with localized `accessibilityLabel`s (`parkingSession.navigate.a11y`,
`parkingSession.share.a11y`). TR + EN keys added.

## Verification

```bash
cd frontend/apps/mobile-v2
npm test -- --testPathPattern="parkingLocationLinks|productAnalytics|useParkingLocationActions.s1p010|ActiveParkingSessionBanner.s1p010|parking"
npm run typecheck
```
