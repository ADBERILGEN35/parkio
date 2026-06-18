# docker — Parkio local development infrastructure

Production-shaped local environment for Parkio. It provides every backing
service the microservices need, following the rules in
[`/docs/ai-context`](../docs/ai-context): **database-per-service**, externalized
config, Kafka for async events, Redis for cache/locks/idempotency, and MinIO for
media bytes.

## Layout

```
docker/
├── docker-compose.yml          # Infrastructure (databases, Redis, Kafka, MinIO, observability)
├── docker-compose.apps.yml     # The 10 application services (overlay on the infra file)
├── .env.example                # Copy to .env and edit
├── alertmanager/               # Alertmanager config + env-safe Slack renderer
├── loki/loki.yml               # Central log storage config
├── promtail/promtail.yml       # Docker log collection config
├── prometheus/prometheus.yml   # Scrape config
└── grafana/provisioning/       # Auto-provisioned datasource + dashboard provider
```

## What's included

| Component                | Container                     | Host port(s)    | Purpose                                   |
|--------------------------|-------------------------------|-----------------|-------------------------------------------|
| PostgreSQL (auth)        | `parkio-postgres-auth`        | 5432            | `auth-service` database                   |
| PostgreSQL (user)        | `parkio-postgres-user`        | 5433            | `user-service` database                   |
| PostgreSQL **+ PostGIS** | `parkio-postgres-parking`     | 5434            | `parking-service` database (geospatial)   |
| PostgreSQL (media)       | `parkio-postgres-media`       | 5435            | `media-service` database                  |
| PostgreSQL (gamification)| `parkio-postgres-gamification`| 5436            | `gamification-service` database           |
| PostgreSQL (notification)| `parkio-postgres-notification`| 5437            | `notification-service` database           |
| PostgreSQL (moderation)  | `parkio-postgres-moderation`  | 5438            | `moderation-service` database             |
| PostgreSQL (analytics)   | `parkio-postgres-analytics`   | 5439            | `analytics-service` database              |
| PostgreSQL (ai-validation)| `parkio-postgres-ai-validation`| 5440           | `ai-validation-service` database          |
| Redis                    | `parkio-redis`                | 6379            | Cache, locks, idempotency, rate limiting  |
| Kafka (KRaft)            | `parkio-kafka`                | 29092 (host)    | Async events; in-network listener `kafka:9092` |
| Kafka Exporter           | `parkio-kafka-exporter`       | 127.0.0.1:9308  | Broker lag / DLT metrics                  |
| MinIO                    | `parkio-minio`                | 9000 / 9001     | S3 API / web console                      |
| Prometheus               | `parkio-prometheus`           | 9090            | Metrics scraping                          |
| Alertmanager             | `parkio-alertmanager`         | 127.0.0.1:9093  | Alert grouping/routing                    |
| Loki                     | `parkio-loki`                 | 127.0.0.1:3100  | Central log storage                       |
| Promtail                 | `parkio-promtail`             | 127.0.0.1:9080  | Docker log shipping                       |
| Grafana                  | `parkio-grafana`              | 3000            | Dashboards                                |

> **Database-per-service:** every service owns a *separate* PostgreSQL instance.
> Services never share a database or read another service's tables
> (`ai-context/01`, `ai-context/05`). `gateway-service` owns no database by design;
> `ai-validation-service` owns one for its advisory results (Flyway + JPA).

> **Kafka uses KRaft mode** (no Zookeeper) — the modern, single-process setup,
> fewer containers to run locally.

## Prerequisites

- Docker Engine 24+ and the Docker Compose v2 plugin (`docker compose ...`).

## Quick start (infrastructure only)

The common dev loop: run infra in Docker, run the services you're working on from
your IDE.

```bash
cd docker
cp .env.example .env          # then edit passwords if you like
docker compose up -d          # starts databases + Redis + Kafka + MinIO + observability
docker compose ps             # all should become "healthy"
```

`docker compose` automatically reads `./.env` when run from this directory.
Before starting the application overlay, set `PARKIO_JWT_PRIVATE_KEY_PEM` in the
git-ignored `.env` to a PKCS#8 RSA private key. Double-quoted `.env` values may
use `\n` escapes; the committed `.env.example` intentionally leaves the key blank.

## Full stack (infrastructure + all services)

```bash
cd docker
docker compose -f docker-compose.yml -f docker-compose.apps.yml up -d --build
```

Service images build from the repository root (each service's `Dockerfile`
compiles the whole monorepo via the Gradle wrapper), so the first build is slow.

## Endpoints

| Tool            | URL                              | Credentials                          |
|-----------------|----------------------------------|--------------------------------------|
| MinIO console   | http://localhost:9001            | `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` |
| Prometheus      | http://localhost:9090            | —                                    |
| Alertmanager    | http://localhost:9093            | —                                    |
| Loki            | http://localhost:3100            | —                                    |
| Grafana         | http://localhost:3000            | `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` |
| Kafka exporter  | http://localhost:9308/metrics    | —                                    |
| Kafka (from host)| `localhost:29092`               | —                                    |
| Kafka (in-net)  | `kafka:9092`                     | —                                    |

Connecting to a database from the host, e.g. parking:

```bash
psql "postgresql://parkio_parking:parking_local_dev_pw@localhost:5434/parkio_parking"
```

## How services connect (when run via the apps overlay)

Connection details are injected as environment variables — no values are baked
into service code (`ai-context/01`):

- `SPRING_DATASOURCE_URL` → `jdbc:postgresql://postgres-<svc>:5432/<db>` (own DB only)
- `SPRING_KAFKA_BOOTSTRAP_SERVERS` → `kafka:9092`
- `SPRING_DATA_REDIS_HOST` → `redis`
- `parking-service` → `PARKIO_MEDIA_SERVICE_URI` (internal media-service client for signed
  access URLs; defaults to `http://localhost:8084` for IDE dev, must be
  `http://media-service:8084` in compose)
- `media-service` storage → `PARKIO_MEDIA_STORAGE_ENDPOINT` (internal SDK ops, e.g.
  `http://minio:9000` in compose) / `PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT` (host
  embedded in presigned GET URLs, e.g. `http://localhost:9000` for local beta) /
  `PARKIO_MEDIA_BUCKET` / `PARKIO_MEDIA_STORAGE_ACCESS_KEY` /
  `PARKIO_MEDIA_STORAGE_SECRET_KEY` (names must match `parkio.media.*` in
  `application.yml`). SigV4 signs the `Host` header — presigned URLs must be
  generated with the same host the browser will use.
- `gateway-service` → `PARKIO_<SVC>_SERVICE_URI` (downstream route targets, e.g.
  `http://user-service:8082`). These resolve the `${PARKIO_*_SERVICE_URI}` placeholders in
  the gateway's `application.yml`; their dev defaults (`localhost:808x`) only work when the
  gateway runs from the IDE. `PARKIO_USER_SERVICE_URI` also backs the per-request
  account-status lookup, so without it every authenticated request fails closed.
- `gateway-service` → `PARKIO_CORS_ALLOWED_ORIGINS` (browser CORS allow-list; defaults to
  the local Vite dev origin `http://localhost:5173`, never `*`)
- `auth-service` → `PARKIO_JWT_PRIVATE_KEY_PEM` for RS256 signing
- `gateway-service` → auth-service's internal JWKS URL for public-key validation

These variables are inert until a service adds the matching Spring starter; they
document the intended wiring without implying any business logic.

## Health & startup ordering

- Every infrastructure container defines a **healthcheck** (`pg_isready`,
  `redis-cli ping`, Kafka API versions, MinIO `/minio/health/live`,
  Prometheus `/-/healthy`, Grafana `/api/health`).
- Application services wait for their dependencies via `depends_on:
  condition: service_healthy`, so they only start once the databases, Kafka, etc.
  are actually ready. `media-service` also waits for the one-shot `minio-setup`
  job that creates the media bucket.

## Networks

Two dedicated bridge networks isolate planes:

- `parkio-backend` — databases, Redis, Kafka, MinIO and the services.
- `parkio-observability` — Prometheus, Alertmanager, Loki, Promtail and Grafana
  (Prometheus also joins `parkio-backend` so it can scrape service metrics).

## Ingress & exposure — the gateway is the only public entrypoint

`gateway-service` (`:8080`) is the **only** component that should ever be reachable
from outside the cluster. It authenticates every request, role-gates privileged
routes, rate-limits, and injects the trusted `X-User-*` identity headers that
downstream services rely on. Those headers are believable **only** because backend
services are not directly reachable — a directly-exposed service would let a client
forge `X-User-Id`/`X-User-Roles` and bypass authentication and edge authorization
entirely (`ai-context/07`; see `services/gateway-service/README.md`).

Access-token signing is asymmetric: only `auth-service` receives the RSA private
key. `gateway-service` fetches and caches public keys from auth-service JWKS, so it
can validate tokens but cannot mint them.

**Defense in depth — `X-Gateway-Auth` shared secret.** The gateway stamps every routed
request with a shared internal secret (`PARKIO_GATEWAY_INTERNAL_SECRET`, set once in
`.env` and injected into every service via the compose `app-env-common` env). Each
service requires it and returns `401 GATEWAY_AUTH_REQUIRED` if it is missing/wrong, so
even a directly-reachable service rejects un-gatewayed calls. There is **no production
default** — services fail to start without the secret. Actuator `health`/`info`/
`prometheus` are exempt so probes and metric scraping keep working. This is a backstop,
**not** a replacement for keeping services private (below).

> **Local dev vs production.** In `docker-compose.apps.yml` each backend service maps
> a host port (`8081`–`8089`) purely for local convenience (hit a service directly
> while developing). **This is not a production layout.** In production:
> - publish **only** the gateway (`8080`) to the public network / load balancer;
> - keep every other service on the internal `parkio-backend` network with **no host
>   port / no public ingress** (Kubernetes: `ClusterIP`, never a public `LoadBalancer`
>   or `NodePort` for a backend service);
> - keep databases, Redis, Kafka and MinIO internal-only as well.

## Hosted beta (single VPS)

The files above publish every port for local convenience. For a small **hosted** beta on
one VPS, add the **`docker-compose.hosted-beta.yml`** overlay: it makes a **Caddy** TLS
reverse proxy the only public entrypoint and stops publishing all other ports.

> **Beta, not public production.** Single-broker Kafka (RF=1), single-node databases and
> MinIO. Automated backups are **required** (below). See
> [`../docs/architecture/production-readiness.md`](../docs/architecture/production-readiness.md)
> for the path to public production.

### What the overlay changes
- **Caddy** (`caddy/Caddyfile`) publishes only **80/443** and auto-issues TLS via ACME.
- **gateway-service** is no longer published; Caddy reaches it at `gateway-service:8080`
  on the private network. It sets `SERVER_FORWARD_HEADERS_STRATEGY=framework` so the gateway
  honors `X-Forwarded-Proto/Host` from Caddy.
- **Backend services (8081–8089), all databases, Redis, Kafka, MinIO**: no published ports.
- **Prometheus/Alertmanager/Loki/Promtail/Grafana**: bound to **127.0.0.1** only
  (SSH-tunnel to view).

### Proxy & media strategy (chosen)
- **Proxy: Caddy** — simplest path to automatic HTTPS, HTTP→HTTPS redirect, forwarded
  headers and HTTP/3.
- **Media: a dedicated media subdomain proxied to *private* MinIO.** The bucket stays
  private; images are served only via time-limited **SigV4 presigned GET URLs**. Caddy
  preserves the `Host` header (its default), which SigV4 validation requires — so
  `PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT` must equal `https://${PARKIO_MEDIA_DOMAIN}` (the exact
  host the browser uses). Uploads go through the API (`/api/v1/media` → gateway →
  media-service → MinIO over the private network), never directly to MinIO. `<img src>` loads
  need no bucket CORS; only set MinIO bucket CORS if the SPA fetches media via JS.

### Forwarded headers & proxy-aware rate limiting
`SERVER_FORWARD_HEADERS_STRATEGY=framework` makes the gateway honor `X-Forwarded-Proto/Host`
from Caddy (correct scheme/host behind TLS). This is safe **only because the gateway is
reachable solely from Caddy** on the Docker network (it publishes no host port).

WebFlux's forwarded-headers handling fixes scheme/host but **not** the socket peer, so the
gateway derives the anonymous rate-limit client IP itself, in a **trusted-proxy-aware** way
(`ClientIpResolver`):

- It consults `X-Forwarded-For` **only** when the socket peer is in
  **`PARKIO_TRUSTED_PROXIES`** (CIDRs/IPs). The hosted-beta overlay defaults this to the
  Docker/RFC-1918 ranges (`172.16.0.0/12,10.0.0.0/8,127.0.0.1/32`), which is where Caddy
  sits. A direct-to-gateway connection (untrusted peer) → the header is ignored.
- The client is the **right-most non-proxy** hop in the chain, so a client-injected
  left-most `X-Forwarded-For` value can never win (spoofing-resistant). Malformed/empty
  values fall back to the socket peer (fails *closed*, never open).
- Authenticated requests are still keyed by the trusted `X-User-Id` (unchanged).

> **Never trust `X-Forwarded-For` globally.** Keep `PARKIO_TRUSTED_PROXIES` limited to your
> actual proxy/Docker ranges; do **not** add public ranges. Leaving it empty (the default
> outside hosted-beta) means the gateway trusts nothing and keys on the socket peer.

Caddy needs no extra config here: by default it sets `X-Forwarded-For` to the real client and
does not trust client-supplied values. Only add Caddy `trusted_proxies` if Caddy itself runs
behind another load balancer (not the case for a single VPS).

### DNS
Point two records at the VPS public IP:
- `PARKIO_DOMAIN` (e.g. `api.beta.example.com`) → gateway / API
- `PARKIO_MEDIA_DOMAIN` (e.g. `media.beta.example.com`) → media (private MinIO via Caddy)

The hosted frontend (SPA) is served separately; set `PARKIO_CORS_ALLOWED_ORIGINS` to its origin.

### Firewall (VPS)
Allow inbound **22** (SSH, ideally IP-restricted), **80**, **443** only; block everything
else. Do **not** open 5432–5440, 6379, 29092, 9308, 9000/9001, 3000, 9090, 9093, 3100,
or 9080. Caddy needs port 80 for the ACME HTTP-01 challenge and the HTTP→HTTPS redirect.

### First deploy

```bash
# on the VPS, in the repo's docker/ directory
cp .env.hosted-beta.example .env
# edit .env: PARKIO_DOMAIN, PARKIO_MEDIA_DOMAIN, PARKIO_ACME_EMAIL,
# PARKIO_CORS_ALLOWED_ORIGINS, PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT=https://<media domain>,
# PARKIO_EMAIL_PROVIDER=resend, PARKIO_RESEND_API_KEY, PARKIO_EMAIL_FROM,
# PARKIO_ALERT_SLACK_WEBHOOK_URL, every CHANGE_ME password, KAFKA_CLUSTER_ID,
# and PARKIO_JWT_PRIVATE_KEY_PEM.

# generate the RS256 key (PKCS#8) and fold it into .env as a quoted \n-escaped line:
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt_key.pem
#   PARKIO_JWT_PRIVATE_KEY_PEM="$(awk 'BEGIN{ORS="\\n"}1' jwt_key.pem)"   then: rm jwt_key.pem

docker compose -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.hosted-beta.yml up -d --build
```

### First-deploy checklist
1. DNS resolves both hostnames to the VPS.
2. Firewall: only 22/80/443 inbound.
3. `.env` complete — no `CHANGE_ME`, JWT key set, CORS = real frontend origin, media public
   endpoint = `https://${PARKIO_MEDIA_DOMAIN}`, Resend email variables set, and
   `PARKIO_ALERT_SLACK_WEBHOOK_URL` set for hosted-beta alert notifications.
4. `docker compose ... ps` → all healthy; `docker logs parkio-caddy` shows certificates obtained.
   *(Tip: first run against the Let's Encrypt staging CA — commented in `caddy/Caddyfile` — to
   avoid production rate limits, then switch back.)*
5. `curl https://${PARKIO_DOMAIN}/actuator/health` → `{"status":"UP"}`.
6. `curl https://${PARKIO_DOMAIN}/api/v1/auth/.well-known/jwks.json` → a `keys` array.
7. From the SPA: register → login → upload a photo → confirm it renders from
   `https://${PARKIO_MEDIA_DOMAIN}/...`.

### Transactional email
Hosted beta uses Resend for email verification and password reset delivery:

```dotenv
PARKIO_EMAIL_PROVIDER=resend
PARKIO_RESEND_API_KEY=...
PARKIO_EMAIL_FROM="Parkio <verify@beta.example.com>"
PARKIO_EMAIL_REPLY_TO=support@beta.example.com
PARKIO_EMAIL_VERIFICATION_LOG_TOKEN=false
PARKIO_PASSWORD_RESET_LOG_TOKEN=false
```

If `PARKIO_EMAIL_PROVIDER=resend` is set without an API key or from address,
auth-service fails startup. Do not enable raw-token logging outside local dev.

### Alert notifications
Prometheus routes firing rules to Alertmanager at `alertmanager:9093`. The committed
`docker/alertmanager/alertmanager.yml` is a safe local no-op receiver: alerts are visible
in Alertmanager, but no outbound notification is sent.

Hosted beta should set Slack delivery in `.env`:

```dotenv
PARKIO_ALERT_SLACK_WEBHOOK_URL=https://hooks.slack.com/services/...
PARKIO_ALERT_SLACK_CHANNEL="#parkio-alerts"
PARKIO_ALERT_REPEAT_CRITICAL=1h
PARKIO_ALERT_REPEAT_WARNING=4h
```

`render-config.sh` generates the runtime Alertmanager config inside the container, so the
Slack webhook never appears in committed YAML. Critical alerts repeat every hour by
default; warning alerts repeat every four hours. Alertmanager stays loopback-only on the
VPS and is not routed by Caddy.

### Kafka exporter
`parkio-kafka-exporter` connects to the private broker at `kafka:9092` and exposes
broker-level metrics for Prometheus:

- `kafka_consumergroup_lag` for service consumer lag.
- `kafka_topic_partition_current_offset` / `kafka_topic_partition_oldest_offset` for retained
  DLT topic depth (`parkio.dlt.*`).
- `kafka_brokers` for exporter-visible broker count.

The exporter is filtered to Parkio topics (`parkio.*`) and service consumer groups
(`*-service`). It is bound to `127.0.0.1:9308` for admin/debug only and is not routed by
Caddy. Useful Prometheus/Grafana queries:

```promql
max by (consumergroup, topic) (kafka_consumergroup_lag{consumergroup=~".*-service",topic=~"parkio\\..+"})
sum by (topic) (kafka_topic_partition_current_offset{topic=~"parkio\\.dlt\\..+"} - kafka_topic_partition_oldest_offset{topic=~"parkio\\.dlt\\..+"})
up{job="kafka-exporter"}
kafka_brokers
```

DLT topics follow `parkio.dlt.<service>`. The retained offset depth query is a practical
broker-side proxy for poison messages waiting in delete-retention DLT topics; it is not a
business-level count from the applications.

### Centralized logs
Promtail discovers Docker containers through the Docker socket and ships only containers
whose Compose project label is `parkio`. It labels each stream with `service`, `container`,
`compose_project`, and `environment`, then sends logs to Loki. It does not scrape host log
directories, `.env` files, or arbitrary filesystem paths.

Loki keeps logs for `PARKIO_LOKI_RETENTION_PERIOD` (`168h` / 7 days by default). This is
beta-sized retention; increase it only with enough disk headroom in the `loki-data` volume.
Grafana provisions the `Loki` datasource automatically, so use **Explore -> Loki** for logs.

Useful LogQL queries:

```logql
{service="gateway-service"}
{environment="hosted-beta", service="gateway-service"} |= "ERROR"
{compose_project="parkio"} |= "traceId=4bf92f3577b34da6a3ce929d0e0e4736"
{compose_project="parkio"} |= "correlationId=abc-123"
{service="auth-service"} |= "login" |= "failure"
{service="media-service"} |~ "upload|rejected|malware|normalization"
```

Every log line carries `[<service>,traceId=<otel-hex>,spanId=<otel-hex>,correlationId=<uuid>]`.
`traceId`/`spanId` are the OpenTelemetry ids (the `traceId=` token links a log straight to its
Tempo trace via a Grafana derived field); `correlationId` is the user-facing request id echoed in
API errors and `X-Correlation-Id`. Full structured JSON logging is not complete yet. Do not log
secrets: access/refresh tokens, verification/reset tokens, passwords, Resend keys, MinIO
credentials, and DB passwords must stay out of application logs.

### Distributed tracing (Tempo)
Each service exports OpenTelemetry spans over OTLP/HTTP to **Tempo** (`tempo:4318`, never
published), built the Spring-native way: Micrometer Observations → `micrometer-tracing-bridge-otel`
→ `opentelemetry-exporter-otlp` (no second tracing stack). Trace context rides the W3C
`traceparent` header injected by the auto-configured HTTP clients, so one trace spans the gateway
and every downstream service it calls.

- Sampling: `PARKIO_TRACING_SAMPLING_PROBABILITY` (default `1.0` = 100% for beta; lower to
  `0.05`–`0.20` for production). `PARKIO_TRACING_ENABLED=false` turns tracing off.
- Storage: local `tempo-data` volume, retention `PARKIO_TEMPO_RETENTION` (default `48h`).
- Tempo's metrics-generator remote-writes service-graph/span metrics into Prometheus
  (`--web.enable-remote-write-receiver`) to power Grafana's **Service Graph**.
- View in **Grafana → Explore → Tempo**: search traces, read the latency waterfall, open the
  service map, and jump trace↔logs (Tempo `tracesToLogsV2` ↔ Loki derived field). See
  `docs/architecture/observability-metrics.md` for the full trace-lookup workflow and troubleshooting.

### Admin access (no public data ports)
- DB shell: `docker exec -it parkio-postgres-auth psql -U parkio_auth -d parkio_auth`
- Grafana/Prometheus/Alertmanager/Loki/Tempo/Kafka exporter (loopback only):
  `ssh -L 3000:localhost:3000 -L 9090:localhost:9090 -L 9093:localhost:9093 -L 3100:localhost:3100 -L 3200:localhost:3200 -L 9308:localhost:9308 user@vps`,
  then open Grafana (`http://localhost:3000`) and use the provisioned Prometheus/Loki/Tempo
  datasources. Alertmanager is at `http://localhost:9093`; direct Loki admin access is
  `http://localhost:3100`, Tempo's query API is `http://localhost:3200`, and Kafka exporter
  metrics are at `http://localhost:9308/metrics`. Tempo's OTLP receivers (4317/4318) are
  never published — services reach them only in-network.

### Backups & restore (required)

Three scripts under `scripts/` cover the full lifecycle. They `docker exec` into each
`parkio-postgres-*` container over its local socket (no DB password needed/logged) and cover
all nine service databases: auth, user, parking, media, gamification, notification,
moderation, analytics, ai-validation.

```bash
chmod +x scripts/backup-databases.sh scripts/restore-database.sh scripts/verify-backup.sh
```

**Back up** (timestamped, gzip; optional AES-256 + offsite upload):

```bash
PARKIO_ENV_FILE=docker/.env ./scripts/backup-databases.sh    # per-DB dumps -> $BACKUP_DIR/<UTC-stamp>/
```

Schedule nightly via cron on the VPS:

```cron
30 3 * * * cd /opt/parkio && PARKIO_ENV_FILE=docker/.env ./scripts/backup-databases.sh >> /var/log/parkio-backup.log 2>&1
```

Set `BACKUP_ENCRYPT_PASSPHRASE` (encrypt at rest, recommended before any offsite upload) and
`BACKUP_MC_DEST` (an `mc` alias/bucket, e.g. `s3/parkio-backups`) in `.env` to encrypt and push
dumps **off-box** — a backup that only lives on the same VPS does not survive losing the VPS.
`BACKUP_RETENTION_DAYS` prunes old local backups.

**Restore** one database (DESTRUCTIVE — dumps use `--clean --if-exists`, so the target is
overwritten; requires typing the service name unless `--yes`):

```bash
PARKIO_ENV_FILE=docker/.env ./scripts/restore-database.sh auth /var/backups/parkio/<stamp>/auth.sql.gz
# encrypted dump (.sql.gz.enc) needs BACKUP_ENCRYPT_PASSPHRASE in the env:
PARKIO_ENV_FILE=docker/.env ./scripts/restore-database.sh media /var/backups/parkio/<stamp>/media.sql.gz.enc
```

**Verify** a dump is actually restorable WITHOUT touching live data — it restores into a
disposable `<db>_verify_<epoch>` database, asserts the schema came back, then drops it:

```bash
PARKIO_ENV_FILE=docker/.env ./scripts/verify-backup.sh analytics /var/backups/parkio/<stamp>/analytics.sql.gz
```

#### Restore drill — automated, one command

The full restore drill is now a single script, `scripts/restore-drill.sh`, and runs
automatically in CI (`.github/workflows/backup-restore-drill.yml`, weekly + on demand). It
seeds a uniquely-tagged canary row into every service DB, runs the real
`backup-databases.sh`, runs `verify-backup.sh`, then restores each dump into its own
disposable `*_drill_*` database and **asserts the canary survived the round-trip**. For
`parking` it additionally asserts the real PostGIS objects restore — the `postgis`
extension, the `idx_parking_spots_location` GiST index, the `trg_parking_spots_sync_location`
trigger — and that a live spatial query still works. If `parking`'s schema is not already
present (DB-only stack), the drill applies the real `V*.sql` migrations first so the dump
contains the production objects. Live business data is never overwritten; the canary table
is dropped afterwards.

Prerequisite: the Docker daemon and the `parkio-postgres-*` containers must be running.

```bash
# All nine services (recommended):
PARKIO_ENV_FILE=docker/.env ./scripts/restore-drill.sh

# Just the PostGIS path (the trickiest — extension + GiST index + location trigger):
PARKIO_ENV_FILE=docker/.env ./scripts/restore-drill.sh --service parking
```

Exit code `0` = every targeted dump restored with its canary (and parking's PostGIS
objects) intact; non-zero = at least one service failed.

**Run it on the target host before relying on backups, then monthly and after any
schema-heavy release.** Additionally, if encrypted/offsite is configured, pull a dump
back from `BACKUP_MC_DEST` and run `verify-backup.sh` against **that** copy — it proves
the offsite path, not just the local file. Record the date + result: a backup you have
never restored is not a backup.

#### RPO / RTO (hosted beta)
- **RPO ≈ 24h** with the nightly schedule (worst case: a full day of writes since the last dump).
  Tighten by running the backup more often (e.g. every 6h) — these are logical dumps, not WAL, so
  there is no point-in-time recovery between dumps.
- **RTO ≈ minutes per database** (restore is a single gzip→psql stream; total depends on dump size
  and how many services you restore).
- True PITR + automatic failover requires **managed Postgres** and is a public-production blocker —
  see `docs/architecture/production-readiness.md` §3.

### Secret & key rotation (zero-downtime)

Both rotations are designed so the old and new credential **overlap** — no coordinated
restart, no logout storm. Never put a private key or secret in git; only edit `.env`.

**JWT signing key (RS256).** auth-service signs with one active key but the gateway (and
auth itself) verify by `kid` against every key in the JWKS, so old access tokens stay valid
until they expire.

1. Generate a new key + kid:
   `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out new.pem`
2. Export the **current** key's public PEM for the JWKS overlap:
   `openssl pkey -in current.pem -pubout`
3. In `.env`: set `PARKIO_JWT_ADDITIONAL_PUBLIC_KEYS_JSON` to a JSON array including the
   **old** key `[{"kid":"<old-kid>","pem":"-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----"}]`,
   then set `PARKIO_JWT_PRIVATE_KEY_PEM`/`PARKIO_JWT_KEY_ID` to the **new** key/kid.
4. Redeploy auth-service. The gateway refreshes its JWKS cache within
   `parkio.security.jwt.jwks-cache-ttl` (15m) — new tokens use the new kid; old tokens still
   verify against the old public key.
5. Wait at least one access-token TTL (`PARKIO_JWT_ACCESS_TTL`, ~15m) so all old tokens expire.
6. Clear `PARKIO_JWT_ADDITIONAL_PUBLIC_KEYS_JSON` and redeploy auth-service. Done.

**Gateway internal secret (`X-Gateway-Auth`).** The gateway sends one secret; downstream
accepts a set (current + previous) during the window.

1. In `.env`, add the **new** secret to every downstream service's accepted list:
   `PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS=<new-secret>` (keep
   `PARKIO_GATEWAY_INTERNAL_SECRET` as the old value for now), then redeploy the downstream
   services. They now accept old **and** new.
2. Flip `PARKIO_GATEWAY_INTERNAL_SECRET` to the **new** secret and redeploy the gateway. It now
   sends the new secret, which downstream already accepts.
3. After the rollout settles, set every service's `PARKIO_GATEWAY_INTERNAL_SECRET` to the new
   value and clear `PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS`, then redeploy. The old secret is
   retired.

Both knobs default to empty (no rotation in progress). Comparisons are constant-time and
secrets are never logged. A blank `PARKIO_GATEWAY_INTERNAL_SECRET` still fails closed.

### Update / rollback / cleanup

```bash
# update to new code
git pull && docker compose -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.hosted-beta.yml up -d --build
# rollback: check out the previous known-good commit/tag, then re-run the same up --build
# stop (keep data):
docker compose -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.hosted-beta.yml down
# wipe (DESTROYS data AND issued TLS certs):
docker compose -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.hosted-beta.yml down -v
```

Image-tag-based rollback and CD arrive in Phase 2; this beta rebuilds from source.

### Validate the merged config (no Docker daemon work)

```bash
docker compose -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.hosted-beta.yml config
```

### Caveats
- **Single VPS — not public production.** One box = a single point of failure.
- **Kafka RF=1** (one broker): un-consumed events are lost if the broker's disk is lost (the
  Postgres outbox bounds this and consumers are idempotent). RF≥3 is a public-prod blocker.
- **Single-node Postgres/MinIO**, no PITR/HA → **backups are mandatory** and must be test-restored.

## Persistence

Each stateful component has a named volume (e.g. `postgres-parking-data`,
`kafka-data`, `minio-data`, `grafana-data`). Data survives `docker compose down`.

```bash
docker compose down       # stop, keep data
docker compose down -v    # stop and DELETE all volumes (fresh start)
```

## Metrics note

Prometheus is preconfigured to scrape each service at `/actuator/prometheus`.
Every service ships `micrometer-registry-prometheus` and exposes the endpoint
(only `health`/`info`/`prometheus` — never sensitive actuator endpoints), so all
targets should report **up** once the stack is healthy. The custom metric
catalogue (outbox/inbox backlog, notification delivery, parking lifecycle,
auth login, media upload, gateway rate-limit counters) is documented in
`docs/architecture/observability-metrics.md`. Kafka DLT depth and consumer lag
are monitored at the broker level (see the same doc).

## Single-image builds

```bash
# build context is the repository root
docker build -f ../services/auth-service/Dockerfile -t parkio/auth-service ..
```
