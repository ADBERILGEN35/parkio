# Container hardening inventory (CL-F29.3)

Every service of the production Compose models runs with `security_opt: no-new-privileges:true`
and `cap_drop: [ALL]`; a service adds back only the capabilities listed below. Since B8 every
service, and since B8b web too, also runs with a read-only root filesystem (`read_only: true`). It
writes only to its volumes and to the mounts listed under "Read-only root filesystem".
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
   - **`Config.Env` exposure (owner decision 5, 2026-10-05).** Through the Docker API, promtail can
     read every container's environment, secrets included. A socket proxy cannot filter that
     (`../architecture/docker-socket-proxy-design.md`, "What remains exposed").
   - The exposure is accepted only for the current non-production promtail setup: the default
     development stack and CI.
   - The hosted-beta deploy profile is not covered. It is a supported deployment path (CL-F12,
     decision 1b). Since CL-F12 it no longer starts promtail, but a promtail container from an
     earlier hosted-beta deploy keeps running with the socket until an operator stops it, so its
     exposure stays open until then.
   - Production enablement stays blocked until a secrets-exposure solution has been reviewed.
   - This records the state. It changes no live configuration and closes no operational criterion.
2. **node-exporter shares the host PID namespace** and mounts `/`, `/proc` and `/sys` read-only
   for host metrics; it runs as nobody with no capabilities.
3. **Root-start images stay root-start** (PostgreSQL, Redis, ClamAV, nginx, Caddy, MinIO, mc,
   blackbox-exporter, promtail). Switching them to a non-root user changes the owner their
   existing data volumes need, which is a host-side migration outside this change; with
   `cap_drop: [ALL]` root keeps only the capabilities listed above.

## Read-only root filesystem (B8)

Every tmpfs has a size, because its pages count against the container's memory limit. The sizes
are caps, not reservations. "Used" is what the local probes measured after real work and a
restart (`du` inside the container).

| Service | Mounts besides volumes (size) | Used | Why |
|---|---|---|---|
| Parkio JVM services except media (9) | tmpfs `/tmp` (128 MB) | not probed locally | JVM perf data, Tomcat and Netty work files: kilobytes. The cap leaves room for library temp files under a 512–768 MB limit. |
| media-service | anonymous volume `/tmp` (disk) | — | Tomcat writes each multipart upload (up to 15 MB per request) to `/tmp` before the service reads it. As tmpfs, concurrent uploads would count against the 768 MB limit next to a 65% heap. |
| postgres-* (10) | `/var/run/postgresql` (1 MB), `/tmp` (16 MB) | 4 KB, 0 | socket, lock and pid files; `PGDATA` is the volume |
| redis | `/tmp` (8 MB) | 0 | data in `/data` |
| kafka | `/tmp` (16 MB), `/etc/kafka` (8 MB, 1777), `/var/log/kafka` (32 MB, 1777) | 32 KB, 16 KB, 28 KB | The entrypoint renders `/etc/kafka` from its templates. `KAFKA_GC_LOG_OPTS` keeps at most three 8 MB GC logs; the image default is ten of 100 MB. |
| minio, minio-setup | `/tmp` (16 MB, 8 MB) | — | data in `/data`; mc keeps its config in `/tmp/.mc` |
| alertmanager | `/tmp` (8 MB) | 4 KB | `render-config.sh` writes the runtime config there |
| loki, tempo | `/tmp` (16 MB) | 0 | data in their volumes |
| grafana | `/tmp` (32 MB) | 0 | data in its volume; headroom for plugin and export temp files |
| caddy | `/tmp` (8 MB) | 0 | certificates and config in the `caddy-data`/`caddy-config` volumes |
| web | `/etc/nginx/conf.d` (1 MB, 755), `/run` (1 MB, 755), `/tmp` (8 MB), `/var/cache/nginx` (8 MB, 755) | 4 KB, 4 KB, 0, 0 | The entrypoint renders `/etc/nginx/conf.d/default.conf` from the CSP template at start (B9, #198). nginx writes its pid to `/run` (`/var/run` links there) and creates its temp directories in `/var/cache/nginx`, owned by nginx; it serves static files, so they stay empty. Mode 755 keeps the root-owned config and pid directories closed to the worker user. See "web images from before #198" below. |
| clamav | `/tmp` (128 MB), `/run/clamav` (1 MB), `/run/lock` (1 MB), `/var/log/clamav` (16 MB) | 0, 0, 0, 8 KB | clamd's socket and the stream of each scan go to `/tmp`; media caps an upload at 12 MB, so ten concurrent scans fit, and a full tmpfs fails the scan, which media treats as unavailable (fail closed). `/var/lock` links to `/run/lock`, where the init script creates its lock link. clamd and freshclam cap each log at 1 MB by default. |
| prometheus, kafka-exporter, blackbox-exporter, node-exporter, promtail | none | — | write only to their volumes, or nothing |

**web images from before #198.** Earlier images ship their server config in
`/etc/nginx/conf.d`, so the tmpfs hides it. nginx then starts without a server, the healthcheck
fails and Caddy answers 502. A local probe of the current Civo pin (`aacf9dc9`, source `3bb89c6c`)
showed it: under these mounts `/login` was refused before and after a restart.

`scripts/parkio-prod-compose.sh` and `parkio_compose_up` (`scripts/lib/deploy-common.sh`) run
`scripts/lib/web_conf_d_guard.py` on the rendered model after the web map guard has bound the image:
- **Gate.** It checks only a model that mounts a tmpfs at `/etc/nginx/conf.d` for web.
- **Inspection.** It creates the bound image without starting it, copies
  `/etc/nginx/templates/default.conf.template` out as a tar stream to confirm a non-empty file, and
  removes the container with its anonymous volumes. Nothing is printed from the file.
- **Refusal.** An image without the template, or one it cannot inspect, stops the command before
  anything starts. The message names the fix: move the web pin
  (`docker/docker-compose.web-release-pin.yml`) to an image built from #198 or later.

The check has its own break-glass, `PARKIO_SKIP_WEB_CONF_D_CHECK=I_ACCEPT_UNCHECKED_WEB_CONF_D`, off by
default. The map guard's break-glass `PARKIO_SKIP_WEB_MAP_GUARD` does not skip it: without a binding,
the check inspects the image the model names (owner decision 4, 2026-10-05).

**Web API endpoint check (H2, owner decision 2026-10-05).** Hosted-beta must not silently use
production's API.
- **Where it runs.** After the two checks above, on the same rendered model: `scripts/parkio-prod-compose.sh` for every command that can start web, and `parkio_compose_up` for the hosted-beta profile (deploy and rollback). It is `scripts/lib/web_api_endpoint_guard.py`.
- **Intended endpoint.** Web's `VITE_API_BASE_URL` build argument in the model, which comes from the env file. When the model sets `PARKIO_DOMAIN` (Caddy), the endpoint's host must be that domain.
- **Inspection.** It creates the bound image, or the model's image when the map guard's break-glass left no binding, with `--pull never --network none`, without starting it. It copies `/usr/share/nginx/html` out and reads the `VITE_API_BASE_URL` that Vite inlined, without executing anything.
- **Comparison.** Scheme, host, port and path must be equal. The case of the scheme and host, a default port and a trailing slash are ignored.
- **Refusal.** A different endpoint, a model without one, an endpoint off `PARKIO_DOMAIN`, an unreadable bundle, or a missing or ambiguous baked URL stops the command before anything starts.
- **No break-glass.** Neither `PARKIO_SKIP_WEB_MAP_GUARD` nor `PARKIO_SKIP_WEB_CONF_D_CHECK` skips it.
- **Limit.** When the map guard's break-glass leaves no binding, Compose can still pull or build another web image during `up`. The conf.d check has the same limit.
- **Scope.** Invite-production and the deprecated Azure profile are not covered.
- **Tests.** `scripts/test_web_api_endpoint_guard.py` (the guard), `scripts/test_web_api_endpoint_models.py` (the real hosted-beta and Civo models with their example env files) and `scripts/test-guard-web-synthetic-map-deploy.sh` (the wrapper and `parkio_compose_up`, including a real Docker case).

**Release step.** The current Civo pin predates #198. Move it before the first deploy that
includes this change; until then the deploy commands refuse to start web.

**Rollback.** With these compose files, the check also refuses a rollback of web to an image built
before #198. `docs/beta/rollback-runbook.md` ("Web images built before #198") names the supported
paths: forward to a #198+ image, or back with the older release's compose files.

**The guard records these mounts.** `scripts/lib/assert-compose-hardening.mjs` fails when:
- a tmpfs has no size;
- a tmpfs allows exec;
- a service's tmpfs list differs from the recorded one (size and options included);
- a service mounts an anonymous volume other than the recorded scratch volumes (media's `/tmp`).

A change to any of these is therefore a reviewed change to the guard. The Civo drift check leaves
them to the guard.

**media's scratch volume.**
- Lifecycle: Compose keeps an anonymous volume when it recreates the container, so `/tmp` survives
  deploys.
- What it holds: Tomcat deletes each upload's temp file when the request ends, so the volume holds
  only Tomcat's work directories and files from requests that a crash interrupted.
- To start it empty, recreate the service with `docker compose up -d --renew-anon-volumes
  media-service`. `docker compose down` leaves the old volume dangling; `down -v` removes it.

**Local probes.** Each image was started locally the way the production model starts it:
`cap_drop ALL`, `no-new-privileges`, the same `cap_add`, `--read-only`, and these mounts with their
sizes. Each was checked for readiness and one real operation, restarted once (which empties the
tmpfs), and checked again:
- web (B8b): the image built from api `aa6aab88`. `/login` answers 200 before and after the
  restart, the CSP header is rendered, and the mounts show `noexec,nosuid,nodev` with the sizes
  and modes above.
- PostgreSQL and PostGIS: a table write survives the restart.
- Redis: SET plus an AOF rewrite, and the key survives.
- Kafka: a topic survives the restart, and the running JVM uses the capped GC options.
- MinIO with mc: bucket and object.
- ClamAV: ready in 8 s, EICAR detected before and after the restart.
- HTTP readiness of Prometheus, Alertmanager, Loki, Tempo, Grafana, Caddy, the exporters and
  node-exporter.

None of them logged a read-only or permission error. The evidence is in
`agent-tools/parkio-u18-readonly-rootfs/`, and for web in `agent-tools/parkio-u18-web-readonly-root/`
(neither committed).

**CI coverage.**
- The CI runtime, chaos and performance workflows start the full stack, so they cover the JVM
  services.
- The real-stack E2E in local mode (`frontend-real-e2e.yml`) covers the media upload path. It ran
  an upload to `READY` on this configuration, with `/tmp` on the scratch volume.
- Runtime validation streams its captures out of the containers instead of copying them, because
  `docker compose cp` cannot read a tmpfs. `capture_status` reads its file with `exec … cat`; the
  readiness and JWKS captures take curl's standard output.

The tmpfs mounts keep Docker's default `noexec`, so nothing can load native code from `/tmp`.
That includes the libraries that extract themselves there:
- Netty's epoll transport does not load. Netty and Reactor Netty then use the NIO transport
  (probe with the gateway's Netty jars on `eclipse-temurin:21-jre`: `noexec` → "failed to map
  segment from shared object", epoll unavailable; `exec` tmpfs or a volume → available).
- The snappy and zstd Kafka codecs would fail the same way. Parkio's producers set no
  compression, so they are not loaded.

If NIO ever shows a cost, the fix is to ship the native library in the image (Netty loads it
from `java.library.path` first), not to make `/tmp` executable.

## Verification

- `./scripts/assert-compose-hardening.sh` — static check of the three models.
- Runtime: the CI workflows that start the stack (runtime validation, chaos, observability,
  performance smoke, backup restore drill) run it hardened. The local check for this change
  (`agent-tools/parkio-u18-container-hardening/`) started the infrastructure services from
  volumes created without hardening and from fresh volumes, and ran write, topic, scan and
  readiness probes in each.

## Follow-ups prepared under B8 (not applied)

- Non-root images for the root-start services (exception 3): volume ownership tooling and targets
  in `nonroot-volume-migration.md`.
- promtail's Docker socket (exception 1): an allowlisting proxy, measured and prototyped, in
  `../architecture/docker-socket-proxy-design.md`.

## Tmpfs declarations

Every tmpfs is declared under the service's `tmpfs:` key, as `target:size=…[,mode=…]`. A long-form
`type: tmpfs` entry under `volumes:` cannot state `noexec`. The guard therefore refuses it and
counts it against the recorded set (B8b).

## Host ports

In `docker-compose.yml` (local development), the exporters, Alertmanager, Loki, Promtail and
ClamAV publish their ports on `127.0.0.1` only (ClamAV since B8b). The production overlays remove
ClamAV's mapping altogether.
