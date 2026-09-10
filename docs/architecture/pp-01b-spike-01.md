# PP-01B-SPIKE-01 — Azure Managed PostgreSQL Technical Validation

| Field | Value |
|-------|-------|
| Spike ID | **PP-01B-SPIKE-01** |
| Status | **CLOSED — ACCEPT WITH NON-BLOCKING NOTES** |
| Evidence snapshot date | **2026-08-04** |
| Method | Repository evidence + Microsoft Learn EXTERNAL VERIFICATION (catalog only) |
| Provisioning | **None** — no Azure credentials, no portal create, no sandbox server |
| Parent ADR | [ADR-PP-01A](adr/ADR-PP-01A-managed-postgresql.md) (**ACCEPTED WITH CONDITIONS**) |
| Registry | [pp-01b-spike-registry.md](pp-01b-spike-registry.md) |
| Next | [PP-01B-SPIKE-02](pp-01b-spike-02-postgis-spatial-parity.md) Mode A complete; [SPIKE-03](pp-01b-spike-03-private-network-dns-tls.md) Mode A complete |

**Statement taxonomy:** REPOSITORY FACT · EXTERNAL VERIFICATION · INFERENCE · RECOMMENDATION · UNKNOWN

**Authority rule:** EXTERNAL VERIFICATION describes provider capability **at the evidence snapshot date**. It is **not** a permanent architecture freeze. **Revalidate every external claim** against the cited Microsoft Learn page before any paid sandbox or production-shaped apply.

**Authorization boundary:** SPIKE-01 closure does **not** authorize paid apply, production-shaped apply, public GO, or municipal production enablement. PP-01 remains **open**.

---

## Overall decision

| Question | Answer | Class |
|----------|--------|-------|
| Can the ADR topology actually be implemented on Azure Flexible Server? | **Yes, with region and SKU constraints** | INFERENCE from EXTERNAL VERIFICATION + REPOSITORY FACT |
| Conflict with ADR-PP-01A? | **No** — provider/topology unchanged; SPIKE-01 adds conditional region/SKU notes | INFERENCE |
| Hard ADR stop FAIL against the product? | **No**, when region avoids temporary `$` ZR blocks and tier is General Purpose (not Burstable) | EXTERNAL VERIFICATION |
| May SPIKE-02 planning proceed? | **Yes** — execution not started by this package | RECOMMENDATION |

PP-01 remains **open**. Public production remains **NO-GO**. Municipal production remains **disabled**. No infrastructure was provisioned.

### Non-blocking notes (do not reopen ADR)

1. Prefer validation candidates **UK South / France Central / Sweden Central** at snapshot date — **not** a frozen production region.
2. **West Europe / North Europe** unsuitable for **new** ZR HA while `$` applies — temporary, must recheck.
3. **Burstable rejected** for ADR HA; **General Purpose** accepted as tier family; exact SKU UNKNOWN.
4. PostGIS docs PASS; Parkio spatial parity UNKNOWN → SPIKE-02.
5. Private networking UNKNOWN → SPIKE-03.
6. Cost ceiling and quota UNKNOWN.

---

## Dynamic provider revalidation rule

Before any paid create or production-shaped apply, re-check and record a new evidence date for:

| Claim | Authoritative source (must re-open) |
|-------|-------------------------------------|
| Region ZR HA / `$` status | [overview — Azure regions](https://learn.microsoft.com/en-us/azure/postgresql/overview#azure-regions) |
| Burstable vs ZR HA | [HA concepts](https://learn.microsoft.com/en-us/azure/postgresql/high-availability/concepts-high-availability) |
| PITR retention 7–35 | [backup-restore](https://learn.microsoft.com/en-us/azure/postgresql/backup-restore/concepts-backup-restore) |
| PostGIS on PG16 | [extensions by engine](https://learn.microsoft.com/en-us/azure/postgresql/extensions/concepts-extensions-by-engine) |
| Private access / DNS | [private networking](https://learn.microsoft.com/en-us/azure/postgresql/connectivity/concepts-networking-private) |
| TLS defaults | [TLS how-to](https://learn.microsoft.com/en-us/azure/postgresql/security/security-tls-how-to-connect) |

Do **not** treat the region table in this document as permanent FACT.

---

## 1. Region validation

### 1.1 Repository region anchor

| Claim | Class | Evidence |
|-------|-------|----------|
| Hosted-beta Azure cost comparison region is **West Europe** (`westeurope`) | REPOSITORY FACT | `docs/azure/README.md` |
| That choice is for the **VM beta**, not a frozen managed-Postgres production region | INFERENCE | ADR freezes provider/topology, not region |

### 1.2 Capability matrix (EXTERNAL VERIFICATION — snapshot 2026-08-04)

Source: [Azure Database for PostgreSQL overview — Azure regions](https://learn.microsoft.com/en-us/azure/postgresql/overview#azure-regions) (Learn page ~2026-07-29; spike snapshot **2026-08-04**). **Must revalidate before paid apply.**

Legend from Microsoft docs: `$` = **new zone-redundant HA deployments temporarily blocked**. Existing provisioned HA servers may remain supported (per Microsoft legend). This is **not** a permanent region rejection.

| Requirement | West Europe | UK South | France Central | Sweden Central | North Europe |
|-------------|-------------|----------|----------------|----------------|--------------|
| Flexible Server present | Yes | Yes | Yes | Yes | Yes |
| Same-zone HA | Yes | Yes | Yes | Yes | Yes |
| Zone-redundant HA (documented) | Yes **`$`** | Yes | Yes | Yes | Yes **`$`** |
| New ZR HA deployable at snapshot | **No** (temp block) | **Yes** (no `$`) | **Yes** | **Yes** | **No** (temp block) |
| Geo-redundant backup option | Yes | Yes | Yes | Yes | Yes |

**PostgreSQL 16:** not a per-region column. EXTERNAL VERIFICATION via [extensions by engine](https://learn.microsoft.com/en-us/azure/postgresql/extensions/concepts-extensions-by-engine) (PG16 pivot). Subscription allow-list: **UNKNOWN**.

**PITR ≥30:** EXTERNAL VERIFICATION — retention 7–35 days ([backup-restore](https://learn.microsoft.com/en-us/azure/postgresql/backup-restore/concepts-backup-restore)); revalidate before apply.

### 1.3 Region result (not a production freeze)

| Category | Regions | Class |
|----------|---------|-------|
| **Preferred validation candidates** (snapshot) | **UK South**, **France Central**, **Sweden Central** | RECOMMENDATION |
| **Temporarily unsuitable for new ZR HA** (snapshot `$`) | **West Europe**, **North Europe** | EXTERNAL VERIFICATION |
| **Final production region** | **Not frozen** | REPOSITORY FACT (board decision deferred) |

Same-zone HA alone does **not** fully satisfy ADR “zone-redundant / Multi-AZ” without a board amendment.

---

## 2. SKU validation

### 2.1 Tier result

Source (EXTERNAL VERIFICATION, snapshot 2026-08-04; revalidate): [HA concepts](https://learn.microsoft.com/en-us/azure/postgresql/high-availability/concepts-high-availability) — ZR HA **not supported for Burstable**.

| Tier | Result | Class |
|------|--------|-------|
| **Burstable** | **FAIL / rejected** for ADR HA topology | EXTERNAL VERIFICATION + RECOMMENDATION |
| **General Purpose** | **Accepted tier family** for both clusters | RECOMMENDATION |
| **Memory Optimized** | Technically valid for HA; **not selected** without workload evidence | RECOMMENDATION |
| Exact SKU / vCPU / RAM | **UNKNOWN** | — |
| Quota | **UNKNOWN** | — |
| Approved cost ceiling | **UNKNOWN** | — |

**REPOSITORY FACT:** `AZURE-COST-MODEL.md` B2ms line is illustrative, not a frozen prod SKU (ADR).

### 2.2 Later exact-SKU selection inputs (do not invent now)

1. Connection budget (Σ Hikari × replicas + admin; PgBouncer plan)
2. Expected storage growth
3. Workload baseline (hosted-beta + future soak)
4. HA availability on that SKU in chosen region (revalidated)
5. Subscription quota
6. Approved cost ceiling
7. PostGIS / parking I/O observations from SPIKE-02

### 2.3 Workload signals (repository only)

| Signal | Value | Class |
|--------|-------|-------|
| Parking DB container ceiling | 384 MiB | REPOSITORY FACT (`runtime-sizing.md`) |
| Hikari guidance | small pools e.g. 5–10; PgBouncer if shared cluster | REPOSITORY FACT |
| Measured managed Flexible Server load | — | UNKNOWN |

---

## 3. PostGIS documentation result

| Check | Result | Class |
|-------|--------|-------|
| Parking requires PostGIS | PASS | REPOSITORY FACT |
| Compose `postgis/postgis:16-3.4` | Recorded baseline | REPOSITORY FACT |
| Azure docs: `postgis` on PG16 (e.g. 3.6.1 at snapshot) | **PASS documentation-level** | EXTERNAL VERIFICATION — [extensions](https://learn.microsoft.com/en-us/azure/postgresql/extensions/concepts-extensions-by-engine); **revalidate** |
| Parkio spatial parity | **UNKNOWN** → SPIKE-02 | — |

Documentation support ≠ runtime proof.

---

## 4. Networking validation

| Topic | Result | Class |
|-------|--------|-------|
| Private access / VNet / Private DNS documented | PASS docs | EXTERNAL VERIFICATION — revalidate |
| Live Parkio private DNS + JDBC TLS | **UNKNOWN** → SPIKE-03 | — |
| Hosted-beta Postgres not public | Aligns with private posture | REPOSITORY FACT |

---

## 5. PITR result

| Item | Value | Class |
|------|-------|-------|
| Native retention range | **7–35 days** | EXTERNAL VERIFICATION — [backup-restore](https://learn.microsoft.com/en-us/azure/postgresql/backup-restore/concepts-backup-restore); **revalidate** |
| ADR floor | **≥ 30 days** | REPOSITORY FACT (ADR / production-readiness) |
| Target configuration | **30–35 days** | RECOMMENDATION |
| Provider restore execution | **Not performed** | — |
| Measured RPO/RTO | **Not available** (production RPO/RTO still NOT APPROVED) | REPOSITORY FACT |
| Actual restore/failover evidence owner | **PP-01E** | RECOMMENDATION |

**Do not claim PITR operational proof from documentation alone.** SPIKE-01 only confirms documented configurability of retention ≥30.

---

## 6. Cost validation (estimate only)

| Item | Class |
|------|-------|
| B2ms ~USD 115/30d West Europe retail (2026-07-12) | REPOSITORY FACT |
| ADR-shaped 2× GP + ZR HA order-of-magnitude ~USD 400–900+/mo | INFERENCE — **not a quote** |
| Approved ceiling | **UNKNOWN** |

No pricing API; no account-tied quotes in Git.

---

## 7. Finalized stop-condition / capability matrix

| Item | Status | Notes |
|------|--------|-------|
| PG16 availability | **PASS** documentation-level | Revalidate before apply |
| ZR HA capability | **PASS** conditionally (region without `$` + GP/Memory Optimized) | Snapshot-dependent |
| Burstable suitability | **FAIL** | Rejected for ADR HA |
| PITR ≥30 | **PASS** documentation-level | Not operational proof |
| PostGIS availability | **PASS** documentation-level | Revalidate |
| Parkio spatial parity | **UNKNOWN** | → SPIKE-02 |
| Private networking | **UNKNOWN** | → SPIKE-03 |
| Exact SKU | **UNKNOWN** | — |
| Quota | **UNKNOWN** | — |
| Cost ceiling | **UNKNOWN** | — |
| IaC modeling | **UNKNOWN** | → PP-01B IaC implementation |
| 10 DB / 10 role support | **PASS** architecture-level | REPOSITORY FACT + community Postgres |

**SPIKE-01 completion does not authorize paid apply or production-shaped apply.**

---

## 8. ADR assumptions — validity check

| Assumption | Still valid? | Note |
|------------|--------------|------|
| Azure Flexible Server primary | Yes | Unchanged |
| PG16 + PostGIS documented | Yes / parity UNKNOWN | SPIKE-02 |
| Target region supports ZR HA | Conditional | Recheck `$` |
| Two-cluster cost fits budget | UNKNOWN | Ceiling unset |
| Private networking | Docs yes; live UNKNOWN | SPIKE-03 |
| West Europe safe for new ZR HA | **Invalid at snapshot** | Temporary `$` |

---

## 9. Remaining unknowns

1. `$` status currency for West Europe / North Europe
2. Exact GP SKU
3. Quota
4. PostGIS parity (SPIKE-02)
5. Private DNS/JDBC (SPIKE-03)
6. Approved cost ceiling
7. Measured RPO/RTO

---

## 10. Explicit non-goals (confirmed)

No PP-01C · no Terraform/ARM/Bicep · no Azure deployment · no credentials · no sandbox provisioning · no production changes · no backend/API/DTO/Flyway · no WEB-MUNI/PROD-MUNI · no SPIKE-02/03 execution in this package.

---

## 11. SPIKE-02 readiness

SPIKE-02 specification: [pp-01b-spike-02-postgis-spatial-parity.md](pp-01b-spike-02-postgis-spatial-parity.md) — **READY, NOT STARTED**.

---

## References

- ADR-PP-01A, production-readiness §3, AZURE-COST-MODEL, AZURE README
- Microsoft Learn pages listed in Dynamic provider revalidation rule (snapshot **2026-08-04**)
- Evidence index: [evidence/pp-01b-spike-01/README.md](evidence/pp-01b-spike-01/README.md)
