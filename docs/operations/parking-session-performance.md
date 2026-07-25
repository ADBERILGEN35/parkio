# Parking Session stale lifecycle — performance expectations

Guidance for scheduler query plans and load at large ACTIVE / history sizes.

## Expected behavior

| Scale | Expectation |
|---|---|
| **~100k ACTIVE** | Hourly tick should page through due reminders/auto-completes using partial indexes. With defaults (batch 100, max 10k pages) a tick can examine up to ~1M candidates theoretically; in practice due sets are a fraction of ACTIVE. Watch `parking_sessions_scheduler_duration_seconds_*` — sustained multi-minute ticks need batch/rate tuning or index health checks. |
| **~1M history (COMPLETED/CANCELLED)** | User history pagination is owner-scoped (`started_at DESC, id DESC`). Retention scans use `idx_parking_sessions_terminal_ended` on `ended_at` when retention is enabled. Retention stays **off** by default so history growth does not add delete IO. |

ACTIVE gauge (`parking_sessions_active`) is refreshed once per tick via
`countByStatus(ACTIVE)` — cheap relative to paging work; not request-path.

## Indexes

| Index | Supports |
|---|---|
| `idx_parking_sessions_stale_active` | Auto-complete candidate ordered scan |
| `idx_parking_sessions_reminder_candidates` | Reminder candidates with `reminder_stage < 2` |
| `idx_parking_sessions_terminal_ended` | Retention purge by `ended_at` |

Rebuild procedure (large DBs):  
[`sql/parking-session-indexes-concurrent.md`](./sql/parking-session-indexes-concurrent.md).

## EXPLAIN guidance (scheduler queries)

Connect to `parkio-postgres-parking` and use realistic timestamps:

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id
FROM parking_sessions
WHERE status = 'ACTIVE'
  AND last_confirmed_at <= now() - interval '72 hours'
  AND started_at <= now() - interval '72 hours'
ORDER BY last_confirmed_at ASC, started_at ASC, id ASC
LIMIT 100;

EXPLAIN (ANALYZE, BUFFERS)
SELECT id
FROM parking_sessions
WHERE status = 'ACTIVE'
  AND reminder_stage = 0
  AND last_confirmed_at <= now() - interval '24 hours'
ORDER BY last_confirmed_at ASC, started_at ASC, id ASC
LIMIT 100;

EXPLAIN (ANALYZE, BUFFERS)
SELECT id
FROM parking_sessions
WHERE status IN ('COMPLETED', 'CANCELLED')
  AND ended_at <= now() - interval '365 days'
ORDER BY ended_at ASC, id ASC
LIMIT 100;
```

Healthy plans: **Index Scan** / **Bitmap Index Scan** on the matching partial
index, not Seq Scan on `parking_sessions`. If Seq Scan appears at 100k+ ACTIVE,
validate indexes (`parking-session-indexes-validate.sql`) and `ANALYZE`.

## When to run benchmarks

Run targeted benchmarks when:

- ACTIVE count approaches or exceeds ~50k–100k in staging
- Scheduler duration p95 grows after a deploy
- Changing `scheduler-batch-size`, fixed delay, or duration thresholds
- After CONCURRENTLY index rebuild on a large table

Prefer staging with production-like data volumes. Pair k6 HTTP probes with
Prometheus snapshots of `parking_sessions_scheduler_*` and outbox gauges.

## k6 harness

Skeleton script (safe; no secrets committed):

[`benchmarks/k6/parking-session-stale.js`](../../benchmarks/k6/parking-session-stale.js)

It exercises start → active → confirm-active → complete when
`PARKIO_K6_EMAIL` / `PARKIO_K6_PASSWORD` (or a bearer token env) are provided.
See [`benchmarks/k6/README.md`](../../benchmarks/k6/README.md).

This is **not** a substitute for DB-side EXPLAIN or scheduler soak tests under
synthetic ACTIVE backlogs.