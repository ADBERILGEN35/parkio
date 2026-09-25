# PP-01B Mode B — Final Status Report

| Field | Value |
|-------|-------|
| **Package** | PP-01B Mode B (SPIKE-02/03 sandbox infrastructure validation) |
| **Authorization** | `PP-01B-MODE-B-20260804-01` |
| **Repository SHA (closeout)** | `a9b30f6e10e40b2b9a30a4fda637495eea3676cd` |
| **Closeout date (UTC)** | 2026-08-05 |
| **Final status** | **Engineering Complete — Runtime Validation Deferred** |
| **NOT** | Failed · NOT production GO · NOT PP-01C started |

**Hosted-beta application was not migrated.** `app.parkio.dev` remains on the existing hosted-beta VM. Production/staging untouched.

---

## 1. Outcome summary

Mode B disposable infrastructure was authored, CI-certified, planned, and applied for network foundation plus two private PostgreSQL Flexible Servers. Probe VM creation failed solely because France Central **Total Regional vCPUs** were exhausted (**4/4**) by the hosted-beta VM. That is an Azure subscription quota limitation, not an infrastructure defect.

Disposable Mode B resources were **destroyed** at closeout. Paid Flexible Server runtime is **stopped**. Runtime proofs that require a private probe VM are **deferred** until sufficient regional compute quota exists.

---

## 2. Completed validations

| Area | Result |
|------|--------|
| PP-01B-R0 IaC contract | ACCEPTED WITH CONDITIONS |
| PP-01B-IAC-01 / IAC-02 offline authoring + Mode B unlock guards | CLOSED / PASS WITH NON-BLOCKING NOTES |
| Remote CI (TFLint / Checkov / Gitleaks) on Mode B sandbox | CERTIFIED |
| Provider registration `Microsoft.DBforPostgreSQL` | COMPLETE (retained) |
| Disposable RG + VNet + delegated PG subnet + probe subnet | Applied and later destroyed |
| Private DNS zone (VNet-integration suffix) + VNet link | Applied and later destroyed |
| Two Flexible Servers (core + parking): PG16, GP `Standard_D2s_v3`, HA Disabled, public access off, private networking | Applied Ready; later destroyed |
| Hosted-beta non-mutation during Mode B | Verified (7 resources; VM running) |
| Teardown + orphan scan | COMPLETE (RG absent; prefix orphans = 0) |
| Hosted-beta smoke after teardown | PASS (app 200; gateway health 200; JWKS 200; nearby unauth 401) |

SPIKE-02 / SPIKE-03 **Mode A** remain PASS WITH NON-BLOCKING NOTES (unchanged).

---

## 3. Successful Azure resources (provisioned during Mode B; now removed)

Under `rg-pp01b-mb-20260804-7nr2` (prefix `pp01b-mb-20260804-7nr2`):

1. Resource group  
2. Virtual network `10.251.0.0/16`  
3. Delegated PostgreSQL subnet  
4. Probe subnet  
5. Private DNS zone `*.postgres.database.azure.com`  
6. Private DNS VNet link  
7. Flexible Server **core**  
8. Flexible Server **parking**  
9. Probe NIC (created; VM never existed)

---

## 4. Failed runtime validation

| Item | Status |
|------|--------|
| Probe VM (`Standard_B1s`) | **Not created** |
| Private DNS resolution from probe | **Not executed** |
| TLS `verify-full` live path | **Not executed** |
| PostGIS live enablement / spatial runtime proof | **Not executed** |
| DB/role bootstrap | **Not executed** (by design; never unlocked) |

**Exact root cause:** Azure `OperationNotAllowed` — France Central **Total Regional vCPUs** usage/limit **4/4**. Hosted-beta `Standard_D4as_v7` consumes the four regional vCPUs. Probe needs +1 vCPU. Standard BS Family quota was already sufficient (**0/4**); regional cores were the blocker.

---

## 5. Why runtime validation is deferred

- Board decision: **no** quota workarounds, **no** hosted-beta resize, **no** region/SKU auto-switch, **no** probe retry.  
- Runtime proofs require a private probe in the disposable VNet.  
- Quota increase was not completed via CLI/API (manual Portal path only; not required for this closeout).  
- Continuing paid Flexible Servers without a path to complete proofs would only burn cost.

Marking Mode B **failed** would misstate the result: IaC and live private Flexible Server + network posture were validated; only probe-dependent runtime steps are deferred.

---

## 6. Remaining work after quota upgrade

When France Central **Total Regional vCPUs ≥ 8** (or otherwise free ≥1 vCPU for `Standard_B1s`) on an authorized sandbox subscription:

1. Re-authorize a short Mode B runtime window (new auth ref + cost ceiling + cleanup deadline).  
2. Recreate disposable network + two Flexible Servers (or equivalent disposable stack).  
3. Create private probe VM (no public IP).  
4. Execute SPIKE-03 Mode B: private DNS resolution + TLS `verify-full`.  
5. Execute SPIKE-02 Mode B: PostGIS on parking + spatial parity proof.  
6. Capture evidence; destroy all disposable resources; verify hosted-beta unchanged.

Do **not** treat this closeout as authorization to start that work.

---

## 7. Cleanup evidence (2026-08-05)

| Check | Result |
|-------|--------|
| Flexible Servers (Mode B prefix) | **0** |
| Disposable RG `rg-pp01b-mb-20260804-7nr2` | **absent** |
| Orphan NIC / disk / public IP / private DNS (prefix) | **0** |
| Hosted-beta resource count | **7** (unchanged) |
| Hosted-beta VM | **running** / Succeeded; VM Agent **Ready** |
| Terraform sandbox state | **empty** (relocked create flags) |
| Machine evidence | `deploy-artifacts/pp-01b-mode-b/finalization/` (gitignored) |

**Cost:** paid Flexible Server runtime stopped at destroy. Approximate paid window ~10 h for two GP D2s_v3 + storage/backup; projected spend remained under the USD 35 warning threshold. Exact invoice figures are subscription billing FACT outside this report.

---

## 8. Final PP-01B package posture

| Item | Status |
|------|--------|
| PP-01B Mode B engineering / IaC / teardown | **Engineering Complete** |
| SPIKE-02/03 Mode B runtime proofs | **Runtime Validation Deferred** |
| PP-01B package (ADR closure) | Remains **OPEN** until deferred Mode B runtime PASS **or** board waiver |
| PP-01 | Remains **open**; public production **NO-GO**; municipal production **disabled** |
| PP-01C | **NOT STARTED** |

---

## 9. Recommended next work package (do not start here)

**Do not start PP-01C. Do not continue Mode B runtime validation in this closeout.**

Logical next engineering options (choose via a separate authorization):

1. **Preferred when unblocked:** Subscription quota raise (France Central Total Regional vCPUs) → short re-authorized Mode B runtime window → complete deferred SPIKE-02/03 proofs → then PP-01B closure criteria.  
2. **If quota remains blocked:** Board waiver path for deferred runtime items (explicit risk acceptance) before any PP-01C connectivity work — only if product/infra accept residual UNKNOWN on live private DNS/TLS/PostGIS.  
3. **Parallel product engineering** already outside PP-01B (e.g. WEB-MUNI accepted) may continue; it does not close PP-01B.

---

## 10. Absolute non-claims

- No production/staging cutover  
- No hosted-beta migration to Flexible Server  
- No PP-01C JDBC/role wiring  
- No municipal production enablement  
- No public production GO  
