# PP-01B Mode B — Sandbox Authorization Record

| Field | Value |
|-------|-------|
| **Document** | PP-01B-MODE-B-AUTH |
| **Authorization reference** | **PP-01B-MODE-B-20260804-01** |
| **Status** | **AUTHORIZED FOR STEP 2** |
| **Date prepared** | 2026-08-04 |
| **Date approved** | 2026-08-04 (explicit user approval recorded in this document) |
| **Parent contract** | [pp-01b-iac-contract.md](pp-01b-iac-contract.md) (PP-01B-R0) |
| **IaC** | [infra/terraform/](../../infra/terraform/) (PP-01B-IAC-01 closed as offline authoring) |
| **Step 1 evidence** | `deploy-artifacts/pp-01b-mode-b/step-01/` (gitignored) |
| **Playbook** | Approved PP-01B Mode B Execution Playbook (session-authoritative; not a separate committed code package) |

**This authorization is limited to SPIKE-02 Mode B and SPIKE-03 Mode B.**

**This document does not authorize staging, production, PP-01C, municipal production, public GO, hosted-beta mutation, or persistent infrastructure.**

**PP-01B remains OPEN. PP-01C remains NOT STARTED. Public production remains NO-GO. Municipal production remains DISABLED.**

**Step 2 is authorized but not started by this document.** Do not register providers, create resources, or unlock Terraform flags until a separate Step 2 execution task begins.

Statement taxonomy: **FACT** · **EXTERNAL VERIFICATION** · **APPROVED** · **UNKNOWN**

---

## 1. Scope (G0) — APPROVED

**Authorization reference:** `PP-01B-MODE-B-20260804-01`

### Authorized

- Registration of `Microsoft.DBforPostgreSQL` (idempotent; Step 2+)
- One disposable non-production Mode B resource group
- One disposable VNet
- One PostgreSQL delegated subnet
- One separate probe subnet (non-delegated)
- One PostgreSQL Private DNS zone and VNet link
- Two temporary PostgreSQL Flexible Servers: **core** and **parking**
- Ten temporary databases
- Ten temporary application roles
- PostGIS **only** in `parkio_parking`
- One temporary private probe VM with **no public IP**
- Azure Run Command for private DNS / TLS / PostGIS verification
- Terraform **sandbox** apply/destroy only after each gate passes
- Sanitized evidence collection
- Complete teardown

### Forbidden (immutable)

- Reuse of `rg-parkio-hosted-beta`
- Mutation of `vm-parkio-hosted-beta-vnet`
- Hosted-beta default subnet reuse or delegation
- Hosted-beta VM / NIC / IP / NSG / disk mutation
- VNet peering to hosted-beta
- Staging or production apply
- Public PostgreSQL access
- Production credentials or data
- PP-01C / PP-02…PP-06
- Municipal production enablement
- Permanent / long-lived infrastructure beyond cleanup deadline
- Any resource outside the disposable Mode B resource group (except unavoidable subscription-scoped provider registration state)

### Expiration

Authorization **expires at the cleanup deadline** (G2). After expiry, all Mode B unlocks must be relocked and disposable resources must be destroyed.

**G0 status:** **SATISFIED (APPROVED)**

---

## 2. Cost ceiling (G1) — APPROVED

| Field | Approved value | Status |
|-------|----------------|--------|
| Currency | **USD** | APPROVED |
| Maximum total Mode B spend | **50.00** | APPROVED |
| Warning threshold | **35.00 USD** → stop nonessential validation; begin orderly teardown | APPROVED |
| Hard-stop threshold | **45.00 USD** → immediately destroy all paid Mode B resources | APPROVED |
| Target paid runtime | **12 hours** from first paid resource creation | APPROVED |
| Absolute maximum paid runtime | **24 hours** from first paid resource creation | APPROVED |
| Approval owner | Current Azure subscription Owner / Parkio repository operator | APPROVED |
| Approval timestamp | **2026-08-04** | APPROVED |
| Ticket / reference | **PP-01B-MODE-B-20260804-01** | APPROVED |

### Cost guards (binding)

1. **STOP before create** if projected cost for the planned topology is likely to exceed **50 USD**.
2. At **35 USD**: stop nonessential validation; begin orderly teardown; no new paid resources.
3. At **45 USD**: immediately destroy all paid Mode B resources.
4. Teardown must begin earlier when evidence is complete (prefer ≤12 hours).
5. Record estimated and actual cost where Azure Cost Management / bill data permits.
6. **Existing hosted-beta costs are excluded** from this ceiling.
7. This approval applies **only** to temporary Mode B resources.

**G1 status:** **SATISFIED (APPROVED)**

---

## 3. Cleanup owner and deadline (G2) — APPROVED

| Field | Approved value | Status |
|-------|----------------|--------|
| Primary cleanup owner | Current Azure subscription Owner and Parkio repository operator | APPROVED |
| Backup cleanup owner | **Single-operator exception** — no independent backup owner assigned | APPROVED (with mandatory compensations below) |
| Cleanup deadline | **No later than 24 hours** after first paid resource is created; **aim to complete within 12 hours** | APPROVED |
| Maximum resource lifetime | **24 hours** from first paid create (prefer ≤12 hours) | APPROVED |
| Evidence owner | Same as primary cleanup owner | APPROVED |
| Escalation | Unresolved cleanup is a **FAIL** and escalation condition | APPROVED |
| Mandatory teardown | **Yes** — even if SPIKE-02/03 validation fails | APPROVED |
| Zero disposable remaining | Disposable RG resource count must become **zero**; disposable RG must then be **deleted**; hosted-beta baseline unchanged | APPROVED |

### Single-operator cleanup exception (mandatory compensations)

Because no independent backup owner is assigned for this sandbox:

1. Cleanup automation (or equivalent scripted destroy) is **mandatory**.
2. Hard deadline of **24 hours** from first paid create is **mandatory**.
3. Resource-group isolation (all paid Mode B resources in disposable RG only) is **mandatory**.
4. End-of-run **zero-resource verification** of the disposable RG is **mandatory**.
5. Evidence must be captured **before** teardown.
6. Hosted-beta baseline must be verified unchanged after teardown.

**G2 status:** **SATISFIED (APPROVED)**

---

## 4. Temporary region and SKU (G3) — APPROVED

| Field | Approved value |
|-------|----------------|
| Primary region | **France Central** (`francecentral`) |
| Temporary server SKU | **Standard_D2s_v3** (General Purpose, non-Burstable) |
| PostgreSQL version | **16** |
| Flexible Servers | Exactly **two**: core + parking |
| Mode B HA mode | **Disabled** (cost-controlled; do **not** silently enable HA) |
| Backup retention | **30 days** if supported without violating the approved cost ceiling; otherwise document and use the minimum that keeps projected spend ≤ ceiling |
| Fallback region | **UK South** (`uksouth`) with equivalent GP D-family SKU — **only after returning HOLD and obtaining explicit approval**; do **not** auto-switch |
| Not selected | **West Europe** — Step 1 FACT: `zoneRedundantHaSupported=Disabled` |

### Interpretation

- This run proves private networking, managed PostGIS, TLS, role isolation, PITR configuration, and provider behavior.
- Zone-redundant HA support remains represented in Terraform and was visible in live regional metadata (Step 1).
- Enabling live ZR HA requires a **separate explicit cost approval** if the board later requires a live HA exercise.
- Exact SKU remains subject to live quota and create-time validation.
- Region/SKU are **not** frozen as production choices.

### STOP (G3)

- Quota unavailable for chosen SKU
- SKU cannot be created
- PostgreSQL 16 unavailable
- Private-access Model A cannot be used
- Projected cost exceeds the approved ceiling
- Forced public access
- Silent HA enablement

**G3 status:** **SATISFIED (APPROVED)** — still subject to create-time quota / availability at apply

---

## 5. Temporary naming prefix (G4) — APPROVED

| Field | Approved value |
|-------|----------------|
| Canonical prefix | **`pp01b-mb-20260804-7nr2`** |
| Expected resource group | **`rg-pp01b-mb-20260804-7nr2`** |

### Constraints

- Lowercase; Azure-name compatible
- Clearly temporary; no production naming; no personal data
- Used for RG, VNet, subnets, DNS link, server name stems, probe VM
- Flexible Server names must add a short collision-safe suffix when globally required
- Required tags must indicate: temporary; PP-01B Mode B; cleanup deadline; authorization reference `PP-01B-MODE-B-20260804-01`
- Recommended tags: `parkio_program=pp-01b`, `parkio_package=mode-b`, `cleanup_deadline=<ISO-8601>`, `auth_ref=PP-01B-MODE-B-20260804-01`, `naming_prefix=pp01b-mb-20260804-7nr2`

**Example disposable names (not created by this document):**

- RG: `rg-pp01b-mb-20260804-7nr2`
- VNet: `vnet-pp01b-mb-20260804-7nr2`
- Delegated subnet: `snet-pg-delegated`
- Probe subnet: `snet-probe`
- Servers: `psql-pp01b-mb-20260804-7nr2-core`, `psql-pp01b-mb-20260804-7nr2-parking` (adjust if globally taken)
- Probe VM: `vm-pp01b-mb-20260804-7nr2-probe`

**G4 status:** **SATISFIED (APPROVED)**

---

## 6. Documentation revalidation (G5) — ACKNOWLEDGED

Verification date: **2026-08-04**. User acknowledgement recorded **2026-08-04**.

Revalidate **again immediately before the first create/apply** for:

- Flexible Server private VNet integration
- Delegated subnet rules
- Private DNS requirements
- PostgreSQL 16 availability
- PostGIS allow-list and enablement
- PITR retention range
- TLS certificate behavior
- Public-network-disabled configuration
- France Central SKU and regional availability

| Topic | Source | Paraphrase | Applicability | Class |
|-------|--------|------------|---------------|-------|
| Private access / VNet integration | [concepts-networking-private](https://learn.microsoft.com/en-us/azure/postgresql/network/concepts-networking-private) | Flexible Server can use private access (VNet injection) with no public internet endpoint | Mode B Model A; France Central | EXTERNAL VERIFICATION |
| Delegated subnet | same | Subnet must be delegated to `Microsoft.DBforPostgreSQL/flexibleServers`; no other resource types in that subnet; min /28 | Disposable delegated subnet | EXTERNAL VERIFICATION |
| Private DNS | same | Private access requires Private DNS zone ending with `.postgres.database.azure.com`; link VNet for resolution | Disposable DNS zone/link | EXTERNAL VERIFICATION |
| Networking immutability | same | After deploy into VNet/subnet, server cannot move to another VNet/subnet | Plan create carefully | EXTERNAL VERIFICATION |
| PostgreSQL 16 + PostGIS listed | [extensions by engine](https://learn.microsoft.com/en-us/azure/postgresql/extensions/concepts-extensions-by-engine); [versions](https://learn.microsoft.com/en-us/azure/postgresql/extensions/concepts-extensions-versions) | `postgis` documented for PG16 | Parking only | EXTERNAL VERIFICATION |
| Allow-list before CREATE EXTENSION | [how-to-allow-extensions](https://learn.microsoft.com/en-us/azure/postgresql/extensions/how-to-allow-extensions) | Extension must be on `azure.extensions` allow list before create | Mode B PostGIS step | EXTERNAL VERIFICATION |
| PITR retention | [backup-restore](https://learn.microsoft.com/en-us/azure/postgresql/backup-restore/concepts-backup-restore) | Retention configurable **7–35** days | Prefer **30 days** if cost ceiling permits | EXTERNAL VERIFICATION |
| Public vs private | networking private doc | Private access chosen to avoid public endpoint | Public disabled posture | EXTERNAL VERIFICATION |
| ZR HA | Step 1 `list-skus` + HA docs | France Central metadata `zoneRedundantHaSupported=Enabled`; Mode B HA **Disabled** | Capability flag ≠ live HA exercise | FACT + EXTERNAL VERIFICATION |
| TLS / certificates | SPIKE-03 Mode A + Microsoft TLS guidance | Client target remains `sslmode=verify-full` | SPIKE-03 Mode B | EXTERNAL VERIFICATION / UNKNOWN until runtime |
| Live PostGIS create | — | Docs ≠ subscription allow-list success | SPIKE-02 Mode B | UNKNOWN until runtime |

**Documentation is not runtime proof.** Any material provider change returns **HOLD**.

**G5 status:** **SATISFIED (ACKNOWLEDGED)** — re-check mandatory immediately before apply

---

## 7. Disposable network and probe (G7) — APPROVED

### Freeze choice

**NEW DISPOSABLE RESOURCE GROUP + NEW DISPOSABLE VNET**

| Resource | Approved rule |
|----------|---------------|
| Disposable RG | `rg-pp01b-mb-20260804-7nr2` in `francecentral` |
| Disposable VNet | New VNet in that RG only |
| Delegated subnet | New empty subnet; delegation `Microsoft.DBforPostgreSQL/flexibleServers` only |
| Probe subnet | **Separate** non-delegated subnet (probe VM must **not** be in the delegated subnet) |
| Private DNS | Zone under `*.postgres.database.azure.com` + VNet link in disposable RG |
| Peering to hosted-beta | **Forbidden** |

### Explicitly rejected

- Reuse of `rg-parkio-hosted-beta`
- Mutation of `vm-parkio-hosted-beta-vnet`
- Reuse of hosted-beta `default` subnet
- Adding Flexible Server delegation to the VM subnet
- Hosted-beta VM / NIC / IP / NSG / disk mutation
- Production or staging networks

### Disposable private probe VM (approved; not provisioned by this document)

| Requirement | Rule |
|-------------|------|
| Count | Exactly **one** temporary small Linux VM |
| Placement | Separate **probe subnet** only (never PostgreSQL delegated subnet) |
| Public IP | **None** |
| Inbound public access | **None** |
| SSH exposure | **None** |
| Execution | **Azure Run Command** only |
| Tools | Minimal tools for DNS, TLS, PostgreSQL, and spatial checks |
| Teardown | Removed during Mode B cleanup |

**G7 status:** **SATISFIED (APPROVED)**

---

## 8. Apply-unlock contract

**No flag changes in this authorization finalization task.**

Later execution order (separate Step 2+ tasks):

1. Revalidate G0–G7.
2. Register `Microsoft.DBforPostgreSQL` idempotently.
3. Relock / stop on registration failure.
4. Temporarily enable `apply_authorized` for **sandbox only**.
5. Create disposable RG / network / DNS / probe path.
6. Validate network.
7. Enable `create_azure_resources`.
8. Create exactly two private Flexible Servers.
9. Validate public access disabled.
10. Enable live bootstrap.
11. Create 10 DBs / 10 roles.
12. Enable PostGIS only on `parkio_parking`.
13. Run SPIKE-02/03 evidence.
14. Relock bootstrap / create / apply flags.
15. Capture evidence.
16. Destroy all disposable resources.
17. Verify hosted-beta unchanged.
18. Record board closure separately.

| Flag | May become true only when | Relock |
|------|---------------------------|--------|
| `apply_authorized` | G0–G7 **SATISFIED** (this document) + Step 2 execution begins | Set false at cleanup / abort |
| `create_network` | Disposable network path only | Destroy network; set false |
| `create_azure_resources` | Provider registered; network preflight pass; cost ceiling active | Destroy servers; set false |
| `enable_live_bootstrap` | Two private servers pass posture checks | Disable after bootstrap / destroy |

- Staging / production: **`apply_authorized` and `create_azure_resources` remain permanently false**
- Unlocks are temporary and Mode B-only
- Every unlock has a corresponding relock step

---

## 9. Permitted mutations (after this authorization; in later Step 2+ tasks)

- Register `Microsoft.DBforPostgreSQL`
- Create/destroy resources **only** in disposable Mode B RG / VNet
- Disposable delegated subnet, probe subnet, Private DNS zone/link
- Two temporary Flexible Servers (core + parking)
- Ten temporary databases and ten temporary application roles
- PostGIS only in `parkio_parking`
- One temporary private probe VM (no public IP; Run Command)
- Sandbox Terraform apply/destroy under unlock contract
- Write gitignored Mode B evidence under `deploy-artifacts/pp-01b-mode-b/`

## 10. Forbidden mutations

- Hosted-beta any change (RG, VNet, subnet, VM, NIC, IP, NSG, disk)
- VNet peering to hosted-beta
- Staging/production apply
- PP-01C wiring
- Secrets in Git
- Persistent infra beyond deadline
- Public Flexible Server endpoints
- PostGIS on core
- Probe VM in PostgreSQL delegated subnet
- Production credentials or data
- Municipal production enablement

## 11. Stop conditions

- Cost ≥ **35 USD** (orderly teardown) or ≥ **45 USD** (immediate destroy)
- Projected spend likely to exceed **50 USD** before create
- Provider registration failure
- Model A (delegated subnet + Private DNS) impossible
- Public access forced
- Quota / SKU / PG16 unavailable
- PostGIS cannot enable on parking
- TLS / `verify-full` proof fails materially
- Cleanup deadline missed or disposable RG not zeroed
- Any production / municipal enablement attempted
- Material Microsoft provider documentation change → **HOLD**

## 12. Evidence paths

| Path | Role |
|------|------|
| `deploy-artifacts/pp-01b-mode-b/step-01/` | Step 1 baseline (COMPLETE) |
| `deploy-artifacts/pp-01b-mode-b/` | Later Mode B steps (gitignored) |
| This document | Governance / gates — reference **PP-01B-MODE-B-20260804-01** |

## 13. Teardown obligation

1. Capture evidence before destroy.
2. Destroy all disposable Mode B resources (servers, probe VM, DNS, VNet, RG contents).
3. Verify disposable RG resource count = **0**, then delete the RG.
4. Verify hosted-beta baseline unchanged.
5. Relock all sandbox unlock flags.
6. Unresolved cleanup = **FAIL**.

## 14. PP-01B closure criteria (unchanged)

PP-01B CLOSED only when authoring complete **and** SPIKE-02+03 Mode B PASS (or board waive) **and** ADR §20 metrics **and** cost ceiling respected **and** cleanup evidence complete.

**This authorization does not close PP-01B.**

---

## 15. G0–G7 matrix (final)

| ID | Status |
|----|--------|
| G0 | **SATISFIED (APPROVED)** — `PP-01B-MODE-B-20260804-01` |
| G1 | **SATISFIED (APPROVED)** — USD 50 / warn 35 / hard-stop 45 |
| G2 | **SATISFIED (APPROVED)** — single-operator exception + mandatory compensations |
| G3 | **SATISFIED (APPROVED)** — France Central / `Standard_D2s_v3` / PG16 / HA Disabled |
| G4 | **SATISFIED (APPROVED)** — `pp01b-mb-20260804-7nr2` |
| G5 | **SATISFIED (ACKNOWLEDGED)** — revalidate before apply |
| G6 | **SATISFIED** (Step 1 FACT — active non-production subscription) |
| G7 | **SATISFIED (APPROVED)** — disposable RG/VNet + separate probe subnet/VM |

**Overall decision:** **AUTHORIZED FOR STEP 2**

Step 2 itself is **not started** by recording this approval.
