# New Relic log pilot — supported, bounded activation handoff

**Status:** implementation-ready, synthetic acceptance only, production
activation deferred. No production helper or collector has been installed or
started, no application container has been changed, and no real log has been
sent to New Relic.

This is a log-only one-hour pilot for `gateway-service`, `auth-service` and
`parking-service`. It installs no APM agent and enables no tracing, session
replay, analytics, alert or Slack integration.

## Candidate identity

- Branch: `feat/p02-nr-log-pilot-preparation` (draft PR #66). At activation,
  record the exact reviewed checkout with `git rev-parse HEAD`; never substitute
  an older candidate SHA.
- Base reconciled by a normal merge from `origin/api` at
  `83fa625b9e37d6c0819862459d5570e305dd5121`.
- Collector: `fluent/fluent-bit:5.0.10` index digest
  `sha256:ea0734ecb445c9805ec1fcbfb3430c8607d502fe7ce773b27e362551c02e3fd9`;
  `linux/amd64` child digest
  `sha256:3fa4a4919f1814f23d27a0108de041e93f6f21d9dc7bbe912d8c44ecd8e7cf20`.
- Gate/mock runtime: `python:3.12-alpine` index digest
  `sha256:c4634f578a412db396771b61b064c6e546c9d6414c7fb5b1b05d5871f1885f7b`;
  `linux/amd64` child digest
  `sha256:9b8dad7f66b5c7751df6cb7a64a07812e86bed85d0116efe82b3a11209f1440d`.
- Supported source: host `docker logs --follow --timestamps --since`, mediated
  by `scripts/newrelic_log_pilot/docker_log_source.py`. The collector and gate
  do not receive `docker.sock` and never open Docker's private log files.

The base and production Compose files are opt-in and absent from the shared
production Compose file list. They define only the collector, budget gate and
local mock. They cannot recreate an application service.

## Read-only production preflight

At 2026-09-21T17:53:42Z, strict SSH host verification matched the supplied
ED25519 fingerprint
`SHA256:WfUpibW3n/acuKcFk6wbwy9DoqGN+faLoPQhLH+JTuM`. The host remained
`x86_64`, Docker Server 29.8.1 and Compose 5.5.1. A content-free
`docker logs --timestamps --since <current-time> --tail 0` probe passed for
exactly one running container per approved service:

| Service | Observed container ID | Driver / rotation |
| --- | --- | --- |
| `gateway-service` | `473829c00ba97e4e6f52bb72dac73c53865655063fae0c79bac4ef3f2852f5cc` | `json-file`, 10m × 5 |
| `auth-service` | `aa28bb4e98907a29471540e93c270bd67f855d6e5124006d4e7424c3ed5865f4` | `json-file`, 10m × 5 |
| `parking-service` | `54266cd6a68eff8439c2e433f5982a7d337aa338e25c4fae1f061d9451ebed4c` | `json-file`, 10m × 5 |

The Docker filesystem had 93,252,596 KiB available. The helper state directory
was absent, confirming no helper installation. Gateway and auth identities had
changed since the earlier P02A observation, directly validating the need for
activation-time resolution after the concurrent deployment settles. These IDs
are evidence only and must not be reused as activation inputs.

## Supported-source behavior and permissions

Docker documents `docker logs` as the interface for fetching container logs and
warns that the `json-file` driver's files are for exclusive daemon use. This
candidate therefore replaces the old direct `LogPath` mounts with the Docker
logs interface. `docker inspect .LogPath` is no longer an activation input.

The helper:

1. resolves exactly one running container for each approved Compose
   project/service label pair;
2. validates the label, full container identity, running state and approved
   driver (`json-file`, `local` or `journald`; production preflight currently
   requires `json-file`);
3. follows stdout and stderr separately with Docker-supplied RFC3339Nano
   timestamps and writes normalized Docker JSON envelopes;
4. writes only three service directories, each with `0750` directory and `0640`
   file permissions;
5. rotates each service at 2 MiB and retains at most three files (18 MiB total
   logical source payload, plus directory/filesystem metadata); and
6. publishes non-secret `attached`, `replaced` or `disconnected` state so the
   activation check fails visibly on a stale identity or missing spool.

The helper runs on the host as root in the proposed transient unit because the
Docker daemon socket is root-equivalent. A Docker-group user would carry the
same effective privilege. The security boundary is reviewed helper code plus
exact service allowlisting—not a claim that Docker API access is intrinsically
narrow. That privilege is never passed into either pilot container.

Initial attachment uses the current time, so the pilot does not backfill old
logs. A stored cursor is advanced only after a spool write is fsynced. Reconnect
uses the last Docker timestamp inclusively; a lost acknowledgement, process
crash or inclusive timestamp can duplicate the last record. Output rotation,
daemon unavailability, a replacement-detection race, an overlong record, full
storage or upstream backlog eviction can lose records. The helper reconnects
and resolves a replacement identity; it never blocks an application write.
stdout/stderr ordering is not guaranteed. Fluent Bit joins recognized Java
exception continuation lines after reading the helper envelope.

Official references:

- [Docker `docker container logs`](https://docs.docker.com/reference/cli/docker/container/logs/)
- [Docker `json-file` warning](https://docs.docker.com/engine/logging/drivers/json-file/)
- [Docker Engine container logs API](https://docs.docker.com/reference/api/engine/version/v1.40/#tag/Container/operation/ContainerLogs)

## Collector lifecycle and security

Fluent Bit's official security policy lists the 5.0 line as active through
2026-11-30; 3.2 is EOL. Version 5.0.10 is the latest patch selected from that
explicitly supported line. This is intentionally not the former EOL 3.2.10
candidate. The near-term 5.0 end-of-maintenance date is a version-sensitive
assumption: repin and repeat acceptance before any later activation.

Enabled plugins are only `tail` and `dummy` inputs, `lua` and
`record_modifier` filters, and the built-in `nrlogs` output. There is no
external plugin, `forward` input/output, Docker input, OpenTelemetry input,
Prometheus Remote Write input or supervisor. The 2026 Secure Forward advisory
(`GHSA-jrp8-r9hx-gf73`) is therefore not reachable through the enabled pipeline;
its vulnerable code may still be present in the image. New Relic NR24-01's core
minimum is 3.0.4 and its separate affected New Relic external plugin is not
loaded here.

An exact-image Trivy scan on 2026-09-21 reported Debian 13.6 with **0 CRITICAL
and 6 HIGH** OS findings for the selected 5.0.10 digest. The image is not clean
and none of these findings is removed from the inventory merely because its
known trigger is absent. Package policy uses `OPEN-NO-FIX` when Trivy reports no
Debian fixed version and `OPEN-FIX-AVAILABLE` when a Debian package fix exists.
Reachability is a separate statement about this exact configuration:

| Finding | Package / scanner status | Policy | Reviewed configuration reachability |
| --- | --- | --- | --- |
| `CVE-2026-12064` | `libcurl4t64` 8.14.1-2+deb13u4; HIGH, affected; no packaged fix | `OPEN-NO-FIX` | Not reached: affects curl CLI schemeless SFTP/SCP with `--proto-default`; the pipeline executes neither curl CLI nor SSH file transfer. |
| `CVE-2026-8286` | `libcurl4t64` 8.14.1-2+deb13u4; HIGH, affected; no packaged fix | `OPEN-NO-FIX` | Not reached: requires STARTTLS IMAP/POP3/SMTP/FTP/LDAP connection reuse; the collector uses only plain HTTP to the private gate. |
| `CVE-2026-8458` | `libcurl4t64` 8.14.1-2+deb13u4; HIGH, affected; no packaged fix | `OPEN-NO-FIX` | Not reached: requires Negotiate authentication and differing service names; neither is configured. |
| `CVE-2026-8927` | `libcurl4t64` 8.14.1-2+deb13u4; HIGH, affected; no packaged fix | `OPEN-NO-FIX` | Not reached: requires environment-selected proxies, proxy changes and Digest proxy authentication; the collector has no proxy configuration or proxy credential. |
| `CVE-2026-58050` | `libssh2-1t64` 1.11.1-1+deb13u1; HIGH, fix available in 1.11.1-1+deb13u2 | `OPEN-FIX-AVAILABLE` | Not reached: requires a malicious SSH server response in the public-key subsystem; no SSH plugin or destination is enabled. |
| `CVE-2026-16742` | `libsystemd0` 257.13-1~deb13u1; HIGH, affected; no packaged fix | `OPEN-NO-FIX` | Not reached: requires a running `systemd-homed` local authentication path; the collector container runs Fluent Bit directly and has no systemd/homed service. |

These are configuration-specific reachability assessments, not waivers. A
plugin, proxy, protocol, entrypoint or base-image change invalidates them. The
`OPEN-FIX-AVAILABLE` libssh2 finding and all `OPEN-NO-FIX` findings remain
visible activation evidence and must be rescanned immediately before the
synthetic proof and again before real-log activation. The prior 3.2.10 image
reported 59 HIGH and 4 CRITICAL and is no longer a candidate.

References:

- [Fluent Bit security and supported versions](https://github.com/fluent/fluent-bit/security)
- [Fluent Bit releases](https://github.com/fluent/fluent-bit/releases)
- [Secure Forward advisory](https://github.com/fluent/fluent-bit/security/advisories/GHSA-jrp8-r9hx-gf73)
- [New Relic NR24-01](https://docs.newrelic.com/docs/security/new-relic-security/security-bulletins/security-bulletin-nr24-01/)
- [curl CVE-2026-12064](https://curl.se/docs/CVE-2026-12064.html)
- [curl CVE-2026-8286](https://curl.se/docs/CVE-2026-8286.html)
- [curl CVE-2026-8458](https://curl.se/docs/CVE-2026-8458.html)
- [curl CVE-2026-8927](https://curl.se/docs/CVE-2026-8927.html)
- [Debian CVE-2026-58050 status](https://security-tracker.debian.org/tracker/CVE-2026-58050)
- [Debian systemd security status](https://security-tracker.debian.org/tracker/source-package/systemd)

## Redaction and exported fields

The Lua filter rebuilds each outbound record from an allowlist. Every accepted
record contains `timestamp`, `service`, `environment`, `severity`, `level`,
`message`, `parse_status`, `redaction`, `source_kind`, `release_id`,
`collector_source_sha`, `pipeline`, `schema_version`, `collector` and
`logtype`. Optional fields are `source_stream`, `correlation_id`, `trace_id`,
`span_id`, `error_code`, `event_name` and `pilot_marker`. `source_kind` is
`docker-logs-api` or `synthetic`; `collector` is `fluent-bit-5.0.10`.

Known email addresses, email hashes, confirmation/withdrawal path tokens,
queries in HTTP(S) URLs, token/password/API-key assignments and Resend-like keys
are redacted. Records containing authorization/cookie headers, JWT-shaped
values, private keys, precise coordinates or the prohibited acceptance canary
are dropped. Multiline Java exceptions are assembled before filtering.

This does not make arbitrary free text safe. Lua patterns can miss novel labels,
encodings, split secrets, non-HTTP URLs and new personal data; they can also
over-redact or drop useful events. `message` remains untrusted free text. An
application-owned structured allowlist would be safer, but it is outside this
collector-only package.

## Persistent outbound budget gate

Fluent Bit sends to `nr-budget-gate` on the private Compose network with a dummy
internal key. Only the gate receives the New Relic ingest license key and opens
the verified HTTPS EU endpoint
`https://log-api.eu.newrelic.com/log/v1`.

Before every upstream attempt, the gate transactionally reserves the byte
length of the uncompressed serialized JSON request body in SQLite. It also
records attempts, consecutive same-payload retry attempts, records, compressed
wire bytes and rejected batches. Every retry reserves the body again because an
upstream attempt might have ingested even if its acknowledgement was lost.
Reservation is committed before network transmission, so a crash can
under-utilize the budget but cannot reset or exceed it.

Admission is whole-batch: if the next body does not fit, none of it is
forwarded, `exhausted=true` is persisted, health changes to HTTP 507, and later
batches receive local HTTP 202/drop responses. Therefore forwarded serialized
body bytes have zero overshoot beyond the configured budget and automatic
Fluent Bit retries do not keep growing backlog after exhaustion. A configuration
change that disagrees with persisted `max_bytes` fails closed at gate startup.

This is **not** an exact New Relic billable-ingest cap. It excludes HTTP/TLS
headers and counts uncompressed serialized request bodies per attempt, while
New Relic billing semantics may differ. The earlier 25 MiB number is only a
configurable synthetic test value; it is not measured production demand or an
approved owner budget.

## Resource controls

| Component | Enforced control | What it does not prove |
| --- | --- | --- |
| Source helper | 2 MiB × 3 files × 3 services; oversized/full-disk writes drop | Not a filesystem/project quota |
| Fluent Bit output | `storage.total_limit_size 20M`, 5 MiB backlog memory, two chunks up | Actual volume use includes DB/filesystem overhead |
| Fluent Bit container | 128 MiB memory, 0.25 CPU, 128 PIDs, read-only root, all caps dropped | New Relic ingest budget |
| Gate container | 64 MiB memory, 0.25 CPU, 64 PIDs, read-only root, all caps dropped | Python/SQLite file size is not a hard filesystem quota |
| Outbound gate | Persistent whole-request serialized-byte budget, 1 MiB request ceiling | Vendor billable bytes |
| Duration | Independent one-hour systemd stop timer | Data-volume control |

Production uses host bind directories for helper, collector and gate state so
an operator can measure actual consumption with `du -sb`. The logical source
and Fluent Bit limits prevent unbounded pilot growth, but only a dedicated
quota filesystem would be a hard total-filesystem quota. Acceptance injects
ENOSPC and verifies safe drop; it does not claim immunity if unrelated host
processes fill the filesystem.

## Isolated acceptance

Run from the exact reviewed checkout:

```bash
python3 scripts/newrelic_log_pilot/run_isolated_validation.py
```

The suite uses task-specific ports, network, volumes and synthetic logs only.
The current candidate passed **31/31** assertions covering the New Relic HTTP
contract, 401/404/429, network interruption/recovery, retry pressure, bounded
buffering, collector restart/checkpoint, rotation, Java multiline parsing,
allowlisted fields, redaction and prohibited-canary absence. Added source/gate
coverage verifies helper disconnect/reconnect, visible container replacement,
service scope, timestamp/stream envelopes, bounded rotation, ENOSPC handling,
activation-time identity/spool guarding, healthy-delivery exhaustion,
retry-storm exhaustion and persisted exhaustion after gate restart. Record the
exact JSON output and source SHA in the PR.

The local measured sample is useful only for estimating serialized record size;
it is not production traffic. The final run exported 58 records, 278,857
uncompressed JSON bytes at the mock, with mean flattened-record size 4,819.1
bytes and maximum 6,683 bytes. Backpressure injected 22,588,890 source bytes;
the collector state measured 82,120 bytes after bounded eviction. The local
256 MiB gate setting existed only to keep recovery tests from prematurely
exhausting it. Do not extrapolate any of these synthetic measurements into an
owner budget or expected production rate; production demand remains unknown
because reading or transmitting production content was intentionally avoided.

### PR CI evidence

For implementation head `6ed1dc2808d5ec57e323d1fb182eb1ea584f2429`, the
terminal checks available on 2026-09-21 were PASS for build/unit tests,
configuration and scripts, integration tests, k6 smoke, CodeQL Java/Kotlin and
JavaScript/TypeScript, dependency and secret scans, the security summary, the
PR Trivy check, all ten application-container scans, and the secret-safe
non-deploy manifest. Production deploy, migration, rollback and runner jobs
were skipped as expected for this PR.

The separate Compose dependency recovery drill was **FAIL (infrastructure)**:
Docker Hub's authentication endpoint timed out while the job was pulling
`grafana/tempo:2.6.1`, during shared-stack startup and before any pilot
component or assertion ran. This is not recorded as a pilot functional failure
or as a PASS; its terminal job evidence is
[run 35636817533 / job 106456765308](https://github.com/ADBERILGEN35/parkio/actions/runs/35636817533/job/106456765308).
The Full Docker Compose runtime validation was still in progress at this
observation and must be recorded separately when it reaches a terminal state.

## Account inputs and hidden key installation

Confirmed: an EU New Relic account exists, Logs UI is accessible, and an ingest
license key is ready. Three operator inputs remain:

1. confirm billing/free-tier and retention conditions;
2. approve a numeric one-hour value for `PARKIO_NR_BUDGET_BYTES`; and
3. install the ready ingest license key using the hidden procedure below.

Never paste the key into chat, a command argument, shell history, Git, a ticket
or rendered Compose output. In an interactive production terminal with xtrace
off, create a reboot-ephemeral root-only env file:

```bash
set +x
sudo install -d -m 0700 -o root -g root /run/parkio-nr-log-pilot
sudo python3 - <<'PY'
import getpass, os, re
path = "/run/parkio-nr-log-pilot/secret.env"
key = getpass.getpass("New Relic EU Ingest - License key: ")
if not re.fullmatch(r"[0-9A-Fa-f]{40}", key):
    raise SystemExit("key format rejected; nothing installed")
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
with os.fdopen(fd, "w", encoding="utf-8") as handle:
    handle.write("PARKIO_NR_LOG_API_KEY=" + key + "\n")
key = ""
PY
sudo test "$(sudo stat -c '%a:%U:%G' /run/parkio-nr-log-pilot/secret.env)" = 600:root:root
sudo grep -Eq '^PARKIO_NR_LOG_API_KEY=[0-9A-Fa-f]{40}$' \
  /run/parkio-nr-log-pilot/secret.env
```

The format check does not prove key type or entitlement. Do not `cat` the file
or run a command that renders the resolved Compose environment.

## Search handoff

Use the same base filter for every view:

```sql
FROM Log SELECT count(*)
WHERE pipeline = 'nr-log-pilot'
FACET service, environment, severity SINCE 1 hour ago
```

Gateway failures:

```sql
FROM Log SELECT timestamp, severity, message, correlation_id, error_code, release_id
WHERE pipeline = 'nr-log-pilot' AND service = 'gateway-service'
  AND severity IN ('ERROR', 'FATAL') SINCE 1 hour ago LIMIT MAX
```

Waitlist delivery failures:

```sql
FROM Log SELECT timestamp, severity, message, correlation_id, error_code
WHERE pipeline = 'nr-log-pilot'
  AND event_name IN ('waitlist.confirmation.delivery_failed',
                     'waitlist.withdrawal.delivery_failed')
SINCE 1 hour ago LIMIT MAX
```

The required pre-production proof below uses `event_name =
'nr_log_pilot.synthetic_probe'` plus a unique non-secret `pilot_marker`. HTTP
202 and gate counters alone are transport evidence; the proof passes only when
that exact marker is searchable in the EU account's Logs UI.

## Required synthetic-only EU proof

**Status: NOT_EXECUTED. This is a hard prerequisite for real-log activation.**
Do not start or install the production source helper first. The proof binds a
new empty source directory, uses a distinct persistent 16 KiB serialized-body
test budget and starts a separate Compose project. Fluent Bit's one-sample
`dummy` input is the only source record.

Run only after billing/retention is confirmed and the hidden key procedure above
has completed under a separate authorization:

```bash
set -euo pipefail
set +x
checkout=/path/to/reviewed/parkio
run_dir=/run/parkio-nr-log-pilot
synthetic_project=parkio-nr-log-pilot-synthetic
cd "$checkout"
candidate_sha="$(git rev-parse HEAD)"
marker="p02-synthetic-$(date -u +%Y%m%dT%H%M%SZ)-$(openssl rand -hex 6)"
synthetic_root="$run_dir/empty-source-$marker"
synthetic_state="/var/lib/parkio-nr-synthetic-$marker"

# Hard source-isolation preconditions.
if sudo systemctl is-active --quiet parkio-nr-log-source.service; then
  printf 'production source helper is active; refusing synthetic proof\n' >&2
  exit 1
fi
if sudo docker ps -q --filter label=com.docker.compose.project=parkio-nr-log-pilot \
  | grep -q .; then
  printf 'production pilot project is running; refusing synthetic proof\n' >&2
  exit 1
fi
sudo install -d -m 0750 -o root -g root \
  "$synthetic_root" "$synthetic_state/collector" "$synthetic_state/budget"
if sudo find "$synthetic_root" -mindepth 1 -print -quit | grep -q .; then
  printf 'synthetic source is not empty; refusing start\n' >&2
  exit 1
fi

# Non-secret runtime values. The 16 KiB test budget is persistent and separate
# from the still-unapproved real-log pilot budget.
sudo install -m 0600 -o root -g root /dev/null "$run_dir/synthetic.env"
{
  printf 'PARKIO_NR_UPSTREAM_BASE_URI=%s\n' 'https://log-api.eu.newrelic.com/log/v1'
  printf 'PARKIO_NR_BUDGET_BYTES=%s\n' '16384'
  printf 'PARKIO_NR_BUDGET_STATE_ROOT=%s\n' "$synthetic_state/budget"
  printf 'PARKIO_NR_COLLECTOR_STATE_ROOT=%s\n' "$synthetic_state/collector"
  printf 'PARKIO_NR_SOURCE_ROOT=%s\n' "$synthetic_root"
  printf 'PARKIO_ENVIRONMENT=%s\n' 'production-synthetic-only'
  printf 'PARKIO_RELEASE_ID=%s\n' 'synthetic-no-production-source'
  printf 'PARKIO_COLLECTOR_SOURCE_SHA=%s\n' "$candidate_sha"
  printf 'PARKIO_NR_PILOT_MARKER=%s\n' "$marker"
} | sudo tee "$run_dir/synthetic.env" >/dev/null
sudo chmod 600 "$run_dir/synthetic.env"

compose=(sudo docker compose
  --env-file "$run_dir/secret.env"
  --env-file "$run_dir/synthetic.env"
  -p "$synthetic_project"
  -f docker/docker-compose.newrelic-log-pilot.yml
  -f docker/docker-compose.newrelic-log-pilot.production.yml
  --profile nr-log-pilot)

# Independent fail-safe stop; failure to arm it means do not start.
sudo systemctl stop parkio-nr-log-pilot-synthetic-autostop.timer \
  parkio-nr-log-pilot-synthetic-autostop.service 2>/dev/null || true
sudo systemctl reset-failed parkio-nr-log-pilot-synthetic-autostop.timer \
  parkio-nr-log-pilot-synthetic-autostop.service 2>/dev/null || true
sudo systemd-run --unit=parkio-nr-log-pilot-synthetic-autostop --on-active=10m \
  /usr/bin/env PARKIO_NR_PILOT_PROJECT="$synthetic_project" \
  PARKIO_NR_SOURCE_HELPER_UNIT=parkio-nr-log-source-synthetic-none.service \
  "$checkout/scripts/newrelic_log_pilot/stop_pilot.sh"
sudo systemctl is-active --quiet parkio-nr-log-pilot-synthetic-autostop.timer

"${compose[@]}" up -d nr-budget-gate fluent-bit-nr-pilot
collector_id="$("${compose[@]}" ps -q fluent-bit-nr-pilot)"
gate_id="$("${compose[@]}" ps -q nr-budget-gate)"
[ -n "$collector_id" ] && [ -n "$gate_id" ]
[ "$(sudo docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/log/parkio-pilot"}}{{.Source}}{{end}}{{end}}' "$collector_id")" = "$synthetic_root" ]
[ -z "$(sudo docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/run/docker.sock"}}{{.Source}}{{end}}{{end}}' "$collector_id" "$gate_id")" ]

# Require one healthy upstream attempt and one forwarded synthetic record.
sleep 5
"${compose[@]}" exec -T nr-budget-gate python - <<'PY'
import json, urllib.request
stats = json.load(urllib.request.urlopen("http://127.0.0.1:8090/stats", timeout=5))
assert stats["max_bytes"] == 16384, stats
assert stats["forwarded_attempts"] == 1, stats
assert stats["records_forwarded"] == 1, stats
assert stats["rejected_attempts"] == 0, stats
assert stats["exhausted"] is False, stats
print(json.dumps({key: stats[key] for key in (
    "max_bytes", "spent_bytes", "remaining_bytes", "attempts",
    "forwarded_attempts", "records_forwarded", "exhausted"
)}, sort_keys=True))
PY
printf 'Search this non-secret marker in New Relic Logs: %s\n' "$marker"
```

In the EU Logs UI, run this query with the printed marker:

```sql
FROM Log SELECT count(*)
WHERE pipeline = 'nr-log-pilot'
  AND environment = 'production-synthetic-only'
  AND source_kind = 'synthetic'
  AND event_name = 'nr_log_pilot.synthetic_probe'
  AND pilot_marker = 'REPLACE_WITH_PRINTED_MARKER'
SINCE 30 minutes ago
```

The proof passes only if the exact marker is searchable and the result is one
record with `collector = 'fluent-bit-5.0.10'` and the expected
`collector_source_sha`. Zero results is **FAIL**, even if New Relic returned HTTP
202. More than one result is also **FAIL** for this one-record proof and must be
recorded as a duplicate investigation.

Whether the query passes or fails, stop and remove the synthetic project
immediately while retaining its dedicated state directory as evidence:

```bash
cd /path/to/reviewed/parkio
compose=(sudo docker compose
  --env-file /run/parkio-nr-log-pilot/secret.env
  --env-file /run/parkio-nr-log-pilot/synthetic.env
  -p parkio-nr-log-pilot-synthetic
  -f docker/docker-compose.newrelic-log-pilot.yml
  -f docker/docker-compose.newrelic-log-pilot.production.yml
  --profile nr-log-pilot)
"${compose[@]}" down --timeout 30
sudo systemctl stop parkio-nr-log-pilot-synthetic-autostop.timer 2>/dev/null || true
test -z "$(sudo docker ps -q --filter label=com.docker.compose.project=parkio-nr-log-pilot-synthetic)"
```

Record the marker, query result, gate counter subset, exact source SHA, image
digests, start/stop timestamps and the six HIGH findings. Do not proceed to the
next section unless this proof is PASS and the synthetic project is stopped.

## Deferred production install and activation

Do not run this section until the required synthetic-only EU proof above is PASS,
its project is stopped, the gateway deployment has settled and a separate
real-log activation decision is recorded. These commands are reviewable handoff,
not an authorization to execute them.

Create bounded state directories and capture fresh identities:

```bash
set -euo pipefail
umask 077
checkout=/path/to/reviewed/parkio
run_dir=/run/parkio-nr-log-pilot
sudo install -d -m 0750 -o root -g root \
  /var/lib/parkio-nr-source/logs /var/lib/parkio-nr-source/state \
  /var/lib/parkio-nr-collector-state /var/lib/parkio-nr-budget-state
cd "$checkout"
sudo ./scripts/newrelic_log_pilot/resolve_production_sources.sh \
  | sudo tee "$run_dir/sources.env" >/dev/null
sudo chmod 600 "$run_dir/sources.env"
```

Create `$run_dir/runtime.env` as root, mode 0600, with no shell interpolation:

```text
PARKIO_NR_UPSTREAM_BASE_URI=https://log-api.eu.newrelic.com/log/v1
PARKIO_NR_BUDGET_BYTES=OWNER_APPROVED_POSITIVE_INTEGER
PARKIO_NR_BUDGET_STATE_ROOT=/var/lib/parkio-nr-budget-state
PARKIO_NR_COLLECTOR_STATE_ROOT=/var/lib/parkio-nr-collector-state
PARKIO_ENVIRONMENT=production
PARKIO_RELEASE_ID=EXACT_SETTLED_APPLICATION_RELEASE
PARKIO_COLLECTOR_SOURCE_SHA=EXACT_REVIEWED_PR_HEAD
PARKIO_NR_PILOT_MARKER=p02-UNIQUE-NONSECRET-MARKER
```

Start only the host helper, then require fresh identity/spool proof:

```bash
sudo systemd-run --unit=parkio-nr-log-source --service-type=exec \
  --property=Restart=on-failure --property=RestartSec=2s \
  --property=UMask=0027 --property=MemoryMax=64M --property=CPUQuota=25% \
  --property=NoNewPrivileges=yes --property=PrivateTmp=yes \
  --property=ProtectSystem=strict --property=ReadWritePaths=/var/lib/parkio-nr-source \
  /usr/bin/python3 "$checkout/scripts/newrelic_log_pilot/docker_log_source.py" \
  --project parkio --output /var/lib/parkio-nr-source/logs \
  --state /var/lib/parkio-nr-source/state --max-file-bytes 2097152 --max-files 3
sudo "$checkout/scripts/newrelic_log_pilot/resolve_production_sources.sh" \
  --check-helper "$run_dir/sources.env"
```

Any missing/stale identity, non-`attached` status or unreadable spool is a hard
stop. Resolve again after any application recreation; never reuse saved IDs.

Arm the independent stop before collector start:

```bash
sudo systemd-run --unit=parkio-nr-log-pilot-autostop --on-active=1h \
  /usr/bin/env PARKIO_NR_PILOT_PROJECT=parkio-nr-log-pilot \
  "$checkout/scripts/newrelic_log_pilot/stop_pilot.sh"
sudo systemctl is-active --quiet parkio-nr-log-pilot-autostop.timer
```

Then start only the two pilot containers in their dedicated project:

```bash
sudo docker compose \
  --env-file "$run_dir/secret.env" --env-file "$run_dir/runtime.env" \
  --env-file "$run_dir/sources.env" -p parkio-nr-log-pilot \
  -f docker/docker-compose.newrelic-log-pilot.yml \
  -f docker/docker-compose.newrelic-log-pilot.production.yml \
  --profile nr-log-pilot up -d nr-budget-gate fluent-bit-nr-pilot
```

Immediately repeat `--check-helper` and inspect only container health and gate
`/stats`. Do not display log payloads or resolved environment. Stop on helper
mismatch, gate health 507, unexpected service, canary exposure, host pressure,
or the one-hour deadline.

## Real-log rollback

Rollback is application-independent:

```bash
sudo PARKIO_NR_PILOT_PROJECT=parkio-nr-log-pilot \
  "$checkout/scripts/newrelic_log_pilot/stop_pilot.sh"
sudo rm -f /run/parkio-nr-log-pilot/secret.env \
  /run/parkio-nr-log-pilot/runtime.env /run/parkio-nr-log-pilot/sources.env
```

Stopping does not delete state; preserved budget state prevents restart from
resetting an exhausted cap. A later reviewed cleanup may remove only the four
dedicated pilot directories after evidence retention is decided. No shared
Compose file, application pin, application source or production service needs
to change for this package.
