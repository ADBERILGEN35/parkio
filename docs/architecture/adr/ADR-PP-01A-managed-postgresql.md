# ADR-PP-01A — Managed PostgreSQL provider and topology

| Field | Value |
|-------|-------|
| **Status** | **ACCEPTED WITH CONDITIONS** |
| **Date** | 2026-08-04 |
| **Deciders** | Parkio PP-01A Architecture Review Board (PP-01A + PP-01A-R1) |
| **Parent program** | PP-01 (managed HA Postgres with PITR) |
| **Related** | [production-readiness.md](../production-readiness.md) §3 · [KNOWN-ISSUES.md](../../releases/KNOWN-ISSUES.md) PP-01 · [PP-01B spike registry](../pp-01b-spike-registry.md) |

## Board posture (authoritative)

| Statement | Status |
|-----------|--------|
| PP-01A is **closed** as an architecture decision | Yes |
| PP-01 (implementation / managed HA+PITR proof) remains **open** | Yes — **NO GO** for public production |
| PP-01B sandbox planning/spikes are **conditionally authorized** | Yes — see §23–§24 |
| Public production | Remains **NO-GO** |
| Production municipal discovery | Remains **disabled** |

**Statement taxonomy used below:** FACT · EXTERNAL VERIFICATION · INFERENCE · RECOMMENDATION · UNKNOWN

---

## 1. Title

Managed PostgreSQL provider selection and production topology for Parkio (PP-01A).

## 2. Status

**ACCEPTED WITH CONDITIONS**

## 3. Date

2026-08-04 (PP-01A package + PP-01A-R1 hardening closure).

## 4. Deciders

Independent PP-01A Architecture Review Board. Product/engineering owners remain responsible for RPO/RTO numeric approval and PP-01B spike execution.

## 5. Context

**FACT:** Public production is blocked while PP-01 (“No managed HA Postgres with PITR”) remains open (`docs/releases/KNOWN-ISSUES.md`).

**FACT:** Hosted-beta runs service-owned Postgres/PostGIS containers with logical `pg_dump` backups and CI restore drills — accepted for closed beta, not equivalent to managed PITR + Multi-AZ failover (`docs/architecture/production-readiness.md` §3; `docker/README.md`).

**FACT:** `infra/` is a placeholder; no managed Postgres IaC exists (`infra/README.md`; HB-03).

**FACT:** No prior ADR selected a managed Postgres provider or frozen production topology.

PP-01A decides provider and topology only. It does not provision resources, close PP-01, or authorize public GO.

## 6. Repository facts

| Fact | Evidence |
|------|----------|
| Compose inventory is **10** Postgres databases (incl. gateway); parking uses `postgis/postgis:16-3.4` | `docker/docker-compose.yml` |
| Backup status matrix lists **10** DBs | `docs/operations/backup-restore.md` |
| PostGIS required for parking-service; other services plain Postgres 16 | `docs/ai-context/05-database-guidelines.md`; production-readiness §3 |
| Isolation = separate DB + separate credentials; **not** necessarily separate servers | production-readiness §3 |
| Recommended production shape: **1–2** managed clusters | production-readiness §3 |
| Provider PITR retention floors: ≥7d beta / ≥30d prod | production-readiness §3 |
| Multi-AZ / standby with automatic failover **required** for public production | production-readiness §3 |
| Logical dumps ≠ PITR | production-readiness RPO text; docker README |
| Production RPO/RTO numeric targets **NOT APPROVED** | `docs/operations/backup-restore.md` |
| Azure Database for PostgreSQL named as managed PostGIS path (extension validation required) | `docs/startup/12-microsoft-founders-hub-package.md` |
| Flexible Server appears in Azure cost model (illustrative B2ms; not a frozen prod SKU) | `docs/azure/AZURE-COST-MODEL.md` |
| AWS RDS + PITR named as Activate path | `docs/startup/11-aws-activate-package.md` |
| PostGIS-capable examples named: AWS RDS, Aiven, Supabase, Crunchy, Neon | production-readiness §3 |
| Stale “9 logical databases” wording existed in production-readiness §3 | Corrected to **10** by this ADR closure; see inventory §13 |

### External verification (not repository fact)

Verification date: **2026-08-04**. Claims may change; re-verify in PP-01B spikes.

| Claim | Source | Class |
|-------|--------|-------|
| Flexible Server supports `postgis` on PostgreSQL 16 (documented version **3.6.1**) | [Microsoft Learn — extensions by engine](https://learn.microsoft.com/en-us/azure/postgresql/extensions/concepts-extensions-by-engine) | EXTERNAL VERIFICATION |
| Automated backups / PITR; retention configurable **7–35 days** | [Microsoft Learn — backup-restore](https://learn.microsoft.com/en-us/azure/postgresql/backup-restore/concepts-backup-restore) | EXTERNAL VERIFICATION |
| Zone-redundant HA with automatic failover on supported tiers/regions | [Microsoft Learn — high availability](https://learn.microsoft.com/en-us/azure/postgresql/high-availability/concepts-high-availability) | EXTERNAL VERIFICATION / region-SKU UNKNOWN until SPIKE-01 |

App spatial parity against managed PostGIS (e.g. 3.6.x vs pinned `16-3.4`) is **UNKNOWN** until PP-01B-SPIKE-02.

## 7. Decision drivers

Weights total **100%**. Mandatory drivers failing with evidence of *unsatisfiability* require HOLD/REJECT (not applicable at acceptance — unverified items are gated).

| ID | Driver | Wt | Mandatory? | Repository basis |
|----|--------|----|------------|------------------|
| D1 | Provider PITR capability | 14 | Yes | PP-01; readiness §3 |
| D2 | Zone-redundant / Multi-AZ automatic failover | 14 | Yes | readiness §3 HA |
| D3 | PostGIS compatibility | 12 | Yes | parking PostGIS |
| D4 | PostgreSQL 16 compatibility | 8 | Yes | compose / ITs |
| D5 | Repository / cloud alignment | 10 | Comparative | Azure beta; Founders Hub; Activate |
| D6 | Private networking | 8 | Yes (prod posture) | hosted-beta private data plane |
| D7 | 10 logical DBs + isolated roles | 8 | Yes | DB-per-service; compose |
| D8 | Portability / exit | 6 | Comparative | Azure exit / `pg_dump` |
| D9 | Cost predictability | 6 | Comparative | cost-aware 1–2 clusters |
| D10 | Credit-program alignment | 5 | Comparative | Founders Hub; Activate |
| D11 | IaC support | 4 | Comparative | future `infra/` |
| D12 | Operational maturity | 3 | Comparative | provider lists |
| D13 | Migration complexity | 2 | Comparative | Option C migration target |

## 8. Weighted provider scorecard

Scores are board judgments combining FACT / EXTERNAL VERIFICATION / INFERENCE. They are **not** runtime certifications.

| Provider | Approx. weighted score | Notes |
|----------|----------------------:|-------|
| Azure Database for PostgreSQL Flexible Server | ~88 | Primary — cloud alignment + named path |
| AWS RDS for PostgreSQL | ~90 | Strong HA/PITR language; approved **alternate** |
| Aiven for PostgreSQL | ~74 | Named PostGIS example; contingency |
| Crunchy (incl. Bridge productization) | ~72 | “Crunchy” named; Bridge not specifically RS |

Neon / Supabase: named for PostGIS only — not primary. GCP Cloud SQL / Hyperscale / Timescale: not in Postgres strategy — out of scope.

## 9. Provider decision

**Primary provider:** Azure Database for PostgreSQL **Flexible Server**.

**Class:** RECOMMENDATION frozen by board acceptance.

## 10. Approved alternate

**AWS RDS for PostgreSQL** with PostGIS, Multi-AZ, and PITR — if landing zone becomes AWS or Azure gates fail.

## 11. Architecture decision tree

```
Public production needs managed PITR + auto failover?  → Yes [FACT]
Shared schemas / cross-DB grants?                      → Forbidden [FACT]
Separate servers mandatory?                            → No [FACT]

Cluster count:
  (A) 1 shared cluster × 10 DBs   → Allowed (1–2) but not default
  (B) 2 clusters (core + parking) → DEFAULT [RECOMMENDATION]
  (C) 10 managed instances        → Rejected (cost-aware compromise) [FACT]
```

Two clusters are **preferred**, not mandatory. One cluster requires the §14 exception contract.

## 12. Frozen topology

| Dimension | Frozen default |
|-----------|----------------|
| Clusters | **2** — `parkio-pg-core`, `parkio-pg-parking` |
| Core | 9 non-spatial logical databases |
| Parking | 1 PostgreSQL + PostGIS database |
| Roles | 10 DB-scoped login roles; **zero** cross-database grants |
| PostgreSQL | **16** |
| PostGIS | **3.4-compatible family** (managed may ship newer, e.g. 3.6.x — prove parity) |
| PITR retention (prod) | **≥ 30 days** |
| HA | Zone-redundant / Multi-AZ **automatic failover** (both clusters for production topology) |
| Network | **Private** connectivity |
| App→DB | **TLS** required |
| Pooling | Small Hikari pools; PgBouncer optional on shared core |

## 13. Database and role ownership map

Canonical inventory (**10** databases / **10** roles):

| Logical DB | Role | Owning service | Default cluster |
|------------|------|----------------|-----------------|
| `parkio_auth` | `parkio_auth` | auth-service | core |
| `parkio_gateway` | `parkio_gateway` | gateway-service | core |
| `parkio_user` | `parkio_user` | user-service | core |
| `parkio_media` | `parkio_media` | media-service | core |
| `parkio_gamification` | `parkio_gamification` | gamification-service | core |
| `parkio_notification` | `parkio_notification` | notification-service | core |
| `parkio_moderation` | `parkio_moderation` | moderation-service | core |
| `parkio_analytics` | `parkio_analytics` | analytics-service | core |
| `parkio_aivalidation` | `parkio_aivalidation` | ai-validation-service | core |
| `parkio_parking` | `parkio_parking` | parking-service | **parking** |

## 14. Single-cluster exception contract

Without an approved exception, **two clusters remain the frozen default**.

One cluster may be used in PP-01B only if **all** hold:

1. PostGIS enablement succeeds on chosen SKU/version  
2. Representative spatial / GiST / trigger parity proof passes  
3. Connection budget proven (Σ pools ≤ limit, or PgBouncer plan)  
4. Parking spatial load accepted on shared instance  
5. PITR ≥30 and HA mode unchanged vs two-cluster plan  
6. Shared failure domain explicitly accepted by board  
7. Still 10 DBs / 10 roles / zero cross-DB grants  
8. Numbered ADR amendment + board approval before apply  

Fallback: revert to two-cluster default.

## 15. Mandatory production requirements

Derived from repository evidence (not invented):

- Managed provider automated backups with **PITR**  
- Production PITR retention **≥ 30 days**  
- Standby/replica with **automatic failover** (Multi-AZ / zone-redundant)  
- PostGIS for parking  
- Database-per-service + distinct credentials; no cross-DB grants  
- Prefer 1–2 managed clusters (cost-aware)  
- Align managed Postgres/PostGIS with proven PG16 family  
- Production RPO/RTO must eventually be **APPROVED** (currently NOT APPROVED)  
- Public GO additionally requires PP-02…PP-06  

## 16. Assumptions register

| ID | Assumption | Blocks PP-01A? | Blocks PP-01B apply? | Validation |
|----|------------|----------------|----------------------|------------|
| A1 | Azure remains production landing zone | No | Yes if false (use alternate) | Board / Q4 resolved as Azure primary |
| A2 | Flexible Server PG16 + usable PostGIS | No | Yes | PP-01B-SPIKE-02 |
| A3 | Target region supports ZR HA | No | Yes (prod topology) | PP-01B-SPIKE-01 |
| A4 | Two-cluster cost fits budget | No | Yes if ceiling breached | Cost estimate |
| A5 | 10 logical DBs operationally supported | No | Yes | Sandbox |
| A6 | Distinct creds later via PP-03 | No | No for sandbox | PP-03 |
| A7 | Private networking available | No | Yes for private apply | PP-01B-SPIKE-03 |
| A8 | Conn limits OK or PgBouncer mitigates | No | Conditional | Budget math |
| A9 | PITR retention configurable ≥30 | No | Yes | SPIKE-01 (EV: ≤35 native) |
| A10 | `pg_dump` remains exit mechanism | No | No | Exit docs |

## 17. Open-question disposition

| Q | Disposition |
|---|-------------|
| Q1 Exact Flexible Server SKU/region for PG16 + PostGIS + ZR HA + PITR≥30 | **DEFERRED TO PP-01B** — SPIKE-01 / SPIKE-02 |
| Q2 Numeric production RPO/RTO | **DEFERRED** — blocks PP-01D/E/F, not PP-01A |
| Q3 Analytics third instance | **REJECTED AS OUT OF SCOPE** for PP-01A (optional later amendment) |
| Q4 Azure vs AWS landing zone | **RESOLVED** — Azure primary; AWS alternate |

## 18. RPO/RTO governance

| Rule | Decision |
|------|----------|
| Retention floor | ≥30 days prod (**FACT**) |
| Numeric RPO/RTO | Remain **NOT APPROVED** until Product + Eng lead approve |
| PP-01A acceptance without numeric RPO/RTO | Allowed |
| PP-01B sandbox without numeric RPO/RTO | Allowed |
| PP-01D/E/F without numeric RPO/RTO approval | **Blocked** |

Candidate targets are recommendations only and must not be treated as approved SLOs.

## 19. Risk register (summary)

High-severity themes: region lacks ZR HA; PostGIS version mismatch; connection exhaustion on shared core; private networking failure; PITR restore exceeds eventual RTO; 9-vs-10 documentation drift (mitigated by this ADR). Full board register lives in PP-01A-R1; owners: Infra / Parking eng / Product / Board as applicable.

## 20. Success metrics by PP-01 package

| Package | Success metrics (selected) |
|---------|----------------------------|
| **PP-01A** | This ADR ACCEPTED WITH CONDITIONS; 10 DB map frozen; no cloud create |
| **PP-01B** | IaC authored; sandbox spikes pass; PostGIS + PITR≥30 option + HA selectable; TLS/private as code; no prod traffic; no secrets in Git |
| **PP-01C** | Non-prod connectivity; private DNS; JDBC TLS; role-per-DB auth |
| **PP-01D** | Staging data cutover; Flyway validate |
| **PP-01E** | Failover + PITR drills; measured RPO/RTO; RPO/RTO APPROVED |
| **PP-01F** | Production cutover evidence; PP-01 closure candidate (still subject to PP-02…06 for public GO) |

## 21. Decision freeze

After acceptance, the following change only via numbered ADR amendment + impact analysis + board approval + revalidation:

- Primary provider and approved alternate  
- Default cluster count (**2**)  
- Database inventory and ownership map  
- PostGIS placement on parking cluster  
- Isolation model  
- PITR retention floor  
- HA requirement  
- Private networking and TLS postures  
- No production enable / no municipal production enable  

| Change | Classification |
|--------|----------------|
| SKU within same provider | Minor amendment |
| Region (HA still OK) | Minor + re-run SPIKE-01 |
| One-cluster exception | Minor + §14 |
| Analytics → third cluster | Minor |
| Parking into shared core without §14 | Major reopen |
| Azure → AWS as primary | New provider decision / major reopen |
| PostgreSQL major version change | Major reopen |
| Dropping PITR≥30 or HA | Major reopen |

### Decision-freeze guard

**FACT:** The repository has no existing architecture-validation / markdown-ADR assertion framework suitable for an executable freeze guard without inventing a new test harness.

**RECOMMENDATION:** Governance remains **document-based** via this ADR + cross-references. An executable guard may be added later only if a docs-test framework is introduced for other ADRs as well.

## 22. PP-01A exit criteria

Met by acceptance of this ADR:

- [x] ADR content complete  
- [x] Evidence reviewed  
- [x] Weighted scorecard recorded  
- [x] Provider + topology selected  
- [x] Mandatory requirements acknowledged  
- [x] Assumptions registered  
- [x] Open questions dispositioned  
- [x] Decision freeze recorded  
- [x] PP-01B authorization boundary written  
- [x] Status = **ACCEPTED WITH CONDITIONS**  
- [x] No PP-01A-blocking item remains  

PP-01A closure does **not** mean cloud resources exist, PP-01 is closed, or public production is GO.

## 23. PP-01B authorization contract

**Allowed:** IaC authoring for the accepted provider/topology; named non-production spikes ([registry](../pp-01b-spike-registry.md)); sandbox provision only with explicit authorization; PostGIS/HA/PITR option validation; databases/roles as code; private networking and TLS as code; cost estimates and plans.

**Forbidden:** production cutover or traffic; production municipal enablement; API/DTO/Flyway business redesign; PP-02…PP-06 implementation; unapproved provider/topology substitution; secrets in Git.

## 24. PP-01B stop conditions

Stop / escalate to board if:

- Required PostGIS unavailable or spatial proof fails  
- Zone-redundant / Multi-AZ HA unavailable for chosen region/SKU  
- PITR retention cannot be set ≥30 days  
- Private-network requirement cannot be met for the intended apply stage  
- Estimated cost breaches an approved ceiling  
- IaC cannot model critical controls  
- Database isolation (10/10/zero cross grants) cannot be maintained  

## 25. Consequences

- PP-01B may plan and spike against Azure Flexible Server (two-cluster default).  
- Application schemas and APIs unchanged by PP-01A.  
- Hosted-beta logical backups remain the interim durability path until managed cutover.  
- Public production and PP-01 remain open/NO-GO until later packages complete.

## 26. Alternatives rejected

| Alternative | Why |
|-------------|-----|
| Remain on Compose Postgres + dumps for public prod | Violates PP-01 |
| Ten managed instances | Rejects cost-aware 1–2 compromise |
| Neon/Supabase as primary | Insufficient Multi-AZ / multi-DB fit for Parkio primary |
| Azure Hyperscale / Timescale / GCP Cloud SQL | Not in repository Postgres strategy |
| AWS RDS as immediate primary | Valid alternate; Azure is current beta host path |

## 27. Amendment policy

Amendments must be numbered (e.g. ADR-PP-01A Amendment N), state rationale, list impacted freeze items, and obtain board approval before PP-01B applies the change.

## 28. Non-goals (PP-01A)

No Terraform/Pulumi/Bicep/ARM/CloudFormation apply; no Helm/K8s; no cloud purchase; no application/API/DTO/Flyway changes; no deployments; no municipal production enablement; no claiming PP-01 CLOSED or public GO; no inventing SLAs as FACT.

## 29. Board decision

**ACCEPT WITH CONDITIONS**

Conditions: complete PP-01B-SPIKE-01…03 evidence before production-shaped apply; cost ceiling before paid HA apply; numeric RPO/RTO APPROVED before PP-01D/E/F; one-cluster only via §14; municipal production remains disabled; public production remains NO-GO.

## 30. References

- `docs/architecture/production-readiness.md` §3, §11  
- `docs/releases/KNOWN-ISSUES.md` PP-01  
- `docs/operations/backup-restore.md`  
- `docs/ai-context/05-database-guidelines.md`  
- `docker/docker-compose.yml`  
- `docs/startup/12-microsoft-founders-hub-package.md`  
- `docs/startup/11-aws-activate-package.md`  
- `docs/azure/AZURE-COST-MODEL.md`  
- `docs/azure/AZURE-ARCHITECTURE-OPTIONS.md`  
- `infra/README.md`  
- `docs/architecture/pp-01b-spike-registry.md`  
- Microsoft Learn Flexible Server extensions / HA / backup-restore (EXTERNAL VERIFICATION, 2026-08-04)  
