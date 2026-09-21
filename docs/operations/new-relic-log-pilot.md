# New Relic log pilot — controlled activation handoff

**Status:** runtime preflight completed read-only; activation blocked. Production
activation and real New Relic ingestion have not been performed. Directly tailing
Docker's private `json-file` storage is not a supported Docker integration, and
the pinned Fluent Bit 3.2.x line is EOL. This is a log-only pilot: no APM agent,
tracing, session replay, analytics, alerts or Slack integration.

The collector is independent of the application Compose project. Starting or
stopping it must not recreate an application container. Only these application
services are in scope: `gateway-service`, `auth-service`, `parking-service`.

## Candidate identity and boundaries

- Recorded base source: `bf9cad5182f9e669cdd555bac6c0c64a2d7500fd`.
- Exact collector candidate with 20/20 acceptance:
  `47d3b73cff2b22f5f6a1a7d8b3aae4df0eeac229`.
- Collector image: `fluent/fluent-bit:3.2.10@sha256:d6dec000c4929a439562525728c708f6e99800d7ddc82efd6aa4f45f3a20b562`.
- On `linux/amd64`, the manifest selects child digest
  `sha256:41370c4edd3a09db6565271ef647a5f69808007cf6c8ce5f205e056a77a18f41`.
- Base Compose: `docker/docker-compose.newrelic-log-pilot.yml`.
- Production mount overlay:
  `docker/docker-compose.newrelic-log-pilot.production.yml`.
- Parser/filter configuration: `docker/fluent-bit/`.
- Buffer: one 20 MiB per-output filesystem backlog plus Fluent Bit state/DB,
  128 MiB container memory, 0.25 CPU and 128 PIDs.
- Collector container logs use `json-file` at 10 MiB × three files. The logical
  collector disk bound is therefore about 50 MiB plus SQLite/filesystem
  overhead; the named volume is not a host filesystem quota.
- Input begins at end of each current file (`Read_from_Head Off`); it does not
  backfill historic logs.
- No `docker.sock` mount or application configuration change exists in this
  candidate. That fact does not make the direct Docker-private-file mount a
  supported integration.

The pilot files are deliberately not in `docker/compose.production.files`.
Always use a separate Compose project and the two explicit `-f` arguments below.

## Production runtime preflight (read-only)

Observed at `2026-09-21T16:44:02Z` through
`ssh -o StrictHostKeyChecking=yes civo@api.parkio.dev`. The presented ED25519
host key matched the independently supplied fingerprint
`SHA256:WfUpibW3n/acuKcFk6wbwy9DoqGN+faLoPQhLH+JTuM`. No log contents were read
and no production state was changed.

| Check | Evidence | Result |
| --- | --- | --- |
| Host | Ubuntu 24.04.5 LTS, `x86_64`, kernel 6.8.0-134 | PASS |
| Runtime | Docker client/server 29.8.1, API 1.56; Compose 5.5.1 | PASS |
| Sources | Exactly one running `parkio` container for gateway/auth/parking | PASS |
| Logging | All three use `json-file`, `max-size=10m`, `max-file=5` | PASS |
| Files | Current `LogPath` files are regular `0640 root:root`; parents are `0710 root:root`; root can read/search | PASS |
| Mount reachability | A root collector can read the proposed read-only directory mounts | PASS (technical reachability only) |
| Supported access | Docker says its `json-file` files are exclusively for the daemon and external interaction should be avoided | **FAIL / activation blocker** |
| Capacity | `/var/lib/docker` is on a filesystem with 93,504,880 KiB available and 4% inode use | PASS at observation time |
| State prerequisites | Docker root is `/var/lib/docker`; no existing pilot container or named volume | PASS |

Observed source identities were:

| Service | Container ID prefix | Started (UTC) | Current log bytes |
| --- | --- | --- | ---: |
| `gateway-service` | `f88fe009c93c` | 2026-09-21 16:11:55 | 56,383 |
| `auth-service` | `8acd1c308b6b` | 2026-09-17 11:51:50 | 72,284 |
| `parking-service` | `54266cd6a68e` | 2026-09-21 08:04:55 | 607,694 |

These IDs and paths are evidence of one instant, not activation inputs. A
container recreation changes its ID and `LogPath`.

## Production log-source compatibility and supportability

Source inspection shows all three applications write to stdout/stderr, and
`docker/docker-compose.apps.yml` uses Docker's `json-file` driver with
`max-size: 10m` and `max-file: 5`. The application configuration does not name
an application log file. Therefore, the old pilot named volume was not connected
to the real application logs.

The production candidate mounts the parent directory of each live container's
Docker `LogPath`, read-only, into a service-specific target. The trusted target
path supplies `service`; log text cannot override it. The live preflight proved
that this is technically reachable with a root collector.

It is nevertheless not a supported Docker logging integration. Docker's
[`json-file` documentation](https://docs.docker.com/engine/logging/drivers/json-file/)
states that these files are designed for exclusive daemon access and warns that
external tools can interfere. `docker inspect .LogPath` only discovers an
implementation path; it provides no supportability guarantee. Production
activation must not treat the reachability PASS as approval.

The smallest Docker-supported read path is the daemon's logs API (`docker logs
--follow` / `GET /containers/{id}/logs`). It requires a narrowly designed host
helper or access to the Docker socket, lifecycle/reconnect logic and a privilege
review. Mounting `docker.sock` directly gives the collector host-equivalent
control and is not approved here. Application-owned file logging or a logging
driver change are alternatives, but cross the application/shared-Compose
ownership boundary. No alternative is implemented in this PR.

Docker log paths and container IDs change when an application is recreated.
`scripts/newrelic_log_pilot/resolve_production_sources.sh` resolves exactly one
running `parkio` container per service, checks labels/driver/rotation/path and
permissions without reading content, and emits non-secret source metadata. Its
`--check-env` mode fails visibly if any saved ID/path is stale. Run it immediately
before a separately approved start, immediately after start, and every 30
seconds during the pilot. The supervising activation wrapper must treat any
failure as a signal to stop only the collector. This guard does not cure
Docker's supportability warning.

Live identity remains a precondition. On the production host, generate a fresh
source file in tmpfs without displaying log contents:

```bash
set -euo pipefail
cd /path/to/reviewed/parkio
umask 077
source_env=/dev/shm/parkio-nr-log-pilot.sources.env
./scripts/newrelic_log_pilot/resolve_production_sources.sh >"$source_env"
chmod 600 "$source_env"
./scripts/newrelic_log_pilot/resolve_production_sources.sh --check-env "$source_env"
```

The activation reviewer must confirm all of the following:

1. Docker Compose is 2.24.4 or newer (`!override` support) and the host is
   `x86_64`/`amd64`.
2. Each service resolves to exactly one running container.
3. `driver=json-file`; each `logpath` is non-empty and ends in `-json.log`.
4. The host path exists, is readable by a root container and has a distinct
   parent directory for each service.
5. The runtime rotation values are bounded. Record any difference from source
   (`10m`, five files).
6. The mounted paths still identify the same container IDs immediately before
   start.

If any check fails, do not substitute `docker.sock` access. Stop and propose the
smallest source-specific alternative. File logging would be an application-owner
change and is outside this package. Keep activation deferred until the concurrent
gateway deployment is settled and this check has been repeated.

## Version-sensitive transport assumptions

This candidate uses Fluent Bit 3.2.10's built-in `nrlogs` output with
`Base_URI`, `License_Key`, gzip, TLS verification and unlimited retry. In the
isolated acceptance image it sent the key as `X-License-Key`, gzip-compressed
JSON to `/log/v1`, and accepted HTTP 202 responses. Treat this wire detail as
version-sensitive and re-run acceptance for any Fluent Bit image change.

Official references reviewed on 2026-09-21:

- [New Relic Log API](https://docs.newrelic.com/docs/logs/log-api/introduction-log-api/)
  and [API key types](https://docs.newrelic.com/docs/apis/intro-apis/new-relic-api-keys/)
- [Fluent Bit 3.2 New Relic output](https://docs.fluentbit.io/manual/3.2/pipeline/outputs/new-relic),
  [filesystem buffering](https://docs.fluentbit.io/manual/3.2/administration/buffering-and-storage),
  [retry behavior](https://docs.fluentbit.io/manual/3.2/administration/scheduling-and-retries)
  and [Tail input](https://docs.fluentbit.io/manual/3.2/pipeline/inputs/tail)
- [Docker logging drivers](https://docs.docker.com/engine/logging/configure/),
  [`docker inspect` LogPath](https://docs.docker.com/reference/cli/docker/inspect/)
  and [Compose `!override`](https://docs.docker.com/reference/compose-file/merge/)

Select the Log API endpoint from the account's data region, not by guesswork:

| Region | `PARKIO_NR_LOG_BASE_URI` |
| --- | --- |
| US | `https://log-api.newrelic.com/log/v1` |
| EU | `https://log-api.eu.newrelic.com/log/v1` |
| Japan | `https://log-api.jp.nr-data.net/log/v1` |
| FedRAMP | `https://gov-log-api.newrelic.com/log/v1` |

Use a New Relic ingest/license key; do not use a user key or browser API key.
New Relic accepts ISO-8601 timestamps but may drop data older than 48 hours.
The API documents a 1 MiB request limit, up to 255 attributes, strings over
4,094 characters stored as blob attributes, and `429` backoff via
`Retry-After`. This candidate caps `message` at 8,192 bytes, so long messages
may not be indexed as ordinary string attributes even though they are accepted.

## Fluent Bit security advisory assessment

The exact image remains
`fluent/fluent-bit:3.2.10@sha256:d6dec000c4929a439562525728c708f6e99800d7ddc82efd6aa4f45f3a20b562`
(`linux/amd64` child
`sha256:41370c4edd3a09db6565271ef647a5f69808007cf6c8ce5f205e056a77a18f41`).
The configured plugin surface is `tail` and `dummy` inputs; `lua` and
`record_modifier` filters; and upstream's built-in `nrlogs` output. There is no
`Plugins_File`, external `out_newrelic.so`, `in_forward`, `out_forward`,
`in_docker`, OpenTelemetry input, Prometheus Remote Write input or
`--supervisor`.

Official-advisory applicability reviewed 2026-09-21:

| Advisory | Exact-candidate assessment |
| --- | --- |
| New Relic [NR24-01 / CVE-2024-4323](https://docs.newrelic.com/docs/security/new-relic-security/security-bulletins/security-bulletin-nr24-01/) | Core Fluent Bit must be >=3.0.4; candidate is 3.2.10. The separate New Relic output-plugin advisory covers external plugin 1.16.0–1.19.2, but this candidate does not load that plugin. Not applicable to the enabled pipeline. |
| CVE-2024-50608 / CVE-2024-50609 | Fixed in [Fluent Bit 3.2.7](https://fluentbit.io/announcements/v3.2.7/) and affect Prometheus Remote Write/OpenTelemetry inputs. Candidate is 3.2.10 and enables neither input. Not applicable. |
| [GHSA-jrp8-r9hx-gf73 / CVE-2026-61674](https://github.com/fluent/fluent-bit/security/advisories/GHSA-jrp8-r9hx-gf73) | Advisory lists all versions >=0.11.0 with no patched version, but reachability requires `out_forward` Secure Forward with a shared key and a malicious/on-path upstream. Candidate uses `nrlogs`, not `out_forward`, and no supervisor. Vulnerable code may exist in the image, but the described path is not enabled. |
| 2025 issues in pipelines running 3.x | Fluent Bit lists tag-key, `out_file`, `in_docker` and `in_forward` issues as fixed only in 4.0.13/4.1.1/4.2. None of those plugins/paths are enabled here. |

The decisive finding is lifecycle, not just individual plugin reachability:
Fluent Bit's [current security policy](https://github.com/fluent/fluent-bit/security)
marks 3.2.x EOL, so it receives no further fixes. Therefore the New Relic UI
warning is not evidence that NR24-01 is exploitable in this configuration, but
the exact 3.2.10 image is still **not acceptable for a new production
activation without explicit security risk acceptance**. Preferred resolution:
repin to a currently supported release/digest, review the enabled plugin surface,
and rerun the exact 20/20 acceptance because the transport/parser runtime has
changed. This PR does not silently change the previously accepted image.

## Exact exported schema

Every accepted record contains:

| Field | Meaning |
| --- | --- |
| `timestamp` | Source Docker timestamp preserved by Fluent Bit |
| `service` | One of the three allowlisted services, derived from mount path |
| `environment` | Exact operator-supplied environment label |
| `severity`, `level` | `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `FATAL`, or `UNKNOWN` |
| `message` | Redacted/multiline message, capped at 8,192 bytes |
| `parse_status` | `ok` or `unparsed` |
| `redaction` | `none` or `applied` |
| `source_kind` | `docker-json-file` or `synthetic` |
| `release_id` | Operator-supplied deployed application identity |
| `collector_source_sha` | Reviewed collector source commit |
| `pipeline` | `nr-log-pilot` |
| `schema_version` | `ops.log.v1` |
| `collector` | `fluent-bit-3.2.10` |
| `logtype` | `parkio-ops` |

Optional allowlisted fields are `source_stream`, `correlation_id`, `trace_id`,
`span_id`, `error_code`, `event_name` and `pilot_marker`. The native output also
adds `plugin`; this was observed in the mock payload.

Recognized waitlist event names are:

- `waitlist.confirmation.delivery_failed`
- `waitlist.withdrawal.delivery_failed`
- `waitlist.confirmation.delivered`
- `waitlist.withdrawal.delivered`

## Redaction model and limits

The Lua filter rebuilds every outbound record from an allowlist. It strips
emails and email hashes; credential/token assignments; confirmation,
unsubscribe and withdrawal path tokens; Resend-style keys; and all query
strings in detected HTTP(S) URLs. Records containing authorization/cookie
headers, JWT-shaped values, private keys, precise coordinates or the prohibited
test canary are dropped as a whole. Multiline Java exceptions are joined before
filtering, so stack frames receive the same treatment.

This is risk reduction, not a proof that arbitrary free text is safe. Lua
patterns are not a general parser and can miss new labels, encodings, split
secrets, non-HTTP URLs or novel personal data. Hard-drop patterns can also
create false positives and visibility gaps; URL query stripping can over-redact.
The remaining `message` is still untrusted free text. A future application-owned
structured allowlist is safer than broadening regex coverage. Do not widen the
service set or export raw Docker envelope fields without a separate privacy
review.

## Local isolated acceptance

The exact collector candidate
`47d3b73cff2b22f5f6a1a7d8b3aae4df0eeac229` previously passed all 20/20
isolated assertions. That result is reused for this runtime preflight. It was
not repeated because the parser, redaction, buffering and output configuration
did not change; the new resolver and production `restart: "no"` setting are
validated separately. Any image, plugin or pipeline configuration change
invalidates that reuse and requires the full acceptance again.

Run only from the reviewed checkout:

```bash
python3 scripts/newrelic_log_pilot/run_isolated_validation.py
```

The harness creates task-specific project, ports, network and volumes; generates
an ephemeral TLS certificate; sends only synthetic Docker JSON records; and
removes the resources on completion. It covers the successful New Relic payload
contract, 401, 404, 429, network disconnect/recovery, persistent 503 pressure,
bounded storage, non-blocking source writes, graceful restart/checkpoint,
rotation, Java multiline, service identity and prohibited-canary absence.

Delivery is at-least-once. A graceful restart/rotation sample should show no
duplicate, but a crash or lost acknowledgement can duplicate a record. When the
20 MiB logical output limit is exceeded, Fluent Bit can discard oldest chunks;
loss is preferable to filling the host disk or blocking application requests.
An abrupt host failure is not claimed lossless.

## External account and key handoff

Current operator-provided state:

| Input | State |
| --- | --- |
| New Relic account | Confirmed present |
| Logs UI access | Confirmed accessible |
| Data region | Confirmed EU |
| Log API endpoint | `https://log-api.eu.newrelic.com/log/v1` |
| `Ingest - License` key | Operator confirms it is ready; value not requested or inspected |
| Billing/free-tier and retention conditions | **Unverified; activation blocker** |
| Approved numeric ingest cap | Proposed below; account-owner approval still required |

Do not create a subscription, change billing/retention or paste a key into chat,
shell history, Compose YAML, Git or ticket text. Install it interactively into a
root-readable tmpfs file. This procedure does not print the value, put it in an
argument, or persist it across reboot:

```bash
set -euo pipefail
umask 077
secret_env=/dev/shm/parkio-nr-log-pilot.secret.env

# Run in an interactive terminal with xtrace disabled. Paste only at this
# hidden prompt; never pass the key in the command line or chat.
set +x
IFS= read -r -s -p 'New Relic EU ingest license key: ' nr_key
printf '\n'
[ -n "$nr_key" ] || { unset nr_key; printf 'empty key; nothing installed\n' >&2; exit 1; }
printf 'PARKIO_NR_LOG_API_KEY=%s\n' "$nr_key" >"$secret_env"
unset nr_key
chmod 600 "$secret_env"

# Verify metadata only; do not cat the file or run `docker compose config`.
test "$(stat -c '%a' "$secret_env")" = 600
test "$(stat -c '%U' "$secret_env")" = "$(id -un)"
test "$(wc -l <"$secret_env")" -eq 1
```

Create a separate non-secret runtime file. Resolve source directories into a
third file only after the application deployment has settled:

```bash
runtime_env=/dev/shm/parkio-nr-log-pilot.runtime.env
source_env=/dev/shm/parkio-nr-log-pilot.sources.env
umask 077
{
  printf 'PARKIO_NR_LOG_BASE_URI=%s\n' 'https://log-api.eu.newrelic.com/log/v1'
  printf 'PARKIO_ENVIRONMENT=%s\n' 'production'
  printf 'PARKIO_RELEASE_ID=%s\n' 'EXACT-DEPLOYED-IDENTITY'
  printf 'PARKIO_COLLECTOR_SOURCE_SHA=%s\n' 'EXACT-REVIEWED-COMMIT'
  printf 'PARKIO_NR_PILOT_MARKER=%s\n' 'p02-YYYYMMDDTHHMMSSZ-RANDOM'
} >"$runtime_env"
./scripts/newrelic_log_pilot/resolve_production_sources.sh >"$source_env"
chmod 600 "$runtime_env" "$source_env"
```

Replace every placeholder from reviewed runtime evidence. Keep xtrace off. Do
not `cat` the secret file, export the key globally, copy it to disk-backed
storage, or render/print the resolved Compose configuration: it contains the
key. Remove the tmpfs files during rollback.

## Bounded synthetic-only New Relic proof

This step is optional and separately authorized only after the external inputs
exist. It tails an empty project-scoped volume and sends exactly one startup
marker; it does not mount production logs:

```bash
secret_env=/dev/shm/parkio-nr-log-pilot.secret.env
runtime_env=/dev/shm/parkio-nr-log-pilot.runtime.env
project=parkio-nr-log-pilot-synthetic
docker compose --env-file "$secret_env" --env-file "$runtime_env" -p "$project" \
  -f docker/docker-compose.newrelic-log-pilot.yml \
  --profile nr-log-pilot up -d --no-deps fluent-bit-nr-pilot
```

In New Relic Logs, prove one matching record:

```sql
SELECT count(*) FROM Log
WHERE pipeline = 'nr-log-pilot'
  AND source_kind = 'synthetic'
  AND event_name = 'nr_log_pilot.synthetic_probe'
  AND pilot_marker = 'EXACT-MARKER'
SINCE 30 minutes ago
```

Then stop and remove only the synthetic project:

```bash
docker compose --env-file "$secret_env" --env-file "$runtime_env" \
  -p parkio-nr-log-pilot-synthetic \
  -f docker/docker-compose.newrelic-log-pilot.yml \
  --profile nr-log-pilot down
```

Do not proceed to continuous collection merely because this marker succeeds.

## Resource controls and one-hour budget gate

These limits are different and must not be conflated:

| Control | What is enforced | What is not enforced |
| --- | --- | --- |
| `storage.total_limit_size 20M` | Logical backlog limit for undelivered `nrlogs` chunks; oldest chunks may be discarded | Not a named-volume/filesystem quota and not a vendor-ingest cap |
| Tail DB and state volume | Checkpoints plus buffered chunks survive a normal collector restart | Named volume has no hard byte quota; SQLite/filesystem overhead is additional |
| Collector Docker logging | `json-file`, 10 MiB × 3 files, about 30 MiB maximum | Does not include Fluent Bit state volume |
| Container memory | Compose `mem_limit: 128m`; `/tmp` tmpfs is 16 MiB and consumes memory | Does not guarantee graceful degradation; the collector can be OOM-killed |
| CPU/PIDs | 0.25 CPU and 128 PIDs | Do not cap disk or New Relic ingest |
| New Relic usage | Account billing/retention policy and accepted payload volume | The 20 MiB backlog does not limit successfully delivered data |

The expected local filesystem footprint is therefore approximately 50 MiB
(20 MiB logical output backlog + 30 MiB collector logs) plus Tail SQLite,
filesystem allocation and metadata overhead. The pressure acceptance wrote more
than 20 MB of synthetic source data and observed bounded queue behavior; it did
not prove a hard disk quota or vendor cap.

The isolated acceptance reports `exported_records`,
`uncompressed_json_bytes`, `mean_exported_record_bytes` and
`max_exported_record_bytes`. These are measured synthetic values, including an
intentional large-message/backpressure sample; they are not production traffic.

Production volume is unknown until measured. Before approval, measure only file
sizes and line counts for 15 minutes on the three verified log directories;
never print log contents. Account for rotations by summing all `*-json.log*`
files at both observations. Calculate:

```text
events_per_hour = positive_line_delta * 4
conservative_uncompressed_bytes_per_hour = events_per_hour * measured_max_exported_record_bytes
one_hour_pilot_cap = min(account_owner_approved_bytes, conservative_uncompressed_bytes_per_hour)
```

If rotations make the delta ambiguous, repeat during a stable interval; do not
invent a traffic rate. The activation decision must record observations,
calculation, numeric account-owner cap and a one-hour pilot duration. Stop early
if New Relic-reported ingest reaches the approved cap.

For planning, reserve a **25 MiB (26,214,400-byte) one-hour pilot ceiling**. It
is deliberately close to, but above, the 22,588,890-byte synthetic pressure
sample and is not a claim about production traffic. The final cap is the lower
of 25 MiB, the measured 15-minute extrapolation and the account owner's approved
allowance.

The current candidate does **not** enforce that vendor ceiling: successful
delivery bypasses the 20 MiB backlog, the state named volume has no quota, and
New Relic's authoritative `NrConsumption` usage is hourly and can lag by hours.
[`bytecountestimate()`](https://docs.newrelic.com/docs/accounts/accounts-billing/new-relic-one-pricing-billing/usage-queries-alerts/)
is an estimate, not billing truth. Fluent Bit's
[`fluentbit_output_proc_bytes_total`](https://docs.fluentbit.io/manual/3.2/administration/monitoring)
counts unique chunks successfully sent and is a useful local stop signal, but a
poller can overshoot during a burst. A systemd timer can enforce the one-hour
duration, but no current setting guarantees stopping before 25 MiB during an
arbitrary burst. This is an activation blocker, not a documentation waiver.
Before activation, add and validate a fail-closed collector-side rate/byte gate
with margin (preferably while repinning to a supported Fluent Bit release), plus
the one-hour stop unit below. Do not use a New Relic alert as the hard stop;
alerts are not authorized here and vendor usage is delayed.

## Log search and minimal dashboard

Service/environment/severity filter:

```sql
SELECT count(*) FROM Log
WHERE pipeline = 'nr-log-pilot'
  AND environment = 'production'
FACET service, severity
SINCE 1 hour ago
```

Gateway errors with useful context:

```sql
SELECT timestamp, severity, message, correlation_id, error_code, release_id
FROM Log
WHERE pipeline = 'nr-log-pilot'
  AND environment = 'production'
  AND service = 'gateway-service'
  AND severity IN ('WARN', 'ERROR', 'FATAL')
SINCE 1 hour ago
LIMIT 100
```

Waitlist delivery failures:

```sql
SELECT timestamp, severity, message, correlation_id, error_code
FROM Log
WHERE pipeline = 'nr-log-pilot'
  AND environment = 'production'
  AND service = 'gateway-service'
  AND event_name IN (
    'waitlist.confirmation.delivery_failed',
    'waitlist.withdrawal.delivery_failed'
  )
SINCE 1 hour ago
LIMIT 100
```

The minimal dashboard needs three widgets: the service/severity count above;
`SELECT count(*) ... FACET event_name TIMESERIES` restricted to the two failure
events; and the exact synthetic-marker count. Do not create alerts in this task.

## Activation — requires a separate production decision

Do not execute this section with the current blockers. Activation requires a
separate decision after all of the following are recorded:

1. the concurrent gateway deployment has settled;
2. a Docker-supported source method (or explicit risk acceptance for direct
   private-file access) has been approved;
3. a supported Fluent Bit image digest has passed the full 20/20 acceptance (or
   the 3.2.10 EOL risk has been explicitly accepted);
4. Logs access, EU endpoint and synthetic-only marker are proven;
5. billing/retention conditions and the numeric budget are approved; and
6. a collector-side byte/rate gate and one-hour automatic stop are validated.

Only after those gates, generate and immediately verify fresh source metadata:

```bash
set -euo pipefail
cd /path/to/reviewed/parkio
secret_env=/dev/shm/parkio-nr-log-pilot.secret.env
runtime_env=/dev/shm/parkio-nr-log-pilot.runtime.env
source_env=/dev/shm/parkio-nr-log-pilot.sources.env
project=parkio-nr-log-pilot-prod

umask 077
./scripts/newrelic_log_pilot/resolve_production_sources.sh >"$source_env"
chmod 600 "$source_env"
./scripts/newrelic_log_pilot/resolve_production_sources.sh --check-env "$source_env"

# This validation emits no rendered configuration and therefore no key.
docker compose --env-file "$secret_env" --env-file "$runtime_env" \
  --env-file "$source_env" -p "$project" \
  -f docker/docker-compose.newrelic-log-pilot.yml \
  -f docker/docker-compose.newrelic-log-pilot.production.yml \
  --profile nr-log-pilot config --quiet

# Starts only the collector service; never omit --no-deps.
docker compose --env-file "$secret_env" --env-file "$runtime_env" \
  --env-file "$source_env" -p "$project" \
  -f docker/docker-compose.newrelic-log-pilot.yml \
  -f docker/docker-compose.newrelic-log-pilot.production.yml \
  --profile nr-log-pilot up -d --no-deps fluent-bit-nr-pilot

# Arm a terminal-independent duration stop. The host preflight verified
# systemd-run 255. Failure to arm this timer means immediate rollback.
collector_id="$(docker compose --env-file "$secret_env" --env-file "$runtime_env" \
  --env-file "$source_env" -p "$project" \
  -f docker/docker-compose.newrelic-log-pilot.yml \
  -f docker/docker-compose.newrelic-log-pilot.production.yml \
  --profile nr-log-pilot ps -q fluent-bit-nr-pilot)"
[ -n "$collector_id" ]
sudo systemd-run --unit=parkio-nr-log-pilot-autostop --on-active=1h \
  --property=Type=oneshot /usr/bin/docker stop --time 30 "$collector_id"
sudo systemctl is-active --quiet parkio-nr-log-pilot-autostop.timer

# Detect a deployment/recreate race immediately. Any mismatch is a failed
# activation even if the synthetic marker arrived.
./scripts/newrelic_log_pilot/resolve_production_sources.sh --check-env "$source_env"
```

Pilot duration: one hour, with the production overlay's `restart: "no"`. The
operator must arm the independently supervised one-hour stop immediately after
starting, roll back if it cannot be armed, and run `--check-env` at least every
30 seconds; a stale/missing source triggers the rollback command below. A shell
loop alone is not an adequate stop timer because it can die while the detached
collector remains running.

Success requires: exactly one startup marker for the activation; all three
services visible if they naturally emit traffic; useful gateway/waitlist error
context; no prohibited canary or raw subscriber values; collector/process and
delivery health; state comfortably below the 20 MiB logical limit; ingest below
the approved cap; and no application restart, latency or availability impact.
Do not generate production user traffic merely to satisfy a visibility
criterion.

Stop immediately for repeated delivery failures, unknown source identity,
sensitive data, buffer growth toward the cap, unexpected cost/volume, or any
application impact.

## Application-independent rollback

Stop only the collector project; do not restart an application:

```bash
secret_env=/dev/shm/parkio-nr-log-pilot.secret.env
runtime_env=/dev/shm/parkio-nr-log-pilot.runtime.env
source_env=/dev/shm/parkio-nr-log-pilot.sources.env
docker compose --env-file "$secret_env" --env-file "$runtime_env" \
  --env-file "$source_env" -p parkio-nr-log-pilot-prod \
  -f docker/docker-compose.newrelic-log-pilot.yml \
  -f docker/docker-compose.newrelic-log-pilot.production.yml \
  --profile nr-log-pilot down
sudo systemctl stop parkio-nr-log-pilot-autostop.timer 2>/dev/null || true
rm -f "$secret_env" "$runtime_env" "$source_env"
```

Named state volumes remain after `down`, preserving checkpoint evidence and
possible buffered logs. Deleting those volumes is a separate destructive
decision. Confirm the three application containers retained the same IDs and
remained running. New Relic data already accepted is governed by its retention
policy; stopping the collector does not delete it.

## Shared-file dependencies

Do not append the overlay to `docker/compose.production.files`: required
secret/path interpolation would affect unrelated production commands even while
the profile is inactive.

The current supportability blocker requires one later coordinated choice; none
is applied here:

- application-owned structured file output plus explicit read-only mounts;
- an approved logging-driver change in shared production Compose; or
- a least-privilege host helper that consumes Docker's supported logs API and
  never exposes the Docker socket inside the collector.

Any of these crosses the current ownership boundary. A durable integration also
needs a shared wrapper that conditionally includes the two collector files,
resolves current identities, refuses stale sources, arms the budget/duration
stop, and stops on checker failure. This document is the proposed handoff; no
shared application, Compose or deployment file was modified.
