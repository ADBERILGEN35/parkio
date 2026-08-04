# PP-01B-SPIKE-02 — PostGIS spatial parity (board inventory)

| Field | Value |
|-------|-------|
| Spike ID | **PP-01B-SPIKE-02** |
| Status | **DOCUMENTATION COMPLETE — HOLD ON RUNTIME PARITY** |
| Evidence snapshot | **2026-08-04** |
| Method | Repository inventory only — **no** SQL, Docker, Azure, or test execution |
| Parent ADR | [ADR-PP-01A](adr/ADR-PP-01A-managed-postgresql.md) (**ACCEPTED WITH CONDITIONS**) — **frozen** |
| Prior spike | [PP-01B-SPIKE-01](pp-01b-spike-01.md) (**CLOSED**) |
| Registry | [pp-01b-spike-registry.md](pp-01b-spike-registry.md) |
| Baseline image (repo) | `postgis/postgis:16-3.4` |
| Target under ADR | Azure Flexible Server PostgreSQL **16** + documented PostGIS (SPIKE-01 EXTERNAL VERIFICATION cited **3.6.1** on PG16 — **revalidate** before sandbox) |

**Statement taxonomy:** REPOSITORY FACT · EXTERNAL VERIFICATION · INFERENCE · RECOMMENDATION · UNKNOWN

**Board rule:** UNKNOWN must not be promoted to PASS without execution. Documentation presence of a function on Azure ≠ Parkio behavioral parity.

**Authorization boundary:** This package does **not** authorize Azure provisioning, Docker/SQL execution, ADR changes, production apply, or SPIKE-03. PP-01 remains **open**. Public production **NO-GO**. Municipal production **disabled**.

---

## Overall decision

| Verdict | Meaning |
|---------|---------|
| **HOLD (runtime parity)** | Inventory shows no *documented* ADR-blocking absence of core PostGIS primitives Parkio uses; **compatibility of PG16 + PostGIS 3.6.x with Parkio behavior remains UNKNOWN** until Mode A/B execution. |
| Documentation phase | **COMPLETE** |

No product-behavior change recommended. No implementation recommended.

---

## 1. Current spatial inventory

### 1.1 Scope of PostGIS in the monorepo

| Area | Finding | Class |
|------|---------|-------|
| Service owning PostGIS | **parking-service only** | REPOSITORY FACT |
| Other microservices | No PostGIS migrations / native spatial SQL found | REPOSITORY FACT |
| Hibernate Spatial / JPA geometry mapping | **Not used** — `location` not mapped on entities | REPOSITORY FACT (`ParkingSpotEntity` comment; V2 migration) |
| App-side geometry | JTS (`org.locationtech.jts`) for municipal district topology | REPOSITORY FACT |
| Offline operator tooling | `scripts/data-wp-19/export-normalized-districts.sh` uses PostGIS SQL | REPOSITORY FACT |
| Restore drills | `scripts/restore-drill.sh`, `scripts/staging/verify-semantic-integrity.sh` assert `postgis` + `ST_DWithin` | REPOSITORY FACT |

### 1.2 Flyway migrations (spatial)

| Migration | Spatial content | Class |
|-----------|-----------------|-------|
| `V1__enable_postgis.sql` | `CREATE EXTENSION IF NOT EXISTS postgis` | REPOSITORY FACT |
| `V2__create_parking_spots.sql` | `GEOGRAPHY(Point, 4326)`; trigger `ST_SetSRID(ST_MakePoint(...))::geography`; `USING GIST (location)` | REPOSITORY FACT |
| `V15__create_parking_sessions.sql` | Same pattern; `ST_Equals(...::geometry)`; GiST on sessions | REPOSITORY FACT |
| `V28__municipal_parking_sources.sql` | Facility location trigger + GiST | REPOSITORY FACT |
| `V30__izelman_inventory_tariffs.sql` | Roadside location trigger + GiST | REPOSITORY FACT |

### 1.3 Production/native SQL call sites (main)

| Location | Operators |
|----------|-----------|
| `ParkingSpotJpaRepository` | `ST_DWithin`, `ST_SetSRID`, `ST_MakePoint`, `::geography`, `ORDER BY location <->` |
| `MunicipalFacilityRepositoryAdapter` | `ST_Distance`, `ST_DWithin`, `ST_SetSRID`, `ST_MakePoint` |
| `LinkCandidatePairDiscoveryAdapter` | `ST_Distance`, `ST_DWithin` |
| `RoadsideParkingController` | `ST_DWithin`, `ST_Distance`, `ST_SetSRID`, `ST_MakePoint` |
| `OsmImportSupportRepositoryAdapter` | `ST_DWithin`, `ST_SetSRID`, `ST_MakePoint` |

### 1.4 Test-only SQL (not product path)

| Location | Operators |
|----------|-----------|
| `ParkingPostgisIntegrationTest` / `ParkingSessionPostgisIntegrationTest` | `ST_X` / `ST_Y` on `location::geometry`; extension count; fixtures with `ST_SetSRID`/`ST_MakePoint` |
| Municipal `*PostgresIT` fixtures | `ST_SetSRID` / `ST_MakePoint` inserts |

### 1.5 District topology (important distinction)

| Path | Mechanism | Class |
|------|-----------|-------|
| Runtime district assignment | **JTS** `PreparedGeometry.covers` / `GeometryFixer` — **not** PostGIS SQL | REPOSITORY FACT |
| Comments referencing PostGIS | `MunicipalDistrictTopologyPolicy` documents that PostGIS `ST_Covers` + `ST_MakeValid` assigned uniquely offline | REPOSITORY FACT (comment) |
| Offline district export | `ST_MakeValid`, `ST_Multi`, `ST_AsGeoJSON` in `scripts/data-wp-19/export-normalized-districts.sh` | REPOSITORY FACT |

---

## 2. Spatial features actually used

### 2.1 Database / SQL (product + migrations)

| Feature | Used? | Where |
|---------|-------|-------|
| `CREATE EXTENSION postgis` | YES | V1 |
| Type `geography(Point,4326)` | YES | V2, V15, V28, V30 |
| Cast `::geography` / `::geometry` | YES | migrations, queries, tests |
| `ST_MakePoint` | YES | triggers + queries |
| `ST_SetSRID` | YES | triggers + queries |
| `ST_DWithin` | YES | nearby / facilities / roadside / OSM / drills |
| `ST_Distance` | YES | facilities / roadside / pair discovery |
| Geography distance operator `<->` (KNN order) | YES | `ParkingSpotJpaRepository` `ORDER BY location <->` |
| `USING GIST` indexes | YES | spots, sessions, facilities, roadside |
| Triggers syncing lat/lng → geography | YES | V2, V15, V28, V30 |
| `ST_Equals` | YES | V15 session location update guard |
| `ST_X` / `ST_Y` | YES (tests) | PostGIS ITs only |
| PL/pgSQL triggers | YES | location sync functions |

### 2.2 Application (non-SQL)

| Feature | Used? | Where |
|---------|-------|-------|
| JTS Point / Polygon / MultiPolygon | YES | district geometry |
| JTS `GeometryFixer` | YES | `MunicipalDistrictJtsFactory` |
| JTS `PreparedGeometry.covers` | YES | district assignment |
| Haversine helper (app) | YES | `GeographyDistanceMeters` (shadow exposure; not PostGIS) |

### 2.3 Offline scripts

| Feature | Used? | Where |
|---------|-------|-------|
| `ST_MakeValid` | YES (offline) | data-wp-19 export |
| `ST_Multi` | YES (offline) | data-wp-19 export |
| `ST_AsGeoJSON` | YES (offline) | data-wp-19 export |

---

## 3. Unused PostGIS features (searched, NOT USED in repo product path)

| Feature | Status |
|---------|--------|
| `ST_Contains` | **NOT USED** |
| `ST_Within` | **NOT USED** |
| `ST_Intersects` | **NOT USED** |
| `ST_Transform` | **NOT USED** |
| `ST_Buffer` | **NOT USED** |
| `ST_Centroid` | **NOT USED** (JTS centroid used in Java hole promotion only) |
| `ST_Collect` / `ST_Union` / `ST_Simplify` / `ST_Subdivide` | **NOT USED** |
| `ST_Covers` (SQL) | **NOT USED** in runtime SQL — comment + offline parity reference only |
| `ST_MakeValid` (SQL runtime) | **NOT USED** in service SQL — offline script only |
| `ST_AsGeoJSON` (service) | **NOT USED** in service — offline script only |
| `SPGIST` | **NOT USED** |
| Bounding-box `&&` operator in app SQL | **NOT USED** (not found in parking main SQL) |
| Generated columns (spatial) | **NOT USED** — triggers used instead |
| Hibernate Spatial / JPA `@Type` geometry | **NOT USED** |
| Raster / topology / tiger extensions | **NOT USED** |

---

## 4. Potential PG16 / PostGIS 3.6 compatibility risks

| Risk | Severity | Class | Notes |
|------|----------|-------|-------|
| Version skew 3.4 (CI) vs 3.6.x (Azure docs) | Medium | EXTERNAL VERIFICATION (SPIKE-01) + UNKNOWN parity | Must not claim PASS |
| Geography `ST_DWithin` / `ST_Distance` meter semantics | Medium | UNKNOWN until execution | Core product path |
| KNN `<->` on geography with GiST | Medium | UNKNOWN until SPIKE-03 execution (incl. PG16 planner behaviour for `<->`) | Nearby ordering |
| GiST index selection / planner behaviour | Medium | UNKNOWN until SPIKE-03 execution | Index use under PG16 |
| Trigger `EXECUTE FUNCTION` syntax vs older `EXECUTE PROCEDURE` | Low | REPOSITORY FACT uses `EXECUTE FUNCTION` on PG16 image | Re-check on managed |
| `ST_Equals` on geometry casts in session trigger | Low | UNKNOWN until Flyway apply on target | |
| Extension create privileges on Flexible Server | Medium | UNKNOWN | SPIKE-01 noted privilege gate for SPIKE-02 |
| Offline `ST_MakeValid` / `ST_AsGeoJSON` if operators re-run export on Azure | Low | UNKNOWN if Mode B used for tooling | Not runtime parking path |
| JTS vs PostGIS district semantics drift | N/A for SQL | REPOSITORY FACT | Runtime districts are JTS — **not** blocked by PostGIS 3.6 SQL |

**INFERENCE:** No repository evidence of exotic/deprecated PostGIS APIs that are known-removed in 3.6 from this inventory alone. Absence of evidence ≠ PASS.

---

## 5. Functions requiring sandbox / local execution validation

Must execute (Mode A and/or Mode B) before any PASS on parity:

1. `CREATE EXTENSION postgis` (+ record version)
2. Full parking Flyway chain on target engine
3. Trigger insert/update → geography populate
4. GiST index create/use
5. `ST_DWithin` / `ST_Distance` representative nearby + roadside + facilities + pair discovery
6. `ORDER BY location <->` KNN ordering vs fixture expectations
7. `ST_Equals` session trigger path
8. Parking PostGIS IT suite (or documented equivalent)
9. Extension privilege model under least-privilege role
10. Optional: offline export functions if tooling must run on managed Postgres

---

## 6. Compatibility matrix (documentation vs runtime)

Documentation compatibility and runtime compatibility are recorded separately. Documentation must never be read as runtime PASS.

| Layer | Statement | Classification |
|-------|-----------|----------------|
| **Documentation** | Azure Database for PostgreSQL Flexible Server documentation exposes the required PostGIS capabilities. | **FACT (documentation)** — SPIKE-01 EXTERNAL VERIFICATION snapshot; **revalidate** Learn page before sandbox |
| **Runtime** | Parkio runtime compatibility on PostgreSQL 16 + PostGIS 3.6.x remains UNKNOWN until sandbox execution. | **UNKNOWN** |

Supporting note (not a PASS): core operators Parkio uses (`ST_MakePoint`, `ST_SetSRID`, `ST_DWithin`, `ST_Distance`, GiST, geography) are long-standing PostGIS features (INFERENCE). That does **not** upgrade runtime UNKNOWN to PASS.

---

## 7. Functions impossible to validate without execution

Everything in §5. Also: exact numeric distance/ordering equality vs `16-3.4` fixtures; migration apply errors; privilege failures; performance under GiST.

Explicit planner / index UNKNOWNs (remain **UNKNOWN** until SPIKE-03 execution):

- PostgreSQL 16 query planner behaviour for KNN (`<->`)
- GiST index selection / planner behaviour

**ST_Covers / ST_MakeValid in product runtime SQL:** N/A (not used) — do not require sandbox for unused SQL. Offline script validation is optional and separate.

---

## 8. Acceptance matrix

| Item | Status | Notes |
|------|--------|-------|
| Inventory of repo spatial usage complete | **PASS** | This document |
| ADR topology unchanged | **PASS** | No ADR edit |
| Azure Flexible Server docs expose required PostGIS capabilities | **FACT (documentation)** | Not runtime PASS; revalidate |
| Parkio spatial parity on PostGIS 3.6.x | **UNKNOWN** | No sandbox execution |
| Flyway apply on Azure PG16 | **UNKNOWN** | No execution |
| Nearby / KNN / GiST behavior (incl. planner) | **UNKNOWN** | Until SPIKE-03 execution for KNN/`<->` planner and GiST index selection |
| Extension privilege fit | **UNKNOWN** | No execution |
| Product/API/DTO/Flyway change required | **PASS** (docs phase) — none proposed | Must reaffirm after execution |
| Unsupported behavior found in inventory | **PASS** (none identified as hard block) | Not a runtime certificate |
| Overall runtime spike verdict | **HOLD** | Pending Mode A/B |

### PASS / HOLD / FAIL / UNKNOWN (board symbols)

| Symbol | Meaning in this spike |
|--------|------------------------|
| **PASS** | Satisfied by **repository evidence** or **completed docs phase** only |
| **HOLD** | Cannot close runtime parity; waiting on execution or authorization |
| **FAIL** | Evidence of incompatibility or required product change (not found in this inventory) |
| **UNKNOWN** | Requires execution; must not be treated as PASS |

---

## 9. Stop conditions

| Condition | Triggers |
|-----------|----------|
| Mode B without board spend approval | **HOLD** |
| `CREATE EXTENSION postgis` fails on target | **HOLD** / escalate |
| Material spatial semantic difference vs `16-3.4` fixtures | **HOLD** / FAIL if unresolvable without product change |
| Migrations require production code/API/DTO/Flyway redesign | **HOLD** / FAIL per ADR non-goals |
| Privilege model breaks 10-role isolation | **HOLD** |
| Discovery that a required SQL feature is absent on managed PostGIS | **FAIL** → ADR alternate path (SPIKE-01 fallbacks) |

Documentation-only package does **not** trip stop conditions that require execution.

---

## 10. Future sandbox plan (not executed here)

### Mode A — Local compatibility

- Image: `postgis/postgis:16-3.4` and, if available offline, an image matching Azure’s reported PostGIS version
- Run parking Flyway + PostGIS IT suites
- No Azure cost

### Mode B — Board-approved Azure sandbox

- General Purpose Flexible Server only (Burstable rejected — SPIKE-01)
- Prefer SPIKE-01 candidate regions; **revalidate** `$` ZR status; region still not production-frozen
- No production data/traffic/credentials
- Mandatory cleanup
- Record exact `version()` / `PostGIS_Full_Version()`

**Do not execute Mode A or B in this package.**

---

## 11. Out-of-scope

- SPIKE-03 private networking
- Terraform / ARM / Bicep / provisioning
- SQL/Docker/test execution
- Backend / API / DTO / Flyway / Docker / deploy changes
- ADR amendments
- Production or municipal enablement
- Implementation recommendations beyond “run Mode A/B later”
- Claiming public GO or PP-01 closed

---

## References (repository)

- `services/parking-service/src/main/resources/db/migration/V1__enable_postgis.sql` … `V2`, `V15`, `V28`, `V30`
- `ParkingSpotJpaRepository`, `MunicipalFacilityRepositoryAdapter`, `LinkCandidatePairDiscoveryAdapter`, `RoadsideParkingController`, `OsmImportSupportRepositoryAdapter`
- `MunicipalDistrictJtsFactory`, `MunicipalDistrictGeometry`, `MunicipalDistrictTopologyPolicy`
- `scripts/data-wp-19/export-normalized-districts.sh`, `scripts/restore-drill.sh`
- SPIKE-01 PostGIS EXTERNAL VERIFICATION links

Evidence index: [evidence/pp-01b-spike-02/README.md](evidence/pp-01b-spike-02/README.md)
