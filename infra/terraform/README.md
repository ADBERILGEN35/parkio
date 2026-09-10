# Parkio managed PostgreSQL — Terraform (PP-01B-IAC-01 + IAC-02)

**Status:** Offline Terraform for the accepted PP-01B architecture, with Mode B sandbox enablement hardening (IAC-02).

| Gate | Status |
|------|--------|
| PP-01B-R0 IaC contract | ACCEPTED WITH CONDITIONS |
| PP-01B-IAC-01 authoring | CLOSED (offline) |
| PP-01B-IAC-02 Mode B enablement | This package — clears Step 3A HOLD blockers |
| Azure Mode B (SPIKE-02/03) | Step 1–2 COMPLETE; Step 3A HOLD cleared in IaC; **Step 3B not started** |
| PP-01B package | **OPEN** (not complete) |
| PP-01C | **NOT STARTED** |
| Public production | **NO-GO** |
| Municipal production | **DISABLED** |
| `terraform apply` | **Forbidden** until Step 3B+ unlock with ephemeral inputs |

Canonical contract: [`docs/architecture/pp-01b-iac-contract.md`](../../docs/architecture/pp-01b-iac-contract.md)

Mode B authorization: [`docs/architecture/pp-01b-mode-b-authorization.md`](../../docs/architecture/pp-01b-mode-b-authorization.md) (`PP-01B-MODE-B-20260804-01`)

Cleanup manifest: [`docs/mode-b-cleanup-manifest.md`](docs/mode-b-cleanup-manifest.md)

## Layout

```
infra/terraform/
  modules/
    disposable-rg/               # Terraform-owned Mode B RG (sandbox)
    network-dns/                 # VNet + delegated PG subnet + probe subnet + VNet-integration DNS
    mode-b-probe/                # private Linux probe (no public IP / no SSH)
    postgresql-flexible-server/  # reusable; instantiate core + parking
    database-roles/              # M-DB-ROLES (live gated)
    postgis-bootstrap/           # M-POSTGIS parking-only (live gated)
    policy-guards/               # M-POLICY-GUARDS
  stacks/
    sandbox/                     # only stack eligible for Mode B unlock
    staging/                     # permanently locked
    production/                  # permanently locked
```

## Networking contract (frozen)

- Model: Flexible Server **VNet integration** (delegated subnet) — **not** Private Endpoint
- Private DNS zone must end with `.postgres.database.azure.com`
- **Forbidden:** `privatelink.postgres.database.azure.com`
- Mode B CIDRs: `10.251.0.0/16`, PG `10.251.1.0/24`, probe `10.251.2.0/24`

## Apply-unlock (sandbox only)

Defaults remain locked (`apply_authorized=false`, creates false). Temporary unlock requires:

- `authorization_reference=PP-01B-MODE-B-20260804-01`
- cost approval reference
- cleanup deadline
- sandbox environment

Staging/production cannot unlock. Use ephemeral gitignored inputs only — never commit unlock=true.

## Offline validation

```
terraform fmt -check -recursive
terraform init -backend=false
terraform validate
terraform test   # modules/*/tests
```

No `terraform apply` / `destroy` in CI or IAC-02.
