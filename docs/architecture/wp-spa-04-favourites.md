# WP-SPA-04 — Favourite Parking and Favourite Destinations

Verification date: 2026-08-06

## Purpose

User-scoped favourites for:

1. **Municipal parking facilities** (reference-only bookmarks)
2. **Destinations** (canonical Destination snapshots)

No recents, ranking, recommendation API, community-spot favourites, or client UI.

## Ownership

| Concern | Owner |
|---------|-------|
| Favourite persistence + CRUD API | `user-service` |
| Municipal facility truth | `parking-service` (unchanged) |
| Destination value semantics | Mirrored in user-service (`PlaceDestinationSource`, `PlaceIdentity`) |
| Gateway | Existing `Path=/api/v1/places/**` → user-service |
| Shared TS / Zod | `@parkio/types` `favourite.ts`, `@parkio/validation` `contracts/favourite.ts` |

**Why user-service:** favourites are user preference data. No user→parking HTTP client exists; introducing one solely for create-time verification would add coupling. SavedPlace remains a separate concept.

## Favourite types

### FavouriteParking

- `targetKind` v1: `MUNICIPAL_FACILITY` only
- Stores `targetId` (facility UUID) — **no** coordinates, occupancy, capacity, or source metadata
- Unique: `(user_profile_id, target_kind, target_id)`
- Ordering: `created_at DESC`
- Limit: 100 per user

### FavouriteDestination

- Destination snapshot: label, lat/lng, source, optional PlaceIdentity/subtitle
- Separate from SavedPlace (HOME/WORK/CUSTOM); same coords may exist in both without merge
- Ordering: `updated_at DESC`
- Limit: 50 per user

## Duplicate policy (destinations)

1. PlaceIdentity when present → `identity:{provider}:{providerPlaceId}`
2. Else coordinates rounded to **5 decimal places** (~1.1 m) → `coord:{lat}:{lng}`
3. Label never defines identity

Five-decimal precision absorbs GPS jitter for the same pin without collapsing destinations ~100 m apart.

## Target validation (Strategy C)

Municipal create validates UUID format only. No synchronous parking-service call (no existing client). Stale/inactive facility IDs may be newly favourited; favourite records are not auto-deleted when facilities disappear.

## Target lifecycle

- Favourite rows remain if a facility becomes inactive/withdrawn
- Delete always works
- List is reference-only (no hydration / unavailable badge in this package)
- Ranking (later packages) decides how to treat inactive favourites

## API

Gated by `parkio.spa.favourites.enabled` (default `false`).

| Method | Path |
|--------|------|
| GET | `/api/v1/places/favourites/parking` |
| POST | `/api/v1/places/favourites/parking` |
| DELETE | `/api/v1/places/favourites/parking/{targetId}` |
| GET | `/api/v1/places/favourites/parking/status?targetIds=` |
| GET | `/api/v1/places/favourites/destinations` |
| POST | `/api/v1/places/favourites/destinations` |
| PUT | `/api/v1/places/favourites/destinations/{id}` |
| DELETE | `/api/v1/places/favourites/destinations/{id}` |

Repeated POST is idempotent (returns existing favourite). Auth via `X-User-Id`. Cross-user → 404.

## Parking response strategy

**Reference-only** (Option A): favourite id, target kind/id, createdAt. Clients hydrate facility details from parking APIs. Occupancy never persisted in favourites.

## Schema

Additive Flyway `V17__create_favourites.sql` — `favourite_parking`, `favourite_destinations`. SavedPlace / Smart Return tables untouched.

## Feature flag

`parkio.spa.favourites.enabled` — one flag for both favourite types. Schema may deploy dark. Rollback = flag off; data retained.

## Non-goals

Recents · recommendation API · ranking · assistant UI · community-spot favourites · private providers · municipal ingestion changes · SavedPlace merge · WP-SPA-05

## Dependency for WP-SPA-05

Recents / recently-used parking may build on these contracts but are **not** started here.
