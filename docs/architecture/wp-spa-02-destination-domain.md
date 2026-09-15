# WP-SPA-02 — Destination Domain and Geocode Binding

Verification date: 2026-08-06

## Purpose

Introduce the smallest safe **Destination** foundation for Smart Parking
Experience. Destination is the trip-target noun; recommendation (later packages)
is the verb.

This package is **domain-first**. It does not persist SavedPlaces, favourites,
recents, or recommendations, and it does not change map search UX.

## Ownership

| Concern | Owner |
|---------|-------|
| Destination / PlaceIdentity / DestinationSource | `parking-service` domain package `com.parkio.parking.domain.place` |
| Geocode → Destination binding | `com.parkio.parking.application.geocoding.GeocodeDestinationBinder` |
| Geocoding provider (Nominatim) | unchanged infrastructure |
| Wire DTO (no public CRUD yet) | `DestinationResponse` |
| Shared TS contract | `@parkio/types` `destination.ts` |
| Zod schema | `@parkio/validation` `contracts/destination.ts` |

**Why parking-service:** geocoding and parking discovery already live here; a new
microservice would over-split a value object. Clients receive provider-neutral
contracts only.

## Destination

Required: `label`, `latitude`, `longitude`, `source`.  
Optional: `placeIdentity`, `subtitle`.

Not included: parking candidates, availability, score, reasons, favourites,
recents, raw provider payloads.

Sources in this package: `GEOCODING`, `MAP_PIN`, `SYSTEM`.  
(`SAVED_PLACE` / `FAVOURITE` / `RECENT` arrive with WP-SPA-03/04.)

## PlaceIdentity

- `provider` — lowercase kebab-case namespace (`osm-nominatim`)
- `providerPlaceId` — provider-local id
- `canonicalKey` — `provider:providerPlaceId`

Optional on Destination. When Nominatim falls back to `lat,lng` as `id`,
identity is **null** (coordinates remain the basis). No cross-provider fusion.

## Geocode binding

`GeocodeDestinationBinder`:

1. Label from `primary`, else `displayName`
2. Subtitle from non-blank `secondary`
3. Source `GEOCODING`
4. Identity from `id` when not a coordinate fallback
5. Invalid rows skipped (no fail-fast of the whole list)

Does not mutate `GeocodeResult`. Does not change `GET /api/v1/geocoding/search`.

## Compatibility

- Geocoding JSON contract unchanged
- Nearby spots / facilities unchanged
- ParkingSession / Smart Return unchanged
- Web / mobile-v2 map search unchanged
- No feature flag required (internal domain + additive shared types)

## Privacy / security

- No destination history persistence in this package
- No analytics events
- No raw Nominatim payload stored
- Invalid bind diagnostics use exception message only (no place labels at warn)

## Non-goals (explicit)

SavedPlace CRUD · Home/Work migration · favourites · recents · recommend API ·
ranking · client assistant UI · provider abstraction · payments

## Next package

**WP-SPA-03 — Saved Places** (depends on this Destination model).
