# Parking session indexes — CONCURRENTLY procedure

Ops guide for the three partial indexes introduced by parking-service Flyway
**V17** (`V17__parking_session_stale_handling.sql`) and **V18**
(`V18__parking_session_lifecycle_evolution.sql`).

Companion scripts (same directory):

| Script | Purpose |
|---|---|
| [`parking-session-indexes-concurrent-create.sql`](./parking-session-indexes-concurrent-create.sql) | `CREATE INDEX CONCURRENTLY IF NOT EXISTS` (rebuild / outside Flyway) |
| [`parking-session-indexes-concurrent-drop.sql`](./parking-session-indexes-concurrent-drop.sql) | `DROP INDEX CONCURRENTLY IF EXISTS` (rollback / replace) |
| [`parking-session-indexes-validate.sql`](./parking-session-indexes-validate.sql) | Read-only existence + validity checks |

Database: **`postgres-parking`** (`parkio-postgres-parking`).  
PostgreSQL: **14+** (Parkio Compose uses `postgres:16-alpine`).  
`CREATE INDEX CONCURRENTLY` / `DROP INDEX CONCURRENTLY` need PostgreSQL 9.2+;
`IF NOT EXISTS` on concurrent create needs **9.5+**. Treat **14+** as the
supported floor for Parkio.

## Why V17 / V18 do not use CONCURRENTLY

Flyway runs each versioned migration inside a **transaction** by default.
PostgreSQL forbids `CREATE INDEX CONCURRENTLY` / `DROP INDEX CONCURRENTLY`
inside a transaction block. Therefore V17/V18 correctly use:

```sql
CREATE INDEX idx_... ON parking_sessions (...) WHERE ...;
```

That is appropriate for empty/small tables and normal hosted-beta rollout. On a
**large** existing `parking_sessions` table, a non-concurrent create blocks
writes for the build duration. Schema correctness stays in transactional
Flyway; large-table index work belongs **outside** Flyway.

## Current state (V17 / V18 already shipped)

V17 and V18 **already create** these indexes on deploy:

| Index | Migration | Definition |
|---|---|---|
| `idx_parking_sessions_stale_active` | V17 | `(last_confirmed_at ASC, started_at ASC, id ASC) WHERE status = 'ACTIVE'` |
| `idx_parking_sessions_reminder_candidates` | V18 | `(last_confirmed_at ASC, started_at ASC, id ASC) WHERE status = 'ACTIVE' AND reminder_stage < 2` |
| `idx_parking_sessions_terminal_ended` | V18 | `(ended_at ASC) WHERE status IN ('COMPLETED', 'CANCELLED')` |

**New deployments** that apply V17+V18 via Flyway already have the indexes —
no CONCURRENTLY procedure is required.

**Existing deployments** that already ran V17/V18: indexes exist. Do not re-run
Flyway DDL. Use CONCURRENTLY only to **rebuild** an index (invalid/bloated
index, deliberate recreate) on a large table.

## When to use CONCURRENTLY outside Flyway

1. **Future** large-table index additions: keep Flyway transactional (or
   document empty Flyway + ops script). Never put `CONCURRENTLY` inside Flyway.
2. **Rebuild** of the V17/V18 indexes on a large production DB.

This procedure does **not** rewrite table schema (no column add/drop, no CHECK
changes). Index-only.

## Production procedure

### 1. Maintenance window

- Prefer a quiet window. `CONCURRENTLY` allows reads/writes but still uses
  CPU/IO and can slow OLTP briefly.
- Notify on-call; parking-service stale scheduler depends on these indexes.
- Estimate: minutes on small DBs; tens of minutes+ on multi-million-row tables.

### 2. Validate indexes from V17/V18

```bash
docker exec -i parkio-postgres-parking \
  psql -U parkio -d parkio_parking -v ON_ERROR_STOP=1 \
  < docs/operations/sql/parking-session-indexes-validate.sql
```

Expect three rows with `indisvalid = true`. If all three exist and are valid,
**stop** — no recreate needed for a normal deploy.

### 3. Rebuild (only if needed)

`DROP` / `CREATE INDEX CONCURRENTLY` cannot run inside a transaction — execute
statements one-by-one (scripts below do that).

```bash
docker exec -i parkio-postgres-parking \
  psql -U parkio -d parkio_parking -v ON_ERROR_STOP=1 \
  < docs/operations/sql/parking-session-indexes-concurrent-drop.sql

docker exec -i parkio-postgres-parking \
  psql -U parkio -d parkio_parking -v ON_ERROR_STOP=1 \
  < docs/operations/sql/parking-session-indexes-concurrent-create.sql
```

### 4. Re-validate

Re-run the validate script. Confirm `indisvalid` and expected index names.

### 5. Rollback

If a concurrent create fails mid-way, PostgreSQL may leave an **invalid**
index. Drop it, then retry:

```sql
DROP INDEX CONCURRENTLY IF EXISTS idx_parking_sessions_stale_active;
```

Rollback of a successful recreate is the same DROP script. Dropping removes
index coverage until recreated. There is no schema rewrite to undo.

## Exact SQL (names match V17 / V18)

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_parking_sessions_stale_active
    ON parking_sessions (last_confirmed_at ASC, started_at ASC, id ASC)
    WHERE status = 'ACTIVE';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_parking_sessions_reminder_candidates
    ON parking_sessions (last_confirmed_at ASC, started_at ASC, id ASC)
    WHERE status = 'ACTIVE' AND reminder_stage < 2;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_parking_sessions_terminal_ended
    ON parking_sessions (ended_at ASC)
    WHERE status IN ('COMPLETED', 'CANCELLED');
```

Drop:

```sql
DROP INDEX CONCURRENTLY IF EXISTS idx_parking_sessions_stale_active;
DROP INDEX CONCURRENTLY IF EXISTS idx_parking_sessions_reminder_candidates;
DROP INDEX CONCURRENTLY IF EXISTS idx_parking_sessions_terminal_ended;
```

## Notes

- Do **not** wrap these statements in `BEGIN`/`COMMIT`.
- Avoid `VACUUM FULL` on `parking_sessions` in the same window.
- After rebuild, optional: `ANALYZE parking_sessions;`
- See also: [parking-session-stale-runbook.md](../parking-session-stale-runbook.md),
  [parking-session-performance.md](../parking-session-performance.md).