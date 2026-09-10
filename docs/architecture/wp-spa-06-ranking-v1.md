# WP-SPA-06 — Deterministic Ranking v1

## Purpose

Given a destination and valid parking candidates from WP-SPA-05 dual inventory,
Ranking v1 answers: which options should be shown first, and why?

Ranking is deterministic, explainable, configuration-driven, feature-flagged,
reversible, non-AI, and safe under missing data.

## Ownership

| Concern | Owner |
|---------|-------|
| Inventory collection / merge | `RecommendationApplicationService` (WP-SPA-05) |
| Favourite facility ID batch lookup | `FavouriteFacilityLookupPort` / `UserFavouritesClient` |
| Scoring + ordering | `DeterministicParkingCandidateRanker` |
| Weight configuration | `RankingProperties` (`parkio.spa.ranking.*`) |

The ranker does **not** query databases, call controllers, mutate source DTOs,
persist scores, or fetch inventory.

## Ranking versions

| Version | When |
|---------|------|
| `DISTANCE_BASELINE_V1` | Flag off, or ranker fallback |
| `DETERMINISTIC_V1` | Flag on and ranking applied |

Response fields: `rankingVersion`, `rankingStatus` (`DISABLED` \| `APPLIED` \| `FALLBACK`).

## Feature flags

```yaml
parkio.spa.ranking.enabled: false          # default — exact SPA-05 order
parkio.spa.ranking.favourites-enabled: true
parkio.spa.ranking.distance-weight: 0.35
parkio.spa.ranking.freshness-weight: 0.25
parkio.spa.ranking.capacity-weight: 0.15
parkio.spa.ranking.confidence-weight: 0.15
parkio.spa.ranking.favourite-weight: 0.10
parkio.spa.ranking.distance-cap-meters: 1200
```

Weights must be finite, non-negative, and sum to `1.0 ± 0.01`. Invalid config
disables ranking at startup (fail-safe).

## Score formula

```
total =
  distanceWeight  × distanceScore +
  freshnessWeight × freshnessScore +
  capacityWeight  × capacityScore +
  confidenceWeight× confidenceScore +
  favouriteWeight × favouriteScore
```

Each factor ∈ [0,1]. Total clamped to [0,1]. No NaN / negative scores.

### Distance (walking-distance proxy)

`1 - min(distanceMeters / distanceCapMeters, 1)`

Cap default 1200 m. Not exact walking time; not route-provider ETA.

### Occupancy freshness

Municipal: `LIVE=1.0`, `AGING=0.7`, `STALE=0.2`, `UNAVAILABLE/INVALID=0.0`.

Community: expires-at based (`fresh≈0.65`, expired=`0.0`). Does not pretend
community reports are municipal occupancy.

### Capacity

Municipal LIVE/AGING only: `0.7 × freeRatio + 0.3 × min(available/50, 1)`.
Zero available is valid data (score 0). Unknown / OSM static / community → **0**
(do not invent capacity; unknown does not beat known poor capacity).

### Inventory confidence

Municipal LIVE=`1.0`, AGING=`0.75`, STALE=`0.3`, static UNAVAILABLE=`0.4`.
Community: conservative blend of freshness + verification status.
Not AI confidence.

### Favourite preference

Municipal facility `refId` in favourited set → `1.0`, else `0`.
Community candidates never receive favourite boost.
Destination favourites are not parking-candidate boosts.

## Favourite lookup

One bounded batch call:

`GET /api/v1/places/favourites/parking/status?targetIds=…`

Headers: `X-Gateway-Auth`, `X-User-Id`. Timeouts short; no retries.
**Fail-open**: empty set → favourite factor 0. Does **not** mark inventory
`DEGRADED`.

## Ordering / tie-breaks

Flag **ON**: score DESC → distance ASC → channel name → refId.

Flag **OFF**: exact SPA-05 distance ASC → channel → refId.

`baselineOrder` is the distance-only position before weighted reorder.
Global limit applied **after** ranking.

## Reasons (top 1–3)

`FAVOURITE`, `LIVE_AVAILABILITY`, `CLOSE_TO_DESTINATION`, `HIGH_CAPACITY`,
`HIGH_CONFIDENCE`, `COMMUNITY_FRESH`, `STATIC_INVENTORY`.

Deterministic selection; no free-form AI text; no “BEST” because rank #1.

## Fallback

Ranker exception → distance baseline order, `rankingStatus=FALLBACK`, response
still available. Favourite outage does not fail the request.

## Partial / degraded inventory

Unchanged from WP-SPA-05: surviving candidates are ranked; failed channel stays
`DEGRADED`; `partial=true`.

## Observability

Low-cardinality metrics only (`parkio.spa.ranking.*`): applied/fallback counts,
duration, favourite lookup outcome, top channel, score bucket, shadow top-1/top-3
overlap. Never log user IDs, candidate IDs, coordinates, or favourite IDs.

## Additive API / shared contracts

Candidate: optional `score`, `scoreBreakdown`, `rankingVersion`.
Response: `rankingVersion`, `rankingStatus`.
Reason codes: `FAVOURITE`, `HIGH_CONFIDENCE`.

Updated: `@parkio/types`, `@parkio/validation`, `@parkio/api-client`.
No web/mobile assistant UI.

## Non-goals

Recents · recently-used boost · AI/ML · price · traffic · walking-route API ·
UI · provider fusion · payments · experiment assignment (WP-SPA-14).

## Dependency

WP-SPA-07 (assistant / presentation UX) may consume reason codes and scores.
This package does not start WP-SPA-07.
