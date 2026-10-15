# F-38R analytics snapshot concurrency — investigation record

Issue: #382 · date: 2026-10-15 · PR: #409

## Scope

Reproduce concurrent-first-row and `@Version` conflicts on analytics snapshot
read/modify/save. Atomic UPDATE/upsert is **not** authorized by this issue until
an owner decision after demonstrated loss.

## Method

`AnalyticsSnapshotConcurrencyPostgresIT` (integrationTest, Testcontainers
Postgres) fires eight concurrent `handlePointsEarned` calls with **distinct**
event IDs against one user.

## Observed evidence (2026-10-15 local IT)

```
F-38R analytics concurrency: success=1 optimistic=0 other=7 ledger=1 snapshot_event_count=1
```

- `other=7` were `DataIntegrityViolationException` on unique constraint
  `uq_daily_analytics_snapshots` (concurrent first-row insert race).
- No `OptimisticLockingFailureException` was observed in this run.
- For the successful transaction: `ledger == snapshot_event_count` (no silent
  under-count).

Kafka DLT (FixedBackOff 500ms × 2 then DLT) would see these failures if the
consumer path raised them after retries; this IT calls the application service
directly.

## Disposition

1. **Silent `@Version` conflict loss: not reproducible** in this run.
2. **Concurrent first-row unique-constraint races: reproducible** (7/8 workers).
3. Atomic upsert / `INSERT ... ON CONFLICT` for daily/user/parking snapshots is a
   **candidate follow-up** requiring an explicit owner design gate — not implemented
   here per issue scope.

## Occupancy batch half

`MunicipalOccupancyBatchQueryIT`: facility-list uses `latestForFacilities` once
(batch), zero per-facility occupancy calls; mixed IZUM/OSM publication preserved.
