# infra

Infrastructure-as-code for deploying Parkio.

## Managed PostgreSQL (PP-01B)

**Authoritative IaC contract:**
[`docs/architecture/pp-01b-iac-contract.md`](../docs/architecture/pp-01b-iac-contract.md)
(**PP-01B-R0 — ACCEPTED WITH CONDITIONS**).

| Frozen by PP-01B-R0 | Value |
|--------------------|-------|
| Tool | **Terraform** (`infra/terraform/`) |
| Network model | Flexible Server **private access** (VNet integration / delegated subnet) + Private DNS |
| Topology | 2 clusters · 10 DBs · 10 roles (ADR-PP-01A) |
| Authoring | Authorized offline |
| Apply | Sandbox only with board/cost/Mode B gates — **not** in this docs package |
| Production apply | **Forbidden** in PP-01B |

Provider and topology remain frozen in
[`ADR-PP-01A`](../docs/architecture/adr/ADR-PP-01A-managed-postgresql.md).

**PP-01 remains open.** Public production **NO-GO**. Municipal production **disabled**.
**PP-01C not authorized.** Azure Mode B **HOLD**.
This directory still has **no** Terraform implementation files until a future authoring package; R0 freezes the contract only.

### Planned layout (do not invent alternatives)

See PP-01B-R0 §6:

- `terraform/modules/` — reusable modules
- `terraform/stacks/{sandbox,staging,production}/` — directory-isolated stacks
- Generated plans/state — gitignored

Suggested non-Postgres overlays (`kubernetes/`, etc.) remain future work and are
**out of PP-01B Postgres IaC scope**.
