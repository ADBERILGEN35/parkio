# PP-01B Spike Registry

Authorized by [ADR-PP-01A](adr/ADR-PP-01A-managed-postgresql.md) (**ACCEPTED WITH CONDITIONS**).

These spikes are **validation only**. They are **not** executed by PP-01A-R2.
They do **not** authorize production apply, public GO, or municipal production enablement.

| Global rule | Value |
|-------------|-------|
| PP-01 status | Remains **open** / public production **NO-GO** |
| PP-01B scope | Planning, IaC authoring, and these named sandbox spikes |
| Secrets in Git | Forbidden |
| Cleanup | Required for any provisioned sandbox resources |

---

## PP-01B-SPIKE-01 — Region / SKU / PG16 / ZR HA / PITR ≥30

| Field | Definition |
|-------|------------|
| **Purpose** | Prove a concrete Azure Flexible Server region + SKU supports PostgreSQL 16, zone-redundant (or Multi-AZ equivalent) automatic failover, and backup retention configurable to **≥ 30 days**. |
| **Evidence required** | Provider portal/CLI/API output showing: engine version PG16; HA mode selectable as zone-redundant (or documented auto-failover equivalent); backup retention settable to ≥30 (≤35 native per EXTERNAL VERIFICATION as of 2026-08-04). Attach redacted screenshots or CLI transcripts **outside** Git if they contain account IDs; store summary in evidence path agreed by ops. |
| **Success criteria** | Named region + SKU recorded; PG16 confirmed; HA mode confirmed; retention ≥30 configurable. |
| **Stop condition** | Any of PG16, ZR/Multi-AZ HA, or retention ≥30 unavailable for all candidate regions/SKUs considered. |
| **Fallback** | Evaluate AWS RDS alternate (ADR §10) or board amendment for region change. |
| **Owner** | Infra |
| **Cloud provisioning required?** | Optional minimal sandbox server **or** dry documentation from provider APIs; prefer cheapest non-prod proof. |
| **Cost may be incurred?** | Yes (sandbox SKU) — require explicit spend authorization before create. |
| **Board approval before execution?** | Yes for any paid create; no for read-only catalog research. |
| **Artifacts expected** | Spike report: region, SKU, HA mode, retention setting, date, links to EXTERNAL VERIFICATION re-check. |
| **Cleanup requirement** | Destroy any created sandbox server within 7 days of spike close unless board extends. |

---

## PP-01B-SPIKE-02 — PostGIS enablement and parking spatial parity

| Field | Definition |
|-------|------------|
| **Purpose** | Prove `postgis` (and required related extensions) enable on the chosen PG16 Flexible Server and that parking spatial behavior is acceptable vs the Compose baseline (`postgis/postgis:16-3.4` family). |
| **Evidence required** | `CREATE EXTENSION postgis` success; version recorded; representative geography/`ST_DWithin` (or equivalent) query; note on GiST index / location trigger expectations aligned with restore-drill assertions. |
| **Success criteria** | Extension available; spatial query passes; compatibility note signed by parking eng (3.6.x acceptable only if parity proven). |
| **Stop condition** | Extension unavailable, or spatial parity fails with no acceptable remediation. |
| **Fallback** | Keep two-cluster isolate for parking; evaluate Crunchy/Aiven contingency via major ADR reopen; or AWS RDS PostGIS. |
| **Owner** | Parking eng + Infra |
| **Cloud provisioning required?** | Yes (sandbox Flexible Server with PostGIS) unless identical SKU already from SPIKE-01. |
| **Cost may be incurred?** | Yes — explicit spend authorization. |
| **Board approval before execution?** | Yes for paid create. |
| **Artifacts expected** | Extension version; SQL transcript (sanitized); parity checklist vs parking PostGIS IT expectations. |
| **Cleanup requirement** | Destroy sandbox unless reused for SPIKE-03 under the same authorization window. |

---

## PP-01B-SPIKE-03 — Private networking / DNS / TLS connectivity

| Field | Definition |
|-------|------------|
| **Purpose** | Prove private connectivity path (VNet integration / private DNS or equivalent) and TLS-required client connections from a non-production compute path. |
| **Evidence required** | Private endpoint or VNet integration proof; DNS resolves privately; JDBC/`psql` connection with TLS required; public admin endpoint disabled or blocked for the intended prod posture. |
| **Success criteria** | Private resolve + TLS-required connect from approved non-prod runner; failure when TLS disabled (negative check). |
| **Stop condition** | Private networking unavailable in target design, or TLS cannot be mandated for app clients. |
| **Fallback** | HOLD private apply; redesign network; or AWS VPC alternate path via ADR amendment. |
| **Owner** | Infra |
| **Cloud provisioning required?** | Likely (private endpoint / DNS zone / sandbox compute). |
| **Cost may be incurred?** | Yes — explicit spend authorization. |
| **Board approval before execution?** | Yes. |
| **Artifacts expected** | Network diagram snippet; connection command (secrets redacted); TLS mode used. |
| **Cleanup requirement** | Tear down private endpoints, DNS records, and sandbox compute with the database sandbox. |

---

## Execution order (recommended)

1. SPIKE-01 (catalog / minimal HA+PITR proof)  
2. SPIKE-02 (PostGIS on chosen SKU)  
3. SPIKE-03 (private + TLS) — may overlap with SPIKE-02 sandbox if authorized  

Do not proceed to production-shaped apply, staging cutover (PP-01D), or PP-01E/F until ADR conditions and spike success criteria are met.
