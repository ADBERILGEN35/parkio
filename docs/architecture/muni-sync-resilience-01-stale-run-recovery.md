# MUNI-SYNC-RESILIENCE-01 — Stale Municipal Sync Run Recovery

## Incident class

OPS-MUNI-IZUM-LOCK-01 proved that a municipal sync can leave an orphan
`municipal_source_sync_runs` row in `RUNNING` after process interruption
(container recreate / crash). The partial unique index
`uq_municipal_source_sync_runs_one_running` then blocks every later
`tryStart` for that source (`concurrent_run` skip loop).

Observability already counted stale RUNNING via SLA
`parkio.municipal.sla.stale-running-after-seconds` (default 600s) but did
**not** terminalize them.

## RUNNING lifecycle

```
                normal
NEW → RUNNING ─────────→ SUCCESS / PARTIAL_SUCCESS
       |
       ├───────────────→ FAILED   (upstream / parse / DB classified)
       |
       └─ stale/orphan → FAILED + error_category=stale_run_recovered
```

Recovered terminal must never return to SUCCESS.

## Stale definition

A row is stale when:

- `status = 'RUNNING'`
- `started_at < now - parkio.municipal.sync.stale-running-threshold`

Default threshold: **20 minutes**.

Predicate uses **strictly older than** (`started_at < cutoff`).

## Threshold rationale

| Budget | Approx |
|--------|--------|
| İZUM HTTP worst-case | ~22s |
| İSPARK HTTP worst-case | ~25s |
| Scheduler fixed delay | 120s |
| SLA observe-only stale alert | 600s |

Recovery threshold is intentionally **larger** than the SLA alert so
healthy long-tail persists are not killed, yet orphans cannot wedge a
provider for hours. Heartbeat is **not** used: legitimate syncs are
timeout-bounded well below 20m.

## Recovery terminal status

**FAILED** + `error_category=stale_run_recovered` (no new DB status enum).

Reasons:

- `CHECK (status IN (...))` already allows FAILED
- Distinct from `read_timeout` / `dns_resolution` / `cancelled`
- History retained; `completed_at` set; counters left at zero

## Ownership

`MunicipalSyncRunRecoveryService` (application layer) owns recovery.

Provider adapters never implement stale recovery.

## Startup recovery

`MunicipalSyncStaleRunRecoveryStartup` (`ApplicationRunner`, high order)
calls `recoverStaleRunning()` before schedulers matter. Zero rows is fine.

## Runtime watchdog

`MunicipalSyncStaleRunWatchdogJob` every
`parkio.municipal.sync.stale-run-watchdog-fixed-delay-ms` (default 120s),
overlap-guarded.

## Self-healing tryStart

Before INSERT RUNNING, `tryStart` recovers stale RUNNING for that
`source_id` (same threshold). Complements startup/watchdog so a source
cannot stay wedged waiting for the next watchdog tick.

## Atomic recovery / races

```sql
UPDATE ... SET status='FAILED', completed_at=..., error_category='stale_run_recovered'
WHERE status='RUNNING' AND started_at < :cutoff
```

`complete(...)` updates only `WHERE id=:id AND status='RUNNING'` and
returns boolean ownership.

If worker completes first → recovery updates 0.
If recovery wins → late `complete` returns false and does not overwrite.

## Late-worker semantics

Before authoritative `deactivateMissing`, sync/import paths require
`isRunning(runId)`.

Late `complete` must not call `markSuccessful`.

Recovery never triggers reconciliation.

## Run ownership / lease

`runId` + `status=RUNNING` is sufficient. No distributed lease / Redis /
Kafka / heartbeat.

## Provider scope

All rows in `municipal_source_sync_runs` (İZUM, İSPARK, OSM, İZELMAN,
fake-test, future adapters). Manual sync uses the same lifecycle.

## Unique index

`uq_municipal_source_sync_runs_one_running` is retained. Recovery
terminalizes the orphan so the next INSERT succeeds naturally.

## Configuration

| Property | Default |
|----------|---------|
| `parkio.municipal.sync.stale-run-recovery-enabled` | `true` |
| `parkio.municipal.sync.stale-running-threshold` | `20m` |
| `parkio.municipal.sync.stale-run-watchdog-enabled` | `true` |
| `parkio.municipal.sync.stale-run-watchdog-fixed-delay-ms` | `120000` |

Hosted-beta compose wires the same env keys. SLA
`stale-running-after-seconds` remains observe-only.

## Metrics

- `parkio.municipal.sync.stale.detected`
- `parkio.municipal.sync.stale.recovered{source_key,status}`
- `parkio.municipal.sync.stale.recovery_failed`

No run IDs as tags. After recovery, SLA gauges refresh so
`stale_running_operations` drops.

## Logging

`municipal_sync_stale_recovered source=… ageBucket=… status=FAILED category=stale_run_recovered`

No payloads / facility IDs.

## Schema

No migration: status stays string CHECK; category is free text.

## Data safety

Recovery mutates **only** sync-run control rows.

Never: delete facilities, deactivate links, invent SUCCESS counters,
or run missing-set reconciliation from an orphan run.

## Rollback

Set `PARKIO_MUNICIPAL_SYNC_STALE_RUN_RECOVERY_ENABLED=false`
(and optionally disable watchdog) or redeploy prior SHA
`741fbb6b57a98446aaedbd568631cb33da594566`. No facility-data rollback.
