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
| IaC contract | [`pp-01b-iac-contract.md`](pp-01b-iac-contract.md) (**PP-01B-R0 ACCEPTED WITH CONDITIONS**) |
| Secrets in Git | Forbidden |
| Cleanup | Required for any provisioned sandbox resources |

## Status board

| Spike / package | Status | Artifact |
|-----------------|--------|----------|
| **PP-01B-SPIKE-01** | **CLOSED / ACCEPT WITH NON-BLOCKING NOTES** | [pp-01b-spike-01.md](pp-01b-spike-01.md) |
| **PP-01B-SPIKE-02** | **MODE A COMPLETE — PASS WITH NON-BLOCKING NOTES** (Mode B **not executed**) | [pp-01b-spike-02-postgis-spatial-parity.md](pp-01b-spike-02-postgis-spatial-parity.md) |
| **PP-01B-SPIKE-03** | **MODE A COMPLETE**; Mode B **HOLD — NOT EXECUTED** | [pp-01b-spike-03-private-network-dns-tls.md](pp-01b-spike-03-private-network-dns-tls.md) |
| **PP-01B-R0 IaC contract** | **ACCEPTED WITH CONDITIONS** | [pp-01b-iac-contract.md](pp-01b-iac-contract.md) |
| **PP-01B-IAC-01** | **CLOSED** as offline authoring (PP-01B still **OPEN**) | [infra/terraform/README.md](../../infra/terraform/README.md) |
| **PP-01B Mode B auth** | **AUTHORIZED FOR STEP 2** (`PP-01B-MODE-B-20260804-01`; G0–G7 satisfied; Step 2 not started) | [pp-01b-mode-b-authorization.md](pp-01b-mode-b-authorization.md) |

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
| **Status** | **MODE A COMPLETE — PASS WITH NON-BLOCKING NOTES**; Mode B pending |
| **Specification / inventory** | [`pp-01b-spike-02-postgis-spatial-parity.md`](pp-01b-spike-02-postgis-spatial-parity.md) |
| **Evidence index** | [`evidence/pp-01b-spike-02/README.md`](evidence/pp-01b-spike-02/README.md) |
| **Modes** | **A executed** (local 3.4.3 vs 3.6.1); B board-approved GP Flexible Server — **not executed** |
| **Owner** | Parking eng + Infra |
| **Cloud provisioning** | Mode B only, with spend approval |
| **Board approval before Mode B** | Yes |
| **Mode A key result** | Flyway tip **33** both; spatial matrix / KNN / GiST / triggers PASS; required PostGIS/municipal ITs PASS |
| **Mode B readiness** | **READY WITH CONDITIONS** |
| **Key inventory note** | Runtime district covers use **JTS**, not PostGIS `ST_Covers` SQL. Core SQL: geography Point, GiST, `ST_DWithin`, `ST_Distance`, `<->`, triggers, `ST_Equals`. |

---

## PP-01B-SPIKE-03 — Private networking / DNS / TLS connectivity

| Field | Definition |
|-------|------------|
| **Purpose** | Prove private connectivity path and TLS-required client connections. |
| **Status** | **MODE A COMPLETE — PASS WITH NON-BLOCKING NOTES**; Mode B **HOLD — NOT EXECUTED** |
| **Specification / report** | [`pp-01b-spike-03-private-network-dns-tls.md`](pp-01b-spike-03-private-network-dns-tls.md) |
| **Evidence index** | [`evidence/pp-01b-spike-03/README.md`](evidence/pp-01b-spike-03/README.md) |
| **Modes** | **A executed**; B attempt **blocked** (no Azure CLI/credentials; cost ceiling/SKU UNKNOWN) |
| **Owner** | Infra |
| **Cloud provisioning** | Mode B only, with spend approval — **none this attempt** |
| **Board approval before Mode B** | Yes (prompt present; tooling still missing) |
| **Mode A key result** | `verify-full` positive/negative matrix PASS; hostname mismatch FAIL; role isolation PASS; Hikari recovery PASS; production private-only guard PASS; 0 required skips |
| **Mode B readiness** | Was **READY WITH CONDITIONS**; execution **HOLD** until Azure tooling + sandbox subscription + cost/SKU envelope |
| **Key inventory note** | Repo defaults lack `sslmode`; Flyway shares runtime datasource; migrator/runtime split deferred to PP-01C/PP-03 |
| **TLS policy** | Production target `sslmode=verify-full` |
| **Azure provisioned** | **No** |

---

## Execution order (recommended)

1. SPIKE-01 — **CLOSED** (catalog)
2. SPIKE-02 — **MODE A COMPLETE**; Mode B **not executed**
3. SPIKE-03 — **MODE A COMPLETE**; Mode B **HOLD — NOT EXECUTED**
4. PP-01B-R0 IaC contract — **ACCEPTED WITH CONDITIONS**
5. PP-01B-IAC-01 — offline Terraform authoring **CLOSED**
6. Mode B Step 1 — read-only baseline **COMPLETE** (Go/No-Go A **NO-GO**)
7. Mode B authorization record — [pp-01b-mode-b-authorization.md](pp-01b-mode-b-authorization.md) (**AUTHORIZED FOR STEP 2** — `PP-01B-MODE-B-20260804-01`; Step 2 not started)

Do not proceed to production-shaped apply, staging cutover (PP-01D), or PP-01E/F until ADR conditions and remaining spike success criteria are met.
Do not start PP-01C until PP-01B is complete per ADR §20 and the IaC contract.
**PP-01B remains OPEN** until Mode B (or waiver) + cleanup close the package.
