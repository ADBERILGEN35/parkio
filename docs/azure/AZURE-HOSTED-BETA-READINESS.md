# Azure Hosted-Beta Readiness

## Repository deployment model

The current hosted path merges, in order:

```text
docker/docker-compose.yml
docker/docker-compose.apps.yml
docker/docker-compose.images.yml
docker/docker-compose.hosted-beta.yml
```

It starts 10 Spring services, 10 PostgreSQL/PostGIS containers, Redis, one KRaft Kafka broker, Kafka/blackbox/node exporters, MinIO plus setup, ClamAV, Prometheus, Alertmanager, Loki, Promtail, Tempo, Grafana, nginx web, and Caddy. The overlay publishes only Caddy on 80/443; admin services bind to loopback. Compose v2.24.4+ is required because the overlay uses `!reset` and `!override`.

The app images use Java 21 JDK build stages and Java 21 JRE non-root runtimes (`uid 10001`), with `cap_drop: ALL`, `no-new-privileges`, PID limits, readiness checks, graceful shutdown, bounded Docker logs, and cgroup-aware JVM flags. Each Dockerfile rebuilds its service from the repository, so a cold on-VM release performs repeated Gradle build work and can stress a 4-vCPU host.

## Inventory

RAM values are current hard ceilings, not reservations. Recommended figures include host/cache headroom and are not claims of measured per-container steady state.

| Component | Image / port | Persistence | RAM min / recommended | CPU and disk risk | Beta class / exposure | Health / backup / main failure |
|---|---|---|---|---|---|---|
| Caddy | `caddy:2.8-alpine`; 80,443 TCP; 443 UDP | cert/config volumes | 256 MiB / 256 MiB | low CPU; certificate state | Mandatory; only public container | admin API health; back up volume; DNS/ACME failure |
| Web | built nginx; internal 80 | immutable image | 128 / 128 MiB | low | Mandatory for web beta; Caddy only | HTTP health; rebuild; wrong baked API URL |
| Gateway | built Java; internal 8080 | gateway DB | 640 / 768 MiB | JWT/rate-limit CPU sensitive | Mandatory; Caddy only | readiness; DB dump; auth/JWKS/Redis failure |
| Auth | built Java; 8081 | auth DB | 768 / 1024 MiB | BCrypt/RSA startup/load | Mandatory; internal | readiness; DB dump; email/JWT/Redis failure |
| User | built Java; 8082 | user DB | 640 / 768 MiB | moderate | Mandatory; internal | readiness; DB dump; gateway status dependency |
| Parking | built Java; 8083 | PostGIS DB | 768 / 1024 MiB | spatial CPU/I/O sensitive | Mandatory; internal | readiness; PostGIS dump; geo/DB failure |
| Media | built Java; 8084 | media DB + MinIO | 768 / 1024 MiB | upload buffers/I/O | Mandatory if uploads enabled; internal | readiness; DB+objects; ClamAV/MinIO fail-closed |
| Gamification | built Java; 8085 | own DB | 640 / 768 MiB | Kafka consumer | Strongly recommended; internal | readiness; DB dump; event lag |
| Notification | built Java; 8086 | own DB | 640 / 768 MiB | worker/provider latency | Strongly recommended; internal | readiness; DB dump; provider/lag failure |
| Moderation | built Java; 8087 | own DB | 640 / 768 MiB | Kafka consumer | Mandatory for safe user content; internal | readiness; DB dump; queue/lag failure |
| AI validation | built Java; 8088 | own DB | 640 / 768 MiB | advisory worker | Optional only after route/event validation; internal | readiness; DB dump; advisory unavailable |
| Analytics | built Java; 8089 | own DB | 640 / 768 MiB | write/event growth | Optional for closed beta evidence, but current UI/routes expect it | readiness; DB dump; consumer lag/disk growth |
| PostgreSQL x9 | `postgres:16-alpine`; 5432 internal | 9 named volumes | 256-320 MiB each / current caps | connection and WAL growth | Mandatory for owning services; internal | `pg_isready`; nightly dumps; disk/corruption |
| PostGIS x1 | `postgis/postgis:16-3.4`; 5432 | parking volume | 384 / 512 MiB | spatial indexes, I/O | Mandatory; internal | `pg_isready`; PostGIS-aware dump/restore; disk |
| Redis | `redis:7-alpine`; 6379 | AOF volume | 384 / 512 MiB | low CPU, auth/rate-limit critical | Mandatory; internal | authenticated ping; preserve only if session/RPO requires; loss invalidates/cache state |
| Kafka | `cp-kafka:7.7.1`; 9092 internal | log volume | 1280 / 1536 MiB, heap 768 MiB | startup CPU/page cache; bounded 7d/2 GiB per partition | Mandatory for current event flows; internal | broker check; normally discard if outboxes replay; RF=1 disk loss |
| MinIO | pinned MinIO; 9000 internal | object volume | 512 / 768 MiB | unbounded media growth | Mandatory for uploads; media hostname proxies signed GET | live health; mirror objects; disk loss |
| ClamAV | `clamav:1.4`; 3310 internal | signature volume | 1536 / 1792 MiB | high startup CPU/RAM | Mandatory while uploads are accepted; internal | clamd health; signatures disposable; OOM/signature startup blocks media |
| Prometheus | `prometheus:v2.54.1`; 9090 loopback | TSDB | 1024 / 1280 MiB | 15d retention | Strongly recommended; SSH tunnel | health; data disposable; TSDB/disk pressure |
| Grafana | `grafana:11.2.0`; 3000 loopback | dashboards/state | 384 / 512 MiB | low | Recommended; SSH tunnel only | health; export dashboards; auth/config failure |
| Kafka exporter | pinned; 9308 loopback | none | 128 / 128 MiB | low | Recommended with Kafka | scrape; none; monitoring blind spot |
| Node exporter | pinned; 9100 loopback | host mounts read-only | 64 / 64 MiB | low | Recommended | scrape; none; host blind spot |
| Blackbox exporter | pinned; 9115 loopback | config | 64 / 64 MiB | low | Recommended | scrape; none; false availability confidence |
| Loki | `loki:2.9.8`; 3100 loopback | log store | 512 / 768 MiB | 168h retention/disk | Disable for 30-day constrained beta | health; logs optional; disk/compactor failure |
| Promtail | `promtail:2.9.8`; 9080 loopback | positions | 128 / 128 MiB | Docker log scanning | Disable with Loki | health; discard; socket/log access |
| Tempo | `tempo:2.6.1`; 3200 loopback | trace store | 512 / 768 MiB | 48h retention/disk | Disable for constrained beta and set tracing off | no Compose health; traces optional; dropped spans/log noise |
| Alertmanager | pinned; 9093 loopback | silences | 128 / 128 MiB | low | Keep only with a real receiver; otherwise disable | health; config backup; alerts not delivered |

Current ceilings total about 15.8 GiB. The repo's sizing contract targets ceilings below about 85% of host RAM and therefore recommends 8 vCPU / 24 GiB. A measured run on a 15.2-GiB host used about 7.7 GiB idle and 10 GiB under heavy authenticated **read** load, but omitted several observability containers and did not test writes/uploads or a soak.

## Minimum viable beta classification

| Class | Components | Judgment |
|---|---|---|
| Mandatory | Caddy, web, gateway, auth, user, parking, media, moderation, all databases for enabled services, Redis, Kafka, MinIO, ClamAV | Preserve first closed-beta user, upload, safety, auth, and event flows |
| Strongly recommended | gamification, notification, Prometheus, Grafana, node/kafka/blackbox exporters | These are product feedback and minimum operability signals |
| Optional | AI validation, analytics, Alertmanager with a real channel | May be deferred only after route, UI, Kafka dependency, preflight, and smoke validation |
| Disable for constrained 30 days | Loki, Promtail, Tempo; Alertmanager if no receiver | Saves 1.28-1.41 GiB of ceilings and disk; set `PARKIO_TRACING_ENABLED=false` to prevent exporter errors |
| Defer until public beta | Log Analytics, Defender paid plans, public Grafana, managed Kafka, AKS, Application Gateway, NAT Gateway | Cost/complexity is not justified by the temporary topology |

There is **no repository-supported reduced application profile today**. Compose and deploy scripts start the whole graph. Omitting AI/analytics or consolidating PostgreSQL requires a separately authorized deployment overlay/configuration phase and focused smoke/event validation; it is not part of this audit.

## Sizing profiles

| Profile | VM | Disks | Headroom and risk | Required changes | Stability / time evidence |
|---|---|---|---|---|---|
| Minimum experiment | `Standard_B4ms`, 4 vCPU, 16 GiB | 64-GiB OS + 128-GiB data, Standard SSD LRS | tight memory; CPU credits can throttle builds/startups | disable Loki/Promtail/Tempo/unused Alertmanager; tracing off; run only scheduled sessions | HIGH risk; cold build/restart NOT VERIFIED |
| Recommended credit profile | `Standard_D4as_v5`, 4 vCPU, 16 GiB | same | tight memory but fixed CPU; measured reads suggest CPU capacity, not startup proof | same observability reduction; 24h stabilization and resize/deallocate triggers | MEDIUM-HIGH risk; cold deploy/restart NOT VERIFIED |
| Safer small public beta | `Standard_D8as_v5`, 8 vCPU, 32 GiB | 64-GiB OS + 256-GiB data | enough for all current ceilings and OS cache | full observability allowed; still single-host/no HA | lower resource risk; exceeds credit; write/soak still unverified |

Expected cold build and restart times cannot be derived from configuration. Reserve a 2-4 hour first-deployment operator window, record actual `time` output, and do not treat that planning window as measured evidence. The existing runbook estimates manifest rollback at 3-8 minutes when prior images remain local.

## Compatibility findings

### Required before deployment

- Use Ubuntu 24.04 LTS **amd64** and `Standard_D4as_v5`. AMD64 matches the most conservative path; current image manifest architecture was not live-verified.
- Install Docker Engine 24+ and Compose v2.24.4+; verify `docker compose version` before render.
- Mount the data disk before Docker creates `/var/lib/docker`, or explicitly migrate Docker data-root.
- Use literal public domains and exact `PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT`; MinIO signatures depend on the Host header.
- Keep gateway and every service/data/admin port un-published. Only Caddy may be public.
- Set all hosted secrets/providers and pass preflight; the checked-in example intentionally fails.
- Disable tracing when Tempo is omitted; otherwise the measured benchmark shows recurring unresolved `tempo` exporter errors.
- Validate `app.parkio.dev` because the current Caddyfile requires a separate web hostname even though the apex remains on Hostinger.

### Recommended

- Build images in CI and pull immutable images in a later phase. Current on-host Dockerfiles duplicate Gradle work and increase deploy time/CPU/disk pressure.
- Use the implemented `azure-hosted-beta` selector and Azure overlay; never replace it with ad-hoc service stops/lists.
- Use one PostgreSQL server process with ten databases/users only after an implementation and restore-test phase. Logical database ownership can remain intact, but current scripts and Compose assume ten containers.
- Add a watchdog or external uptime probe; Compose does not restart a process that is unhealthy but still running.

### Optional

- Key Vault with managed identity if the environment continues beyond 30 days.
- A separate status provider outside the VM's failure domain.
- Azure DNS migration later; Hostinger DNS is adequate now.

### Not needed for this beta

AKS, Azure Container Apps, Load Balancer, Application Gateway, NAT Gateway, Bastion, private endpoints, availability zones, Premium SSD, public Grafana, and Log Analytics ingestion.

## Architecture verification boundary

AMD64 application builds are source-compatible with Java/nginx base images, but every pinned third-party image manifest must still be checked on the Azure VM:

```bash
docker buildx imagetools inspect <IMAGE:TAG>
docker compose --env-file docker/.env.azure-hosted-beta \
  -f docker/docker-compose.yml -f docker/docker-compose.apps.yml \
  -f docker/docker-compose.images.yml -f docker/docker-compose.hosted-beta.yml \
  -f docker/docker-compose.azure-hosted-beta.yml config --images
```

ARM64 is **NOT VERIFIED**. Do not choose an Ampere/ARM Azure SKU until all pinned images, especially PostGIS, Confluent Kafka, ClamAV, MinIO, and exporters, report `linux/arm64` and a complete ARM build/smoke/backup/restore run passes.

## Verification record

| Command | Working directory | Result | Evidence / rerun |
|---|---|---|---|
| `git status --short` | repo root | PASS | Recorded pre-existing Hostinger/frontend changes, `dist/`, `docs/audit/`, zip; untouched |
| `bash -n scripts/*.sh scripts/lib/*.sh docker/alertmanager/render-config.sh` | repo root | PASS | Shell syntax clean |
| `./scripts/test-preflight-hosted-beta.sh` | repo root | PASS | 36 assertions passed |
| `PARKIO_ENV_FILE=scripts/preflight-fixtures/valid.env ./scripts/preflight-hosted-beta.sh --skip-compose` | repo root | PASS | 47 checks passed; Compose intentionally skipped |
| `./gradlew test --no-daemon` | repo root | PASS | `BUILD SUCCESSFUL`; 76 tasks up to date |
| `corepack pnpm --filter @parkio/web exec vitest run src/pages/landing/waitlistService.test.ts` | `frontend/` | PASS | 3 tests passed |
| `corepack pnpm --filter @parkio/web exec vitest run src/pages/LandingPage.test.tsx --reporter=verbose` | `frontend/` | FAIL | 6 passed, 1 failed: real waitlist flow timed out at 5s; pre-existing modified frontend work, not changed by this audit |
| `./scripts/verify-security-boundaries.sh` | repo root | BLOCKED | WSL Docker daemon unavailable; rerun on target VM with real env and running stack |
| `docker info` / `docker.exe info` | repo root | BLOCKED | WSL integration/Windows Docker transport unavailable |
| `az account show` | repo root | BLOCKED | Azure CLI not installed; use Cloud Shell or install CLI, then rerun |
| Compose render / deploy dry-run / backup dry-run / restore dry-run | repo root | BLOCKED | Requires Docker v2.24.4+ and a real non-placeholder env; exact commands are in the deployment runbook |
| Docker image build and manifest inspection | repo root | BLOCKED | No daemon; rerun on target/CI |
| Live TLS, health, authenticated, waitlist, backup/restore, rollback | Azure VM | BLOCKED | No resources were created by rule |
