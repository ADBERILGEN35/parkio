# PP-01B-SPIKE-02 evidence index

Evidence snapshot: **2026-08-04**.

## Documentation inventory (prior phase)

Method: **repository inventory**. No secrets in Git.

| Item | Location | Class |
|------|----------|-------|
| Board inventory + Mode A report | [`../pp-01b-spike-02-postgis-spatial-parity.md`](../pp-01b-spike-02-postgis-spatial-parity.md) | Deliverable |
| Baseline image | `postgis/postgis:16-3.4` | REPOSITORY FACT + Mode A |
| Extension + geography + GiST | parking Flyway V1, V2, V15, V28, V30 | REPOSITORY FACT |
| Nearby / KNN SQL | `ParkingSpotJpaRepository` | REPOSITORY FACT |
| Azure PostGIS docs | SPIKE-01 → [extensions by engine](https://learn.microsoft.com/en-us/azure/postgresql/extensions/concepts-extensions-by-engine) | EXTERNAL VERIFICATION — **revalidate** before Mode B |

## Mode A local runtime (executed)

| Item | Value |
|------|-------|
| Decision | **PASS WITH NON-BLOCKING NOTES** |
| Baseline digest | `postgis/postgis@sha256:44126d872ac91993766c341e369c539e8196614321765d36a6f1bab0419a5fa5` (PG 16.4 / PostGIS 3.4.3) |
| Newer digest | `imresamu/postgis@sha256:74377ef202667cf7ec0d3e03699e43f037f1773323fc6a60445041eb821ffa10` (PG 16.11 / PostGIS 3.6.1) |
| Flyway tip | **33** both targets; validate OK |
| Harness | `ModeAPostgisSpatialParityIT` — function matrix, KNN/GiST EXPLAIN, nearby, triggers, privileges |
| Image override | `-Dparkio.postgis.image=` via `PostgisTestImages` |
| Scripts | `scripts/pp-01b-spike-02-mode-a.ps1`, `scripts/pp-01b-spike-02-mode-a.sh` |
| Machine evidence (gitignored) | `deploy-artifacts/pp-01b-spike-02/` |
| Mode B | **READY WITH CONDITIONS** — not executed; no Azure provisioned |
| SPIKE-03 | **NOT STARTED** |
| Public production | **NO-GO** |
| Municipal production | **DISABLED** |

Do **not** commit logs with secrets, dumps, credentials, env files, or Docker volumes.
