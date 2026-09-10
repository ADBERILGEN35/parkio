# WP-SPA-05 — Recommendations API v1

Verification date: 2026-08-06

## Purpose

Destination-scoped parking recommendations that compose existing dual inventory:

1. municipal facilities
2. community parking spots

into one provider-neutral `ParkingCandidate` list with deterministic **baseline**
ordering (distance ascending). Weighted ranking belongs to WP-SPA-06.

## Ownership

| Concern | Owner |
|---------|-------|
| Recommendation orchestration + API | `parking-service` |
| Community nearby truth | `ParkingApplicationService.searchNearby` |
| Municipal nearby truth | `MunicipalFacilityQueryService.nearby` |
| Destination semantics | WP-SPA-02 `Destination` |
| Gateway | Existing `Path=/api/v1/parking/**` |
| Shared TS / Zod / api-client | `@parkio/types` `recommendation.ts`, `@parkio/validation`, `parkingApi.recommendParking` |

No new microservice. `ParkingCandidate` is a computed view — not persisted.

## Endpoint

`POST /api/v1/parking/recommendations`

Gated by `parkio.spa.recommendations.enabled` (default `false`).

### Auth (Option A)

Authenticated via gateway JWT + `X-User-Id`. Required because community nearby
already needs a searcher user id and future personalization will use user
context. This package does **not** call user-service or apply favourite boosts.

### Request defaults

| Field | Default | Max |
|-------|---------|-----|
| `radiusMeters` | 1500 | 5000 |
| `limit` | 10 | 50 |
| `includeCommunity` | true | — |
| `includeMunicipal` | true | — |

## Dual-inventory orchestration

- Parallel fetch via virtual-thread executor when both channels requested
- Destination coordinates (not map center)
- Per-channel fetch limit = `min(limit × 2, channel max)` (community 50 / municipal 100)
- Map independently → merge → sort → apply global limit
- No loopback HTTP

## Baseline ordering

1. `distanceMeters` ascending
2. `channel.name` ascending
3. `refId` ascending

No weighted score, favourites, capacity, or freshness reordering.

## Reason codes

`CLOSE_TO_DESTINATION`, `LIVE_AVAILABILITY`, `HIGH_CAPACITY`, `STATIC_INVENTORY`,
`COMMUNITY_FRESH`, `INVENTORY_DEGRADED` (response warning when partial).

Reasons describe; they do not rank.

## Partial / degraded

| Outcome | Behavior |
|---------|----------|
| Both succeed | `partial=false` |
| One fails | return survivors; `partial=true`; failed channel `DEGRADED` |
| Both fail | `503 RECOMMENDATION_INVENTORIES_UNAVAILABLE` |
| Intentionally excluded | `DISABLED` (not degraded) |
| Success empty | `EMPTY` |

## Cache

**None** in WP-SPA-05. Nearby inventories are occupancy-sensitive and currently uncached.

## Privacy

Logs/metrics: counts, status enums, radius/limit buckets, duration.
Never: destination label, precise coordinates, spot/facility IDs, titles, user IDs.

## Boundary with WP-SPA-06

Same response contract may later replace baseline order with weighted ranking.
WP-SPA-05 must not claim “best parking”.

## Non-goals

Weighted ranking · favourite/recent boosts · recents persistence · price · traffic ·
walking ETA · AI ranking · client UI · provider facilities · spot/facility fusion ·
nearby contract changes · municipal ingestion changes
