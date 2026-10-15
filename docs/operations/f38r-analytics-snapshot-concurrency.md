# F-38R analytics snapshot concurrency — investigation record

Issue: #382 · date: 2026-10-15 · api base: see PR head

## Scope

Reproduce concurrent-first-row and `@Version` conflicts on analytics snapshot
read/modify/save. Atomic UPDATE/upsert is **not** authorized unless loss is
demonstrated.

## Method

`AnalyticsSnapshotConcurrencyPostgresIT` (integrationTest, Testcontainers
Postgres) fires eight concurrent `handlePointsEarned` calls with **distinct**
event IDs against one user. Counts:

- successful ingestions
- `OptimisticLockingFailureException` / `ObjectOptimisticLockingFailureException`
- other failures
- `analytics_events` ledger rows vs `user_analytics_snapshots.event_count`

Kafka DLT (FixedBackOff 500ms × 2 then DLT) is the consumer-layer policy; this
IT exercises the application service directly, so DLT delivery is out of scope
here and is unchanged.

## Disposition

See surefire output line `F-38R analytics concurrency: ...`.

- If `ledger == snapshot_event_count` and failures are only optimistic locks or
  zero, **silent conflict loss is not reproducible**.
- Atomic snapshot UPDATE remains **out of scope** until an owner decision
  authorizes it after a demonstrated loss.

## Occupancy batch half

`MunicipalOccupancyBatchQueryIT` covers the authorized product fix: facility-list
occupancy uses `latestForFacilities` (bounded), preserving mixed-source output.
