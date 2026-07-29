# WP-05.9 Availability Engine

**Status:** Complete (WP-05.9)  
**Date:** 2026-07-28  
**Related:** [Decision domain model](./wp-05-decision-domain-model.md), [Implementation plan](./wp-05-implementation-plan.md), [Controlled authority migration](./wp-05-controlled-authority-migration.md)

## 1. Purpose

Introduce a **standalone Availability Engine** inside `parking-service` under
`com.parkio.parking.availability`.

| Question | Owner |
|----------|-------|
| Can this report exist? | Decision Engine (`PublicationDisposition`) |
| Is the parking place likely still empty? | Availability Engine (`AvailabilityState`) |

A spot may remain **published** while availability **decays** toward `EXPIRED` or
`LIKELY_OCCUPIED`. Publication and availability must never be merged.

WP-05.9 delivers the pure domain engine, replay boundary, metrics adapter, and
tests. Search ranking, hot-path orchestration, and persistence are deferred.

## 2. Domain separation

```text
ParkingSpot aggregate (lifecycle + TTL mutation)
        |
        v
AvailabilityEvidence (snapshot: status, TTL, verifications, filled reports, confidence)
        |
        v
AvailabilityEvaluationContext (injected Instant + policy version + advertised lifetime)
        |
        v
AvailabilityEngine -> AvailabilityEvaluation (state, score, freshness, reasons, expiration)
```

The Decision Engine pipeline is unchanged. `decision.score.AvailabilityScore` remains
a Decision-layer placeholder port; the occupancy engine uses
`availability.score.AvailabilityScore`.

## 3. Availability states

| State | Meaning |
|-------|---------|
| `AVAILABLE` | Fresh published report, high remaining TTL, no occupancy signals |
| `LIKELY_AVAILABLE` | Still plausible but aging |
| `UNKNOWN` | Unpublished, insufficient signals, or mid-decay ambiguity |
| `LIKELY_OCCUPIED` | Suspicious status, filled reports, or low remaining TTL |
| `UNAVAILABLE` | Terminal fill/reject — not discoverable as empty |
| `EXPIRED` | Advertised validity window elapsed |

## 4. Supported evidence (repository-backed)

Signals are taken only from existing `ParkingSpot` fields:

- lifecycle `ParkingSpotStatus`
- `activatedAt` / `expiresAt` (advertised lifetime)
- `createdAt` (report age)
- `verificationCount` (community verify)
- `filledReportCount` (pre-terminal fill reports)
- `confidenceScore` (negative-signal aggregate)

Not used in v1: moderator-only overrides as separate evidence, duplicate/conflicting
report graph, future occupancy feedback APIs.

## 5. Time model

Availability is **time-varying**; publication decisions are not.

| Concept | Type | Role |
|---------|------|------|
| `evaluatedAt` | `Instant` (injected) | Clock for replay; engine never calls `Instant.now()` |
| `advertisedLifetime` | `Duration` | Aligns with `ModerationPolicy.activeDuration` |
| `AvailabilityFreshness` | `FRESH / AGING / STALE / EXPIRED` | Elapsed-lifetime band |
| `AvailabilityExpiration` | value object | `expiresAt`, `expired`, `remaining` at evaluation instant |

## 6. Decay policy (`availability-v1`)

`AvailabilityPolicyConfig` holds basis-point thresholds (integer math, no floats in
classification):

- remaining TTL >= 7500 bps -> `AVAILABLE`
- remaining TTL >= 5000 bps -> `LIKELY_AVAILABLE`
- remaining TTL >= 2500 bps -> `UNKNOWN`
- below -> `LIKELY_OCCUPIED` until expiration

Occupancy signals (`SUSPICIOUS`, filled reports, low confidence) accelerate toward
`LIKELY_OCCUPIED`. Verifications apply a bounded score boost.

Policy constants are engineering baselines — not product-calibrated.

## 7. State machine (decay)

```text
[pending / not published] --> UNKNOWN

AVAILABLE --> LIKELY_AVAILABLE --> UNKNOWN --> LIKELY_OCCUPIED --> EXPIRED
                  |                              |
                  +-------- (filled / claim) ----+--> UNAVAILABLE
```

Documented transitions: `AvailabilityTransition` enum. Aggregate mutation remains in
`ParkingSpot`; the engine is read-only.

## 8. Engine architecture

- **`AvailabilityEngine`**: pure facade over `AvailabilityPolicy`
- **`DefaultAvailabilityPolicy`**: reference v1 implementation
- **`AvailabilityEvidenceFactory`**: maps `ParkingSpotAvailabilityContext` -> evidence
- **No Spring / JPA / Kafka** in the availability package (see
  `AvailabilityPackageIndependenceTest`)

## 9. Replay

- **`AvailabilitySnapshot`**: evidence + context + evaluation (offline boundary)
- **`AvailabilityReplayer`**: resolves policy version, re-evaluates deterministically
- Unknown policy versions -> `UnsupportedAvailabilityPolicyVersionException`

Availability history is **not** stored in `decision_audit`. Future persistence uses
`AvailabilityHistoryPort` (noop in WP-05.9).

## 10. Persistence decision

**No database migration in WP-05.9.** The engine is pure; snapshots are for offline
replay and future history tables only.

## 11. Observability

`AvailabilityMetrics` (`AvailabilityObserverPort`) records bounded tags:

- `parkio.parking.availability.evaluation`
- `parkio.parking.availability.state` (enum tag)
- `parkio.parking.availability.freshness`
- `parkio.parking.availability.expiration`
- `parkio.parking.availability.aging`
- `parkio.parking.availability.evaluation.duration`

Never spot IDs or exact scores.

## 12. Search integration (future)

Current search still filters via `ParkingSpot.isVisibleForSearch` (publication +
legal status + TTL). WP-05.9 does **not** change search behavior.

Future integration pattern:

```text
visible = publicationAllowsSearch(spot, now) AND availabilityAllowsDiscovery(evaluation)
```

Ranking may combine publication disposition, availability score, and distance in a
later work package.

## 13. Trust integration (future)

Trust (`decision.score.TrustScore`) remains actor-level. Availability remains
opportunity-level. WP-05.11 may combine trust into **publication** decisions; availability
may consume outcome validation from WP-05.10 but must not conflate actor trust with
occupancy freshness.

## 14. Backward compatibility

Unchanged in WP-05.9:

- Decision Engine, authority path, shadow/calibration/audit
- `ParkingSpot` lifecycle and TTL mutation
- Search, API, Kafka contracts
- Rewards, fraud, trust stubs

## 15. Tests

Package tests cover aging, clock injection, expiration boundaries, determinism, policy
math, replay, evidence factory, package independence, and metrics tagging. Full
`:services:parking-service:test` suite must remain green.