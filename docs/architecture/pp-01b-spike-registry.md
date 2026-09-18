# PP-01B Spike Registry

Authorized by [ADR-PP-01A](adr/ADR-PP-01A-managed-postgresql.md) (**ACCEPTED WITH CONDITIONS**).

These spikes are **validation only**. They do **not** authorize production apply,
public GO, or municipal production enablement. **PP-01B Mode B engineering is
complete with runtime validation deferred** (Azure regional vCPU quota); ADR
closure criteria are not yet fully met. **PP-01 remains open.**

See [pp-01b-mode-b-final-report.md](pp-01b-mode-b-final-report.md).

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
| **PP-01B-SPIKE-02** | **MODE A COMPLETE — PASS WITH NON-BLOCKING NOTES**; Mode B infra partial (**FS Ready validated**); PostGIS runtime **DEFERRED** (quota) | [pp-01b-spike-02-postgis-spatial-parity.md](pp-01b-spike-02-postgis-spatial-parity.md) |
| **PP-01B-SPIKE-03** | **MODE A COMPLETE**; Mode B network+FS **validated**; probe DNS/TLS runtime **DEFERRED** (quota) | [pp-01b-spike-03-private-network-dns-tls.md](pp-01b-spike-03-private-network-dns-tls.md) |
| **PP-01B-R0 IaC contract** | **ACCEPTED WITH CONDITIONS** | [pp-01b-iac-contract.md](pp-01b-iac-contract.md) |
| **PP-01B-IAC-01** | **CLOSED** as offline authoring (PP-01B still **OPEN**) | [infra/terraform/README.md](../../infra/terraform/README.md) |
| **PP-01B Mode B auth** | **CLOSED OUT** (`PP-01B-MODE-B-20260804-01`) — Engineering Complete; Runtime Validation Deferred; disposable RG destroyed | [pp-01b-mode-b-authorization.md](pp-01b-mode-b-authorization.md) · [final report](pp-01b-mode-b-final-report.md) |
| **PP-01B-IAC-02** | Mode B sandbox enablement hardening (**PASS WITH NON-BLOCKING NOTES**) | [infra/terraform/README.md](../../infra/terraform/README.md) |
| **PP-01B Mode B final** | **Engineering Complete — Runtime Validation Deferred** (France Central Total Regional vCPUs 4/4) | [pp-01b-mode-b-final-report.md](pp-01b-mode-b-final-report.md) |

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
| **Status** | **MODE A COMPLETE — PASS WITH NON-BLOCKING NOTES**; Mode B PostGIS runtime **DEFERRED** (quota); see [final report](pp-01b-mode-b-final-report.md) |
| **Specification / inventory** | [`pp-01b-spike-02-postgis-spatial-parity.md`](pp-01b-spike-02-postgis-spatial-parity.md) |
| **Evidence index** | [`evidence/pp-01b-spike-02/README.md`](evidence/pp-01b-spike-02/README.md) |
| **Modes** | **A executed** (local 3.4.3 vs 3.6.1); B board-approved GP Flexible Server — **not executed** |
| **Owner** | Parking eng + Infra |
| **Cloud provisioning** | Mode B disposable FS applied then destroyed; live PostGIS **not** executed |
| **Board approval before Mode B** | Yes (`PP-01B-MODE-B-20260804-01`) |
| **Mode A key result** | Flyway tip **33** both; spatial matrix / KNN / GiST / triggers PASS; required PostGIS/municipal ITs PASS |
| **Mode B readiness** | Infra path validated; runtime proof **deferred** until regional vCPU quota allows probe |
| **Key inventory note** | Runtime district covers use **JTS**, not PostGIS `ST_Covers` SQL. Core SQL: geography Point, GiST, `ST_DWithin`, `ST_Distance`, `<->`, triggers, `ST_Equals`. |

---

## PP-01B-SPIKE-03 — Private networking / DNS / TLS connectivity

| Field | Definition |
|-------|------------|
| **Purpose** | Prove private connectivity path and TLS-required client connections. |
| **Status** | **MODE A COMPLETE**; Mode B network+private FS **validated**; probe DNS/TLS **DEFERRED** (quota) |
| **Specification / report** | [`pp-01b-spike-03-private-network-dns-tls.md`](pp-01b-spike-03-private-network-dns-tls.md) |
| **Evidence index** | [`evidence/pp-01b-spike-03/README.md`](evidence/pp-01b-spike-03/README.md) |
| **Modes** | **A executed**; B infra applied (RG/VNet/DNS/FS); probe blocked by regional vCPU 4/4; stack destroyed |
| **Owner** | Infra |
| **Cloud provisioning** | Mode B disposable stack destroyed at closeout — **zero** lingering Mode B resources |
| **Board approval before Mode B** | Yes (`PP-01B-MODE-B-20260804-01`) |
| **Mode A key result** | `verify-full` positive/negative matrix PASS; hostname mismatch FAIL; role isolation PASS; Hikari recovery PASS; production private-only guard PASS; 0 required skips |
| **Mode B readiness** | Engineering complete; live private DNS/TLS from probe **deferred** |
| **Key inventory note** | Repo defaults lack `sslmode`; Flyway shares runtime datasource; migrator/runtime split deferred to PP-01C/PP-03 |
| **TLS policy** | Production target `sslmode=verify-full` |
| **Azure provisioned** | Temporary Mode B only; **destroyed** 2026-08-05 |

---

## Execution order (recommended)

1. SPIKE-01 — **CLOSED** (catalog)
2. SPIKE-02 — **MODE A COMPLETE**; Mode B **not executed**
3. SPIKE-03 — **MODE A COMPLETE**; Mode B **HOLD — NOT EXECUTED**
4. PP-01B-R0 IaC contract — **ACCEPTED WITH CONDITIONS**
5. PP-01B-IAC-01 — offline Terraform authoring **CLOSED**
6. Mode B Step 1 — read-only baseline **COMPLETE**
7. Mode B authorization — `PP-01B-MODE-B-20260804-01` — **CLOSED OUT** ([final report](pp-01b-mode-b-final-report.md))
8. Mode B Step 2 provider registration — **COMPLETE** (retained)
9. Mode B Steps 3B–4B — network + Flexible Servers applied; probe **blocked** by regional vCPU quota
10. Mode B finalization — disposable RG **destroyed**; hosted-beta smoke **PASS**
11. Deferred — probe private DNS / TLS `verify-full` / PostGIS runtime after quota upgrade (separate auth)

Do not proceed to production-shaped apply, staging cutover (PP-01D), or PP-01E/F until ADR conditions and remaining spike success criteria are met.
Do not start PP-01C until PP-01B is complete per ADR §20 and the IaC contract (or an explicit board waiver of deferred runtime items).
**PP-01B remains OPEN** for ADR closure until deferred runtime PASS or waiver; **Mode B engineering is complete**.
