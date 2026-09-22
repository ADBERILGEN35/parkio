# New Relic logs — continuous-operation release candidate

**Preparation status (2026-09-22):** implementation and isolated acceptance
only. Production collection is OFF. This package does not authorize installation,
another pilot, permanent activation or a merge.

The bounded pilot remains accepted only within its demonstrated scope. Its quiet
gateway/auth sources are not used to estimate demand. The continuous candidate
adds restart-safe UTC daily/monthly transport budgets, bounded local queues, a
live source-identity guard, graceful gate shutdown and a remediated collector
image. The initial transport budgets and private collector artifact are now
fixed below; production activation remains a separate decision.

## Release identity and image policy

PR #66 was reconciled without force-push with current `api` commit
`e113a151d5f9cee12c5fa5a3e97f53a80429329f`, which includes merged PR #71 and
the subsequent parking timestamp fix. The published artifact was built from
release-source commit `a41f2f7d7fe2997f5bd270f1f1ef8938002e1b1e`; the
release-package content commit is
`73bc2af5259213b15e77a1d557f2b8914b709961`. The terminal integration head and
CI result are recorded in the PR and release report. `docker/Dockerfile.fluent-bit-nr`
has these immutable inputs:

- upstream Fluent Bit 5.1.2 manifest-list digest
  `sha256:d792375ca8e53be72fc25716c28f291f32c6fc6f4f31d12d0d14bc78cefe9226`;
- selected `linux/amd64` upstream child digest
  `sha256:71cda445290efc2d45d565c12e0de4b15aa0182510276ad50c317aae1423d7ee`;
- Debian 13 slim patch-builder manifest-list digest
  `sha256:a99cfc517144bc59b1978475ec53b46ecabec7e43635402ee5b77cc54cd1b20a`;
- exact fixed package `libssh2-1t64=1.11.1-1+deb13u2`, archive SHA-256
  `dbb1024c192d4d292b7cfa902b96076cfe81b56a5eed4fb28da36e8bf543e49a`.

The private published artifact and immutable deployment pin are:

```text
ghcr.io/adberilgen35/parkio/fluent-bit-nr@sha256:1fc39bab4984ade1fef732d0b5892963aae8c67af00f21ca8bbb643dcb181fee
```

Both tags `sha-a41f2f7d7fe2997f5bd270f1f1ef8938002e1b1e` and
`5.1.2-libssh2-deb13u2` resolve to that OCI index. Its `linux/amd64` runtime
manifest is
`sha256:ace21c25374a43de693de3d37354f15e9f650d27430422d4a258b9f618792a83`.
The attached attestation manifest
`sha256:a61dd835703d32b984df89604dfa8b5c7ca9a4d8bdbfe9e3fb640a9a3ce49ebb`
references that runtime manifest and contains an SPDX document plus SLSA v1
provenance. OCI labels report the source repository, release-source commit and
version. GitHub reports package `parkio/fluent-bit-nr` as `private`. Compose pins
the index digest directly; tags are evidence only and are not deployment input.

An exact Trivy scan of the digest-pulled private GHCR artifact found **0
CRITICAL and 5 HIGH** OS findings on Debian 13.6. The prior fix-available sixth HIGH,
`CVE-2026-58050` in `libssh2-1t64`, is removed by Debian's `deb13u2` package.
The image is not clean. `readelf` confirms that `libcurl.so.4` and
`libsystemd.so.0` remain dynamic dependencies of Fluent Bit, so the five
findings stay open even though their known trigger paths are not configured:

| Finding | Exact package | Policy | Candidate-specific exposure |
| --- | --- | --- | --- |
| `CVE-2026-12064` | `libcurl4t64` 8.14.1-2+deb13u4 | `OPEN-NO-FIX` | SFTP/SCP and curl-CLI trigger is not configured; library remains linked. |
| `CVE-2026-8286` | `libcurl4t64` 8.14.1-2+deb13u4 | `OPEN-NO-FIX` | STARTTLS connection-reuse protocols are not configured; library remains linked. |
| `CVE-2026-8458` | `libcurl4t64` 8.14.1-2+deb13u4 | `OPEN-NO-FIX` | Negotiate authentication is not configured; library remains linked. |
| `CVE-2026-8927` | `libcurl4t64` 8.14.1-2+deb13u4 | `OPEN-NO-FIX` | Proxy/Digest-proxy behavior is not configured; library remains linked. |
| `CVE-2026-16742` | `libsystemd0` 257.13-1~deb13u1 | `OPEN-NO-FIX` | No systemd-homed service or journald input is present; library remains linked. |

Only `tail`, `lua`, `record_modifier` and `nrlogs` are enabled. Fluent Bit sends
plain HTTP only to the private gate; the Python gate performs the EU HTTPS
request. A plugin, protocol, proxy, entrypoint, base-image or package change
invalidates the exposure review and requires a rebuild, scan and acceptance.

Repository policy in `.github/workflows/security-ci.yml` reports fixable
HIGH/CRITICAL image findings and blocks on fixable CRITICAL image findings; both
commands use `--ignore-unfixed`. It separately blocks fixable HIGH/CRITICAL
dependency findings. The explicit collector scan intentionally omits
`--ignore-unfixed` so the five findings remain visible. Therefore this exact
artifact, with zero CRITICAL and five inventoried no-fix HIGH findings,
satisfies the existing automated image gate. This is a policy classification,
not a waiver or a claim that the image is clean. Any fixed version appearing
later, any new CRITICAL, or any change that reaches the affected protocols
blocks reuse pending rebuild and review.

References: [Fluent Bit releases](https://github.com/fluent/fluent-bit/releases),
[Fluent Bit security policy](https://github.com/fluent/fluent-bit/security),
[Debian CVE-2026-58050](https://security-tracker.debian.org/tracker/CVE-2026-58050),
[Debian systemd tracker](https://security-tracker.debian.org/tracker/source-package/systemd).

## Collection and replacement behavior

`docker_log_source.py` is the only component with Docker access. It runs on the
host and uses Docker's supported `docker logs --follow --timestamps --since`
interface. Neither collector container receives `docker.sock`; Docker's private
`json-file` paths are never mounted or read. The helper resolves exactly one
running container for each of `gateway-service`, `auth-service` and
`parking-service`, validates project/service labels, full IDs, running state and
the approved driver, then maintains one stdout and one stderr follower per
service. Short `docker ps`/`inspect` commands are serialized to avoid the helper
cgroup being charged three simultaneous Docker CLI RSS peaks.

The source unit has `MemoryHigh=100M`, `MemoryMax=128M`, `CPUQuota=25%` and
`TasksMax=64`. Isolated three-service replacement/disconnect acceptance peaked
at 29.7 MiB with no OOM, no helper-reported drops and a clean exit. This used
Docker CLI 29.5.3; a future host preflight must repeat the content-free identity
and resource check against the production Docker version.

New identities start at attachment time and do not backfill historical records.
For the same identity, the last fsynced Docker timestamp is reused inclusively;
a crash or acknowledgement loss can duplicate the boundary record. A replacement
gets a new cursor and can have a detection-window gap. Docker disconnects create
a visible `disconnected` status and retry; the one-minute guard stops the entire
dedicated transport if any approved source remains stale at a check. The guard
also transitions the transport unit to inactive, and its timer does not pull an
inactive transport back up. It never
restarts or blocks an application container. `docker logs` timestamps are
RFC3339Nano; stdout/stderr ordering and exactly-once delivery are not guaranteed.

References: [Docker logs](https://docs.docker.com/reference/cli/docker/container/logs/)
and [Docker's json-file access warning](https://docs.docker.com/engine/logging/drivers/json-file/).

## Budget, queue and storage semantics

The gate counts the UTF-8 byte length of the uncompressed serialized JSON body
before every admitted upstream HTTP attempt. Each admitted retry is charged
again because a lost response cannot prove that New Relic rejected the prior
attempt. Records and compressed wire bytes are counters only. A rejected batch
increments attempted/rejected counters but consumes no transport-byte budget and
is acknowledged locally with HTTP 202 so Fluent Bit cannot build an unbounded
retry storm. That acknowledgment means the rejected batch is **dropped
permanently**, the Fluent Bit checkpoint advances, and it is not retained for
the next budget window. Records arriving while a daily or monthly window is
exhausted are likewise filtered/redacted, presented to the gate and dropped;
there is no next-day/month backfill. The gate health endpoint returns 507 while
a window is exhausted, but application services remain unaffected.

Daily and monthly UTC window key, configured limits, spent bytes, exhaustion,
attempt/retry/record counters and lifetime totals live in one SQLite database
with full synchronous writes. Restart preserves them. A changed configured limit
fails startup rather than resetting the ledger. Daily exhaustion fails closed
until the next UTC date; monthly exhaustion fails closed until the next UTC
month. On the first request after the UTC boundary—or a health/stats read—the
persisted window rolls, health returns 200 if no other window is exhausted, and
only subsequent records can be delivered. Restart is neither required nor able
to reset an exhausted current window. Monthly exhaustion continues to dominate
across daily resets until the next UTC month. Exact-fill exhaustion has no byte
overshoot. Concurrent reservation is serialized. An already admitted request
can finish during graceful shutdown; it was charged before transmission. If an
upstream attempt fails after reservation, Fluent Bit may retry it and every
admitted retry is charged again; exhaustion can therefore convert the eventual
retry into a permanent local drop.

The initial configured transport values—not traffic forecasts or New Relic
billable caps—are:

- `PARKIO_NR_DAILY_BUDGET_BYTES=26214400` (25 MiB/day);
- `PARKIO_NR_MONTHLY_BUDGET_BYTES=524288000` (500 MiB/month).

The daily value reuses the previously approved one-hour transport ceiling as a
conservative configurable envelope; it is not extrapolated from the quiet pilot.
The monthly value permits at most twenty fully saturated daily windows. New
Relic measures billable stored data differently
from these uncompressed attempted request bodies, so neither value guarantees a
vendor invoice or entitlement ceiling. New Relic documents a 1 MB request limit;
the gate enforces 1,000,000 uncompressed bytes, including after gzip expansion.

Local bounds are distinct:

| Layer | Control | What it guarantees |
| --- | --- | --- |
| Source helper | 2 MiB x 3 files x 3 services | 18 MiB logical payload; oldest source files rotate away. |
| Source allocation guard | 24 MiB | Stops dedicated transport when source parent allocation exceeds it. |
| Fluent Bit output | `storage.total_limit_size 20M` | Logical per-output backlog; Fluent Bit discards oldest chunks at the limit. |
| Collector allocation guard | 32 MiB | Stops dedicated transport when collector state allocation exceeds it. |
| Budget allocation guard | 4 MiB | Stops dedicated transport if the one-row SQLite state exceeds it. |
| Container logs | 10m x 3 for each of two containers | At most about 60 MB of Docker JSON log files, separate from the state guards. |
| Host floor | 5 GiB available | Guard stops transport below the floor. |

The guard runs every minute, so directory and host thresholds can overshoot by
all writes made between checks; filesystem allocation is also not the same as
logical file length. On detection it stops only the helper, collector and gate,
leaves their state in place and requires an explicit operator start after the
cause is resolved. These are stop controls, not filesystem quotas. Fluent Bit's
logical queue can discard data before the directory guard; the source helper
likewise rotates away its oldest data under prolonged downstream failure. No
application request waits on these components.

No repository policy requiring a filesystem/project quota was found. The
bounded pilot demonstrated only 82,120 bytes of collector state and the isolated
helper test stayed within its file-count/size limit; the production preflight
also had a large free-space margin. Those observations do not prove future
demand, but they do not demonstrate a host risk requiring a hard quota for this
bounded initial setup. The 18 MiB source rotation, 20 MB logical output queue,
allocation guards, container-log rotation and 5 GiB host floor are the selected
initial controls. Reassess a real quota if an allocation stop occurs, the host
free-space margin changes materially, or policy later mandates one.

References: [Fluent Bit backpressure and queue limits](https://docs.fluentbit.io/manual/administration/backpressure),
[New Relic Log API limits](https://docs.newrelic.com/docs/logs/log-api/introduction-log-api/),
and [New Relic data ingest billing](https://docs.newrelic.com/docs/accounts/accounts-billing/new-relic-one-pricing-billing/new-relic-one-pricing-billing/).

## Prepared installation and lifecycle (do not execute yet)

From the reviewed PR checkout, install an immutable archive and units without
starting or enabling them:

```bash
release_sha="$(git rev-parse HEAD)"
test -n "$release_sha"
sudo install -d -m 0755 -o root -g root "/opt/parkio-nr-log-continuous/releases/$release_sha"
git archive "$release_sha" | sudo tar -x -C "/opt/parkio-nr-log-continuous/releases/$release_sha"
sudo ln -sfn "/opt/parkio-nr-log-continuous/releases/$release_sha" /opt/parkio-nr-log-continuous/current
sudo install -m 0644 -o root -g root scripts/newrelic_log_pilot/systemd/*.service scripts/newrelic_log_pilot/systemd/*.timer /etc/systemd/system/
sudo install -d -m 0750 -o root -g root /var/lib/parkio-nr-log-continuous/source/logs /var/lib/parkio-nr-log-continuous/source/state /var/lib/parkio-nr-log-continuous/collector /var/lib/parkio-nr-log-continuous/budget
```

The four persistent directories are:

```text
/var/lib/parkio-nr-log-continuous/source/logs
/var/lib/parkio-nr-log-continuous/source/state
/var/lib/parkio-nr-log-continuous/collector
/var/lib/parkio-nr-log-continuous/budget
```

Create `/etc/parkio-nr-log-continuous/runtime.env` as root mode 0600 with:

```bash
sudo install -d -m 0700 -o root -g root /etc/parkio-nr-log-continuous
sudo install -m 0600 -o root -g root /dev/null /etc/parkio-nr-log-continuous/runtime.env
sudoedit /etc/parkio-nr-log-continuous/runtime.env
```

```dotenv
PARKIO_NR_UPSTREAM_BASE_URI=https://log-api.eu.newrelic.com/log/v1
PARKIO_ENVIRONMENT=production
PARKIO_RELEASE_ID=<current-application-release-id>
PARKIO_COLLECTOR_SOURCE_SHA=<final-pr-66-source-sha>
PARKIO_NR_DAILY_BUDGET_BYTES=26214400
PARKIO_NR_MONTHLY_BUDGET_BYTES=524288000
PARKIO_NR_SOURCE_ROOT=/var/lib/parkio-nr-log-continuous/source/logs
PARKIO_NR_SOURCE_STATE_ROOT=/var/lib/parkio-nr-log-continuous/source/state
PARKIO_NR_COLLECTOR_STATE_ROOT=/var/lib/parkio-nr-log-continuous/collector
PARKIO_NR_BUDGET_STATE_ROOT=/var/lib/parkio-nr-log-continuous/budget
PARKIO_NR_PILOT_PROJECT=parkio-nr-log-continuous
PARKIO_NR_SOURCE_HELPER_UNIT=parkio-nr-log-source.service
PARKIO_NR_TRANSPORT_UNIT=parkio-nr-log-continuous.service
PARKIO_NR_SOURCE_ALLOCATED_MAX_BYTES=25165824
PARKIO_NR_COLLECTOR_ALLOCATED_MAX_BYTES=33554432
PARKIO_NR_BUDGET_ALLOCATED_MAX_BYTES=4194304
PARKIO_NR_HOST_MIN_FREE_BYTES=5368709120
```

Install the key only in a root terminal, using hidden input and exclusive file
creation. Do not paste it into chat or put it on a command line:

```bash
sudo install -d -m 0700 -o root -g root /etc/parkio-nr-log-continuous
sudo bash -c 'set -euo pipefail; umask 077; test ! -e /etc/parkio-nr-log-continuous/secret.env; IFS= read -r -s -p "New Relic ingest license key: " key; printf "\n" >&2; test -n "$key"; printf "PARKIO_NR_LOG_API_KEY=%s\n" "$key" > /etc/parkio-nr-log-continuous/secret.env; unset key; chown root:root /etc/parkio-nr-log-continuous/secret.env; chmod 0600 /etc/parkio-nr-log-continuous/secret.env'
```

The private image requires a production-host GHCR credential with only
`read:packages`. Install it without command-line/history exposure; do not paste
it into chat:

```bash
sudo bash -c 'set -euo pipefail; IFS= read -r -s -p "GHCR read token: " token; printf "\n" >&2; test -n "$token"; printf "%s" "$token" | docker login ghcr.io -u ADBERILGEN35 --password-stdin >/dev/null; unset token'
sudo docker pull ghcr.io/adberilgen35/parkio/fluent-bit-nr@sha256:1fc39bab4984ade1fef732d0b5892963aae8c67af00f21ca8bbb643dcb181fee
sudo docker image inspect ghcr.io/adberilgen35/parkio/fluent-bit-nr@sha256:1fc39bab4984ade1fef732d0b5892963aae8c67af00f21ca8bbb643dcb181fee --format 'platform={{.Os}}/{{.Architecture}} revision={{index .Config.Labels "org.opencontainers.image.revision"}}'
```

Expected output is `platform=linux/amd64` and revision
`a41f2f7d7fe2997f5bd270f1f1ef8938002e1b1e`. Before any later authorized
activation, wait for application deployment activity to settle and resolve a
fresh source set. The parking container `86542ec9...` was superseded by
`446fd531...` at `2026-09-22T08:58:10.873072693Z` during the ISPARK allowlist
correction; that replacement prefix is lifecycle evidence, not a pinned
activation identity. All previously recorded gateway/auth/parking IDs are
stale, and all three full identities must be resolved immediately before any
separately authorized activation:

```bash
sudo install -d -m 0700 -o root -g root /run/parkio-nr-log-continuous
sudo bash -c 'set -euo pipefail; umask 077; PARKIO_NR_COMPOSE_PROJECT=parkio PARKIO_NR_SOURCE_ROOT=/var/lib/parkio-nr-log-continuous/source/logs PARKIO_NR_SOURCE_STATE_ROOT=/var/lib/parkio-nr-log-continuous/source/state /opt/parkio-nr-log-continuous/current/scripts/newrelic_log_pilot/resolve_production_sources.sh > /run/parkio-nr-log-continuous/sources.env'
sudo systemctl daemon-reload
sudo systemctl start parkio-nr-log-source.service
sudo env PARKIO_NR_COMPOSE_PROJECT=parkio PARKIO_NR_SOURCE_ROOT=/var/lib/parkio-nr-log-continuous/source/logs PARKIO_NR_SOURCE_STATE_ROOT=/var/lib/parkio-nr-log-continuous/source/state /opt/parkio-nr-log-continuous/current/scripts/newrelic_log_pilot/resolve_production_sources.sh --check-helper /run/parkio-nr-log-continuous/sources.env
```

The last check must print exactly three `helper=attached` results and no
unexpected source. Immediately before collector start, activation is
deliberately fail-closed:

```bash
sudo env PARKIO_NR_COMPOSE_PROJECT=parkio PARKIO_NR_SOURCE_ROOT=/var/lib/parkio-nr-log-continuous/source/logs PARKIO_NR_SOURCE_STATE_ROOT=/var/lib/parkio-nr-log-continuous/source/state /opt/parkio-nr-log-continuous/current/scripts/newrelic_log_pilot/resolve_production_sources.sh --check-live-helper
sudo systemctl start parkio-nr-log-continuous.service
sudo systemctl start parkio-nr-log-continuous-guard.timer
sudo systemctl start parkio-nr-log-continuous-guard.service
```

The collector unit repeats the live source-identity precheck. The guard then
repeats identity, allowlist, OOM, directory-allocation and host-free-space checks every
minute. Do not enable the units until a separate permanence decision. Inspect
only content-free status/counters; never print raw spooled or exported records.

Content-free health verification commands are:

```bash
sudo systemctl is-active parkio-nr-log-source.service parkio-nr-log-continuous.service parkio-nr-log-continuous-guard.timer
sudo env PARKIO_NR_COMPOSE_PROJECT=parkio PARKIO_NR_SOURCE_ROOT=/var/lib/parkio-nr-log-continuous/source/logs PARKIO_NR_SOURCE_STATE_ROOT=/var/lib/parkio-nr-log-continuous/source/state /opt/parkio-nr-log-continuous/current/scripts/newrelic_log_pilot/resolve_production_sources.sh --check-live-helper
sudo docker ps --filter label=com.docker.compose.project=parkio-nr-log-continuous --format '{{.Label "com.docker.compose.service"}} {{.Status}}'
gate_id="$(sudo docker ps -q --filter label=com.docker.compose.project=parkio-nr-log-continuous --filter label=com.docker.compose.service=nr-budget-gate)"
collector_id="$(sudo docker ps -q --filter label=com.docker.compose.project=parkio-nr-log-continuous --filter label=com.docker.compose.service=fluent-bit-nr-pilot)"
sudo docker inspect "$gate_id" "$collector_id" --format '{{index .Config.Labels "com.docker.compose.service"}} running={{.State.Running}} oom={{.State.OOMKilled}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}'
gate_ip="$(sudo docker inspect "$gate_id" --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')"
collector_ip="$(sudo docker inspect "$collector_id" --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')"
curl --fail --silent --show-error "http://${gate_ip}:8090/stats"
curl --fail --silent --show-error "http://${collector_ip}:2020/api/v1/health"
```

`/stats` contains counters and budgets, not log bodies. HTTP 507 from gate
health means a budget window is exhausted; it is an expected fail-closed state,
not application failure.

Rollback is application-independent:

```bash
sudo systemctl stop parkio-nr-log-continuous-guard.timer parkio-nr-log-continuous-guard.service
sudo systemctl stop parkio-nr-log-continuous.service parkio-nr-log-source.service
if sudo docker ps -q --filter label=com.docker.compose.project=parkio-nr-log-continuous | grep -q .; then echo 'rollback FAIL: transport container remains' >&2; exit 1; fi
sudo env PARKIO_NR_COMPOSE_PROJECT=parkio PARKIO_NR_SOURCE_ROOT=/var/lib/parkio-nr-log-continuous/source/logs PARKIO_NR_SOURCE_STATE_ROOT=/var/lib/parkio-nr-log-continuous/source/state /opt/parkio-nr-log-continuous/current/scripts/newrelic_log_pilot/resolve_production_sources.sh --check-env /run/parkio-nr-log-continuous/sources.env
```

Stop leaves source cursors, Fluent Bit checkpoints and budget ledger protected
on disk. Do not delete or replace the budget database to regain budget. Removal
of units, state or the secret is a separate, explicitly authorized uninstall.

## Acceptance evidence and remaining decisions

Local results for the final prepared runtime manifest on 2026-09-22 were:

- exact collector/source pipeline: **33/33 PASS**, 58 exported synthetic
  records, 280,205 uncompressed JSON bytes, 22,588,890 bytes of injected
  backpressure input, 82,120 bytes of Fluent Bit state, 1.334-second producer
  time, 6,704-byte largest exported record, and no prohibited canary in outbound
  payloads;
- continuous gate/unit behavior: **7/7 PASS**, covering UTC daily/monthly
  rollover, persistent configuration mismatch, legacy total mode, bounded gzip
  expansion, graceful in-flight SIGTERM and guard no-restart semantics;
- helper resource/replacement run: 29.7 MiB peak sampled cgroup usage, all three
  sources attached after replacement, zero reported drops, clean exit and no
  OOM under a 128 MiB maximum; and
- digest-pulled private GHCR artifact: Fluent Bit 5.1.2, `linux/amd64`, fixed
  `libssh2-1t64` package and OCI revision label verified; Trivy 0.74.0 with DB
  updated 2026-09-22 reported **0 CRITICAL / 5 HIGH**; provenance/SBOM
  attestation presence and subject link verified; and
- continuous Compose render with the immutable registry pin: PASS.

After the 33/33 functional run, the only runtime-image change was OCI metadata
and the only Compose change was replacing the image variable with the exact
registry digest; collector binary, fixed package filesystem and pipeline config
were unchanged. The relevant validation was therefore exact registry pull,
platform/label/package/version inspection, full no-fix-filter Trivy scan,
attestation inspection, 7/7 budget/lifecycle tests and Compose render. The
33/33 transport suite was not repeated without a behavioral change.

The exact pipeline suite includes:

- focused UTC window, restart mismatch and graceful in-flight shutdown tests;
- the exact remediated collector pipeline, including auth/gateway representative
  structured and multiline records, redaction canaries, retry/recovery, rotation,
  replacement, budget exhaustion and restart-safe accounting;
- three-service helper resource/replacement testing under the proposed cgroup;
- exact-image Trivy scan and Compose render; and
- applicable PR CI, whose terminal result is recorded in the PR after push;
  infrastructure failures are distinguished from candidate failures.

Representative gateway/auth logs are synthetic and prove routing, parsing and
redaction only. They do not prove production traffic volume or future behavior.
The existing allowlisted structured export and redaction limitations in
`new-relic-log-pilot.md` remain unchanged.

Remaining decisions before any permanent activation are:

1. authorize the activation window and select its operator/on-call owner;
2. provision and lifecycle-manage the production host's read-only GHCR
   credential and the already prepared New Relic ingest-key file;
3. repeat the exact-digest scan at activation time and stop if the image-policy
   result changes; and
4. schedule migration of the classic Fluent Bit `.conf` format to YAML, because
   Fluent Bit marks classic configuration deprecated with end-of-support at the end of
   2026.

Reference: [Fluent Bit configuration formats](https://docs.fluentbit.io/manual/administration/configuring-fluent-bit).
