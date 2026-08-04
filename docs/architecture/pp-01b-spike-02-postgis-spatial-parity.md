# PP-01B-SPIKE-02 — PostGIS spatial parity (specification)

| Field | Value |
|-------|-------|
| Spike ID | **PP-01B-SPIKE-02** |
| Status | **READY, NOT STARTED** |
| Parent | [ADR-PP-01A](adr/ADR-PP-01A-managed-postgresql.md) · [SPIKE-01](pp-01b-spike-01.md) (**CLOSED**) |
| Registry | [pp-01b-spike-registry.md](pp-01b-spike-registry.md) |
| Owner | Parking eng + Infra |
| This package | Specification only — **no execution** |

**Authorization:** SPIKE-02 may be planned now. Mode B (Azure sandbox) requires explicit board spend approval. This document does **not** start execution.

PP-01 remains **open**. Public production remains **NO-GO**. Municipal production remains **disabled**.

---

## Purpose

Prove that PostgreSQL **16** with the available PostGIS version (repository baseline `postgis/postgis:16-3.4`; Azure Flexible Server may expose a newer documented version such as **3.6.x** — EXTERNAL VERIFICATION at SPIKE-01 snapshot) can satisfy Parkio **parking-service** spatial requirements currently proven against the repository PostGIS test environment.

SPIKE-02 does **not** change application code, APIs, DTOs, Flyway design, production flags, or municipal feature behavior. It validates compatibility only.

---

## Baseline (REPOSITORY FACT)

| Item | Evidence |
|------|----------|
| Extension bootstrap | `services/parking-service/.../V1__enable_postgis.sql` — `CREATE EXTENSION IF NOT EXISTS postgis` |
| Compose / IT image | `postgis/postgis:16-3.4` |
| Geography points + triggers | `V2__create_parking_spots.sql`, sessions/municipal migrations |
| Nearby / distance | `ST_DWithin`, `ST_Distance`, `<->` KNN in JPA / roadside queries |
| Facility discovery | `MunicipalFacilityRepositoryAdapter` — `ST_DWithin` + `ST_Distance` |
| Pair discovery | `LinkCandidatePairDiscoveryAdapter` — `ST_DWithin` + `ST_Distance` |
| District topology policy | `MunicipalDistrictTopologyPolicy` — PostGIS `ST_Covers` after `ST_MakeValid` |
| JVM GeometryFixer | `MunicipalDistrictJtsFactory` uses JTS `GeometryFixer` (app-side); DB must still support `ST_MakeValid` / covers semantics used in SQL paths |
| Integration tests | `*Postgis*IT`, municipal `*PostgresIT` suites under parking-service |

---

## Mandatory validation checklist

| # | Check | Pass condition |
|---|-------|----------------|
| 1 | `CREATE EXTENSION postgis` | Succeeds without unsupported privilege escalation beyond ADR role model |
| 2 | Installed PostGIS version | Recorded exactly (e.g. `POSTGIS_FULL_VERSION`) |
| 3 | Geography / geometry types used by Parkio | `geography(Point,4326)` (and related) usable |
| 4 | GiST indexes | Parking location GiST (and equivalent) create/use successfully |
| 5 | `ST_DWithin` | Exists and matches expected nearby semantics in representative query |
| 6 | `ST_Distance` | Exists; ordering/distance usable |
| 7 | `ST_Covers` | Exists; district assignment semantics acceptable |
| 8 | `ST_MakeValid` | Exists; usable where SQL expects valid geometry |
| 9 | GeometryFixer-equivalent DB behavior | Where SQL relies on make-valid/covers, results align; JVM GeometryFixer path remains unchanged |
| 10 | Current parking Flyway migrations | Apply cleanly on target engine |
| 11 | Flyway compatibility | No production migration redesign required |
| 12 | Representative nearby query | Matches expected results vs fixtures |
| 13 | Municipal district / topology query paths | Coverage / covers paths behave as expected |
| 14 | Import mutation checks | OSM/import-related spatial predicates do not break |
| 15 | Extension privileges | No unexpected superuser-only requirement that breaks least-privilege roles |
| 16 | No app/API/DTO changes | Zero code/schema contract changes required for PASS |

---

## Execution modes

### Mode A — Local compatibility (preferred first)

| Field | Definition |
|-------|------------|
| Environment | Repository PostGIS container (`postgis/postgis:16-3.4` or board-approved local image matching Azure’s reported version if available offline) |
| Azure resources | **None** |
| Credentials | None |
| Fixtures | Deterministic parking / municipal fixtures already used by ITs |
| Cost | None |
| Board approval | Not required for local IT runs |
| Allowed in this package? | Spec only — **do not execute** in the closure package |

### Mode B — Board-approved Azure sandbox

| Field | Definition |
|-------|------------|
| Environment | Temporary **General Purpose** Flexible Server only (Burstable **forbidden** per SPIKE-01) |
| Region | Prefer SPIKE-01 candidates (UK South / France Central / Sweden Central); **revalidate** `$` ZR status before create; region still **not** a production freeze |
| Data | No production data; no public traffic; no production credentials |
| Cost | Explicit spend authorization required |
| Cleanup | **Mandatory** destroy within registry window |
| Board approval | **Required** before create |
| Allowed in this package? | Spec only — **do not execute** |

Mode B is optional if Mode A proves parity against an image that matches Azure’s PostGIS version; if Azure version differs from `16-3.4`, Mode B (or a local image matching Azure) is required for PASS against managed reality.

---

## Acceptance criteria

### PASS

All of:

- PostGIS extension enables successfully  
- Required spatial functions exist (`ST_DWithin`, `ST_Distance`, `ST_Covers`, `ST_MakeValid`, geography ops used by Parkio)  
- Repository Flyway migrations apply  
- Parking PostGIS integration tests pass (or documented equivalent suite on target)  
- Representative municipal and nearby spatial queries match expected results  
- No schema/API/DTO change required  
- No unsupported behavior blocks parking  
- Evidence records exact PostgreSQL and PostGIS versions  
- If Mode B used: resource cleanup proven  

### HOLD

Any of:

- Azure sandbox authorization missing when Mode B is required  
- Required extension cannot be enabled  
- Spatial behavior differs materially from baseline  
- Migrations require production code changes  
- Privilege model incompatible with 10-role isolation  

### FAIL (escalate to ADR / alternate provider)

Unresolvable PostGIS gap on Flexible Server after Mode A/B evidence — fallback per registry (isolate parking, Crunchy/Aiven reopen, or AWS RDS alternate).

---

## Explicit non-goals

- No SPIKE-03 networking proof  
- No Terraform / paid apply without board auth  
- No production cutover  
- No PP-01 closure  
- No municipal production enablement  
- No inventing spatial results without running tests (when execution starts)

---

## Evidence expectations (when executed later)

Store sanitized transcripts **outside Git** if they contain subscription IDs. In-repo: version strings, PASS/HOLD decision, link from spike registry. No credentials, portal exports, or account-tied cost quotes in Git.
