# WP-05.13 Adaptive Exposure Engine - Shadow Mode

## 1. Executive summary

WP-05.13 introduces a standalone, deterministic, explainable, replayable and shadow-only Adaptive Exposure Engine in `com.parkio.parking.exposure`. After legacy nearby search returns a bounded candidate set, the request-path orchestrator evaluates canonical `ExposureEvidence`, compares legacy PostGIS distance order with shadow exposure order, emits bounded metrics, and never mutates search output.

## 2. Scope and non-goals

In scope:
- pure exposure domain (`ExposureEngine`, `ExposurePolicyConfig`, `ExposureShadowOrdering`)
- canonical mapping from already-returned search candidates (`SearchExposureEvidenceFactory`)
- request-path shadow orchestration (`ExposureShadowOrchestrator`) with deterministic sampling and time budget
- snapshot/replay verification (`ExposureSnapshot`, `ExposureReplayer`)
- bounded Micrometer metrics, Prometheus recording rules, Grafana dashboard

Out of scope:
- real search reordering, filtering, suppression, or public exposure scores
- reward, gamification level, trust mutation, decision/availability/outcome mutation
- per-candidate repository reads, remote calls, durable audit persistence (v1 metrics-only)
- viewport/recommendation/admin search flows (unsupported in v1)

## 3. Repository-backed search audit

### Entry points
- **Nearby (only server discovery path):** `ParkingController.searchNearby` -> `ParkingApplicationService.searchNearby` -> `GET /api/v1/parking/spots/nearby`
- **Map viewport / list / recommendation / admin spot search:** not implemented server-side; clients commit a point + radius only.

### Filters (SQL + domain)
- SQL pre-filter in `ParkingSpotJpaRepository.findNearby`: `ACTIVE|VERIFIED`, `legal_status <> ILLEGAL_OR_RISKY`, `expires_at > now()`, `ST_DWithin`.
- Authoritative app filter: `ParkingSpot.isVisibleForSearch(now)` in `ParkingApplicationService.searchNearby`.
- No vehicle-type, availability-engine, trust, reward, or user-personalization filter on search.

### Ordering
- SQL: `ORDER BY location <-> ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography` (nearest first).
- Application preserves SQL order after visibility filter; optional client-side re-sort exists in frontend only.

### Pagination
- Limit-only (`default 10`, `max 50` via `ParkingSearchSettings` / `ParkingProperties.Search`).
- No offset/cursor for spot discovery.

### Geospatial
- WGS84 SRID 4326 geography, GIST index `idx_parking_spots_location`.
- Radius default 1000 m, max 50000 m.

### Caching
- No server-side nearby search cache.
- Client React Query caches only; exposure shadow is request-local.

### Observable candidate data in search response
- `PublicSpotResponse.from` exposes spot metadata but not distance, trust, availability engine state, or decision provenance.

### Shadow safety
- Shadow evaluates only the bounded list already loaded for the response.
- No N+1 repository reads and no per-candidate remote calls in v1.

## 4. Exposure ownership boundary

Exposure answers: "How prominently should an already published candidate be shown in a specific viewing context?" It does not answer publication authority (`Decision`), occupancy certainty (`AvailabilityEngine`), validated contribution compensation (`Reward`), or fraud verdicts (`Fraud`).

## 5. Domain model

Key symbols in `com.parkio.parking.exposure`:
- `ExposureEvidence`, `ExposureEvaluation`, `ExposureEngine`
- `ExposureEligibility`, `ExposureDisposition`, `ExposureScore`
- `ExposureComparison`, `ExposureSnapshot`, `ExposureReplayer`
- Policy: `ExposurePolicyConfig.POLICY_VERSION = exposure-policy-v1`

## 6. Eligibility

Eligibility is evaluated before scoring. Exposure cannot make an ineligible candidate eligible. Legacy search results are normally already published/visible; boundary cases are still modeled (`INELIGIBLE_NOT_PUBLISHED`, `INELIGIBLE_EXPIRED`, `INELIGIBLE_AVAILABILITY`, etc.).

## 7. Evidence mapping

`SearchExposureEvidenceFactory.fromSearchResults` maps each returned `ParkingSpot` plus query center into `ExposureEvidence` using:
- haversine distance meters (`GeographyDistanceMeters`) aligned with PostGIS geography semantics
- publication quality from spot status
- bounded availability adapter from visibility + TTL + fill signals (not a live `AvailabilityEngine` rerun)
- vehicle match `NOT_REQUESTED` (search does not accept vehicle filter today)
- trust `UNKNOWN` (v1 neutral / zero weight)

Deferred: outcome-history aggregates, live trust snapshots, decision audit joins (would require N+1).

## 8. Distance, freshness, availability

- Distance component max 5000 bp, monotonic decreasing with meters within request radius.
- Freshness bands from `publishedAt` / `expiresAt` vs injected evaluation time.
- Availability adapter maps visible spots to `AVAILABLE|LIKELY_AVAILABLE|UNKNOWN|LIKELY_OCCUPIED`; terminal states ineligible.

## 9. Trust input decision

V1 uses `ExposureTrustLevel.UNKNOWN` with `trustMaxContribution = 0`. No trust snapshot reads on the search path. High trust cannot override poor availability; low trust cannot hide eligible candidates.

## 10. Reward input exclusion

`PendingRewardIntent`, user points, levels, and gamification state are explicitly excluded from exposure scoring in v1.

## 11. Scoring policy and component caps

`ExposurePolicyConfig.referenceV1()` caps:
- distance 5000, freshness 2500, availability 1500, vehicle 500, publication 500, trust 0 (total bound 10000)
Disposition thresholds: PRIORITIZE >= 7500, STANDARD >= 4000, DEPRIORITIZE >= 2000, else HOLD.

## 12. Tie-breaking and shadow comparison

`ExposureShadowOrdering` orders eligible candidates by score desc, distance asc, publication quality, candidate id. `ExposureComparison` records sameTop1, sameTop3 set/order, movement bands, promoted/demoted counts.

## 13. Execution location and failure isolation

Strategy A (implemented): after `ParkingApplicationService.searchNearby` builds `visible`, `ExposureShadowOrchestrator.maybeEvaluateNearbySearch` runs best-effort. Failures are caught at orchestrator boundary; legacy response order/count/pagination unchanged.

Configuration (`parkio.parking.exposure-shadow`):
- `enabled` default false
- `sample-percent` default 10 (deterministic hash of bounded query context)
- `time-budget-millis` default 25

## 14. Persistence decision

V1 is metrics-only. `ExposureSnapshot` supports offline replay in tests/fixtures; no Flyway migration introduced.

## 15. Observability

Micrometer: `ExposureShadowMetrics`.
Prometheus rules: `docker/prometheus/exposure-shadow-recording-rules.yml`.
Grafana: `docker/grafana/provisioning/dashboards/parkio-exposure-shadow.json`.
CI: `.github/workflows/observability-validation.yml`.

Recording ratios:
- success rate denominator: sampled requests
- skipped rate denominator: received requests
- same-top1 rate denominator: successful evaluations

## 16. Security, privacy, fairness

No public exposure score, no user id / exact coordinates / candidate ids in metric tags, no reward- or level-based ranking, no permanent suppression. Documented bias risks: popularity, geographic density, trust amplification; mitigated in v1 by shadow-only operation, capped trust weight (zero), geospatial dominance, and calibration-before-authority requirement.

## 17. Backward compatibility

Search SQL, response order, pagination, cache keys, public APIs, Kafka contracts, Decision/Availability/Outcome/Trust/Reward behavior unchanged.

## 18. Exact files and symbols

| Area | Path | Symbol |
|------|------|--------|
| Search entry | `.../presentation/ParkingController.java` | `searchNearby` |
| Search app | `.../application/ParkingApplicationService.java` | `searchNearby` |
| PostGIS query | `.../persistence/jpa/ParkingSpotJpaRepository.java` | `findNearby` |
| Visibility | `.../domain/ParkingSpot.java` | `isVisibleForSearch` |
| Exposure engine | `.../exposure/ExposureEngine.java` | `evaluate` |
| Evidence mapping | `.../application/exposure/SearchExposureEvidenceFactory.java` | `fromSearchResults` |
| Orchestrator | `.../application/ExposureShadowOrchestrator.java` | `maybeEvaluateNearbySearch` |
| Metrics | `.../infrastructure/metrics/ExposureShadowMetrics.java` | implements `ExposureShadowObserverPort` |

## 19. WP-05.14 prerequisites

Before Fraud Engine authority: stable exposure shadow calibration samples, explicit documentation of search-only entry point, and agreed non-use of reward/trust as ranking proxies.

## 20. Deferred scope

- durable sampled exposure audit table
- vehicle-type-aware search filter integration
- bounded trust band adapter once search-safe read path exists
- outcome-derived reliability aggregates without N+1
- offline replay job over stored search snapshots
- LIMITED_PUBLISH / SHADOW / HOLD product semantics (future authority phase)