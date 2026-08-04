# PP-01B-SPIKE-02 — PostGIS spatial parity (board inventory)

| Field | Value |
|-------|-------|
| Spike ID | **PP-01B-SPIKE-02** |
| Status | **MODE A COMPLETE — PASS WITH NON-BLOCKING NOTES** (Mode B **not executed**; SPIKE-02 not fully closed) |
| Evidence snapshot | **2026-08-04** |
| Method | Repository inventory + **Mode A local runtime parity** (no Azure) |
| Parent ADR | [ADR-PP-01A](adr/ADR-PP-01A-managed-postgresql.md) (**ACCEPTED WITH CONDITIONS**) — **frozen** |
| Prior spike | [PP-01B-SPIKE-01](pp-01b-spike-01.md) (**CLOSED**) |
| Registry | [pp-01b-spike-registry.md](pp-01b-spike-registry.md) |
| Baseline image (Mode A) | `postgis/postgis:16-3.4` @ `sha256:44126d872ac91993766c341e369c539e8196614321765d36a6f1bab0419a5fa5` (PG 16.4 / PostGIS 3.4.3 / GEOS 3.9.0 / PROJ 7.2.1) |
| Newer image (Mode A) | `imresamu/postgis:16-3.6.1-bookworm` @ `sha256:74377ef202667cf7ec0d3e03699e43f037f1773323fc6a60445041eb821ffa10` (PG 16.11 / PostGIS 3.6.1 / GEOS 3.11.1 / PROJ 9.1.1) |
| Target under ADR | Azure Flexible Server PostgreSQL **16** + documented PostGIS (SPIKE-01 EXTERNAL VERIFICATION cited **3.6.1** on PG16 — **revalidate** before Mode B) |

**Statement taxonomy:** REPOSITORY FACT · EXTERNAL VERIFICATION · INFERENCE · RECOMMENDATION · UNKNOWN

**Board rule:** UNKNOWN must not be promoted to PASS without execution. Documentation presence of a function on Azure ≠ Parkio behavioral parity. Mode A local PASS does **not** certify Azure managed runtime.

**Authorization boundary:** Mode A does **not** authorize Azure provisioning, ADR changes, production apply, or SPIKE-03. PP-01 remains **open**. Public production **NO-GO**. Municipal production **disabled**. No Azure resource provisioned. No Azure credential used. No IaC generated.

---

## Overall decision

| Verdict | Meaning |
|---------|---------|
| **Mode A** | **PASS WITH NON-BLOCKING NOTES** — local PG16 PostGIS 3.4.3 vs 3.6.1 semantic parity proven |
| **Mode B** | **READY WITH CONDITIONS** — still required for Azure allow-list / privilege / managed build |
| **SPIKE-02 closure** | **Not fully closed** until Mode B (or board waive) |
| Documentation phase | **COMPLETE** |

No public API/DTO/business-rule/Flyway migration **content** changes. Linking remains disabled. İZELMAN publication remains disabled.

### Mode A non-blocking notes

1. Official `postgis/postgis` has no reproducible `16-3.6` tag; Target B used pinned `imresamu/postgis:16-3.6.1-bookworm` (digest recorded) — community build, not Microsoft-managed binaries.
2. Under full `integrationTest` load on Target B, `TrustShadowPersistencePostgresIT.concurrentDistinctEvidence…` intermittently failed (`APPENDED` vs `FAILED`); **isolated re-runs PASS**. Not spatial/nearby/KNN/Flyway. Classified suite-load concurrency flake.
3. `OsmRealIzmirImportValidationIT` is env-gated (optional; not required Mode A).
4. Shadow-migration tip assertions updated `26` → current tip `33` (test harness only).

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
| Version skew 3.4 (CI) vs 3.6.x | Medium | Mode A FACT | **PASS WITH NOTE** — semantics matched locally |
| Geography `ST_DWithin` / `ST_Distance` meter semantics | Medium | Mode A FACT | **PASS** (tolerance 0.05 m) |
| KNN `<->` on geography with GiST (local) | Medium | Mode A FACT | **PASS** — identical nearest IDs |
| GiST index selection / planner (local) | Medium | Mode A FACT | **PASS** — EXPLAIN Index Scan using GiST |
| Azure managed planner / build | Medium | UNKNOWN | Pending Mode B |
| Trigger `EXECUTE FUNCTION` syntax | Low | Mode A FACT | **PASS** on both local images |
| `ST_Equals` session immutable path | Low | Mode A FACT | **PASS** |
| Extension create privileges (local model) | Medium | Mode A FACT | App role cannot create extension |
| Extension create privileges on Flexible Server | Medium | UNKNOWN | Pending Mode B |
| Offline `ST_MakeValid` / `ST_AsGeoJSON` on Azure | Low | UNKNOWN | Optional tooling / Mode B |
| JTS vs PostGIS district semantics drift | N/A for SQL | REPOSITORY FACT | Runtime districts are JTS |

**INFERENCE (inventory):** No exotic/deprecated PostGIS APIs found. Mode A confirmed core Parkio operators on 3.6.1 locally. Azure managed still UNKNOWN.

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
| **Documentation** | Azure Database for PostgreSQL Flexible Server documentation exposes the required PostGIS capabilities. | **FACT (documentation)** — SPIKE-01 EXTERNAL VERIFICATION snapshot; **revalidate** Learn page before Mode B |
| **Local runtime (Mode A)** | Parkio spatial semantics match on PG16 + PostGIS 3.4.3 and PG16 + PostGIS 3.6.1 (pinned local images). | **PASS WITH NON-BLOCKING NOTES** |
| **Azure runtime (Mode B)** | Parkio runtime on **managed** Flexible Server PostGIS remains UNKNOWN until Mode B. | **UNKNOWN** |

Never imply Azure runtime PASS from documentation or from local Mode A alone.

---

## 7. Remaining unknowns after Mode A

Local Mode A closed: Flyway tip **33**, core function matrix, KNN/`<->` ordering, local GiST planner selection, triggers, required PostGIS/municipal ITs.

Still **UNKNOWN** until Mode B (Azure sandbox) — **not** SPIKE-03:

- Extension allow-list on Flexible Server General Purpose
- Extension enable privilege under least-privilege roles
- Exact managed PostGIS build / GEOS / PROJ
- Azure-specific planner/runtime behaviour
- General Purpose SKU behaviour under Parkio load shape

**ST_Covers / ST_MakeValid in product runtime SQL:** N/A (not used). Offline script validation is optional and separate.

---

## 8. Acceptance matrix

| Item | Status | Notes |
|------|--------|-------|
| Inventory of repo spatial usage complete | **PASS** | This document |
| ADR topology unchanged | **PASS** | No ADR edit |
| Azure Flexible Server docs expose required PostGIS capabilities | **FACT (documentation)** | Not Azure runtime PASS; revalidate |
| Local Mode A spatial parity (3.4 vs 3.6.1) | **PASS WITH NOTES** | Digests + harness + ITs |
| Flyway tip / validate (local) | **PASS** | Tip **33** both targets |
| Nearby / KNN / GiST (local) | **PASS** | GiST Index Scan in EXPLAIN |
| Extension privilege model (local) | **PASS** | App cannot create extension |
| Azure Flyway / privilege / managed build | **UNKNOWN** | Mode B |
| Product/API/DTO/Flyway migration content change | **PASS** — none | Tip assertion test fix only |
| Mode A overall | **PASS WITH NON-BLOCKING NOTES** | |
| Mode B readiness | **READY WITH CONDITIONS** | |
| Overall SPIKE-02 closure | **HOLD** (Mode B pending) | |

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

### Mode A — Local compatibility — **EXECUTED**

- Baseline `postgis/postgis:16-3.4` + newer pinned `imresamu/postgis:16-3.6.1-bookworm`
- Parking Flyway tip **33** + Mode A harness + required PostGIS/municipal ITs
- Override: `-Dparkio.postgis.image=…` via `PostgisTestImages`
- Scripts: `scripts/pp-01b-spike-02-mode-a.ps1`, `scripts/pp-01b-spike-02-mode-a.sh`
- No Azure cost; containers ephemeral / cleaned

### Mode B — Board-approved Azure sandbox — **NOT EXECUTED**

- General Purpose Flexible Server only (Burstable rejected — SPIKE-01)
- Prefer SPIKE-01 candidate regions; **revalidate** `$` ZR status; region still not production-frozen
- No production data/traffic/credentials
- Mandatory cleanup
- Record exact managed `version()` / `PostGIS_Full_Version()`

---

## 11. Out-of-scope

- SPIKE-03 private networking (**MODE A COMPLETE — PASS WITH NON-BLOCKING NOTES**; Mode B pending)
- Terraform / ARM / Bicep / provisioning
- ADR amendments
- Production or municipal enablement
- Claiming public GO or PP-01 closed

---

## References (repository)

- `services/parking-service/src/main/resources/db/migration/V1__enable_postgis.sql` … `V2`, `V15`, `V28`, `V30`
- `PostgisTestImages`, `ModeAPostgisSpatialParityIT`
- `ParkingSpotJpaRepository`, municipal adapters, OSM/İZUM ITs
- `MunicipalDistrictJtsFactory` (JTS — not PostGIS SQL)
- `scripts/pp-01b-spike-02-mode-a.ps1` / `.sh`, `scripts/restore-drill.sh`
- SPIKE-01 PostGIS EXTERNAL VERIFICATION links

Evidence index: [evidence/pp-01b-spike-02/README.md](evidence/pp-01b-spike-02/README.md)
Machine evidence (gitignored): `deploy-artifacts/pp-01b-spike-02/`
