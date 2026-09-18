# PP-01 — Managed PostgreSQL + PITR (implementation)

This document records the **PP-01 implementation and non-production acceptance**
on `api`. Architecture decisions remain in
[ADR-PP-01A](adr/ADR-PP-01A-managed-postgresql.md) (**ACCEPTED WITH CONDITIONS**).

Parkio is **not** overall production-ready. This package does **not** migrate
hosted-beta or production. Public production remains **NO-GO**.

## Decision (this package)

| Item | Result |
|------|--------|
| Provider | Azure Database for PostgreSQL **Flexible Server** (ADR-PP-01A) |
| First-rollout topology | **One** Flexible Server, **10** logical databases, **10** roles |
| Public-production topology | ADR default remains **two** clusters (`core` + `parking`) + zone-redundant HA |
| PITR | **Proven** on disposable West Europe Flexible Server (new server restore) |
| HA (zone-redundant) | **DEFERRED WITH ACCEPTED RISK** for first invite rollout; **required** before broad public GO (ADR) |
| Hosted-beta cutover | **Not executed** |
| Feature / domain behavior | Unchanged |

## Inventory (authoritative)

Hosted-beta / Compose still uses **10 container Postgres instances** (parking =
`postgis/postgis:16-3.4`). Gateway **does** own `parkio_gateway` (waitlist).

| Service | Database | Flyway tip | Extensions | Hikari max pool (default) |
|---------|----------|------------|------------|---------------------------|
| auth | `parkio_auth` | 21 | none | 8 |
| gateway | `parkio_gateway` | 1 | none | 4 |
| user | `parkio_user` | 19 | none | 8 |
| parking | `parkio_parking` | 40 | **PostGIS** | 8 |
| media | `parkio_media` | 13 | none | 8 |
| gamification | `parkio_gamification` | 15 | none | 8 |
| notification | `parkio_notification` | 14 | none | 8 |
| moderation | `parkio_moderation` | 13 | none | 8 |
| analytics | `parkio_analytics` | 9 | none | 8 |
| ai-validation | `parkio_aivalidation` | 11 | none | 8 |

Media **binaries** stay in object storage. JDBC URLs are already
`SPRING_DATASOURCE_URL` (hostname, not IP). Hibernate timezone **UTC**.

## Current failure domain (hosted-beta)

| Event | Effect | Backup vs HA |
|-------|--------|--------------|
| Postgres container dies | Compose restart; RPO = unflushed writes | Restart ≠ HA |
| Docker volume damaged | Data loss until last **logical** dump | Offsite dump, not PITR |
| VM disk / VM delete | All 10 DBs gone | Offsite encrypted dumps (PROD-BACKUP-OFFSITE-01) |
| Azure zone / region | Same VM failure domain | Offsite blob is a **second region copy of dumps**, still not WAL PITR |

Logical backup is **not** HA and **not** PITR.

## Options

| Option | Verdict |
|--------|---------|
| A — 1 Flexible Server × 10 DBs | **First rollout** (smallest independent failure domain) |
| B — 2 servers (core + parking) | **ADR frozen default** for public production |
| C — 10 servers | Rejected (cost) |

Option A is a documented **exception** to ADR §14: shared failure domain accepted
for early rollout; isolation remains 10 databases / 10 roles / no cross-DB FKs.

## Selected first-rollout topology

- **SKU family:** General Purpose (not Burstable in production — Microsoft: Burstable is non-production). Acceptance used `Standard_B1ms` only.
- **Suggested prod SKU:** `Standard_D2ds_v5` (2 vCore / 8 GiB) West Europe or France Central, **storage autogrow**, **backup retention 30 days** (ADR floor).
- **HA:** Disabled for first invite rollout.
- **Network (prod):** private VNet / private DNS (SPIKE-03). Acceptance used public firewall to a single operator IP.
- **TLS:** `sslmode=require` (verify CA). `sslmode=disable` forbidden.
- **PITR:** vendor restore **creates a new server** (not in-place).
- **Layered backup:** (1) managed PITR (2) existing encrypted `pg_dump` offsite. Neither replaces the other.

## Connection budget

B1ms user connections ≈ **35** — **unsafe** for 10 × pool 8 (=76).

First prod on D2ds_v5: max connections **859** / user **844** ([limits](https://learn.microsoft.com/en-us/azure/postgresql/configure-maintain/concepts-limits)).

Recommended pools for managed: **4** per service (gateway 4) → **40** + Flyway/startup **10** + operators **10** ≈ **60**. Overlay sets Hikari `maxLifetime` 25m and `keepaliveTime` 60s so Azure idle/failover drops recover.

## Flyway compatibility

| Path | Class |
|------|--------|
| 9 non-parking services | **COMPATIBLE** (empty-DB Flyway on Flexible Server 16.14) |
| parking `V1 CREATE EXTENSION postgis` | **REQUIRES PRE-PROVISIONED EXTENSION** (`azure.extensions=POSTGIS` then admin `CREATE EXTENSION`) |

Do not rewrite V1. App user should not need `CREATE EXTENSION` at runtime (already the Mode A test posture).

Empty managed migrate (2026-08-14): auth 21, gateway 1, user 19, parking 40, media 13, gamification 15, notification 14, moderation 13, analytics 9, ai-validation 11.

Encoding UTF8, collation `en_US.utf8`, timezone UTC. Turkish text is UTF-8; no locale rewrite.

## PITR acceptance (disposable)

Resource group `rg-parkio-pp01-accept` (West Europe), servers
`psql-parkio-pp01-accept` and restore `psql-parkio-pp01-pitr`. Synthetic data only.

| Step | UTC (approx) | Result |
|------|----------------|--------|
| Seed marker `BEFORE`, ACTIVE user, parking geography SRID 4326 | 11:42:38 | Spatial `ST_DWithin` true |
| Mutate marker `AFTER`, delete spot | 11:46:15 | Source after-state |
| `az postgres flexible-server restore --restore-time 2026-08-14T11:43:30+00:00` | 11:46:28–11:54:36 | **~8.1 min** until Ready |
| Restored marker | | **BEFORE** (source still AFTER) |
| Restored spot | | count=1, SRID 4326 |
| Flyway | | 21 / 40 |
| Tombstone replay on restored auth | | status **ERASURE_IN_PROGRESS**, tombstone retained |

PITR restore **can resurrect** a pre-erasure ACTIVE user. Replay/suppression is
mandatory after logical restore **and** PITR (same PRIV-001 ledger).

Observed RTO is **not** a production guarantee (tiny dataset, same region, public endpoint).

Vendor PITR capability: restore to custom timestamp within retention 7–35 days;
restore **always a new server**. Geo-redundant backup optional at create.

## HA

West Europe new zone-redundant HA was previously catalog-blocked (PP-01B-SPIKE-01).
First invite rollout: single-zone + PITR. Zone-redundant HA remains the ADR
requirement before **broad public** production.

## Erasure

PRIV-001 stays certified. Managed move must copy `erasure_requests`, acks,
`erased_user_tombstones`, ERASED rows. After any restore (dump or PITR), replay
the **post-erasure** ledger before serving.

## Cutover (not executed)

1. Maintenance window; stop writes.  
2. Final encrypted logical dump + confirm PITR healthy.  
3. Restore into managed DBs (`pg_dump`/`pg_restore` — data volume is small).  
4. Integrity (row counts, Flyway, PostGIS).  
5. Switch `SPRING_DATASOURCE_URL` / secrets; restart apps.  
6. Smoke. Keep old volumes **read-only**.  
7. Replay erasure ledger.

Rollback: trivial only **before** new writes on managed. After writes: forward-fix /
reconcile — do not blindly reverse connection strings.

## Secrets

Do not reuse container passwords. Per-service `POSTGRES_*_PASSWORD` via env/Key
Vault. Rotate after real cutover. Never commit connection strings.

## Observability / alerting

Use Azure Monitor on Flexible Server: storage percent, connections, CPU, replica
HA state, backup health. Page via existing PROD-ALERTING-01 Slack path with
**test labels** only. Do not fire production pager from acceptance.

Compose overlay: `docker/docker-compose.managed-db.yml` (profiles hide local
postgres; JDBC `sslmode=require`). Hosted-beta **unchanged** unless overlay applied.

Backup: `PARKIO_PG_MODE=managed` + `PARKIO_PG_HOST` uses TLS `pg_dump`. Default
remains `docker exec` so restore-drill CI is unchanged. `sslmode=disable` rejected.

## Cost (estimate, not a quote)

Official list prices vary; calculator: https://azure.microsoft.com/pricing/details/postgresql/flexible-server/

| Component | Estimate |
|-----------|----------|
| Acceptance B1ms + 32 GiB, hours-scale | cents–few dollars (destroyed after test) |
| First prod D2ds_v5 + 32–64 GiB + 30d backup, no HA | **estimated** low hundreds USD/month |
| Zone-redundant HA | typically ~2× compute |
| Geo-redundant backup | extra backup GB |

## Remote certification (CI-TRUST-SHADOW-01)

PP-01 PITR acceptance on `69c5a70` was blocked by a **pre-existing** Backend
Integration failure in `TrustShadowPersistencePostgresIT` (outside the PP-01
managed-PostgreSQL diff). CI-TRUST-SHADOW-01 stabilized trust-shadow concurrent
persistence without changing PP-01 datasource, Flyway, or Azure PITR evidence.

| Item | Result |
|------|--------|
| Candidate SHA | `34ac67811ede2767b1b2b08901ea91ab448d500c` |
| PP-01 PITR evidence SHA | `69c5a70207fb2ccea214150a44aa0b2abe3aceaf` (unchanged; RG deleted) |
| Backend CI | **SUCCESS** — run `32011855518` |
| Backend integration | **SUCCESS** — run `32011858519` |
| Security CI | **SUCCESS** — run `32011861845` |
| PITR | **CLOSED** (prior disposable Azure proof stands) |
| HA | **DEFERRED WITH ACCEPTED RISK** for first invite rollout |
| Hosted-beta cutover | **Not executed** |
| Parkio overall | **NO-GO** (public production) |

**Root cause:** concurrent distinct evidence could persist a later `evaluatedAt`
first; incremental apply then rejected earlier evidence (`trust evaluations must
be replayed in canonical order`) and/or published a snapshot folded from a stale
ledger view under optimistic snapshot races.

**Fix:** fold the durable ledger in canonical `(evaluatedAt, ledgerEntryId)`
order; take a pessimistic write lock on the subject snapshot row before
fold+upsert so concurrent writers cannot omit newer ledger rows from the
projection.

## Cleanup

Acceptance RG is **deleted after certification**. Do not retain unknown billable
servers.

## Explicit non-actions

No production/hosted-beta DB migration. No PROD-DEPLOY-01. No master. No PR #44.
No `agent-tools/`. No PP-01 HA enablement on a paid prod server.
