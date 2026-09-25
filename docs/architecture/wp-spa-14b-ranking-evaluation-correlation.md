# WP-SPA-14B — Ranking Evaluation Correlation

## Purpose

Privacy-safe correlation between **deterministic recommendation exposure** and
explicit client outcomes (selection, navigation, parking session), plus offline
**observational / counterfactual** comparison against SPA-14 shadow order.

This package does **not** promote shadow ranking to the public path and does
**not** claim causal lift from shadow position.

## Metrics rename (SPA-06 baseline)

SPA-06 distance-baseline vs deterministic comparison previously wrote
`parkio.spa.ranking.shadow.top1_changed` / `…top3_overlap`, which collided with
SPA-14 challenger metrics.

| Source | Metric namespace |
|--------|-----------------|
| SPA-06 baseline comparison (`RankingMetrics.recordShadow`) | `parkio.spa.ranking.baseline.top1_changed`, `parkio.spa.ranking.baseline.top3_overlap` |
| SPA-14 challenger shadow | `parkio.spa.ranking.shadow.*` (unchanged) |
| SPA-14B evaluation correlation | `parkio.spa.ranking.evaluation.*` |

## evaluationId

When `parkio.spa.ranking.evaluation.enabled=true`, recommendation responses may
include an opaque `evaluationId` (UUID). With Jackson `NON_NULL`, the field is
omitted when null.

- Minted after authoritative ranking; fail-open (persistence errors → no id).
- Never stores userId, coordinates, facility/spot/session IDs, titles, or labels.
- Snapshots keep ordinal lists + allowlisted feature buckets only.

## Outcomes

Authenticated `POST /api/v1/parking/recommendations/evaluation-outcomes` records
`(evaluationId, candidateOrdinal, outcomeType)` with optional platform /
latency bucket. Auth protects the endpoint; identity is not persisted.

Dedupe: unique `(evaluationId, candidateOrdinal, outcomeType)`. Expired tokens
return gone; invalid ordinals reject.

## Counterfactual labeling

Offline reports from `RankingEvaluationOfflineEvaluator` must state:

- **OBSERVATIONAL / COUNTERFACTUAL — NOT CAUSAL**
- Users were exposed only to deterministic order
- Shadow ranks are **COUNTERFACTUAL_POSITIONAL**

Data sufficiency gates: selections ≥ 500, navigations ≥ 100, parking sessions ≥ 50;
otherwise status is `INSUFFICIENT DATA`.

## Feature flags

```yaml
parkio.spa.ranking.evaluation:
  enabled: false
  retention-hours: 24          # clamped 1..168
  cleanup-enabled: true
  cleanup-fixed-delay-ms: 900000
  cleanup-batch-size: 500
```

Env passthrough (hosted beta): `PARKIO_SPA_RANKING_EVALUATION_ENABLED` (and related
retention/cleanup keys if wired in Compose).

## Rollback

1. Set evaluation enabled to `false`.
2. Public recommendations continue without `evaluationId`.
3. Outcomes endpoint returns disabled write result; no durable writes.
