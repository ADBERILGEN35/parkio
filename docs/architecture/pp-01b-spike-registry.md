# PP-01B Spike Registry

Authorized by [ADR-PP-01A](adr/ADR-PP-01A-managed-postgresql.md) (**ACCEPTED WITH CONDITIONS**).

These spikes are **validation only**. They do **not** authorize production apply,
public GO, or municipal production enablement. **PP-01B is not complete.**
**PP-01 remains open.**

| Global rule | Value |
|-------------|-------|
| PP-01 status | Remains **open** / public production **NO-GO** |
| Municipal production | Remains **disabled** |
| PP-01B scope | Planning, IaC authoring, and named sandbox spikes |
| Secrets in Git | Forbidden |
| Cleanup | Required for any provisioned sandbox resources |

## Status board

| Spike | Status | Artifact |
|-------|--------|----------|
| **PP-01B-SPIKE-01** | **CLOSED / ACCEPT WITH NON-BLOCKING NOTES** | [pp-01b-spike-01.md](pp-01b-spike-01.md) |
| **PP-01B-SPIKE-02** | **DOCUMENTATION COMPLETE — HOLD ON RUNTIME PARITY** (Mode A/B **not executed**) | [pp-01b-spike-02-postgis-spatial-parity.md](pp-01b-spike-02-postgis-spatial-parity.md) |
| **PP-01B-SPIKE-03** | **NOT STARTED** | (networking — registry section below) |

---

## PP-01B-SPIKE-01 — Region / SKU / PG16 / ZR HA / PITR ≥30

| Field | Definition |
|-------|------------|
| **Purpose** | Prove Azure Flexible Server can support ADR HA/PITR/PG16 constraints (catalog and/or sandbox). |
| **Closure method** | Documentation / EXTERNAL VERIFICATION catalog (2026-08-04). **No** sandbox provisioned. |
| **Closure status** | **CLOSED — ACCEPT WITH NON-BLOCKING NOTES** |
| **Key notes** | Burstable **rejected**; General Purpose **accepted** as tier family; preferred validation regions UK South / France Central / Sweden Central (**not** frozen); West Europe / North Europe `$` temp block on **new** ZR HA; exact SKU/quota/ceiling **UNKNOWN**; PITR ≥30 docs PASS (not operational proof). |
| **Owner** | Infra |
| **Report** | [`pp-01b-spike-01.md`](pp-01b-spike-01.md) |
| **Evidence index** | [`evidence/pp-01b-spike-01/README.md`](evidence/pp-01b-spike-01/README.md) |

Live portal/CLI proof remains optional future work and must **revalidate** Microsoft Learn before paid create.

---

## PP-01B-SPIKE-02 — PostGIS enablement and parking spatial parity

| Field | Definition |
|-------|------------|
| **Purpose** | Prove PostGIS on PG16 satisfies Parkio parking spatial requirements vs `16-3.4` baseline. |
| **Status** | **DOCUMENTATION COMPLETE — HOLD ON RUNTIME PARITY** |
| **Specification / inventory** | [`pp-01b-spike-02-postgis-spatial-parity.md`](pp-01b-spike-02-postgis-spatial-parity.md) |
| **Evidence index** | [`evidence/pp-01b-spike-02/README.md`](evidence/pp-01b-spike-02/README.md) |
| **Modes** | A local PostGIS container; B board-approved GP Flexible Server sandbox — **neither executed** in docs phase |
| **Owner** | Parking eng + Infra |
| **Cloud provisioning** | Mode B only, with spend approval |
| **Board approval before Mode B** | Yes |
| **Key inventory note** | Runtime district covers use **JTS**, not PostGIS `ST_Covers` SQL. Core SQL: geography Point, GiST, `ST_DWithin`, `ST_Distance`, `<->`, triggers, `ST_Equals`. |

---

## PP-01B-SPIKE-03 — Private networking / DNS / TLS connectivity

| Field | Definition |
|-------|------------|
| **Purpose** | Prove private connectivity path and TLS-required client connections. |
| **Status** | **NOT STARTED** |
| **Evidence required** | Private endpoint or VNet integration; private DNS; JDBC/`psql` TLS required; public admin blocked for prod posture. |
| **Success criteria** | Private resolve + TLS-required connect; negative check when TLS disabled. |
| **Owner** | Infra |
| **Board approval before paid create** | Yes |

---

## Execution order (recommended)

1. SPIKE-01 — **CLOSED** (catalog)
2. SPIKE-02 — **DOCUMENTATION COMPLETE**; runtime Mode A/B **HOLD / not executed**
3. SPIKE-03 — **NOT STARTED**

Do not proceed to production-shaped apply, staging cutover (PP-01D), or PP-01E/F until ADR conditions and remaining spike success criteria are met.
