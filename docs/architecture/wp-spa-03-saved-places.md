# WP-SPA-03 — Saved Places and Smart Return HOME Migration

Verification date: 2026-08-06

## Purpose

Introduce user-scoped **Saved Places** (HOME / WORK / CUSTOM) with secure CRUD,
idempotent Smart Return HOME backfill, and temporary dual-read/dual-write so
existing Smart Return alerts and clients do not regress.

Backend-first. No favourites, recents, recommendations, ranking, or assistant UI.

## Ownership

| Concern | Owner |
|---------|-------|
| SavedPlace persistence + API | `user-service` |
| Legacy Smart Return home columns | `user_preferences` (unchanged; dual-write retained) |
| Destination value semantics (WP-SPA-02) | `parking-service` domain; **mirrored** enums/validation in user-service (`PlaceDestinationSource`, `PlaceIdentity`) to avoid cross-service cycles |
| Shared TS / Zod | `@parkio/types` `saved-place.ts`, `@parkio/validation` `contracts/saved-place.ts` |
| Gateway route | `Path=/api/v1/places/**` → user-service (`places-service` route id) |

**Why user-service:** Smart Return home already lives on `user_preferences`. Keeping
SavedPlace in the same service preserves scheduler reliability (no sync call to
parking-service for morning alerts).

## Domain

- Kinds: `HOME`, `WORK`, `CUSTOM`
- At most one HOME and one WORK per `user_profile_id` (DB partial unique indexes + service upsert)
- CUSTOM requires non-blank label; max 20 CUSTOM per user
- HOME/WORK label optional; semantic kind is authoritative; `displayLabel()` defaults to `Home`/`Work`
- Coordinates reuse WP-SPA-02 bounds; optional namespaced `PlaceIdentity`; no raw geocoder payload

## Schema

Additive Flyway `V16__create_saved_places.sql` — table `saved_places`.
Legacy Smart Return columns are **not** dropped or altered destructively.

## API

Gated by `parkio.spa.saved-places.enabled` (default `false`).

| Method | Path |
|--------|------|
| GET | `/api/v1/places/saved` |
| PUT | `/api/v1/places/saved/home` |
| PUT | `/api/v1/places/saved/work` |
| POST | `/api/v1/places/saved` |
| PUT | `/api/v1/places/saved/{id}` |
| DELETE | `/api/v1/places/saved/{id}` |
| DELETE | `/api/v1/places/saved/home` |

Auth via gateway `X-User-Id`. Ownership always from auth — never from client body.

## Smart Return migration

### Backfill

`SavedPlacesHomeMigrationRunner` (ApplicationRunner) when
`parkio.spa.saved-places.migration-enabled=true`.

- Batched, idempotent: only users with valid legacy home **and** no SavedPlace(HOME)
- Source `SYSTEM`; does not overwrite an existing HOME
- Logs aggregate `migrated` / `skipped` only (no user ids, labels, or coordinates)

### Dual-read

Priority for Smart Return home consumers:

1. SavedPlace(HOME) when present
2. Legacy `user_preferences.home_*` fallback

### Dual-write

Orchestration boundary: `SavedPlaceApplicationService` + Smart Return update path.

- Legacy Smart Return home write → upsert SavedPlace(HOME) (`mirrorLegacyHomeToSavedPlace`)
- SavedPlace HOME upsert/clear → mirror/clear legacy columns
- No recursive loops (each call site writes one direction)

### Clear / delete HOME

Deletes SavedPlace(HOME) and clears legacy lat/lng/label. Unrelated Smart Return
fields (lead minutes, default return time, today status) are preserved.
If Smart Return was enabled, it is disabled so alerts cannot use stale coordinates.

## Feature flags

| Flag | Default | Role |
|------|---------|------|
| `parkio.spa.saved-places.enabled` | `false` | Public Saved Places API |
| `parkio.spa.saved-places.migration-enabled` | `false` | Startup backfill runner |
| `parkio.spa.saved-places.migration-batch-size` | `200` | Batch size |

Rollback = flags off; data retained. No destructive rollback.

## Eventual legacy removal criteria (future package)

- Dual-read traffic shows SavedPlace HOME coverage for all active Smart Return users
- Dual-write stable for one release window
- Clients no longer depend solely on legacy preference home fields
- Then a dedicated package may stop writing legacy columns (not this package)

## Non-goals (explicit)

Favourites · recents · recommendation API · ranking · assistant UI · WP-SPA-04 ·
destructive migration · removing legacy Smart Return columns · new microservice
