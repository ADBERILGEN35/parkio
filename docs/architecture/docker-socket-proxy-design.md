# Docker socket proxy for promtail (design, CL-F29.3, owner decision B8)

**Status:** design only. Owner decision B8 asks for a minimally scoped socket-proxy design, and
this is it. Nothing here is wired into Compose, and no image is published.

## Problem

promtail mounts `/var/run/docker.sock` for Docker service discovery and log streaming. A read-only
bind does not restrict the Docker API. Whoever controls promtail can create a privileged
container, which is root on the host. This is exception 1 in
`docs/operations/container-hardening-inventory.md`.

promtail runs in the default and hosted-beta stacks and in CI. The invite-production models
disable it, and the Civo model has none. It also keeps promtail root-start: its
`promtail-positions` row in `scripts/lib/nonroot-volume-map.tsv` is `blocked` until this lands.

## What promtail needs (measured)

promtail 2.9.8 was traced through a logging nginx in front of the socket. The repository's
`docker_sd_configs` was used, limited by an API label filter to one probe container, on an
`--internal` network. It made exactly these calls (Docker API version negotiated to 1.42):

| Call | Purpose |
|---|---|
| `HEAD /_ping` | API version negotiation |
| `GET /v1.42/networks` | discovery labels for networks |
| `GET /v1.42/containers/json?filters=…` | discovery |
| `GET /v1.42/containers/{id}/json` | inspect a discovered target (TTY, log settings) |
| `GET /v1.42/containers/{id}/logs?follow=1&since=…&stderr=1&stdout=1&timestamps=1` | the log stream |

Nothing else: no POST, no `/info`, `/version` or `/events`. The evidence is in
`agent-tools/parkio-u18-socket-proxy-design/` (not committed).

## Design

The proxy is a separate `docker-socket-proxy` service that allows those five call shapes and
refuses everything else with 403. promtail reaches it over an internal network and loses its
socket mount.

```nginx
# Matching uses nginx's normalized $uri, and the normalized URI is what reaches Docker, so
# encoded or dot-segment paths cannot reach an endpoint the map does not list. Patterns end in
# \z, not $: PCRE's $ also matches before a final newline, so ".../networks%0A" would match.
map "$request_method:$uri" $parkio_docker_allowed {
  default 0;
  "~^(GET|HEAD):/_ping\z" 1;
  "~^GET:/v1\.[0-9]+/networks\z" 1;
  "~^GET:/v1\.[0-9]+/containers/json\z" 1;
  "~^GET:/v1\.[0-9]+/containers/[A-Za-z0-9][A-Za-z0-9_.-]*/json\z" 1;
  "~^GET:/v1\.[0-9]+/containers/[A-Za-z0-9][A-Za-z0-9_.-]*/logs\z" 1;
}
server {
  listen 2375;
  location / {
    if ($uri ~ "[[:cntrl:]]") { return 403; }   # no control character anywhere in the path
    if ($parkio_docker_allowed = 0) { return 403; }
    proxy_pass http://unix:/var/run/docker.sock:$uri$is_args$args;
    proxy_http_version 1.1;
    proxy_buffering off;          # the log stream
    proxy_read_timeout 3600s;
  }
}
```

**Container.**
- Image: the upstream `nginx:alpine`, pinned by digest, the same way the web image's base is.
  This adds no new image to build or publish.
- Hardening: `read_only: true`, `cap_drop: [ALL]` with **no** `cap_add`, and `no-new-privileges`.
- It runs as **non-root**: `user: "101:${DOCKER_SOCKET_GID}"`. That is nginx's user, with the
  group that owns the socket on the host (`docker` on Linux hosts; 1001 on the Docker Desktop
  used for the probe).
  - Started as root instead, nginx needs `SETUID`/`SETGID` to switch its workers. Without them a
    worker fails with `initgroups(root, 0) failed (1: Operation not permitted)`.
- tmpfs mounts:
  - `/var/cache/nginx:size=8m,uid=101,gid=101`. Docker gives a tmpfs the owner of the directory
    it covers, which is root here.
  - `/tmp:size=8m`, for the pid file.
- Socket: `/var/run/docker.sock:/var/run/docker.sock:ro`. The `:ro` stops the file from being
  replaced; it does not limit the API.

**Network.** The proxy joins only an `internal: true` network shared with promtail. It publishes
no port.

**promtail.**
- `docker_sd_configs.host: tcp://docker-socket-proxy:2375`.
- The socket bind and the `DOCKER_SOCKET_EXCEPTIONS` entry move to `docker-socket-proxy`.
- promtail can then run non-root once its positions volume is migrated with
  `scripts/nonroot-volume-migration.sh`.

**Prototype result** (same evidence directory):
- promtail discovered the probe container through the allowlist and streamed its logs.
- These got 403: `/info`, `/version`, `/images/json`, `/volumes`, `/events`, container `archive`
  and `export`, `POST /containers/create`, `POST …/exec` and `POST …/stop`.
- So did the two traversal attempts, `/containers/json/../../images/json` and an encoded
  `%2F..%2F` path.
- **Control characters (#202 review N1).** With `$` anchors, `/v1.42/networks%0A` matched and the
  raw line feed reached the upstream request line; Docker refused it with 400. With `\z` and the
  control-character rule:
  - `%0A`, `%0D` and `%09` in the path get 403 from the proxy;
  - `%00` gets 400 from nginx's URI parser before any location runs;
  - the access log shows no upstream for any of them (`$upstream_status` is `-`).
  - The test is 24/24.
- The non-root, capability-free variant served the allowed list (200) and refused
  `/images/json` (403).

## What remains exposed

**Owner decision (2026-10-05, decision 5): limited acceptance.** The `Config.Env` exposure below is
accepted as a documented limitation only for the current non-production promtail setup: the default
development stack and the CI stacks that start promtail.
- **hosted-beta is not covered.**
  - The hosted-beta deploy profile is a supported deployment path (CL-F12, owner decision 1b).
  - A hosted-beta deploy starts promtail with the Docker socket today: `scripts/lib/deploy-common.sh`
    disables no service for that profile, and `docker/docker-compose.hosted-beta.yml` keeps promtail.
  - Its exposure stays open under the next rule.
- **Production enablement of promtail stays blocked** until a secrets-exposure solution has been
  reviewed. The invite-production models disable promtail, and the Civo and Azure models have none.
- The candidate solutions are a filtering proxy that drops `Config.Env`, or file-based collection.
- This text records the state. It changes no live configuration and closes no operational criterion;
  CL-F29.3 keeps its open items.

- **Container metadata, including secrets.** `GET /containers/{id}/json` returns every
  container's `Config.Env`, which holds secrets. A path allowlist cannot filter a response body.
  Removing that exposure needs either a filtering proxy that drops `Config.Env` (new code with its
  own review) or file-based collection (below).
- **Logs of every container**, as today.
- **Resource use.** A client can open many follow streams. Add `limit_conn` when this is wired.
- **The proxy holds the socket.** Its compromise is still host-level. The design keeps its surface
  small: allowlisted GETs only, a read-only root, no capabilities and no published port.

The change is strictly smaller than today's: promtail can no longer create, exec, stop, export or
copy anything.

## Alternatives considered

- **tecnativa/docker-socket-proxy (HAProxy).** It grants whole API sections. `CONTAINERS=1` opens
  every `GET /containers/…`, including `archive` (reads files from containers) and `export`. It is
  coarser than the five calls promtail makes, and it is a new image to pin and review.
- **wollomatic/socket-proxy.** It takes per-method regex allowlists, so it is as precise as the
  nginx map, but it is a new image to pin and review.
- **File-based collection** from `/var/lib/docker/containers/*/*-json.log`.
  - It needs no API at all, but needs a read-only host bind of every container's logs.
  - Container names and Compose labels then come from json-file `labels`/`tag` options, not from
    discovery. That changes the Loki labels the dashboards and alerts use.
  - It is the only option that also removes the `Config.Env` exposure.

## Rollout (not authorized by B8)

1. Add the `docker-socket-proxy` service and the internal network, and point promtail at the
   proxy. This is a Compose change in the stacks that run promtail.
2. Update `DOCKER_SOCKET_EXCEPTIONS` in `scripts/lib/assert-compose-hardening.mjs` and the
   inventory.
3. Add an acceptance script with:
   - a Loki query that shows promtail's lines;
   - the 403 set above against the proxy;
   - no socket in promtail's mounts.
4. Then migrate promtail's positions volume and run it non-root.
