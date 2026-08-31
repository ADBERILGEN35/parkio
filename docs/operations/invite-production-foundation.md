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
  cannot be accepted until DNS authorization/routing is performed in 01B. The
  01A dark runtime therefore does **not** start Caddy at all: its automatic
  HTTPS would open production Let's Encrypt orders for api/app/media.parkio.dev
  while those names still resolve to hosted-beta, which cannot validate and
  burns the failed-validation budget 01B needs. See "Dark runtime and Caddy".
- web build: `VITE_API_BASE_URL=https://api.parkio.dev/api/v1` is immutable in
  the candidate image.
- auth callbacks and transactional links: `https://app.parkio.dev`; provider
  callback consoles must be reviewed in the cutover window.
- mobile: no mobile production release or callback scheme is added by 01A. The
  native client remains outside this invite boundary unless separately approved.

## Network topology

The diagram below is the **01B** (post-cutover) topology. During 01A the stack
runs dark: Caddy is not started, nothing binds beyond loopback, and the only
acceptance endpoint is `http://127.0.0.1:8080`.

```text
Internet
  -> Azure Standard public IP
  -> app-subnet NSG: TCP 443 only
  -> Caddy TLS edge                     (01B only; not started in 01A dark mode)
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

Operator-provided values remain mandatory: ACME contact email, MapTiler public
key, Resend API key, and the real Slack webhook. Mobile is outside the invite
boundary, so push delivery is explicitly disabled with the noop provider and no
Expo token is required. The renderer fails closed and writes no env file when
any required operator value is absent.

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

The scheduler runs from a versioned operational payload at
`/opt/parkio/invite-production-backup/`, installed by
`scripts/azure/install-invite-production-backup-scheduler.sh`. That payload is
the backup execution closure and nothing else — it is not a repository checkout —
and it records the revision it came from in `VERSION` plus checksums in
`MANIFEST.sha256`.

The three storage boundaries are deliberately disjoint:

- `/opt/parkio/invite-production` is the application runtime root and owns
  `current/`, `releases/`, and `acceptance/`;
- `/opt/parkio/invite-production-backup` is scheduler code only;
- `/var/backups/parkio` contains successful local backup data, while
  `invite-production-backups` is the production offsite container.

Never pass the application runtime root (or one of its children) as the backup
installer destination. The installer rejects runtime markers, symlink/traversal
paths, protected parents, transient paths, and the backup-data root before it
stages or removes anything.

There is deliberately **no persistent production `.env` on the VM**. Each
scheduled run calls `scripts/azure/invite-production-backup-run.sh`, which
authenticates with the VM managed identity, renders the env from Key Vault into
`/dev/shm` at mode 0600, runs the canonical backup, and shreds the env on every
exit path (success, failure, or signal). The run fails closed if Key Vault is
unreachable, a secret is missing, or the rendered env has the wrong mode.

Installing the payload never enables the timer. Enablement is an explicit
backup-acceptance step:

```bash
sudo scripts/azure/install-invite-production-backup-scheduler.sh          # install/upgrade
sudo scripts/azure/install-invite-production-backup-scheduler.sh --enable # enable at acceptance
sudo scripts/azure/install-invite-production-backup-scheduler.sh --disable # rollback
```

## Feature policy

ON: core auth, municipal discovery, IZUM, ISPARK, recommendations, Saved Places,
Favourites, Recents, account erasure, and deterministic ranking.

OFF: ANPARK, KONYA, KAYSERI, OSM import until an operator import is approved,
ranking shadow/evaluation/rollup, learned ranking, AI ranking authority, and
unfinished provider experiments.

The exact strategy is `DETERMINISTIC_V1`; defaults are not relied on.

## Dark runtime and Caddy

PROD-DEPLOY-01A deploys a **dark** runtime: the stack runs on
`vm-parkio-invite-prod` while `api.parkio.dev`, `app.parkio.dev` and
`media.parkio.dev` still resolve to the hosted-beta VM.

`docker/caddy/Caddyfile` enables Caddy's automatic HTTPS against production
Let's Encrypt for exactly those three names. Starting Caddy in dark mode would
therefore emit public ACME orders the runtime cannot validate — an externally
visible side effect of a supposedly dark deploy, and one that consumes the
Let's Encrypt failed-validation budget the 01B cutover depends on.

Caddy is consequently **excluded from the invite-production runtime and
required-healthy lists** and declared in `PARKIO_DISABLED_SERVICES`, so the
omission is explicit and lands in the deploy manifest. Nothing in dark
acceptance needs it: no service declares `depends_on: caddy`, smoke never
contacts it, and the dark endpoint is `gateway-service` on `127.0.0.1:8080`.
Omitting it makes the no-ACME property structural rather than configurational —
the ACME client is never started, so no config edit or overlay-ordering change
can re-enable issuance.

`docker/caddy/Caddyfile` itself is unchanged: automatic HTTPS for all three
hostnames remains intact, and 01B starts Caddy normally by deploying without the
dark runtime-set restriction.

`scripts/assert-invite-dark-acme-isolation.sh` enforces both halves and runs
from `scripts/deploy-invite-production.sh` before anything starts, so the deploy
fails closed rather than issuing a certificate request. It is source/config
inspection only — it never resolves DNS, opens a socket, or contacts an ACME
directory. Regression coverage lives in
`scripts/test-invite-dark-acme-isolation.sh`.

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

## PROD-DEPLOY-01B-01 public edge source foundation

`PROD-DEPLOY-01B-01` adds source-only contracts for Level B (public-edge
controlled invite). Nothing in this package starts Caddy, issues ACME
certificates, opens NSG ports, or mutates production.

### Edge mode (`PARKIO_INVITE_EDGE_MODE`)

| Mode | Default | Caddy | Gateway publish | ACME |
|------|---------|-------|-----------------|------|
| `dark` | yes (legacy) | absent / disabled | `127.0.0.1:8080` | not authorized |
| `public` | no (01B-02+) | staged / disabled until ACME gate | loopback until cutover | requires `PARKIO_INVITE_ACME_AUTHORIZED=true` |

Unknown values fail closed. Validation:
`./scripts/validate-invite-production-edge.sh`.

### Registration (`PARKIO_REGISTRATION_MODE`)

| Mode | invite-production default | Behaviour |
|------|-------------------------|-----------|
| `closed` | yes | registration denied |
| `invite` | future cohort | opaque single-use hashed invite required |
| `open` | hosted-beta only | canonical open registration |

Invite tokens: Flyway `V22__create_registration_invites.sql`, SHA-256 hash at
rest, atomic consume in the same transaction as user creation. Operator
creation: `./scripts/create-registration-invite.sh` (internal route only; disabled
unless `PARKIO_REGISTRATION_INVITE_CREATION_ENABLED=true`).

PRIV-001 synthetic harness: `PARKIO_REGISTRATION_PRIV001A_SYNTHETIC_BYPASS`
allows `priv001a-*@priv001a.parkio.invalid` only when explicitly enabled.

### Trusted proxy and HSTS

- `PARKIO_TRUSTED_PROXIES`: empty in dark mode; Docker/proxy CIDRs when Caddy
  fronts the gateway in public mode.
- `PARKIO_HSTS_HEADER_VALUE`: initial public cutover uses `max-age=86400` (no
  preload / includeSubDomains by default).
- `PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED`: set `false` on public cutover
  to block `/actuator/info`; dark loopback acceptance keeps `true`.

### Runtime identity (`/actuator/info`)

Gateway surfaces non-secret deployment identity at `/actuator/info`:

| Field | Env | Required value |
|-------|-----|----------------|
| `deployment.environment` | `PARKIO_ENVIRONMENT` | `invite-production` |
| `deployment.gitSha` | `PARKIO_GIT_SHA` | exact deploy SHA |

All invite-production edge overlays that start `gateway-service` must map both
variables into the container:

- `docker-compose.invite-dark.yml`
- `docker-compose.invite-public.yml` (inherited by `invite-public-staged`)

Dark loopback smoke (`scripts/smoke-hosted-beta.sh`) and public-staged
acceptance read identity on `http://127.0.0.1:8080/actuator/info`. Public edge
cutover must keep `/actuator/info` blocked via
`PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED=false` — identity wiring does not
make that endpoint public.

Regression guard:
`./scripts/assert-invite-production-runtime-identity.sh`.

### Resource budgets

| Profile | Continuous MiB | Caddy |
|---------|----------------|-------|
| `invite-production` (dark) | 15488 | absent |
| `invite-production-public` | 15744 continuous / 15872 configured | present |

Ceiling remains 16384 MiB. ClamAV >= 3072 MiB, Tempo >= 1024 MiB.

Resolved-compose certification:
`./scripts/assert-invite-production-edge-resource-budget.sh` produces
`invite-edge-resource-budget.json` with dark, public-candidate, and
public-staged profiles.

## PROD-DEPLOY-01B-02 dark-stage deploy

Production template default edge mode is `public` with
`PARKIO_INVITE_ACME_AUTHORIZED=false`. The `invite-public-staged` overlay keeps
loopback gateway acceptance while Caddy remains disabled. Live continuous memory
stays at 15488 MiB (23 services); the 15744 MiB public candidate applies only
after ACME authorization at cutover.

Deploy workflow inputs must be:
`invite_edge_mode=public`, `invite_acme_authorized=false`,
`registration_mode=closed`. Human approval on the `invite-production`
environment is required before any deploy mutation.
