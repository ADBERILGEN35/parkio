# infra

Infrastructure-as-code for deploying Parkio.

## Managed PostgreSQL (PP-01B)

**Authoritative IaC contract:**
[`docs/architecture/pp-01b-iac-contract.md`](../docs/architecture/pp-01b-iac-contract.md)
(**PP-01B-R0 — ACCEPTED WITH CONDITIONS**).

**Implementation root:** [`terraform/`](terraform/) — see [`terraform/README.md`](terraform/README.md).

| Frozen by PP-01B-R0 | Value |
|--------------------|-------|
| Tool | **Terraform** (`infra/terraform/`) |
| Network model | Flexible Server **private access** (VNet integration / delegated subnet) + Private DNS |
| Topology | 2 clusters · 10 DBs · 10 roles (ADR-PP-01A) |
| Authoring | **PP-01B-IAC-01** offline package (this tree) |
| Apply | **Forbidden** in IAC-01; sandbox apply later only with board/cost/Mode B gates |
| Production apply | **Structurally forbidden** in PP-01B |

Provider and topology remain frozen in
[`ADR-PP-01A`](../docs/architecture/adr/ADR-PP-01A-managed-postgresql.md).

**PP-01 remains open.** Public production **NO-GO**. Municipal production **disabled**.
**PP-01C not started.** Azure Mode B **HOLD**.
**PP-01B package remains OPEN** — IaC authoring ≠ PP-01B complete.

### Layout

- `terraform/modules/` — reusable modules (network-dns, flexible-server ×2, db-roles, postgis, policy-guards)
- `terraform/stacks/{sandbox,staging,production}/` — directory-isolated stacks (no Terraform workspaces)
- Generated plans/state — gitignored
- Offline CI: `.github/workflows/pp-01b-terraform-offline.yml` (no apply)
