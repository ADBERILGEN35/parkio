# WP-SPA-14 — AI Ranking Shadow FRAMEWORK

## Purpose

Observe a **local feature-based challenger** reorder against the authoritative
deterministic ranking (WP-SPA-06), without changing the public recommendation
response.

This work package delivers a **SHADOW FRAMEWORK**, not “real AI ranking” and not
an LLM-backed product claim. `ai-validation-service` remains vision-only and is
not used for ranking.

## Architecture decision (locked)

| Decision | Choice |
|----------|--------|
| Strategy | **C** — async fire-and-forget after deterministic ranking |
| Challenger | `LocalChallengerShadowParkingRanker` (local buckets only) |
| Public path | **Never waits** on shadow evaluation |
| Authoritative ranker | `DeterministicParkingCandidateRanker` remains the only `ParkingCandidateRanker` `@Component` |
| Shadow interface | Separate `ShadowParkingRanker` — never `@Primary` over the public ranker |
| External LLM / Gemini | **Forbidden** for ranking |

```
RecommendationApplicationService
  → DeterministicParkingCandidateRanker (authoritative)
  → build RecommendationResult (public fields/order unchanged)
  → ShadowRankingOrchestrator.maybeEvaluateAsync(...)  // non-blocking
       → extract privacy-minimized features
       → ShadowParkingRanker.rank (timeout + semaphore + circuit breaker)
       → validate → compare → metrics → BoundedShadowEvaluationStore
```

## Invariants

1. **Public order / scores / reasons / rankingVersion are never mutated by shadow.**
2. Deterministic weights, formula, and tie-breaks are unchanged.
3. `RecommendationResponse` public fields and order are unchanged.
4. Shadow failures (timeout, throw, invalid, circuit open, budget) never affect HTTP success.
5. No userId, destination text, lat/lng, titles, refIds, facility IDs, session IDs, or email in shadow features / store / metrics labels.

## Privacy

`ShadowFeatureExtractor` emits only:

- ephemeral aliases `c0..cN-1`
- channel name
- distance / capacity / availability / freshness / confidence **buckets**
- reason code **names** (`RecommendationReasonCode`)
- deterministic score bucket + position
- inventory composition + radius bucket + partial flag

## Feature flags

```yaml
parkio.spa.ranking.shadow:
  enabled: false          # PARKIO_SPA_RANKING_SHADOW_ENABLED
  sample-rate: 0.0        # PARKIO_SPA_RANKING_SHADOW_SAMPLE_RATE (clamped 0..1)
  timeout-ms: 500
  max-candidates: 10
  max-concurrent: 4
```

Hosted-beta Compose passthrough (defaults false / 0.0):

- `PARKIO_SPA_RANKING_SHADOW_ENABLED`
- `PARKIO_SPA_RANKING_SHADOW_SAMPLE_RATE`

## Metrics (low cardinality)

Under `parkio.spa.ranking.shadow.*`:

- `requests`, `sampled`, `success`
- `skipped{reason=...}`
- `timeout`, `provider_error`, `invalid_output`, `circuit_open`
- `duration`
- `top1_agreement{agreed=...}`, `top3_overlap{overlap=0..3}`
- `rank_correlation_bucket`, `mean_rank_delta_bucket`

## Rollback

1. Set `PARKIO_SPA_RANKING_SHADOW_ENABLED=false` (or sample-rate `0.0`).
2. Recreate parking-service. No web/mobile rebuild.
3. Authoritative ranking and recommendation API behavior are unaffected.

## Promotion prohibition

Shadow challenger output **must not** be promoted to the public path without a
separate, explicitly approved work package. This package forbids:

- swapping `ParkingCandidateRanker` to a shadow implementation
- `@Primary` on any `ShadowParkingRanker`
- feeding LLM / Gemini into ranking
- changing deterministic formula to match the challenger

## Versions recorded

| Constant | Value |
|----------|-------|
| Feature schema | `PARKING_SHADOW_FEATURES_V1` |
| Shadow ranker | `LOCAL_CHALLENGER_V1` |
| Prompt version | `AI_SHADOW_PROMPT_V1` (schema stability; no LLM call) |
