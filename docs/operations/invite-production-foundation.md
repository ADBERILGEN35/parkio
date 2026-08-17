# Invite-production foundation

This is the dedicated controlled-invite target created by `PROD-DEPLOY-01A`.
It is not hosted-beta, is not public production traffic, and must remain dark
until separately authorized `PROD-DEPLOY-01B`.

## Environment and domains

- environment: `invite-production`
- resource group / region: `rg-parkio-invite-production-we` / West Europe
- web: `app.parkio.dev`
- API: `api.parkio.dev`
- media: `media.parkio.dev`
- current traffic policy: no DNS changes and no app/API/media cutover in 01A

Option A consequences:

- DNS: the existing three names eventually switch to the invite edge during
  01B; 01A creates no public DNS record.
- CORS: the sole credentialed browser origin is `https://app.parkio.dev`; no
  wildcard is permitted.
- certificates: Caddy is the renewal mechanism, but public certificate issuance
  cannot be accepted until DNS authorization/routing is performed in 01B.
- web build: `VITE_API_BASE_URL=https://api.parkio.dev/api/v1` is immutable in
  the candidate image.
- auth callbacks and transactional links: `https://app.parkio.dev`; provider
  callback consoles must be reviewed in the cutover window.
- mobile: no mobile production release or callback scheme is added by 01A. The
  native client remains outside this invite boundary unless separately approved.

## Network topology

```text
Internet
  -> Azure Standard public IP
  -> app-subnet NSG: TCP 443 only
  -> Caddy TLS edge
       -> web
       -> gateway-service
            -> internal Docker services only

VM app subnet 10.42.1.0/24
  -> private DNS
  -> delegated PostgreSQL subnet 10.42.2.0/24
       -> PostgreSQL private address 10.42.2.4:5432
```

There is no public SSH rule. Ports 8081-8089, 5432, 6379, 9092, 9000/9001,
9090/9093, and 3000 have no Azure public allow rule. The database has
`publicNetworkAccess=Disabled`; internal clients use its FQDN, never an IP.

## Provisioned resources

The reproducible definition is `infra/azure/invite-production/main.bicep`.
The 2026-08-17 deployment created:

- VM `vm-parkio-invite-prod`, `Standard_E4bds_v5` (4 vCPU / 32 GiB), Ubuntu
  24.04, Trusted Launch, 128 GiB OS plus 256 GiB Premium SSD data disk;
- VNet `vnet-parkio-invite-prod`, app subnet, PostgreSQL delegated subnet,
  private DNS zone `invite.parkio.postgres.database.azure.com`;
- PostgreSQL `pg-parkio-invite-jcvgwc`, version 16, General Purpose
  `Standard_D2ds_v5`, 128 GiB auto-grow, 30-day PITR, HA disabled;
- Key Vault `kvparkioinvjcvgwc`, RBAC, purge protection, 90-day soft delete,
  subnet firewall default-deny;
- GRS backup storage `stparkioinvjcvgwc`, OAuth-only, TLS 1.2 minimum,
  versioning and 30-day delete retention, container
  `invite-production-backups`, subnet firewall default-deny.

No resource is shared with the hosted-beta VM or its data. The older
`rg-parkio-backups/stparkiobakwesteu` account is not used by this foundation.

## Managed PostgreSQL acceptance

The application VM proved the following on 2026-08-17:

- FQDN resolution to private `10.42.2.4`;
- libpq `sslmode=verify-full` using
  `/opt/parkio/certs/azure-postgres-root.crt`;
- TLS 1.3 / `TLS_AES_256_GCM_SHA384`;
- ten logical databases;
- ten runtime roles with no superuser, database-create, or role-create rights;
- ten separate migration roles that own only their service database/schema;
- PostGIS 3.6.1 pre-provisioned in `parkio_parking`;
- transactional SRID 4326 + GiST + `ST_DWithin` synthetic proof.

Spring uses the runtime role through Hikari and the migrator through
`SPRING_FLYWAY_USER/PASSWORD`. The managed overlay fixes Hikari at four maximum
and one minimum idle connection per service.

Connection budget:

- Hikari: 10 x 4 = 40
- Flyway startup reserve: 10
- backup/operator/monitoring reserve: 10
- invite budget: 60

Services must be started in controlled batches to avoid all ten Flyway pools
opening at once.

## Secrets and injection

Generated first-party credentials are in Key Vault and never in Git, images, or
workflow artifacts. This includes JWT signing material, gateway/waitlist
secrets, runtime/migration database passwords, Redis, Kafka, MinIO, Grafana, and
backup encryption material. The VM uses a system-assigned identity with Key
Vault Secrets User and Storage Blob Data Contributor.

`scripts/azure/render-invite-production-env.py` writes the ignored runtime env
atomically with mode 0600 and suppresses values. Rotation is: create a new Key
Vault version, rematerialize env, restart only dependent services, verify, then
disable the old version after the overlap window.

Operator-provided secrets remain mandatory: ACME contact email, MapTiler public
key, Resend API key, Expo access token, and the real Slack webhook. The renderer
fails closed and writes no env file when any are absent.

## Runtime, observability, and backup policy

The invite profile runs application services, Kafka, Redis, MinIO, ClamAV,
Prometheus, Alertmanager, Grafana, node-exporter, blackbox-exporter, and
kafka-exporter. The observability interfaces bind internally/loopback; only the
TLS edge is public. PostgreSQL provider metrics are available through Azure
Monitor and the VM identity has read-only resource discovery.

`BACKUP_PRODUCTION_MODE=1` requires encryption and offsite upload. The only
orchestrator is `parkio-invite-backup.timer`:

- daily at 02:30 UTC, persistent, randomized by up to ten minutes;
- local encrypted staging under `/var/backups/parkio`;
- all ten managed databases plus MinIO objects;
- OAuth upload to `invite-production-backups`;
- 14-day artifact retention plus 30-day blob delete protection/versioning;
- Prometheus textfile success/failure metrics; operations owner is Parkio on-call.

## Feature policy

ON: core auth, municipal discovery, IZUM, ISPARK, recommendations, Saved Places,
Favourites, Recents, account erasure, and deterministic ranking.

OFF: ANPARK, KONYA, KAYSERI, OSM import until an operator import is approved,
ranking shadow/evaluation/rollup, learned ranking, AI ranking authority, and
unfinished provider experiments.

The exact strategy is `DETERMINISTIC_V1`; defaults are not relied on.

## HA and retained risk

HA is **deferred with accepted risk** for the tiny invite and is not closed.
Loss of the single VM or single Flexible Server can cause an outage until Azure
repair/operator recovery. Zone-redundant HA is mandatory before broad public
access.

## Cost and retention decision

Current monthly estimate: VM EUR 255.00, PostgreSQL compute EUR 135.78, and
EUR 30-60 for disks, database storage, GRS backup, public IP, and monitoring;
total EUR 421-451. The DSv5 VM family had zero subscription quota, so the
quota-available EBDSv5 SKU was selected.

These are intended production-foundation resources and are retained awaiting
cutover authorization. They are not disposable acceptance resources. Any future
destroy operation requires an explicit plan covering backup retention and Key
Vault purge protection.

## Canonical gates

The single authoritative readiness checklist is
[`invite-production-pre-cutover-checklist.md`](invite-production-pre-cutover-checklist.md).
The prepared, unexecuted cutover and rollback procedures are
[`invite-production-cutover-runbook.md`](invite-production-cutover-runbook.md)
and [`invite-production-rollback-runbook.md`](invite-production-rollback-runbook.md).
