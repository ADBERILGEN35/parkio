# WP-SPA-14D — Privacy-Safe Long-Horizon Ranking Evaluation

## Purpose

Extend ranking evaluation evidence beyond the 24h raw correlation window
**without** retaining journey-linkable raw rows.

```
Evaluation / Outcome (raw, ~24h)
        |
        | closed UTC hour + grace
        v
Privacy-safe Rollup (idempotent replace)
        |
        v
Long-horizon Aggregate (bounded, e.g. 90d)
        |
        v
Evidence Gate (organic preferred)
```

Public ranking remains **DETERMINISTIC_V1**.
All reports: **OBSERVATIONAL / COUNTERFACTUAL — NOT CAUSAL**.

## Why aggregates instead of longer raw retention

Raw rows contain ephemeral `evaluationId` and per-journey ordinal/feature
vectors that could, in combination, reconstruct a single assistant journey.
Daily/hourly aggregates keep only **counts and sums** over coarse dimensions
(platform, inventory composition, outcome type, rank buckets). After rollup,
individual journeys cannot be reconstructed.

## Ownership

**parking-service** — owns raw evaluation tables, privacy guards, and retention.

## Raw retention

Default remains **24h**. Parking-session outcomes are expected within the same
assistant journey window; 24h is retained for journey correctness, not sample
inflation. Raw TTL is not silently extended for volume.

When rollups are enabled, expired raw rows are deleted only if
`created_at < watermark.completed_through` (rollup-before-delete).

## Rollup grain

Closed **UTC hour** slices (idempotent replace), reported by `rollup_date` /
window. Dimensions:

- platform (`WEB` / `MOBILE_V2` / `UNKNOWN`)
- inventoryComposition
- outcomeType (+ scaffold `NONE` for evaluation counts)
- evidenceSource (`UNKNOWN` | `ORGANIC` | `CONTROLLED_QA`) — current mint writes `UNKNOWN`
- ranking / shadow / feature / evaluation schema versions
- candidateCountBucket, freshnessMix, zeroAvailabilityPresent, highCapacityPresent, partial
- exposurePolicy = `DETERMINISTIC_ONLY`

No evaluationId, candidate IDs, coordinates, labels, user/session IDs.

## Shadow denominators

Shadow positional metrics use **shadow-attached outcome count** only.
Total outcomes and shadow-attached outcomes are stored separately.

## Small-cell policy

Internal DB keeps exact counts. Report/export marks cells with
`outcome_count < 5` (`MIN_REPORT_CELL_COUNT`). Counts remain in weighted
aggregates; export surfaces may suppress narrow cells.

## Watermark / idempotency

`ranking_evaluation_rollup_watermark.completed_through` advances to each
processed slice end. Reprocessing a slice **deletes then inserts** rows for
that `rollup_hour` — no double count.

## Late outcomes

Grace default **60 minutes** after hour end before a slice is closed.
Outcomes arriving within the grace window are included. Do not aggregate the
current incomplete hour.

## Aggregate retention

Default **90 days** of rollup hours, then batched cleanup.

## Flags

```yaml
parkio.spa.ranking.evaluation:
  rollup-enabled: false
  rollup-retention-days: 90
  rollup-grace-minutes: 60
```

Env:

- `PARKIO_SPA_RANKING_EVALUATION_ROLLUP_ENABLED` (default false)
- `PARKIO_SPA_RANKING_EVALUATION_ROLLUP_RETENTION_DAYS` (default 90)

## Evidence gates

Provisional gates remain 500 / 100 / 50. Strategic interpretation should prefer
**ORGANIC** evidence. QA/`UNKNOWN` traffic must not silently satisfy the gate.

## Rollback

`PARKIO_SPA_RANKING_EVALUATION_ROLLUP_ENABLED=false`

Raw evaluation correlation and shadow can continue. Deterministic ranking
unchanged.
