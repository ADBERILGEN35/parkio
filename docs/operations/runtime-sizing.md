# Runtime Sizing & Production Runtime Hardening (P1.5)

This document is the source of truth for **how the Parkio Docker Compose deployment is
sized and made operationally safe** on a single VPS. It covers resource ceilings, JVM
memory, the container health model, graceful shutdown, log/disk growth, and the known
runtime limitations of the single-host beta topology.

It does **not** change architecture, services, APIs, schemas, Kafka contracts, or auth.
It governs the *runtime* only. Kubernetes is explicitly out of scope.

---

## 1. Target host

| Profile | vCPU | RAM | Notes |
| --- | --- | --- | --- |
| **Recommended (all-in-one)** | 8 | **24 GB** | App + data + observability on one box, with headroom. |
| **Minimum (all-in-one)** | 8 | **16 GB** | Tight; move the observability plane off-box, or accept less cache headroom. |
| Split (app/data box + obs box) | 6 + 2 | 16 GB + 4 GB | Observability on a small second VPS drops the app host to ≈ 13 GB of ceilings. |

The sum of all `mem_limit` **ceilings** is ≈ **15.8 GB**. Ceilings are hard cgroup caps,
not reservations — real steady-state usage for a low-traffic beta is roughly half that.
The contract we hold is: **sum(mem_limit) ≤ ~85 % of host RAM**, so even if every container
simultaneously hit its ceiling, the kernel never has to OOM-kill an innocent neighbour.

> **Why ceilings at all?** Before P1.5 there were *no* limits and every JVM ran with
> `MaxRAMPercentage=75` against **total host RAM** — 10 JVMs each sizing their heap to 75 %
> of the whole box. The first real memory spike would have cascaded into host-wide OOM
> kills. Ceilings + a heap sized to 65 % *of the ceiling* + `ExitOnOutOfMemoryError` make
> memory pressure local, predictable, and self-healing.

---

## 2. Resource allocation table

`mem_limit` / `cpus` / `pids_limit` are set in `docker/docker-compose.hosted-beta.yml`
(host-specific). JVM flags, healthchecks, log rotation and shutdown grace are in
`docker/docker-compose.apps.yml` (environment-agnostic).

### Application services (JVM)

JVM heap = `MaxRAMPercentage=65 %` of `mem_limit`; the remaining ~35 % is the native
footprint (metaspace ≤ 192 MB, thread stacks, code cache, direct/NIO buffers, GC).

| Service | Memory | CPU | JVM Heap (≈65%) | Reason |
| --- | --- | --- | --- | --- |
| gateway-service | 640 MB | 1.5 | ~416 MB | Reactive edge; CPU-heavy (JWT verify, JWKS, rate-limit, per-request status/epoch lookups). |
| auth-service | 768 MB | 1.5 | ~500 MB | Busiest security path: BCrypt, RSA signing, JWKS, refresh rotation, Redis throttles. |
| user-service | 640 MB | 1.0 | ~416 MB | JPA profile reads + the gateway account-status lookups. |
| parking-service | 768 MB | 1.5 | ~500 MB | PostGIS spatial search, geocoding (Resilience4j), Redis cache — heaviest read path. |
| media-service | 768 MB | 1.5 | ~500 MB | Multipart upload buffering, MinIO SDK, ClamAV streaming. |
| gamification-service | 640 MB | 1.0 | ~416 MB | JPA + Kafka consumer + Redis. |
| notification-service | 640 MB | 1.0 | ~416 MB | JPA + Kafka consumer + outbox relay. |
| moderation-service | 640 MB | 1.0 | ~416 MB | JPA + Kafka consumer/producer. |
| ai-validation-service | 640 MB | 1.0 | ~416 MB | JPA + Kafka (advisory). |
| analytics-service | 640 MB | 1.0 | ~416 MB | JPA write path + Kafka consumer. |
| **Subtotal** | **6.6 GB** | | | 10 services. |

All app services additionally inherit (from the apps overlay): `pids_limit: 512`,
`stop_grace_period: 45s`, `cap_drop: [ALL]`, `security_opt: [no-new-privileges:true]`,
readiness healthcheck, and log rotation.

### Data plane

| Service | Memory | CPU | Reason |
| --- | --- | --- | --- |
| postgres-auth | 320 MB | 1.0 | Hot auth writes/reads. |
| postgres-parking | 384 MB | 1.0 | PostGIS + GiST index — heaviest DB. |
| postgres-analytics | 320 MB | 0.75 | Analytics write volume. |
| postgres-user/media/gamification/notification/moderation/ai-validation | 256 MB each | 0.5 each | Light per-service DBs (shared_buffers default 128 MB + work_mem + conns). |
| redis | 384 MB | 0.5 | Caches, rate-limit token buckets, login-failure/idempotency keys (appendonly). |
| kafka | 1280 MB | 2.0 | `KAFKA_HEAP_OPTS=-Xmx768m`; the rest is OS page cache (Kafka depends on it). |
| minio | 512 MB | 1.0 | S3 object storage for media bytes. |
| clamav | 1536 MB | 2.0 | **Dominant single consumer** — loads the full signature DB (~1.3 GB) into RAM. |
| kafka-exporter | 128 MB | 0.25 | Metrics exporter. |
| **Subtotal** | **≈ 6.4 GB** | | |

Postgres also gets `pids_limit: 256` and `stop_grace_period: 60s` (clean shutdown
checkpoint); Kafka gets `pids_limit: 512` and `stop_grace_period: 60s` (KRaft log flush).

### Observability plane

| Service | Memory | CPU | Reason |
| --- | --- | --- | --- |
| prometheus | 1024 MB | 1.0 | 15-day TSDB + remote-write receiver + exemplars. |
| loki | 512 MB | 1.0 | Log store (168h retention, compactor). |
| tempo | 512 MB | 1.0 | Trace store (48h block retention). |
| grafana | 384 MB | 0.5 | Dashboards. |
| promtail | 128 MB | 0.5 | Log shipper. |
| alertmanager | 128 MB | 0.25 | Alert routing. |
| node-exporter | 64 MB | 0.25 | Host metrics. |
| caddy | 256 MB | 0.5 | TLS reverse proxy (hosted-beta only). |
| **Subtotal** | **≈ 3.0 GB** | | incl. Caddy. |

**Grand total ceilings ≈ 15.8 GB** → recommended **24 GB** host (≈ 66 % committed, the rest
for OS + filesystem cache), minimum **16 GB** with observability moved off-box.

> CPU `cpus` values are **ceilings** and are intentionally oversubscribed (sum > 8): CPU is
> compressible (throttle, not kill) and the workloads do not peak simultaneously. Memory is
> not compressible, so memory ceilings are sized to fit; CPU ceilings are sized to bound any
> single runaway.

---

## 3. JVM memory model

Set once in `docker-compose.apps.yml` via `JAVA_TOOL_OPTIONS` (override per-env if needed):

```
-XX:MaxRAMPercentage=65.0      # heap ≤ 65% of the container mem_limit
-XX:InitialRAMPercentage=40.0  # commit a sane starting heap, reduce resize churn
-XX:MaxMetaspaceSize=192m      # cap a common native-OOM source (classloader leaks)
-XX:+UseG1GC                   # explicit, predictable collector for these heap sizes
-XX:+ExitOnOutOfMemoryError    # heap OOM -> fast exit -> `restart: unless-stopped` recycles
```

- **Why 65 %, not 75 %?** The native footprint (metaspace, ~thread stacks, code cache,
  direct buffers) is roughly fixed regardless of heap. On a 640–768 MB container, leaving
  35 % (~224–268 MB) for native keeps RSS under the cgroup limit, so the kernel does not
  `SIGKILL` the container. 75 % left too little and risked silent OOM-kills.
- **`ExitOnOutOfMemoryError`** converts a heap exhaustion into a clean, fast restart instead
  of an endless GC-thrash. It pairs with the existing `HighJvmMemoryUsage` Prometheus alert
  (heap > 90 % for 10 m) which warns *before* the exit.
- Hikari pool (default 10) and Tomcat threads (default 200) are left at Spring defaults
  deliberately — database-per-service keeps connection counts isolated, and the
  `DatabaseConnectionPoolExhausted` alert covers the pool. Tuning these is a follow-up, not
  a launch blocker (avoid over-tuning).

---

## 4. Health model

Every app container has a Docker `HEALTHCHECK` (defined in the apps overlay) that probes
the Spring Boot Actuator **readiness** endpoint with `curl` (added to the runtime image):

```
curl -fsS http://localhost:<port>/actuator/health/readiness
interval: 15s  timeout: 5s  retries: 5  start_period: 60s
```

- **Readiness vs liveness.** We gate the *startup graph* on readiness: a service is healthy
  for its dependents only once the Spring context has fully started (Flyway migrated,
  datasource pool up, Kafka admin connected) and the `ReadinessState` is
  `ACCEPTING_TRAFFIC`. This is exactly the signal `depends_on: condition: service_healthy`
  needs. Liveness (`/actuator/health/liveness`) remains available for external watchdogs.
- **Probe is unauthenticated by design.** `/actuator/**` is excluded from each service's
  `GatewayAuthFilter` and `permitAll` in Spring Security; the reactive gateway marks
  `/actuator/health/**` public. The probe runs *inside* the container against localhost, so
  it never traverses the gateway.
- **Startup ordering.** `gateway-service` now waits for `auth-service` and `user-service` to
  be **healthy** (in addition to Redis), removing the window of fail-closed 503s while JWKS /
  account-status dependencies are still booting. App services wait for their Postgres + Kafka
  (+ Redis / MinIO / ClamAV where used) to be healthy first.

> **Known limitation:** plain Docker Compose does **not** auto-restart a container that goes
> *unhealthy* but is still running (a hung/deadlocked JVM). `restart: unless-stopped` only
> reacts to process exit (which `ExitOnOutOfMemoryError` guarantees for heap OOM). Auto-
> restart on `unhealthy` needs an external watchdog (e.g. `willfarrell/autoheal`) or an
> orchestrator — tracked in the follow-up backlog.

---

## 5. Graceful shutdown

- Each service sets `server.shutdown: graceful` in `application.yml`. On `SIGTERM`, the HTTP
  server stops accepting new requests and lets in-flight ones finish before the context
  closes — for both the MVC services and the reactive gateway.
- The drain budget is `spring.lifecycle.timeout-per-shutdown-phase=30s`, injected centrally
  via `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE` in the apps overlay.
- Compose `stop_grace_period` is **45s** for app services (> 30s drain) and **60s** for
  Postgres/Kafka, so Docker never escalates to `SIGKILL` mid-drain or mid-checkpoint.
- **Signal delivery is correct without `tini`:** the Dockerfile `ENTRYPOINT` is exec-form
  (`["java","-jar",...]`), so the JVM is PID 1 and receives `SIGTERM` directly. The services
  spawn no child processes, so there are no zombies to reap — `tini` would add nothing and is
  intentionally omitted.
- Outbox relay, Kafka listeners (manual ack), the HTTP server, the Hikari pool and Redis
  connections are all Spring-managed beans and are closed in dependency order by the same
  graceful shutdown — no extra wiring needed.

---

## 6. Disk & log growth

| Source | Bound | Where |
| --- | --- | --- |
| Container stdout/stderr (json-file) | 50 MB/container (5 × 10 MB rotated) | `x-logging` anchor in base + apps overlays |
| Prometheus TSDB | 15 days | `--storage.tsdb.retention.time=15d` |
| Loki logs | 168h + compactor delete | `loki.yml` (`PARKIO_LOKI_RETENTION_PERIOD`) |
| Tempo traces | 48h block retention | `tempo.yml` (`PARKIO_TEMPO_RETENTION`) |
| Kafka topic logs | 7 days / 2 GiB per partition, 256 MiB segments | `KAFKA_LOG_RETENTION_*` (beta overlay) |
| Outbox/inbox tables | 7 days / 30 days | `parkio.lifecycle.retention.*` (RetentionCleanupJob) |
| Postgres data / MinIO media | **unbounded** (real data) | Monitored, not capped — see below |

Postgres volumes and MinIO media are genuine business data and are deliberately not capped.
They are covered operationally by host-level alerts already in `prometheus/alerts.yml`:
`HostDiskSpaceLow`, `HostDiskSpaceCritical`, `HostDiskWillFillSoon`, `HostInodesLow/Critical`
(from node-exporter). Run the backup job (`scripts/backup-databases.sh`) on a schedule and
watch those alerts; storage expansion is a manual, planned operation on a single VPS.

---

## 7. Container security posture

- Non-root: images run as system uid `10001` (`USER parkio`), no login, no home.
- `no-new-privileges:true` + `cap_drop: [ALL]` on every app container (HTTP/JVM services
  need no Linux capabilities; they bind ports > 1024).
- `pids_limit` caps thread/process explosions.
- Only one extra runtime package (`curl`) is installed — solely for the readiness probe.
- **Read-only root filesystem is a deliberate follow-up, not yet enabled.** It needs live
  validation of Tomcat multipart temp + heap-dump paths under real upload load (which this
  sprint could not run — no Docker daemon in the build env). The ready-to-apply snippet:
  `read_only: true` + `tmpfs: /tmp` (sized for media multipart), verified against an upload
  smoke test, then enabled per service.

---

## 8. Known runtime risks (unchanged by this sprint)

These are topology limits of the single-host beta, documented in
`docs/architecture/production-readiness.md`. P1.5 does not (and is not meant to) fix them:

- **Single Kafka broker, RF=1** — `acks=all` only guarantees one replica; broker disk loss
  loses unconsumed events. Outbox rows are the durable replay source.
- **Single-node Postgres ×9, no PITR** — backups are logical `pg_dump` (CI restore-drilled);
  RPO = time since last dump. No WAL archiving / streaming replica.
- **Single Redis / single MinIO** — no replication; loss degrades rate-limiting/sessions /
  media respectively.
- **No auto-restart on `unhealthy`** (hung JVM) without an external watchdog — see §4.
- **Secrets via environment/.env** — not a secret manager.

---

## 9. Operational quick reference

```bash
# Full hosted-beta stack (base + apps + beta overlay, in that order):
docker compose \
  -f docker-compose.yml \
  -f docker-compose.apps.yml \
  -f docker-compose.hosted-beta.yml \
  up -d --build

# Watch health roll-up:
docker compose ps

# Confirm a service became healthy (readiness):
docker inspect --format '{{.State.Health.Status}}' parkio-auth-service  # via container name/id

# Verify a JVM honoured its container limit (heap should be ~65% of mem_limit):
docker exec <ctr> java -XX:+PrintFlagsFinal -version | grep -E 'MaxHeapSize|MaxRAMPercentage'

# Graceful stop (sends SIGTERM, waits stop_grace_period):
docker compose stop auth-service
```
