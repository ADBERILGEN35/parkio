# Parkio managed PostgreSQL — Terraform (PP-01B-IAC-01)

**Status:** Offline Terraform **authoring** for the accepted PP-01B architecture.

| Gate | Status |
|------|--------|
| PP-01B-R0 IaC contract | ACCEPTED WITH CONDITIONS |
| PP-01B-IAC-01 authoring | This package |
| Azure Mode B (SPIKE-02/03) | **AUTHORIZED FOR STEP 2** — G0–G7 satisfied; Step 2 not started; flags still locked |
| PP-01B package | **OPEN** (not complete) |
| PP-01C | **NOT STARTED** |
| Public production | **NO-GO** |
| Municipal production | **DISABLED** |
| `terraform apply` | **Forbidden** until Step 2+ unlock contract executes (auth recorded; flags still false) |

Canonical contract: [`docs/architecture/pp-01b-iac-contract.md`](../../docs/architecture/pp-01b-iac-contract.md)

Mode B authorization: [`docs/architecture/pp-01b-mode-b-authorization.md`](../../docs/architecture/pp-01b-mode-b-authorization.md) (**AUTHORIZED FOR STEP 2** — `PP-01B-MODE-B-20260804-01`)

## Layout

```
infra/terraform/
  modules/
    network-dns/                 # M-NET-DNS
    postgresql-flexible-server/  # reusable; instantiate core + parking
    database-roles/              # M-DB-ROLES (live gated)
    postgis-bootstrap/           # M-POSTGIS parking-only (live gated)
    policy-guards/               # M-POLICY-GUARDS
  stacks/
    sandbox/                     # only future apply-eligible stack (gated)
    staging/                     # definition; apply forbidden in IAC-01
    production/                  # guarded definition; apply impossible
  policies/service-manifest.yaml
  tests/
  docs/
```

## Tool / provider versions

| Tool | Constraint |
|------|------------|
| Terraform | `>= 1.5.0, < 2.0.0` (CI pin **1.9.8**) |
| hashicorp/azurerm | `~> 4.26` (exact via lockfile) |
| cyrilgdn/postgresql | `~> 1.25` (DB/role/PostGIS bootstrap only) |

## Offline validation (no Azure credentials)

```powershell
$env:PATH = "$(Resolve-Path .tools/terraform);$env:PATH"   # if using portable TF
cd infra/terraform
terraform fmt -check -recursive

foreach ($s in 'sandbox','staging','production') {
  Push-Location "stacks/$s"
  terraform init -backend=false
  terraform validate
  Pop-Location
}

Push-Location modules/policy-guards
terraform init -backend=false
terraform test
Pop-Location

pwsh -File tests/policy-guards.ps1
# or: bash tests/policy-guards.sh
```

Do **not** run `terraform apply`, `terraform destroy`, or Azure login for this package.

## Apply boundary

- `create_azure_resources` defaults **false**
- `enable_live_bootstrap` defaults **false**
- `apply_authorized` defaults **false** and is rejected for production
- Production `production_enablement` defaults **false** and is rejected if true
- Cost approval reference required before any future sandbox create

## Outputs (non-secret)

FQDNs (null until create), resource IDs (null until create), Private DNS ids,
10/10 service→database→role→cluster map, HA/PITR posture, public-access=false,
PostGIS parking-only, `sslmode=verify-full` handoff flag for PP-01C.

Terraform does **not** own application JDBC binding (PP-01C) or Key Vault (PP-03).
