# New Relic logs — continuous-operation release candidate

**Preparation status (2026-09-22):** implementation and isolated acceptance
only. Production collection is OFF. This package does not authorize installation,
another pilot, permanent activation or a merge.

The bounded pilot remains accepted only within its demonstrated scope. Its quiet
gateway/auth sources are not used to estimate demand. The continuous candidate
adds restart-safe UTC daily/monthly transport budgets, bounded local queues, a
live source-identity guard, graceful gate shutdown and a remediated collector
image. The exact production budgets and image publication remain owner decisions.

## Release identity and image policy

The reviewed source identity must be the final PR #66 head recorded after CI.
Build `docker/Dockerfile.fluent-bit-nr` from that checkout. Its immutable inputs
are:

- upstream Fluent Bit 5.1.2 manifest-list digest
  `sha256:d792375ca8e53be72fc25716c28f291f32c6fc6f4f31d12d0d14bc78cefe9226`;
- selected `linux/amd64` upstream child digest
  `sha256:71cda445290efc2d45d565c12e0de4b15aa0182510276ad50c317aae1423d7ee`;
- Debian 13 slim patch-builder manifest-list digest
  `sha256:a99cfc517144bc59b1978475ec53b46ecabec7e43635402ee5b77cc54cd1b20a`;
- exact fixed package `libssh2-1t64=1.11.1-1+deb13u2`, archive SHA-256
  `dbb1024c192d4d292b7cfa902b96076cfe81b56a5eed4fb28da36e8bf543e49a`.

The locally tested image is
`parkio/fluent-bit-nr:5.1.2-libssh2-deb13u2` with local OCI index/image ID
`sha256:09c3829bae0b250321745bd056df9600e58165a4327c7c78d6dcd4417ed0ab0f`
and `linux/amd64` runtime manifest
`sha256:5aab2bc30f06c0a2ac964042f835fc62024b24364e3d007158dab93517df1d70`.
It reports Fluent Bit 5.1.2. This local identity is evidence, not a deployable
registry reference. Before installation, publish the reviewed build to an
approved registry, verify its `linux/amd64` platform digest, rescan that exact
digest and set `PARKIO_NR_COLLECTOR_IMAGE` to the registry digest. Tag-only use
is prohibited.

An exact filesystem scan of the final local candidate found **0 CRITICAL and 5
HIGH** OS findings. The prior fix-available sixth HIGH,
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
retry storm. The gate health endpoint returns 507 while a window is exhausted.

Daily and monthly UTC window key, configured limits, spent bytes, exhaustion,
attempt/retry/record counters and lifetime totals live in one SQLite database
with full synchronous writes. Restart preserves them. A changed configured limit
fails startup rather than resetting the ledger. Daily exhaustion fails closed
until the next UTC date; monthly exhaustion fails closed until the next UTC
month. Exact-fill exhaustion has no byte overshoot. Concurrent reservation is
serialized. An already admitted request can finish during graceful shutdown;
it was charged before transmission.

Proposed governance values—not approved traffic forecasts or New Relic billable
caps—are:

- `PARKIO_NR_DAILY_BUDGET_BYTES=26214400` (25 MiB/day);
- `PARKIO_NR_MONTHLY_BUDGET_BYTES=524288000` (500 MiB/month).

The daily value reuses the previously approved one-hour transport ceiling as a
conservative configurable envelope; it is not extrapolated from the quiet pilot.
The monthly value permits at most twenty fully saturated daily windows and needs
explicit owner approval. New Relic measures billable stored data differently
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

The guard runs every minute, so directory and host thresholds can overshoot
between checks and filesystem allocation is not the same as logical file length.
These are stop controls, not filesystem quotas. A strict hard disk quota requires
a dedicated quota-enabled filesystem/project quota and remains an operator
decision. Fluent Bit's logical queue can discard data before the directory guard;
the source helper likewise loses oldest data under prolonged downstream failure.
No application request waits on these components.

References: [Fluent Bit backpressure and queue limits](https://docs.fluentbit.io/manual/administration/backpressure),
[New Relic Log API limits](https://docs.newrelic.com/docs/logs/log-api/introduction-log-api/),
and [New Relic data ingest billing](https://docs.newrelic.com/docs/accounts/accounts-billing/new-relic-one-pricing-billing/new-relic-one-pricing-billing/).

## Prepared installation and lifecycle (do not execute yet)

Install a reviewed immutable checkout under
`/opt/parkio-nr-log-continuous/releases/<source-sha>` and atomically point
`current` to it. Copy the four unit files from
`scripts/newrelic_log_pilot/systemd/` to `/etc/systemd/system/`. Create these
root-owned directories with mode 0750:

```text
/var/lib/parkio-nr-log-continuous/source/logs
/var/lib/parkio-nr-log-continuous/source/state
/var/lib/parkio-nr-log-continuous/collector
/var/lib/parkio-nr-log-continuous/budget
```

Create `/etc/parkio-nr-log-continuous/runtime.env` as root mode 0600 with:

```dotenv
PARKIO_NR_UPSTREAM_BASE_URI=https://log-api.eu.newrelic.com/log/v1
PARKIO_NR_COLLECTOR_IMAGE=<approved-registry>@sha256:<linux-amd64-digest>
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

After later authorization, activation order is deliberately fail-closed:

```bash
sudo systemctl daemon-reload
sudo systemctl start parkio-nr-log-source.service
sudo systemctl start parkio-nr-log-continuous.service
sudo systemctl start parkio-nr-log-continuous-guard.timer
sudo systemctl start parkio-nr-log-continuous-guard.service
```

The collector start has a live source-identity precheck. The guard then repeats
identity, allowlist, OOM, directory-allocation and host-free-space checks every
minute. Do not enable the units until a separate permanence decision. Inspect
only content-free status/counters; never print raw spooled or exported records.

Rollback is application-independent:

```bash
sudo systemctl stop parkio-nr-log-continuous-guard.timer parkio-nr-log-continuous-guard.service
sudo systemctl stop parkio-nr-log-continuous.service parkio-nr-log-source.service
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
- exact-image Trivy scan: Debian 13.6, **0 CRITICAL / 5 HIGH**, as inventoried
  above; continuous Compose render: PASS.

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

Before any permanent activation, an owner must decide:

1. whether to approve or revise the proposed daily/monthly transport budgets;
2. where to publish the exact image and which resulting platform digest to pin;
3. whether the five `OPEN-NO-FIX` linked-library findings meet image policy at
   activation-time scan, or require waiting for a rebuilt upstream base;
4. whether one-minute stop controls are sufficient or a hard filesystem quota is
   required; and
5. when to migrate the classic Fluent Bit `.conf` format to YAML, because Fluent
   Bit marks classic configuration deprecated with end-of-support at the end of
   2026.

Reference: [Fluent Bit configuration formats](https://docs.fluentbit.io/manual/administration/configuring-fluent-bit).
