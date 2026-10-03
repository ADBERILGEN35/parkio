# Container hardening inventory (CL-F29.3)

Every service of the production Compose models runs with `security_opt: no-new-privileges:true`
and `cap_drop: [ALL]`; a service adds back only the capabilities listed below. Since B8 every
service except ClamAV also runs with a read-only root filesystem (`read_only: true`). It writes
only to its volumes and to the tmpfs mounts listed under "Read-only root filesystem".
`scripts/assert-compose-hardening.sh` renders the models and fails on any other state
(`scripts/lib/assert-compose-hardening.mjs`, run by the invite-production PR job).

Production models checked:

| Model | Files | Started services |
|---|---|---|
| invite-production, dark and public edge | `scripts/lib/deploy-common.sh` (`docker-compose.yml`, `apps`, `images`, `hosted-beta`, `managed-db`, `invite-dark` or `invite-public`) | `PARKIO_INVITE_RUNTIME_SERVICES` (+ `caddy` once ACME is authorized); promtail and the PostgreSQL containers are disabled |
| Civo production | `docker/compose.production.files` + `docker-compose.civo-alertmanager.yml` (`scripts/parkio-prod-compose.sh`) | the model has no promtail, Loki or Tempo; PostgreSQL runs in containers |

Not covered: activation-only overlays outside these file sets (New Relic log pilot and its
continuous-operation overlay, which already set `cap_drop`/`no-new-privileges`/`read_only` in
`docker-compose.newrelic-log-pilot.yml`; the Slack business worker; the alerting acceptance
stack; the restored-application staging verification stack).

## Per service

"Runs as" is the image default. Capabilities are added back only where the image starts as
root and needs them to prepare a data directory or switch to its service user.

| Service | Image | Runs as | `cap_add` | Why |
|---|---|---|---|---|
| auth, user, parking, media, gamification, notification, moderation, ai-validation, analytics, gateway | Parkio JVM images | 10001 | — | unchanged (`x-app-common`) |
| postgres-* (10) | `postgres:16-alpine`, `postgis/postgis:16-3.4` | root → postgres | CHOWN, DAC_OVERRIDE, FOWNER, SETGID, SETUID | the entrypoint creates and chowns `PGDATA`, then switches to postgres |
| redis | `redis:7-alpine` | root | DAC_OVERRIDE | the command is a shell wrapper, so the entrypoint does not switch to redis; redis-server runs as root and writes into `/data`, which the image owns as redis |
| clamav | `clamav/clamav:1.4` | root → clamav | CHOWN, DAC_OVERRIDE, FOWNER, SETGID, SETUID | the init script prepares the database and run directories, then runs freshclam and clamd as clamav |
| web | Parkio web (nginx 1.30) | root → nginx | CHOWN, NET_BIND_SERVICE, SETGID, SETUID | the nginx master binds port 80, prepares its cache directories and starts workers as nginx |
| caddy | `caddy:2.8-alpine` | root | NET_BIND_SERVICE | binds 80/443 |
| minio | Parkio MinIO (GHCR pin) | root | — | root owns its own data volume |
| minio-setup | Parkio mc (GHCR pin) | root | — | one-shot client; `MC_CONFIG_DIR=/tmp/.mc` because `/root` in the image is 0550 and root no longer has DAC_OVERRIDE |
| kafka | `confluentinc/cp-kafka:7.7.1` | appuser | — | |
| kafka-exporter | `danielqsj/kafka-exporter:v1.8.0` | nobody | — | |
| blackbox-exporter | `prom/blackbox-exporter:v0.25.0` | root | — | HTTP and TCP probes only (ICMP would need NET_RAW) |
| prometheus | `prom/prometheus:v2.54.1` | nobody | — | |
| alertmanager | `prom/alertmanager:v0.27.0` | nobody | — | |
| loki | `grafana/loki:2.9.8` | 10001 | — | |
| tempo | `grafana/tempo:2.6.1` | 10001 | — | |
| grafana | `grafana/grafana:11.2.0` | 472 | — | |
| promtail | `grafana/promtail:2.9.8` | root | — | see exception 1 |
| node-exporter | `prom/node-exporter:v1.8.2` | nobody | — | see exception 2 |

## Documented exceptions

1. **promtail mounts `/var/run/docker.sock`.** A read-only bind does not restrict the Docker
   API, so this is root-equivalent access to the host's Docker daemon. promtail is not started
   in either production model (invite-production disables it; the Civo model has none); it runs
   in the default and hosted-beta stacks and in CI. Replacing it is an owner decision: a socket
   proxy needs a new pinned image (image publication is outside this change), and file-based
   collection from `/var/lib/docker/containers` changes the Loki labels that dashboards and
   alerts use. Until then the guard allows the socket for promtail only.
2. **node-exporter shares the host PID namespace** and mounts `/`, `/proc` and `/sys` read-only
   for host metrics; it runs as nobody with no capabilities.
3. **Root-start images stay root-start** (PostgreSQL, Redis, ClamAV, nginx, Caddy, MinIO, mc,
   blackbox-exporter, promtail). Switching them to a non-root user changes the owner their
   existing data volumes need, which is a host-side migration outside this change; with
   `cap_drop: [ALL]` root keeps only the capabilities listed above.
4. **ClamAV keeps a writable root filesystem.** Its init script runs
   `ln -f -s /run/lock /var/lock` at every start. That rewrites a symlink inside `/var`, which a
   tmpfs cannot cover without hiding the rest of `/var`. The local probe failed on exactly that
   (`ln: /var/lock/lock: Read-only file system`). Changing this needs a different init or image.

## Read-only root filesystem (B8)

| Service | tmpfs | Why |
|---|---|---|
| Parkio JVM services (10) | `/tmp` (128 MB) | JVM perf data, Tomcat/Netty work files, multipart uploads (media: up to 12 MB per part). tmpfs counts against the container memory limit. |
| postgres-* (10) | `/var/run/postgresql`, `/tmp` | socket and lock files; `PGDATA` is the volume |
| redis | `/tmp` | data in the `/data` volume |
| kafka | `/tmp`, `/etc/kafka` (1777), `/var/log/kafka` (1777) | the cp-kafka entrypoint renders `/etc/kafka` from its templates and writes GC logs at every start |
| minio, minio-setup | `/tmp` | data in `/data`; mc keeps its config in `/tmp/.mc` |
| alertmanager | `/tmp` | `render-config.sh` writes the runtime config there |
| loki, tempo, grafana | `/tmp` | data in their volumes |
| web | `/etc/nginx/conf.d`, `/var/cache/nginx`, `/var/run`, `/tmp` | the entrypoint renders `conf.d` from the image template (B9, #198), so this needs the B9 image |
| caddy | `/tmp` | certificates and config in the `caddy-data`/`caddy-config` volumes |
| prometheus, kafka-exporter, blackbox-exporter, node-exporter, promtail | — | write only to their volumes, or nothing |

Each image was started locally the way the production model starts it: `cap_drop ALL`,
`no-new-privileges`, the same `cap_add`, `--read-only` and these tmpfs mounts. Each was checked
for readiness and one real operation, restarted once (which empties the tmpfs), and checked
again:
- PostgreSQL and PostGIS: a table write survives the restart;
- Redis: SET plus an AOF rewrite, and the key survives;
- Kafka: a topic survives the restart;
- MinIO with mc: bucket and object;
- the HTTP readiness of Prometheus, Alertmanager, Loki, Tempo, Grafana, Caddy, the exporters and
  node-exporter.

None logged a read-only or permission error. The evidence is in
`agent-tools/parkio-u18-readonly-rootfs/` (not committed). The JVM services and web are covered
by the CI runtime, chaos and performance workflows, which start the full stack.

## Verification

- `./scripts/assert-compose-hardening.sh` — static check of the three models.
- Runtime: the CI workflows that start the stack (runtime validation, chaos, observability,
  performance smoke, backup restore drill) run it hardened. The local check for this change
  (`agent-tools/parkio-u18-container-hardening/`) started the infrastructure services from
  volumes created without hardening and from fresh volumes, and ran write, topic, scan and
  readiness probes in each.
