# WP-SPA-11 — Parked Car Unification

## Ownership

**ParkingSession** (parking-service) remains the single source of truth for the
active parked state. No `parked_car` table or parallel store.

Smart Return HOME (user-service SavedPlace) stays separate from Return to Car.

## Lifecycle

| Client lifecycle | Meaning |
|------------------|---------|
| NONE | No ACTIVE ParkingSession |
| ACTIVE | One ACTIVE session with usable return coordinates |
| ENDING | Transient complete/cancel mutation only |

“Returning” is a client action over ACTIVE, not a stored status.

## ParkedCarView

Client presentation model (`@parkio/types` / `@parkio/validation`):
`toParkedCarView(session)` — no persistence.

## Explicit Park Here

Allowed:

- current location map chrome (existing)
- municipal facility preview/detail
- municipal recommendation candidate

Not allowed:

- impression / Open in Maps / destination select alone

## RecentParking

After **successful** session start with a municipal target:

`recordRecentParking({ targetKind: MUNICIPAL_FACILITY, targetId })`

Fail-open: RecentParking failure does not roll back ParkingSession.
Location-only park does **not** invent a RecentParking id.
Community RecentParking remains deferred (SPA-07 contract).

## Target association on session

ParkingSession schema stores coordinates + `parkingSource` only (no facility FK).
Municipal association for recents is carried at the Park Here call site.

## Single ACTIVE

Existing DB unique index + 409 conflict preserved. No silent replace.

## Clients

| Surface | Web | Mobile-v2 |
|---------|-----|-----------|
| Park Here (location) | existing | existing |
| Park Here (municipal) | preview + detail + recommendation | sheet + recommendation |
| Marker | existing ParkedCarMarker | new mapHtml parked pin |
| Quick Action | focus map | flyTo parked coords |
| Return / end | ActiveParkingSessionCard | ActiveParkingSessionBanner |
| RecentParking | after municipal park | after municipal park |

## Feature flags

No separate parked-car flag. Core ParkingSession UI remains available when
authenticated. SPA Quick Action still requires the assistant flag.

## Future analytics (WP-SPA-12)

Do not emit PII (coords, session ids, facility ids) in event payloads.
