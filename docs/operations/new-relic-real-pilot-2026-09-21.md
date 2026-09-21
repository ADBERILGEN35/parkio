# Bounded New Relic real-log pilot — 2026-09-21

Execution evidence for PR #66. **Status: bounded run completed; collection OFF,
teardown PASS.** Parking searchable delivery is user-observed PASS for 42
matching records at an intermediate observation. Gateway/auth were quiet;
their real-log searchability and the final parking UI total are not proven.
No extension, restart after scheduled shutdown or permanent activation is authorized.

## Authorization and identities

The operator authorized gateway-service, auth-service and parking-service only,
for at most one hour from collection start and 26,214,400 uncompressed serialized
outbound JSON body bytes, including every retry. This is a transport-byte ceiling,
not a vendor-enforced spending or billable-ingest cap. The completed synthetic
end-to-end acceptance was reused without resending its marker or repeating its
unchanged tests.

- Activated source: `a156ecf8c165f5a83bb4a1035e01ac636fab67e2`.
- Runtime files unchanged from the 31/31-tested implementation
  `6ed1dc2808d5ec57e323d1fb182eb1ea584f2429`.
- Fluent Bit 5.0.10 index:
  `sha256:ea0734ecb445c9805ec1fcbfb3430c8607d502fe7ce773b27e362551c02e3fd9`.
- Fluent Bit linux/amd64 child:
  `sha256:3fa4a4919f1814f23d27a0108de041e93f6f21d9dc7bbe912d8c44ecd8e7cf20`.
- Gate Python image index:
  `sha256:c4634f578a412db396771b61b064c6e546c9d6414c7fb5b1b05d5871f1885f7b`.
- Gate linux/amd64 child:
  `sha256:9b8dad7f66b5c7751df6cb7a64a07812e86bed85d0116efe82b3a11209f1440d`.
- EU destination: `https://log-api.eu.newrelic.com/log/v1`.

Fresh identities were resolved repeatedly before attachment, then compared
about every 65 seconds during monitoring. The Docker logging interface was used;
the collector had neither Docker socket access nor private json-file mounts.

| Service | Container ID | Application image ID |
| --- | --- | --- |
| gateway-service | `473829c00ba97e4e6f52bb72dac73c53865655063fae0c79bac4ef3f2852f5cc` | `sha256:8b8a08ba974aeec91ec690ada406552a474c2319880752d4d4ea5ff1798028aa` |
| auth-service | `aa28bb4e98907a29471540e93c270bd67f855d6e5124006d4e7424c3ed5865f4` | `sha256:575eef1f34f7aed39e3793cd8a8eaae546049f75c9e401d360db7798c7e294fd` |
| parking-service | `54266cd6a68eff8439c2e433f5982a7d337aa338e25c4fae1f061d9451ebed4c` | `sha256:e353baed3f464849208ef8852314ad8c469663d20ad8c2f755ac236bd1452dfe` |

All three started healthy, with json-file rotation `10m × 5`. The production
checkout was dirty and was not used as a deployed release identity. The emitted
`release_id` is instead
`container-set-66ff0d56bcb3a368b09a4ee8f74ca9b716b901a7b6ef24cbdcda9b5300455634`.
The source-set hash includes the approved container identities and spool paths.

## Required checks and security policy

Live branch protection required `Build & unit tests` and `Secret scan`; both
passed before activation. Current-head CodeQL, security summary, all application
image scans, configuration checks, integration tests, k6 and Full Compose
runtime subsequently reached PASS. The non-required recovery drill failed while
building shared application images because Maven Central returned HTTP 429 for
Kotlin dependencies. This is an infrastructure/dependency-fetch failure, not a
collector acceptance failure, and was not relabeled PASS:
[failed job](https://github.com/ADBERILGEN35/parkio/actions/runs/35647196945/job/106490885068).

A fresh exact-collector-image Trivy 0.64.1 scan before activation retained six
HIGH and zero CRITICAL findings. Existing repository image policy reports HIGH
and blocks fixable CRITICAL (`--severity CRITICAL --ignore-unfixed`); no blocking
image gate was waived. The image is not clean:

| Finding | Package | Policy classification |
| --- | --- | --- |
| CVE-2026-12064 | libcurl4t64 | OPEN-NO-FIX |
| CVE-2026-8286 | libcurl4t64 | OPEN-NO-FIX |
| CVE-2026-8458 | libcurl4t64 | OPEN-NO-FIX |
| CVE-2026-8927 | libcurl4t64 | OPEN-NO-FIX |
| CVE-2026-16742 | libsystemd0 | OPEN-NO-FIX |
| CVE-2026-58050 | libssh2-1t64 | OPEN-FIX-AVAILABLE; Debian fix 1.11.1-1+deb13u2 |

Configuration reachability assessments remain in [the runbook](new-relic-log-pilot.md#collector-lifecycle-and-security).

## Timeline and operational issues

1. At 20:03:48Z, the first launch failed because `PrivateTmp=yes` hid the helper
   script under `/var/tmp` from the systemd service. No attachment, collector or
   external transmission occurred. Cleanup passed.
2. `/run` was verified `noexec`. The identical helper/stop sources were then
   invoked through `/usr/bin/python3` and `/bin/bash`; mount security was not
   changed.
3. At 20:08:57Z, all three helper attachments passed. The container gate could
   not read its `/run` bind-mounted Python file (EACCES); collector startup was
   prevented. No source records or outbound bytes were observed. Cleanup passed.
4. Host helper sources remained in `/run`; container configuration/script binds
   used the byte-verified `/var/tmp` package that had worked in the synthetic
   procedure. At 20:13:49Z, the collector and gate started. The operator's script
   streamed over stdin ended prematurely; cleanup began at 20:14:05Z. Its
   recorded `deadline` reason was inaccurate: this was an operator-script
   interruption, not expiration of the one-hour window. Only a 780-byte startup
   marker had been charged; no real source record was present.
5. At 20:19:43Z, a root-only operator script file resumed monitoring. The same
   real ledger retained its 780-byte charge, with the same collector checkpoint
   and source state. A distinct restart marker was charged to the same budget.
   The original accepted synthetic marker was not reused.
6. The overall duration was conservatively anchored to the first successful
   helper attachment at 20:08:57Z. Independent systemd timers were armed for
   21:08:00Z (shutdown grace) and 21:08:57Z (hard window end), without extending
   the authorized hour. Earlier longer timers are superseded by these limits.
7. The grace-stop service ran at 21:08:01Z. The helper stopped and Fluent Bit
   exited 0 at 21:08:01.717687548Z. The gate stopped at
   21:08:31.370430890Z with exit 137 after Docker's 30-second stop grace;
   `OOMKilled=false`. This is a forced-stop operational limitation, not an OOM
   or a successful graceful gate shutdown. SQLite integrity subsequently passed.
8. Both containers were stopped before the 21:08:57Z hard deadline. From the
   first helper attachment to collector stop was 59m04.7s; to both containers
   stopped was 59m34.4s. The final uninterrupted run began at 20:19:43Z,
   approximately 48m18s before collector stop. Startup interruptions mean this
   was not an uninterrupted hour of coverage.
9. At 21:09:00Z the monitor observed the timer-stopped helper and removed the
   stopped containers and network. Its `helper_inactive`/exit-1 result is the
   scheduled shutdown being detected, not an unexpected helper fault. Its
   printed duration of 3311 seconds uses the obsolete 20:13:49 anchor and its
   stop timestamp is cleanup initiation, not the actual collection end. Docker
   `FinishedAt` evidence above is authoritative for deadline compliance.
10. At 21:09:58Z teardown verification passed: zero project containers/networks,
    zero helper processes, and all helper/stop timer/service units inactive or
    absent. All three application IDs and StartedAt values were unchanged,
    running/healthy, with restart counts zero. No production activity was
    generated to populate the pilot.

The first unsuccessful source state is preserved separately at
`/var/lib/parkio-nr-real-20260921T195730Z-dd0faacd`; it contains no outbound ledger.
The real pilot's persistent state is
`/var/lib/parkio-nr-real-20260921T201113Z-9cef1cfd`, with `budget/budget.db`,
collector checkpoint, helper cursors/spool and protected `evidence/monitor.log`.
Neither was mixed with the synthetic proof's ledger. No initialized ledger was
reset. An empty fresh source begins following at its activation timestamp;
services without a cursor resume at current time, so the interruption can lose
records. Cursor replay can duplicate a boundary record after reconnect.

## Monitoring and measurement limits

The watchdog checked exact identities, helper attachment, approved source
directories, the two-service project, container running/OOM status, persistent
gate accounting and host resources. Early-stop thresholds were 5 GiB free disk,
100,000 free inodes or 512 MiB host available memory. Independent limits remained
128 MiB / 0.25 CPU for the collector and 64 MiB / 0.25 CPU for the gate and helper.

Collector counters were read without log bodies to verify output processing,
retries, errors, filter drops and skipped lines. Source metadata counts stayed
on the host. Known-sensitive-pattern scans printed only per-service counts,
never log text. Zero pattern matches does not certify arbitrary free text as
safe; the existing regex limitations remain. New Relic search results require
operator UI evidence because authenticated NRQL access is unavailable here.

The operator subsequently reported a matching EU Logs query with parking-service
42 records, gateway-service no row, and auth-service no row. This confirms
searchable real-log delivery for parking for that observation, not the final
window total. At the follow-up host check, parking had 46 source lines; gateway
and auth each had `attached` status, a zero-byte spool and no cursor (no line
received). An independent bounded `docker logs --tail 1000` count covering
20:19:43Z–20:44:46Z returned zero lines for each quiet service. Collector metrics
had three opened files and zero filter drops, long-line skips, output errors or
retries. The no-row results therefore agree with quiet sources in that interval.

At 20:43Z the helper reported zero restarts, disconnects, replacements and drops.
Its cgroup memory peak reached the configured 64 MiB and `memory.events.max` was
7, with zero OOM and zero OOM kills. This is recorded as memory-limit pressure
within the helper cgroup; it was not host memory exhaustion or a proven loss.

## Final counters and protected evidence

| Measurement | Final observation |
| --- | --- |
| Persistent transport ceiling | 26,214,400 uncompressed serialized JSON body bytes |
| Reserved/spent bytes | 93,955 (0.3584%); 26,120,445 remaining |
| Compressed body bytes attempted | 47,740; excludes HTTP/TLS overhead |
| Admitted outbound attempts | 68 |
| Gate retry / rejected attempts | 0 / 0 |
| Records admitted / rejected | 90 / 0; includes two distinct real-run startup markers |
| Real source records | parking 88; gateway 0; auth 0 |
| Source spool | 41,263 bytes; no rotation during this low-volume observation |
| Ledger integrity / exhaustion | `PRAGMA integrity_check=ok` / false |
| Final helper drops / disconnects / replacements | 0 / 0 / 0; three attachments in the uninterrupted run |

The two startup markers cost 1,564 bytes, leaving 92,391 serialized body bytes
for the observed real-log batches. These are measured samples, not a production
demand forecast or a New Relic billable-ingest measurement. The gate reserves
and increments `forwarded_*` before network I/O, so those counters alone do not
prove an upstream response or searchability. Its retry counter detects consecutive
same-body digests, not all possible interleaved retries; every admitted body is
charged regardless. Collector retry metrics were separately inspected.

The final pre-stop collector snapshot at 21:07:19Z recorded tail 87 records,
one startup dummy, three opened files, and zero output errors/retries/dropped
records, Lua drops, long-line skips/truncation or multiline truncation. One
additional parking line arrived at 21:07:46Z; the final gate count reconciles
88 real records plus two startup markers. The snapshot is not mislabeled as
a post-shutdown metric scrape. The final source helper journal reported no drops.

Final bounded content-free Docker logs counts for 20:08:57Z–21:08:02Z returned
zero lines for both gateway and auth (`--tail 1000` ceiling). Together with their
attached state, empty spools and zero filter drops, this supports quiet sources,
not a detected collection/filter failure. Parking's source timestamps span
20:20:01.972962383Z–21:07:46.674094814Z. Known email, authorization/cookie, JWT,
credential, private-key, token-URL and prohibited-canary pattern counts were
zero across all 88 source lines. The scan ran on-host and exported only counts;
it is neither a generic PII proof nor a retained outbound-payload audit.

Across 45 monitor samples, collector memory was 5.105–5.656 MiB and gate memory
15.82–48.66 MiB. Helper peak was 64 MiB, with `memory.events.max=8` and zero
OOM/OOM-kill events at the final pre-stop snapshot; this cgroup pressure remains
an explicit limitation. Minimum host free disk was 95,169,884,160 bytes,
available inodes 17,697,801 and MemAvailable 7,087,228 KiB. Sampled apparent
state peaked at 484,554 bytes. After shutdown, before the final metadata report
was added, apparent state was 90,785 bytes and allocated filesystem blocks were
176,128 bytes. These observations are not a hard filesystem quota.

Protected evidence remains under the real state directory: `monitor.log`,
`collector-prestop-metrics.json`, `container-stop.json`, `final-runtime.json`
and `final-quiet-and-resources.json` in `evidence/`. The state/evidence parents
are root-only 0700; the persistent budget DB is 0600 and was not reset. Source
spools/checkpoints and staged configuration remain inert for evidence retention.
The secret file is still 0600 root:root; its contents were not displayed.

Only this report and the owned log-pilot runbook changed for completion.
No collector/runtime source changed, so the existing 31/31 exact-implementation
acceptance was not rerun. No shared Compose, pins, application/admin/marketing,
root lockfile, shared CI, Cursor worktree or PR #61/#62 change was made. The PR
remains draft and unmerged. The six HIGH findings remain open under existing
policy. Any later real-log run requires a new bounded activation decision.

## Real-log search

Run this in the EU account after the window. It excludes both startup markers
and the previously completed synthetic proof:

```sql
FROM Log
SELECT count(*), earliest(timestamp), latest(timestamp)
WHERE pipeline = 'nr-log-pilot'
  AND environment = 'production'
  AND collector_source_sha = 'a156ecf8c165f5a83bb4a1035e01ac636fab67e2'
  AND release_id = 'container-set-66ff0d56bcb3a368b09a4ee8f74ca9b716b901a7b6ef24cbdcda9b5300455634'
  AND source_kind = 'docker-logs-api'
SINCE '2026-09-21 20:08:57 +0000'
UNTIL '2026-09-21 21:08:57 +0000'
FACET service LIMIT MAX
```

For severity/redaction coverage, keep those exact filters and window and change
the selection to `count(*)` and the facet to `service, severity, redaction`.
Only the three approved service names are expected. A service with no records
but a healthy attachment is quiet; visibility for it is unproven, not a proven
collection failure. Never create user actions or errors to populate this view.
