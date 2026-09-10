# WP-SPA-12 — Telemetry, Funnel and Rollout Controls

Observability and analytics only. Does **not** change recommendation ordering,
ranking weights, persistence, or user-facing product semantics.

## Ownership

| Layer | Role |
|-------|------|
| Client `trackProductEvent` / `spaTelemetry` | Privacy-safe product funnel events |
| parking-service Micrometer | Operational recommendation/ranking health (`parkio.spa.*`) |
| analytics-service Kafka | Domain ParkingSession KPI snapshots — **not** SPA UI funnel ingest |

No third-party SDK is wired in this package. Release builds queue events until a
vendor transport is set via `setProductAnalyticsTransport`.

## Event taxonomy

Primary funnel:

`assistant_opened` → `destination_confirmed` → `recommendations_shown` →
`recommendation_selected` → `navigation_started` → `parking_session_started` →
`return_to_car_started` → `parking_session_ended`

Supporting: search started/selected, recommendations partial/empty/failed,
`ranking_fallback`, quick action selected/unavailable, `park_here_failed`,
`recent_parking_record_failed`, `time_to_confident_choice`.

No render/impression/map-pan events.

## Payload policy

Allowed: platform, coarse buckets, enums (origin, channel, ranking status/version,
quick-action kind/availability, targetKind, failureReason, sessionOutcome),
ephemeral `journeyId`.

Forbidden: userId, email, phone, labels, addresses, lat/lng, facility/spot/
session/favourite/saved/recent IDs, providerPlaceId, raw search query.

Enforced by `@parkio/validation` `assertSpaTelemetryParams` /
`sanitizeSpaTelemetryParams`. Tracking is fail-open (drops bad events; never
blocks UX).

## Journey correlation

Short-lived random `journeyId` created on `assistant_opened` (in-memory only).
Not derived from user/device/destination. Dropped on restart. Safe to lose.

## Time-to-confident-choice

From `destination_confirmed` → first `recommendation_selected` or
`navigation_started`. Emits `time_to_confident_choice` with buckets:
`lt_5s`, `5_15s`, `15_30s`, `30_60s`, `gt_60s`.

## Backend metrics (existing SPA-05/06)

Reuse without rename:

- `parkio.spa.recommendations.requests|candidates|result_count|duration|…`
- `parkio.spa.ranking.applied|fallback|top_channel|favourite_lookup|shadow.*`

No high-cardinality ID tags.

## Parked-car operational signals

Client funnel: `parking_session_started|ended`, `park_here_failed`,
`return_to_car_started`, `recent_parking_record_failed`.

Backend Kafka session events remain authoritative for domain lifecycle.

## Feature flags

| Flag | Default | Effect when OFF |
|------|---------|-----------------|
| `VITE_SMART_PARKING_ASSISTANT_ENABLED` | false | Web SPA chrome hidden |
| `EXPO_PUBLIC_SMART_PARKING_ASSISTANT_ENABLED` | false | Mobile SPA chrome hidden |
| `parkio.spa.recommendations.enabled` | false | Recommend API fail-closed |
| `parkio.spa.ranking.enabled` | false | Distance baseline |
| `parkio.spa.ranking.favourites-enabled` | true | Ranking without favourites boost |
| `parkio.spa.saved-places.enabled` | false | No HOME/WORK saved places |
| `parkio.spa.favourites.enabled` | false | Favourite sections absent |
| `parkio.spa.recents.enabled` | false | No recent history writes/reads |
| Municipal discovery client flags | product-specific | Community-only discovery |

No new `spa.analytics.enabled` flag. Product events follow the same assistant
surfaces; when SPA is OFF, funnel events are not emitted from those surfaces.

## Rollout matrix

| Combination | Expected | Degraded? | Safe rollback |
|-------------|----------|-----------|---------------|
| Client SPA OFF | Normal map discovery | No | Preferred first kill |
| Recommendations OFF | No recommend requests | Yes | Backend flag |
| Ranking OFF | Distance baseline list | Mild | Backend flag |
| Saved Places OFF | Geocoding search still works | Mild | user-service flag |
| Favourites OFF | Favourite QA/sections gone | Mild | user-service flag |
| Recents OFF | Search works; no history | Mild | user-service flag |
| Municipal OFF | Community candidates only | Mild | client municipal flag |

## Kill-switch order

1. Disable client assistant flag(s)
2. Disable `parkio.spa.recommendations.enabled`
3. Disable `parkio.spa.ranking.enabled`
4. Disable saved-places / favourites / recents only if those APIs are unhealthy

No auto-kill in this package.

## Funnel definitions (behavioral proxies)

- Destination confirmation rate = `destination_confirmed` / `assistant_opened`
- Recommendation availability = `recommendations_shown` / `destination_confirmed`
- Selection rate = `recommendation_selected` / `recommendations_shown`
- Navigation intent = `navigation_started` / `recommendations_shown`
- Park conversion proxy = `parking_session_started` / `recommendations_shown`
- Return usage = `return_to_car_started` / `parking_session_started`
- Session completion = `parking_session_ended` / `parking_session_started`

These are not guaranteed physical outcomes.

## Dashboard specification (PromQL / event counts)

1. Assistant opens (client event count)
2. Destination confirms
3. Recommendations shown
4. Recommendation selected
5. Navigation started
6. Parking sessions started
7. Sessions ended
8. Funnel conversion % (from formulas above)
9. Partial rate — `parkio.spa.recommendations.requests{partial="true"}`
10. Ranking fallback — `parkio.spa.ranking.fallback`
11. Recommendation duration p50/p95 — `parkio.spa.recommendations.duration`
12. Top channel mix — `parkio.spa.ranking.top_channel`
13. Quick-action distribution (client)

No destination-level map analytics panels.

## Retention

Client queue: max 100 in-memory events; not durable across restart.

analytics-service inbox retention remains as configured for domain events
(separate from SPA product funnel). Product events must not substitute for
user Saved/Recent history.

## Failure semantics

Analytics fail-open: no toast, no retry storm, no UX block, no payload logging.

## Consent

No dedicated SPA analytics consent UI in this package. Respect future global
privacy/opt-out if introduced. Current policy: coarse product events only when
SPA surfaces are used.

## Non-goals

AI, ranking changes, new persistence, UI redesign, third-party SDK adoption,
WP-SPA-13.
