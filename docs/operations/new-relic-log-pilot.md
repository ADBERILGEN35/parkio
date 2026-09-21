# New Relic log pilot — controlled activation handoff

**Status:** source-prepared, disabled by default. Production activation and real
New Relic ingestion have not been performed. This is a log-only pilot: no APM
agent, tracing, session replay, analytics, alerts or Slack integration.

The collector is independent of the application Compose project. Starting or
stopping it must not recreate an application container. Only these application
services are in scope: `gateway-service`, `auth-service`, `parking-service`.

## Candidate identity and boundaries

- Base source: record the exact reviewed `origin/api` SHA in the change/PR.
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
- No `docker.sock` mount and no application configuration change are required.

The pilot files are deliberately not in `docker/compose.production.files`.
Always use a separate Compose project and the two explicit `-f` arguments below.

## Production log-source compatibility

Source inspection shows all three applications write to stdout/stderr, and
`docker/docker-compose.apps.yml` uses Docker's `json-file` driver with
`max-size: 10m` and `max-file: 5`. The application configuration does not name
an application log file. Therefore, the old pilot named volume was not connected
to the real application logs.

The production candidate instead mounts the parent directory of each live
container's Docker `LogPath`, read-only, into a service-specific target. The
trusted target path supplies `service`; log text cannot override it. Docker log
paths and container IDs can change when an application is recreated, so resolve
and re-check them immediately before every collector start. Stop the collector
before any application recreate, resolve the new paths, then make a separate
decision to restart it.

Live compatibility remains a precondition. On the production host, run this
read-only preflight without displaying log contents:

```bash
set -eu
cd /path/to/reviewed/parkio

docker compose version
docker info --format 'architecture={{.Architecture}}'

for service in gateway-service auth-service parking-service; do
  cid="$(./scripts/parkio-prod-compose.sh ps -q "$service")"
  test -n "$cid"
  docker inspect --format 'service={{index .Config.Labels "com.docker.compose.service"}} driver={{.HostConfig.LogConfig.Type}} logpath={{.LogPath}} max-size={{index .HostConfig.LogConfig.Config "max-size"}} max-file={{index .HostConfig.LogConfig.Config "max-file"}}' "$cid"
done
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
change and is outside this package.

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

## Required external inputs

Before any external request, an authorized account owner must confirm, out of
band:

1. A New Relic account and Logs entitlement exist.
2. The account data region and exact Log API endpoint.
3. An ingest-enabled license key dedicated or approved for this pilot.
4. Retention/billing terms and a numeric maximum pilot ingest budget.

Do not create a subscription, change billing/retention or paste a key into chat,
shell history, Compose YAML, Git or ticket text. One secure installation option
on the production host is a root-readable tmpfs file:

```bash
set -eu
umask 077
env_file=/dev/shm/parkio-nr-log-pilot.env
read -r -s -p 'New Relic ingest license key: ' nr_key
printf '\n'
{
  printf 'PARKIO_NR_LOG_API_KEY=%s\n' "$nr_key"
  printf 'PARKIO_NR_LOG_BASE_URI=%s\n' 'https://REGION-ENDPOINT/log/v1'
  printf 'PARKIO_ENVIRONMENT=%s\n' 'production'
  printf 'PARKIO_RELEASE_ID=%s\n' 'EXACT-DEPLOYED-IDENTITY'
  printf 'PARKIO_COLLECTOR_SOURCE_SHA=%s\n' 'EXACT-REVIEWED-COMMIT'
  printf 'PARKIO_NR_PILOT_MARKER=%s\n' 'p02-YYYYMMDDTHHMMSSZ-RANDOM'
  printf 'PARKIO_NR_GATEWAY_LOG_DIR=%s\n' '/var/lib/docker/containers/GATEWAY-ID'
  printf 'PARKIO_NR_AUTH_LOG_DIR=%s\n' '/var/lib/docker/containers/AUTH-ID'
  printf 'PARKIO_NR_PARKING_LOG_DIR=%s\n' '/var/lib/docker/containers/PARKING-ID'
} >"$env_file"
unset nr_key
chmod 600 "$env_file"
```

Replace every placeholder from verified runtime/account evidence. Do not render
or print the resolved Compose configuration: it contains the key.

## Bounded synthetic-only New Relic proof

This step is optional and separately authorized only after the external inputs
exist. It tails an empty project-scoped volume and sends exactly one startup
marker; it does not mount production logs:

```bash
env_file=/dev/shm/parkio-nr-log-pilot.env
project=parkio-nr-log-pilot-synthetic
docker compose --env-file "$env_file" -p "$project" \
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
docker compose --env-file "$env_file" -p parkio-nr-log-pilot-synthetic \
  -f docker/docker-compose.newrelic-log-pilot.yml \
  --profile nr-log-pilot down
```

Do not proceed to continuous collection merely because this marker succeeds.

## Measured volume and pilot budget gate

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

After source review, live preflight, synthetic-only New Relic proof, privacy
acceptance and budget approval:

```bash
set -eu
cd /path/to/reviewed/parkio
env_file=/dev/shm/parkio-nr-log-pilot.env
project=parkio-nr-log-pilot-prod

# This validation emits no rendered configuration and therefore no key.
docker compose --env-file "$env_file" -p "$project" \
  -f docker/docker-compose.newrelic-log-pilot.yml \
  -f docker/docker-compose.newrelic-log-pilot.production.yml \
  --profile nr-log-pilot config --quiet

# Starts only the collector service; never omit --no-deps.
docker compose --env-file "$env_file" -p "$project" \
  -f docker/docker-compose.newrelic-log-pilot.yml \
  -f docker/docker-compose.newrelic-log-pilot.production.yml \
  --profile nr-log-pilot up -d --no-deps fluent-bit-nr-pilot
```

Pilot duration: one hour. Success requires: exactly one startup marker for the
activation; all three services visible if they naturally emit traffic; useful
gateway/waitlist error context; no prohibited canary or raw subscriber values;
collector/process and delivery health; state comfortably below the 20 MiB
logical limit; ingest below the approved cap; and no application restart,
latency or availability impact. Do not generate production user traffic merely
to satisfy a visibility criterion.

Stop immediately for repeated delivery failures, unknown source identity,
sensitive data, buffer growth toward the cap, unexpected cost/volume, or any
application impact.

## Application-independent rollback

Stop only the collector project; do not restart an application:

```bash
env_file=/dev/shm/parkio-nr-log-pilot.env
docker compose --env-file "$env_file" -p parkio-nr-log-pilot-prod \
  -f docker/docker-compose.newrelic-log-pilot.yml \
  -f docker/docker-compose.newrelic-log-pilot.production.yml \
  --profile nr-log-pilot down
rm -f "$env_file"
```

Named state volumes remain after `down`, preserving checkpoint evidence and
possible buffered logs. Deleting those volumes is a separate destructive
decision. Confirm the three application containers retained the same IDs and
remained running. New Relic data already accepted is governed by its retention
policy; stopping the collector does not delete it.

## Shared-file dependencies

There is no shared-file change required for this controlled one-hour activation:
the explicit collector Compose files are sufficient. Do not append the overlay
to `docker/compose.production.files`: required secret/path interpolation would
affect unrelated production commands even while the profile is inactive.

A later durable integration would require coordinated ownership of a wrapper
that conditionally includes the two files only when explicitly enabled, securely
resolves current `LogPath` parents, and refuses stale container IDs. That is not
part of this collector-only package.
