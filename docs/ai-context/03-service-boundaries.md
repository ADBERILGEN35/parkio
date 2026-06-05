# 03 — Service Boundaries

Each service owns a bounded context and its own data. Cross-service needs are met
via REST (sync) or Kafka events (async) — **never** shared tables or shared domain
modules. This file is the ownership map.

## Ownership

| Service                 | Owns (data / decisions)                                                   | Must NOT do                                            |
|-------------------------|---------------------------------------------------------------------------|--------------------------------------------------------|
| `gateway-service`       | Routing, edge concerns (rate limit, auth forwarding). No domain data.     | Hold business state.                                   |
| `auth-service`          | Credentials, tokens, sessions, roles.                                     | Store profile/business data.                           |
| `user-service`          | User profile, preferences, account status.                                | Compute points/scores; own auth credentials.          |
| `parking-service`       | Parking spots, status lifecycle, location, claims, validity.              | Compute scores; store media bytes; moderate.           |
| `media-service`         | Media objects in MinIO/S3, metadata, signed URLs.                         | Own spot domain; decide validity.                      |
| `gamification-service`  | Points, level, Trust Score, Contribution Score, ranking.                  | Own spot/user master data.                             |
| `notification-service`  | Delivery of push/email/in-app; user channel prefs for delivery.           | Decide domain outcomes.                                |
| `moderation-service`    | Reports, moderation queue, decisions, penalties.                          | Compute scores directly (emits events instead).        |
| `ai-validation-service` | Advisory analysis results for submissions.                                | Make final accept/reject/ban decisions.                |
| `analytics-service`     | Read-model aggregations, metrics, reporting.                              | Be a source of truth other services depend on at runtime. |

## Reference data between services

- Reference another service's entity by **ID only** (e.g. `userId`, `spotId`).
- If a service needs a snapshot of another's data, it keeps a **local copy**
  populated from events (its own table), not a live FK or shared model.
- Need fresh data on demand → call the owning service via Feign.

## Typical flows (illustrative)

**Spot upload:**
`parking-service` creates spot (`SUBMITTED`) → emits `SpotSubmitted` →
`ai-validation-service` analyzes, emits `SpotAiReviewed` (advisory) →
`moderation-service` may queue → on accept `parking-service` sets `VERIFIED`,
emits `SpotVerified` → `gamification-service` awards points →
`notification-service` notifies contributor.

**Claim:**
`parking-service` records claim → on confirmation emits `ClaimConfirmed` →
`gamification-service` awards the larger reward → `notification-service` notifies.

**Report/penalty:**
`moderation-service` resolves a report → emits `UserPenalized`/`SpotRejected` →
`gamification-service` (score), `user-service` (account status),
`notification-service` (notice).

> Media bytes always live in `media-service`; other services hold media IDs/URLs.
