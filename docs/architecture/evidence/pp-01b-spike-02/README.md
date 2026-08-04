# PP-01B-SPIKE-02 evidence index

Evidence snapshot: **2026-08-04**.

Method: **repository inventory only**. No SQL transcripts, Docker logs, Azure portal
exports, or credentials belong in Git.

| Item | Location | Class |
|------|----------|-------|
| Board inventory report | [`../pp-01b-spike-02-postgis-spatial-parity.md`](../pp-01b-spike-02-postgis-spatial-parity.md) | Deliverable |
| Baseline image | `postgis/postgis:16-3.4` in parking ITs / compose | REPOSITORY FACT |
| Extension + geography + GiST | parking Flyway V1, V2, V15, V28, V30 | REPOSITORY FACT |
| Nearby / KNN SQL | `ParkingSpotJpaRepository` | REPOSITORY FACT |
| Azure PostGIS docs (version may change) | SPIKE-01 → [extensions by engine](https://learn.microsoft.com/en-us/azure/postgresql/extensions/concepts-extensions-by-engine) | EXTERNAL VERIFICATION — **revalidate** before Mode B |

When Mode A/B eventually runs, store sanitized version strings and PASS/HOLD
decision summaries here or linked from the registry — **never** secrets or
subscription IDs.
