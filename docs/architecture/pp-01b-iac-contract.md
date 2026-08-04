# PP-01B-R0 — Managed PostgreSQL IaC Contract

| Field | Value |
|-------|-------|
| **Package** | **PP-01B-R0** |
| **Status** | **ACCEPTED WITH CONDITIONS** |
| **Date** | 2026-08-04 |
| **Authority** | Independent Parkio Infrastructure Architecture Board |
| **Parent** | [ADR-PP-01A](adr/ADR-PP-01A-managed-postgresql.md) (**ACCEPTED WITH CONDITIONS**, frozen) |
| **Spikes** | [pp-01b-spike-registry.md](pp-01b-spike-registry.md) |
| **Connectivity** | [pp-01b-spike-03-private-network-dns-tls.md](pp-01b-spike-03-private-network-dns-tls.md) |

**This document is the single authoritative implementation contract for PP-01B IaC authoring.**

It does **not** generate Terraform/Bicep/ARM/Pulumi code. It does **not** authorize Azure apply, production GO, municipal enablement, or PP-01C.

| Closure distinction | Meaning |
|---------------------|---------|
| **IaC AUTHORING COMPLETE** | Future package meets §11 offline acceptance criteria |
| **PP-01B COMPLETE** | Authoring complete **and** sandbox spikes Mode B pass (or board waive) **and** ADR §20 metrics met |
| **PP-01 CLOSED** | Not this package; later PP-01F + PP-02…06 for public GO |

**PP-01B remains open. Public production remains NO-GO. Municipal production remains disabled. PP-01C is not authorized. Azure Mode B remains HOLD.**

Statement taxonomy: **FACT** · **EXTERNAL VERIFICATION** · **INFERENCE** · **RECOMMENDATION** · **UNKNOWN**

---

## 1. Frozen architecture (inherited — do not reopen)

| Dimension | Frozen value | Source |
|-----------|--------------|--------|
| Primary provider | Azure Database for PostgreSQL Flexible Server | ADR-PP-01A §9 |
| Approved alternate | AWS RDS for PostgreSQL | ADR-PP-01A §10 |
| Clusters | **2** — core + parking | ADR §12 |
| Core DBs | 9 non-spatial | ADR §12–§13 |
| Parking | 1 PostgreSQL + PostGIS | ADR §12–§13 |
| Inventory | 10 databases / 10 application login roles; zero cross-DB grants | ADR §13 |
| PostgreSQL | 16 family | ADR §12 |
| HA | Zone-redundant / Multi-AZ **selectable** | ADR §12 |
| PITR prod floor | ≥30 days | ADR §12 |
| Network | Private only | ADR §12 |
| Client TLS | `sslmode=verify-full` | SPIKE-03 |

---

## 2. IaC tool decision

### Decision

**Primary tool: Terraform**

| Item | Frozen value |
|------|--------------|
| Tool | **Terraform** |
| Version family | **Terraform ≥ 1.5, < 2.0** (pin exact patch in lockfile when authored) |
| Azure provider | **hashicorp/azurerm** — pin exact version in required_providers + `.terraform.lock.hcl` |
| PostgreSQL provider (bootstrap) | **cyrilgdn/postgresql** (or successor equivalent) — pin exact version; used only for DB/role/extension bootstrap |
| Directory root | `infra/terraform/` |
| Plan-only CI | Static validate/fmt/lint/tflint/tfsec(or equiv)/secret-scan — **no** `terraform apply` in PP-01B CI |
| State | Remote backend required before any shared apply; local state never committed |

### Evaluation record

| Criterion | Finding | Class |
|-----------|---------|-------|
| Repository convention | `infra/README.md` suggests `terraform/` for cloud resources; production-readiness maturity table names Terraform for managed resources | **FACT** |
| Existing `.tf` / `.bicep` / Pulumi / ARM in repo | None | **FACT** |
| Competing Bicep convention | None | **FACT** |
| Flexible Server + VNet/Private DNS coverage | Widely supported via azurerm; revalidate provider docs at authoring | **EXTERNAL VERIFICATION** (revalidate) |
| AWS alternate portability | Multi-provider Terraform aligns with ADR alternate without rewriting control plane language | **INFERENCE** |
| Plan-time validation / modules / pinning / drift | Native Terraform workflow | **RECOMMENDATION** |
| Bicep | Azure-native; weaker multi-cloud story for ADR alternate; no repo convention | **RECOMMENDATION** rejected as primary |

**Rejected primary:** Bicep (no repository convention; weaker AWS-alternate path).
**Rejected:** Pulumi, ARM templates, CloudFormation as primary PP-01B tools.

**HOLD trigger:** If azurerm cannot model a mandatory ADR control at authoring time → escalate; do not silently drop the control.

---

## 3. Azure private-network model decision

### Decision

**Primary model A — Flexible Server private access (VNet integration / delegated subnet) + Private DNS zone**

| Property | Contract |
|----------|----------|
| Networking mode | **Private access (VNet injection)** |
| Subnet | Delegated subnet for Flexible Server |
| DNS | Private DNS zone ending in `.postgres.database.azure.com` (per Azure docs); linked to client VNets as required |
| Public network access | **Disabled** (no public endpoint in this posture) |
| Addressing | Applications use **FQDN only** — never hard-coded private IPs |
| Create-time | Networking mode chosen at create; treat as immutable for PP-01B (migration to PE is out of PP-01B scope) |

### Rejected / fallback

**Model B — Public-access-capable server + Private Endpoint with public access disabled** is **rejected as primary** for PP-01B.

| Reason | Class |
|--------|-------|
| SPIKE-03 EXTERNAL VERIFICATION centers private access + Private DNS as the private-connectivity design under review | **FACT** (SPIKE-03 §4) |
| Model B introduces a public-access-capable create path that must be continuously guarded against accidental public enable | **INFERENCE** |
| Microsoft documents PE as an alternative to VNet integration; PE not supported on VNet-integrated servers | **EXTERNAL VERIFICATION** ([private link concepts](https://learn.microsoft.com/en-us/azure/postgresql/network/concepts-networking-private-link), [private access](https://learn.microsoft.com/en-us/azure/postgresql/network/concepts-networking-private)) — **revalidate before Mode B** |
| Fallback only if Mode B proves Model A incompatible with selected region/SKU/HA — requires **PP-01B-R0 minor amendment** + board note; not dual-optional for implementers | **RECOMMENDATION** |

Do **not** freeze VNet names, CIDRs, resource groups, or subscription IDs.

---

## 4. Database and role provisioning decision

### Decision

**Split model (canonical):**

| Layer | Owns | Does not own |
|-------|------|--------------|
| **Terraform control plane** (`azurerm`) | Flexible Servers (core + parking), private network integration, Private DNS association, HA/PITR/storage/SKU inputs, public-access disabled, tags, deletion protection knobs | Schema / Flyway / app wiring / secret manager |
| **Bootstrap component** (Terraform `postgresql` provider and/or versioned idempotent SQL invoked by a controlled provisioning step **owned by the IaC package**) | Create 10 databases; create 10 application login roles; ownership; `CONNECT` grants; `REVOKE` cross-DB / PUBLIC as required; enable PostGIS **on parking only** | Application datasource configuration |
| **Flyway** (existing per-service migrations) | Schema objects inside each database | Creating the empty database or login role inventory |
| **PP-01C** | App `SPRING_DATASOURCE_*` wiring; JDBC `verify-full`; runtime DNS; connection proof | Creating Azure servers |
| **PP-03** | Secret storage/injection/rotation for admin + role passwords; trust material distribution | Inventing topology |

### Identity boundary

| Identity | Contract |
|----------|----------|
| Administrator (server admin) | Bootstrap-only; password via secret reference; never in Git |
| Application roles | Exactly the §10 manifest names; one role per DB; no shared identity across services |
| Migrator vs runtime | **Remain shared temporarily** (repository FACT: Flyway uses same datasource as runtime — SPIKE-03). Split is **PP-01C / PP-03**; IaC must not invent silent dual-role app wiring |
| PostGIS | `CREATE EXTENSION` (or Azure allow-list + create) **only** in `parkio_parking` |

### Operational rules

- Bootstrap must be **idempotent**.
- Passwords: generated or injected via secret references; **never** literal values in `.tfvars` committed to Git.
- Failure: fail closed; no partial “shared role” remediation.
- Rollback: destroy/recreate only in **sandbox** with cleanup deadline; no production path in PP-01B.
- Do **not** alter existing Flyway migration contents.

---

## 5. Module boundaries

Reuse one Flexible Server module instantiated twice (**core**, **parking**) unless Mode B proves a hard PostGIS/network divergence — default: **shared module, two instances**.

| Module | Responsibility | Owns | Must not own | Mode B dependency |
|--------|----------------|------|--------------|-------------------|
| **M-NET-DNS** | VNet integration inputs, delegated subnet reference, Private DNS zone link/association contracts | Network identity inputs/outputs; DNS zone id/name | Server compute SKU; DB roles; app deploy | Apply requires SPIKE-03 Mode B |
| **M-CORE** | Core Flexible Server instance | Core server resource; HA/PITR/SKU/storage; public access disabled | PostGIS; parking DB | Apply: SPIKE-01 SKU/region; SPIKE-03 net |
| **M-PARKING** | Parking Flexible Server instance (same module type as core) | Parking server resource; HA/PITR/SKU/storage; public access disabled | Core DBs | Apply: SPIKE-02 PostGIS + SPIKE-03 |
| **M-DB-ROLES** | 10 DB + 10 role bootstrap | Databases, roles, grants, revokes | Flyway schema; app config | Apply needs live server |
| **M-POSTGIS** | PostGIS on parking only | Extension enablement + parking-only guard | Enabling PostGIS on core | Requires SPIKE-02 Mode B for managed claim |
| **M-POLICY-GUARDS** | Offline/policy assertions | Validation rules, CI guards, forbidden combo rejects | Live Azure mutations | Authorable offline |
| **M-OUTPUTS** | Non-secret handoff outputs | FQDNs, IDs, manifest map, posture flags | Passwords, JDBC URLs with creds | Offline structure; live values after apply |

**Dependencies:** M-CORE / M-PARKING → M-NET-DNS; M-DB-ROLES → both servers; M-POSTGIS → M-PARKING + M-DB-ROLES; M-OUTPUTS → all; M-POLICY-GUARDS → all modules (static).

**Test strategy:** offline `terraform validate` / fmt / tflint / policy tests / secret scan; no apply in authoring CI.

---

## 6. Directory and environment layout

```
infra/
  README.md                          # points to this contract
  terraform/
    modules/
      flexible_server/               # shared server module (core + parking instances)
      net_dns/                       # M-NET-DNS
      db_roles/                      # M-DB-ROLES (+ PostGIS hook or sibling)
      policy/                        # M-POLICY-GUARDS helpers/tests fixtures
    stacks/
      sandbox/                       # only stack eligible for future authorized apply
      staging/                       # structure only; NO PP-01B apply authorization
      production/                    # structure only; NO PP-01B apply authorization
    policies/                        # OPA/Conftest or equivalent policy-as-code (future)
    docs/                            # module READMEs
  evidence/                          # gitignored runtime plans/logs (see .gitignore)
```

| Rule | Contract |
|------|----------|
| Environment isolation | **Directory-per-environment stacks** under `stacks/{sandbox,staging,production}` — **not** shared CLI workspaces as the sole isolation mechanism |
| Composition | Root module per stack + `*.tfvars.example` (no secrets); optional `tfvars` local gitignored |
| Production apply | **Forbidden** in PP-01B (no workflow, no docs that instruct prod apply) |
| Generated plans / `.terraform/` / `*.tfstate*` | **Gitignored** |

---

## 7. Typed input contract

Canonical input names (snake_case). Exact SKU/region remain **configurable** — no fabricated production defaults.

| Name | Type | Req | Default policy | Validation | Secret? | Known at |
|------|------|-----|----------------|------------|---------|----------|
| `environment` | string enum `sandbox`\|`staging`\|`production` | yes | none | PP-01B apply only if `sandbox` | no | authoring |
| `location` | string | yes | none | non-empty; revalidate ZR `$` at apply | no | apply |
| `naming_prefix` | string | yes | none | lowercase alnum/hyphen; temporary for sandbox | no | apply |
| `postgres_version` | string | yes | `"16"` | major 16 family only | no | authoring |
| `core_sku` | string | yes | none | **reject Burstable** when `ha_mode` requests ZR/Multi-AZ | no | apply |
| `parking_sku` | string | yes | none | same as core_sku rule; GP family | no | apply |
| `storage_mb` | number | yes | none | > 0 | no | apply |
| `backup_retention_days` | number | yes | none | ≥30 when `environment=production` **or** `production_shaped=true` | no | apply |
| `ha_mode` | string enum `Disabled`\|`ZoneRedundant`\|`SameZone` (names per provider) | yes | none | production topology expects ZoneRedundant selectable | no | apply |
| `az_preference` | string optional | no | null | provider-valid AZ if set | no | apply |
| `vnet_id` | string | yes (sandbox apply) | none | Azure resource ID format | no | apply |
| `delegated_subnet_id` | string | yes (sandbox apply) | none | subnet delegated for Flexible Server | no | apply |
| `private_dns_zone_id` | string | yes (sandbox apply) | none | zone for `postgres.database.azure.com` | no | apply |
| `administrator_login` | string | yes | none | not an application role name | no | apply |
| `administrator_password_secret_ref` | string | yes | none | secret reference URI/name — **not** password value | **ref only** | apply |
| `app_role_secret_refs` | map(string→string) | yes | none | exactly 10 keys matching §10 roles | **ref only** | apply |
| `tags` | map(string) | no | `{}` | — | no | authoring |
| `cost_ceiling_ref` | string | yes before paid HA apply | none | non-empty when paid apply requested | no | org |
| `public_network_access` | bool | yes | `false` | **must be false** in private posture | no | authoring |
| `deletion_protection` | bool | yes | sandbox `false`; staging/prod prefer `true` | — | no | authoring |
| `production_shaped` | bool | yes | `false` | if true, enforce PITR≥30, private, HA selectable rules | no | authoring |
| `apply_authorized` | bool | yes | `false` | must be false for production; sandbox apply requires board flags | no | org |
| `postgis_enabled_parking` | bool | yes | `true` | must be true for parking; core must not enable PostGIS | no | authoring |
| `diagnostics_enabled` | bool | no | `false` | final sink owned by PP-05 | no | later |

### Mandatory reject rules (M-POLICY-GUARDS)

Reject when any hold:

- `environment=production` **and** `apply_authorized=true` in PP-01B
- Burstable SKU **and** HA ZoneRedundant/Multi-AZ requested
- `backup_retention_days < 30` **and** (`environment=production` **or** `production_shaped=true`)
- `public_network_access=true` in private posture
- Missing/weak TLS server posture (public plaintext path)
- Parking database/role targeted at core server
- Duplicate or shared application role identity across services
- Paid HA apply without `cost_ceiling_ref`
- Literal passwords in committed parameter files
- `postgis` enabled on core

---

## 8. Output contract

| Name | Type | Secret? | Consumer | Stability |
|------|------|---------|----------|-----------|
| `core_fqdn` | string | no | PP-01C | stable after apply |
| `parking_fqdn` | string | no | PP-01C | stable after apply |
| `core_server_id` | string | no | ops | stable |
| `parking_server_id` | string | no | ops | stable |
| `private_dns_zone_id` | string | no | net/ops | stable |
| `service_db_role_cluster_map` | object/map | no | PP-01C | stable (ADR inventory) |
| `postgres_version` | string | no | PP-01C | major family |
| `ha_mode_core` / `ha_mode_parking` | string | no | ops | as applied |
| `backup_retention_days_core` / `_parking` | number | no | ops | as applied |
| `public_network_access_state` | bool | no | audit | must be false |
| `postgis_enabled_parking` | bool | no | SPIKE-02/ops | must be true after bootstrap |
| `environment` | string | no | all | as configured |

### Forbidden outputs

Passwords; credential-bearing JDBC URLs; private keys; access tokens; raw connection strings with embedded secrets.

### PP-01C handoff outputs (minimum)

`core_fqdn`, `parking_fqdn`, `service_db_role_cluster_map`, `public_network_access_state=false`, expected client TLS mode `verify-full`, DNS-hostname contract, secret-reference **interface** (not values), PostGIS-on-parking flag.

---

## 9. Service / database / role manifest

Canonical inventory (**REPOSITORY FACT** — ADR §13; Compose defaults):

| service | database | application_role | cluster |
|---------|----------|------------------|---------|
| auth-service | parkio_auth | parkio_auth | core |
| gateway-service | parkio_gateway | parkio_gateway | core |
| user-service | parkio_user | parkio_user | core |
| media-service | parkio_media | parkio_media | core |
| gamification-service | parkio_gamification | parkio_gamification | core |
| notification-service | parkio_notification | parkio_notification | core |
| moderation-service | parkio_moderation | parkio_moderation | core |
| analytics-service | parkio_analytics | parkio_analytics | core |
| ai-validation-service | parkio_aivalidation | parkio_aivalidation | core |
| parking-service | parkio_parking | parkio_parking | parking |

Count: **10** services / **10** databases / **10** roles. Parking alone on parking cluster.

Machine-readable form for future IaC (illustrative YAML — not credentials):

```yaml
manifest_version: 1
clusters: [core, parking]
entries:
  - {service: auth-service, database: parkio_auth, role: parkio_auth, cluster: core}
  - {service: gateway-service, database: parkio_gateway, role: parkio_gateway, cluster: core}
  - {service: user-service, database: parkio_user, role: parkio_user, cluster: core}
  - {service: media-service, database: parkio_media, role: parkio_media, cluster: core}
  - {service: gamification-service, database: parkio_gamification, role: parkio_gamification, cluster: core}
  - {service: notification-service, database: parkio_notification, role: parkio_notification, cluster: core}
  - {service: moderation-service, database: parkio_moderation, role: parkio_moderation, cluster: core}
  - {service: analytics-service, database: parkio_analytics, role: parkio_analytics, cluster: core}
  - {service: ai-validation-service, database: parkio_aivalidation, role: parkio_aivalidation, cluster: core}
  - {service: parking-service, database: parkio_parking, role: parkio_parking, cluster: parking}
```

---

## 10. Authoring-only acceptance criteria

**IaC AUTHORING COMPLETE** when all hold (**no Azure apply required**):

1. `infra/terraform/` layout matches §6
2. Modules exist and `terraform fmt` + `terraform validate` pass (with pinned providers; no cloud creds required for validate where supported)
3. Provider versions pinned; lockfile committed
4. Lint (tflint) + security static analysis pass
5. Offline policy tests encode: 2 servers, 10/10 manifest, parking-only PostGIS, HA/PITR/private/public-disabled controls
6. Variable validation rejects §7 forbidden combinations (unit/policy tests)
7. Outputs schema contains no secret-typed attributes
8. Plan with sanitized placeholder tfvars is generatable offline **or** explicitly documented as requiring provider refresh (classified separately — must not be required for AUTHORING COMPLETE)
9. No production apply path/workflow
10. Secret scan clean
11. Docs match implementation; ADR freeze not violated
12. This contract remains the authority

**PP-01B COMPLETE** additionally requires Mode B (SPIKE-02 + SPIKE-03) pass or board waive, cost ceiling for any paid apply, cleanup evidence — **not** claimed by authoring alone.

---

## 11. CI and policy contract (future — do not create workflows in R0)

| Check | Failure behavior |
|-------|------------------|
| `terraform fmt -check` | fail job; no auto-apply retry |
| `terraform validate` | fail |
| tflint | fail |
| tfsec / Checkov / equivalent | fail on high/critical |
| secret scan | fail |
| policy: public access must be false | fail |
| policy: two-cluster topology | fail |
| policy: exactly 10 DB/role entries | fail |
| policy: PostGIS parking-only | fail |
| policy: PITR/HA selectable representation | fail |
| policy: production apply prohibited | fail |
| provider lockfile present | fail |
| plan artifact sanitation (no secrets uploaded) | fail |

**No hidden retries** that could mask flaky policy failures. Credentials must not be injected into authoring CI for apply.

---

## 12. State and backend contract

| Rule | Owner |
|------|-------|
| Never commit `*.tfstate*`, `.terraform/`, crash logs | PP-01B authoring |
| Remote state required before any **shared** apply | Platform / release governance |
| State encryption + locking enabled | Platform |
| Environment isolation (separate state keys per stack) | PP-01B layout |
| Least-privilege state access | Platform + PP-03 for secrets adjacency |
| State may contain sensitive metadata — treat as secret-adjacent | PP-03 / ops |
| Backend bootstrap | Platform governance (not invented ad hoc in app repos without approval) |
| No production backend/apply in PP-01B | This contract |
| Sandbox state cleanup after Mode B | Infra + board cleanup deadline |

---

## 13. Cost and apply boundary

| Activity | Azure spend | Credentials | Authorization |
|----------|-------------|-------------|---------------|
| Author modules/docs | No | No | PP-01B-R0 |
| fmt / validate / lint / policy | No | No | CI |
| Provider refresh plan | Maybe metadata calls | Possibly | Classify separately; not silent |
| Sandbox apply | Yes | Non-prod sandbox only | Cost ceiling + spend approval + prefix + cleanup deadline + evidence path + board approval |
| Staging/production apply | — | — | **Forbidden in PP-01B** |

---

## 14. Mode B dependency matrix

| Item | Offline author | Offline test | Azure plan | Azure apply | SPIKE-02 Mode B | SPIKE-03 Mode B | Board |
|------|----------------|--------------|------------|-------------|-----------------|-----------------|-------|
| Contract (this doc) | yes | n/a | no | no | no | no | accepted |
| M-NET-DNS code | yes | static | optional | yes | no | **yes** | spend |
| M-CORE / M-PARKING | yes | static | optional | yes | SKU/HA | **yes** | spend |
| M-DB-ROLES | yes | static | no | yes | no | yes (reachability) | — |
| M-POSTGIS | yes | static | no | yes | **yes** | yes | — |
| M-POLICY-GUARDS | yes | yes | no | no | no | no | — |
| Managed runtime claims | no | no | no | yes | **yes** | **yes** | — |

Mode B HOLD **does not** block repository authoring. It **blocks** managed-runtime PASS claims and apply authorization.

---

## 15. PP-01C handoff boundary

### PP-01B delivers

- `core_fqdn`, `parking_fqdn` (non-secret)
- service/database/role/cluster manifest (§9)
- expected TLS mode: `verify-full`
- DNS hostname contract (no IP literals)
- secret-reference interface for role passwords
- network reachability assumption: private VNet path to Flexible Server
- role provisioning status (created by bootstrap)
- certificate trust requirement: Azure Database for PostgreSQL CA (distribution = PP-03)

### PP-01C owns

- application datasource wiring
- runtime DNS resolution
- JDBC `verify-full` binding
- role-per-DB connection proof
- migrator/runtime application behavior

Do not pull PP-01C work into PP-01B IaC.

---

## 16. Stop conditions

| Condition | Action |
|-----------|--------|
| azurerm cannot model mandatory ADR control | **HOLD** / escalate |
| Networking mode cannot stay Model A after Mode B evidence | **HOLD**; Model B only via R0 amendment |
| DB/role ownership unclear in implementation PR | **HOLD** |
| Module boundaries violated (e.g. app deploy in DB module) | **HOLD** |
| Secret committed | **FAIL** governance |
| Production apply path introduced in PP-01B | **FAIL** authorization |
| PP-01C/PP-03 scope pulled into IaC | **HOLD** |
| Azure resource required to finish **this R0 docs package** | N/A — must not |

**REJECT** only if frozen ADR impossible under verified tool/network constraints (not claimed here).

---

## 17. Decision freeze (PP-01B-R0)

After acceptance, freeze:

- IaC tool = Terraform (+ version policy)
- Directory layout §6
- Azure private-network **Model A**
- Split DB/role provisioning §4
- Module boundaries §5
- Typed inputs §7
- Outputs §8
- CI contract §11
- State contract §12
- PP-01C handoff §15

| Change type | Path |
|-------------|------|
| Implementation detail (provider pin, module file names) | **Minor PP-01B-R0 amendment** |
| Provider / topology / security posture | **Major ADR-PP-01A amendment** |

---

## 18. Board decision

**ACCEPT WITH CONDITIONS**

| Condition | Status |
|-----------|--------|
| Exact region/SKU | Remain inputs — not fabricated | **CONDITION** |
| Azure Mode B | HOLD until sandbox env | **CONDITION** |
| Cost ceiling | Required before paid apply | **CONDITION** |
| Offline IaC authoring may start without inventing architecture/security posture | **AUTHORIZED** by this contract |

Implementers must **not** choose alternate tool, alternate primary network model, alternate inventory, or weak TLS/public access.

---

## 19. References

- `docs/architecture/adr/ADR-PP-01A-managed-postgresql.md`
- `docs/architecture/pp-01b-spike-registry.md`
- `docs/architecture/pp-01b-spike-01.md`
- `docs/architecture/pp-01b-spike-02-postgis-spatial-parity.md`
- `docs/architecture/pp-01b-spike-03-private-network-dns-tls.md`
- `infra/README.md`
- Microsoft Learn — Flexible Server [private access](https://learn.microsoft.com/en-us/azure/postgresql/network/concepts-networking-private) · [private link](https://learn.microsoft.com/en-us/azure/postgresql/network/concepts-networking-private-link) (revalidate before Mode B)
