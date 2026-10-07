# PR #104 production integration decision

**PR:** https://github.com/ADBERILGEN35/parkio/pull/104
**Reviewed head:** `e7a29c73d24feefdd75a2da4cbeeaa778ed1457c`
**Decision:** **HOLD.** Isolated implementation and reported checks are complete.
Production enablement is not authorized. No merge. No image build or publish.
No host file change. No service control. No real snapshot or restore.
No scheduled-backup polling. Source drafts #101/#102/#103 stay open and
unmodified. #104 remains the sole integration PR.

Required checks on this head: Build & unit tests PASS
(https://github.com/ADBERILGEN35/parkio/actions/runs/35920746085);
Secret scan PASS
(https://github.com/ADBERILGEN35/parkio/actions/runs/35920746129).
Dedicated coordination lifecycle PASS on the same SHA.

This document is a production integration decision, not another synthetic
adapter cycle.

## Evidence basis

A scoped live inspect was attempted from this workstation to the previously
used host path (`civo@185.224.137.213:65002`). Authentication failed
(`Permission denied (publickey,password)`). That path is **stopped**. No
other key, Civo token, Azure login, or `BACKUP_AZURE_*` value was retrieved.

Unit, mount, user, and restart facts below are therefore:

- **[REPO]** repository deployment files at `e7a29c73`
- **[OBS]** last authorized read-only host observations on `parkio-civo-prod`
  dated 2026-09-22 through 2026-09-23 (PR #86/#91 closeouts, waitlist
  overlay inventory, backup-restore readiness, FU-1 install note)
- **[THIS SESSION]** SSH denied; no newer live confirmation

A later authorized identity may refresh the [OBS] column. Do not treat
[OBS] as a substitute for a pre-enablement inspect.

## 1. Production preflight vs repository catalog

### 1.1 Last-observed host identities

| Logical writer | Repo catalog | Last-observed production control | Restart / timer | Notes |
|---|---|---|---|---|
| `slack_worker` | systemd `parkio-slack-biz-worker.service`; compose `slack-biz-worker` exists only as an opt-in overlay | **[OBS 2026-09-23]** systemd unit active after PR #86. No slack-biz container. Installer refuses a compose worker on the same host. | `Restart=on-failure` `RestartSec=10s`. Clean `systemctl stop` stays down. | User `parkio-slackbiz`. `ReadWritePaths=/var/lib/parkio/slack-biz`. Inbox is `InaccessiblePaths`. Webhook via root-only secret file. |
| `inbox_consumer` | systemd `parkio-slack-biz-waitlist-consumer.service`; **no** production compose service | **[OBS 2026-09-23]** systemd unit active. | `Restart=on-failure` `RestartSec=10s`. `PrivateNetwork=yes`. | User `parkio-slackbiz`, group `parkio-waitlist-inbox`. Writes inbox + slack-biz state. |
| `gateway_exporter` | pause file only; not a process | **[OBS 2026-09-23]** live gateway `sha256:7458e4fb...` started `2026-09-23T10:28:00Z`, `USER parkio` (uid 10001). Inbox overlay is applied. Pause-file env is **absent** from that image and overlay. | Gateway compose `restart: unless-stopped`. Exporter is `@Scheduled` `fixedDelay` default `PT30S`, Spring default single scheduler thread, `batch-size` 20. | Admission/outbox stay on the request path. Do not flip `ops-notifications.enabled`. |
| `nr_source` | systemd `parkio-nr-log-source.service` | **[OBS 2026-09-23]** enabled, active/running since `2026-09-22T18:18:13Z`. `parkio-nr-logs-source.service` is **not** installed. | `Restart=on-failure` `RestartSec=5s`. | Host Python helper. `ReadWritePaths=/var/lib/parkio-nr-log-continuous/source`. |
| `fluent_bit` + `nr_gate` | compose services `fluent-bit-nr-pilot` and `nr-budget-gate` under documented project `parkio-nr-log-continuous` | **[OBS 2026-09-23]** containers `...-fluent-bit-nr-pilot-1` and `...-nr-budget-gate-1` healthy since `2026-09-22T10:49:16Z`. systemd `parkio-nr-log-continuous.service` enabled, active/exited (`Type=oneshot` `RemainAfterExit=yes`). | Continuous overlay `restart: unless-stopped`. Guard timer every 1 minute. | Production start/stop is the **systemd unit**, not per-service compose. Isolated catalog forbids this project. |
| NR guard | `parkio-nr-log-continuous-guard.timer` + oneshot service | **[OBS 2026-09-23]** timer enabled, active/waiting. Service is static, timer-driven, inactive between ticks. | `OnUnitActiveSec=1min`. `ExecCondition`: continuous unit must be active. On fail it **stops** transport; it does **not** start anything. | No source-specific timer exists. Source health is checked inside the continuous guard. |

### 1.2 Last-observed mounts, users, permissions

| Path | Repo intent | Last observed |
|---|---|---|
| `/var/lib/parkio/waitlist-ops-inbox` | `2770` `parkio-slackbiz:parkio-waitlist-inbox` setgid; bind-mounted into gateway; `create_host_path: false` | **[OBS 2026-09-23]** overlay live (`group_add`, `EXPORT_DIR`, bind). Host `compose.production.files` drifted to list the overlay; canonical repo list still excludes it. |
| `/var/lib/parkio/slack-biz` | `0700` `parkio-slackbiz`; expected `slack_biz.sqlite3` | **[OBS 2026-09-23]** directory located. SQLite file existence **not** established (access restricted). |
| `/var/lib/parkio-nr-log-continuous/{source,collector,budget}` | `0750` root; budget bind → container `/var/lib/parkio-nr-budget` | **[OBS 2026-09-23]** `budget.db` present. Source state JSON for gateway/auth/parking present. |
| `/opt/parkio` | checkout used by Slack units | **[OBS]** HEAD still `bf9cad51` with in-place drift. Do not `git pull` / reset. |
| `/opt/parkio-nr-log-continuous/current` | immutable NR archive, not `/opt/parkio` | **[REPO]** install layout. **[OBS]** units and containers match that project. |
| `/etc/parkio/slack-biz.*.env` | conf 0644; secret 0600 root | **[OBS]** present after relay install. Secret not opened this session. |
| `/etc/parkio-nr-log-continuous/{runtime,secret}.env` | 0600 root | **[REPO]** documented. Secret not opened. |

### 1.3 Catalog vs production: what the isolated adapter cannot do

The isolated adapter (`PARKIO_WRITER_CONTROL_ISOLATED=1`, project prefix
`parkio-writer-control-isolated-`) is executable and must stay refused for
production. It is **not** a production control path:

- Slack production writers are **systemd**, not compose `slack-biz-worker`.
- Inbox consumer has **no** production compose service.
- NR production stop is `systemctl stop parkio-nr-log-continuous.service`
  (ExecStop → `stop_pilot.sh` stops helper + both containers). Per-service
  compose stop against `parkio-nr-log-continuous` is forbidden and unsafe
  while the oneshot unit remains active (`RemainAfterExit=yes`).
- Backup hook `parkio_ordinary_ops_snapshot_after_complete` still prints
  `NOT IMPLEMENTED` and refuses live units. That refusal is preserved.

Repo unit SHA-256 at `e7a29c73` (for a later host compare; host hashes were
not collected this session):

| File | SHA-256 |
|---|---|
| `parkio-slack-biz-worker.service` | `bcadcda822b1f0e6615fd664d9145818890934f8059043531f0be1c77df81022` |
| `parkio-slack-biz-waitlist-consumer.service` | `b5a6139df2fdb2782ae75b99af59390f73c0b7434ee7b72329ef01427ffb1195` |
| `parkio-nr-log-source.service` | `c4b75c1465f669fc8e0805be6ede9ba1ed410b7fb67c6386c8fe5fc6b8b3ec43` |
| `parkio-nr-log-continuous.service` | `0d66325cd6e96e7ddb79c6eeacdcd67cc92c2e5e62fe7b3759568fdccf5aa235` |
| `parkio-nr-log-continuous-guard.service` | `9a8c7bd733839add1a0b066e8e4465d1599718a9774c5e46090bc46f86c1dee7` |
| `parkio-nr-log-continuous-guard.timer` | `e15f9cd1fc90482986dad2e4e4a26c42c14960d6fc808149a61bc719b7589a83` |

## 2. Gateway exporter pause-file integration

### 2.1 Chosen path (no new mount)

Use the **existing** waitlist inbox bind. Do not add a second gateway volume.

| Item | Value |
|---|---|
| Host path | `/var/lib/parkio/waitlist-ops-inbox/.export-paused` |
| Container path | `/var/lib/parkio/waitlist-ops-inbox/.export-paused` |
| Env | `PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_PAUSE_FILE=/var/lib/parkio/waitlist-ops-inbox/.export-paused` |
| Mount | existing overlay `docker-compose.waitlist-ops-inbox.yml` (already live) |
| Why this path | gateway uid 10001 already has inbox group via `group_add`; consumer already ignores non-`*.json`; exporter temp files are `.waitlist-<id>.json.tmp`, so the gate name does not collide |

The live overlay today sets `EXPORT_DIR` only. The pause env is **not**
deployed. The live gateway image does **not** contain
`export-pause-file`. Both are later deploy work, not this cycle.

### 2.2 Ownership and identities

| Actor | Role |
|---|---|
| Coordinator / backup hook (host root; cron is root) | **Creates and removes** the gate. Proposed mode `0644` `root:parkio-waitlist-inbox` (or `0644` `root:root`). Linux `stat` needs search on the parent (`2770` + gateway in inbox group), not read of the file body. |
| Gateway uid 10001 (`parkio`) | **Reads existence only** via `Files.isRegularFile`. Must never create or unlink the gate. |
| Inbox consumer | Ignores `.export-paused` (`*.json` glob only). Must keep running for already-exported envelopes unless the coordinator also pauses it. |
| Slack worker | Does not see the inbox (`InaccessiblePaths`). Unaffected by the file. |
| Isolated adapter | Writes `export_paused\n` and unlinks on resume. Production hook must not call it. |

### 2.3 Behavior review

**Export already in progress.** `exportDue()` checks the file at the start of
the scheduled invocation, then `findDue` + write/markExported for up to 20
rows. Creating the file mid-batch does **not** abort that batch. Default
`fixedDelay PT30S` plus Spring's default single scheduler thread means at
most one in-flight export cycle. A crash between file write and
`markExported` remains the existing at-least-once inbox contract.

**File existing is not drain proof.** `Files.isRegularFile` is an admission
gate for the *next* cycle only. Quiescence before capture requires all of:

1. Coordinator created the file and can still see it.
2. Gateway uid 10001 can see the same path inside the container
   (`test -f` as 10001). If the JVM cannot stat the path,
   `Files.isRegularFile` returns false and export **continues** (fail-open).
3. No inbox temp files matching `.waitlist-*.json.tmp`.
4. Wait at least one completed poll after the file mtime (minimum `PT30S`;
   recommend `2 * poll-interval`).
5. No new `waitlist-*.json` after `T_pause`, and the exported counter is
   flat across that window.
6. Outbox may still gain `PENDING` rows. That is admission working, not
   failed drain.

Do not start plaintext capture until those checks pass. Do not flip
`ops-notifications.enabled`. Confirmation HTTP and durable outbox writes
stay up.

**Stale pause after coordinator failure.** The file persists on the host
bind. Export stays paused; admission and confirmation continue; `PENDING`
grows. Consumer/worker keep running unless also stopped. There is no TTL.
Operator or a later coordinator `resume()` must unlink. Do not auto-unlink
on coordinator process start.

**Gateway restart.** Bind-mount file survives recreate. Next poll sees it
and stays paused. Good.

**Coordinator restart.** Inventory must treat file presence as exporter
already paused. Only an explicit resume unlinks. Isolated `tear_down`
unlinks and must never be pointed at production.

**Cannot write the gate.** Fail closed before any Slack or NR stop.
`require_capabilities` must create-and-stat the file, then leave it in
place only after that check (or create it as the first pause step and abort
the rest if write fails).

**Cannot read the gate (gateway).** Fail-open in current code. Preflight
must prove uid 10001 can stat the path before the first production pause.
If that check fails, do not stop other writers.

**Cannot read the gate (coordinator).** Fail closed; do not capture.

### 2.4 Overlay change still required (not done now)

Add the pause env to the existing activation overlay so a later gateway
deploy does not need a new mount:

```yaml
PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_PAUSE_FILE: /var/lib/parkio/waitlist-ops-inbox/.export-paused
```

Keep it out of canonical `compose.production.files`. Host already appends
the overlay. Do not rebuild or publish the image in this cycle.

## 3. NR stop sequence vs existing guards

### 3.1 What exists

There is **one** transport guard timer: `parkio-nr-log-continuous-guard.timer`
(`OnBootSec=1min`, `OnUnitActiveSec=1min`). There is no separate source
timer. The source helper is `Restart=on-failure`.

`continuous_guard.sh` (every minute, only while the oneshot unit is
active):

- requires helper live and expected
- requires **exactly** `fluent-bit-nr-pilot` and `nr-budget-gate` running
- on any miss: `systemctl --no-block stop parkio-nr-log-continuous.service`
  and `stop_pilot.sh` (helper + both containers)
- does **not** start or restart transport
- documented: timer does not pull an inactive unit back up

`parkio-nr-log-continuous.service` start uses
`docker-compose.newrelic-log-pilot.yml` +
`docker-compose.newrelic-log-continuous.yml` (not the one-hour
`restart: "no"` production-pilot overlay). Continuous overlay sets
`restart: unless-stopped` on both containers.

### 3.2 Compatibility verdict

The isolated per-service compose stop is **not compatible** with production
guards.

| Sequence | Guard / restart effect |
|---|---|
| Compose-stop fluent-bit or gate while oneshot unit stays active | Within ~1 minute the guard sees `unexpected_or_missing_transport_service` and stops the **rest** of the transport, including the helper. Extra mutation during snapshot. |
| Kill helper without `systemctl stop` | `Restart=on-failure` brings it back in 5s during the snapshot. |
| Crash a container while unit still active | `unless-stopped` restarts it during the snapshot; guard may then pass or flap. |
| `systemctl stop parkio-nr-log-continuous.service` | ExecStop runs `stop_pilot.sh`: clean helper stop + docker stop of both allowlisted services. Guard `ExecCondition` fails (unit inactive). Timer does not start them. **This is the compatible stop.** |
| Leave the timer running after the unit is inactive | Safe: condition fails, no start. Still stop the timer first so a race cannot fire mid-stop. |
| Docker daemon restart during a window where containers were only `compose stop`ped and the unit is still "active" | `unless-stopped` can bring containers back. Another reason not to compose-stop under an active oneshot unit. |

Slack units: `Restart=on-failure` is compatible **only** with `systemctl
stop`. Do not kill the Python processes.

### 3.3 Proposed production NR latch (inspect-only; not applied)

Do not change unit files, timers, or add a new stop latch on the host.

Adapter behavior still to implement, later, on #104 only:

1. `systemctl stop parkio-nr-log-continuous-guard.timer`
2. `systemctl stop parkio-nr-log-continuous.service`
   (ExecStop already stops helper + both containers)
3. Confirm helper inactive, both NR containers absent, timer inactive
4. Capture
5. Resume only the pre-existing set: start source if it was running,
   start continuous, start timer
6. Never `docker compose -p parkio-nr-log-continuous stop <service>`

Ordinary failure still resumes only the pre-state. DR still leaves Fluent
Bit and Slack down.

## 4. Smallest deployable scope

Do not build or publish images now. Scope below is the later production
enablement set, in order.

### 4.1 What must be deployed (when a human later authorizes)

1. **Gateway image** built from a #104-descended SHA that contains
   `export-pause-file` / `exportLoopIsPaused()`. Pin and recreate
   **only** `gateway-service`.
2. **Overlay env** on the existing inbox overlay (no new mount):
   `PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_PAUSE_FILE`.
3. **Operational scripts** copied in-place onto `/opt/parkio` (host git
   remains `bf9cad51`; no `git pull`). Coordinator, catalog, state backup,
   recovery-coordination hook. Flags stay unset.
4. **Production writer-control adapter** (not yet written): systemd stop/start
   of the four host units above, pause-file create/unlink, NR timer latch,
   refuse compose projects `parkio`, `parkio-nr-log-continuous`,
   `parkio-invite`. Keep the isolated adapter for CI.
5. **NR guard:** no new unit. Use the existing timer stop/start. Do not
   edit installed NR unit files.
6. **Permissions:** coordinator (root) can write
   `/var/lib/parkio/waitlist-ops-inbox/.export-paused`; uid 10001 can stat
   it; consumer still only reads `*.json`.
7. **Remote upload:** not in the first host enablement. Local sealed
   plaintext-then-encrypt remains the first capture shape.

Out of scope for first enablement: web, Alertmanager, Slack webhook,
NR license, invite-production, FU-1 polling, Azure provision.

### 4.2 Order

| Step | Action | Gate before next |
|---|---|---|
| 0 | Keep all four flags unset. Production hook refusal stays. | -- |
| 1 | Implement production systemd adapter + quiescence checks on #104 (next implementation, not this cycle). Isolated tests remain. | Dedicated + required CI green |
| 2 | Repository merge of #104 only, if separately authorized | Distinct from production enablement |
| 3 | Build/publish gateway image (not now) | Digest pin review |
| 4 | Copy scripts in-place; add overlay env; recreate only gateway | Gateway healthy; inbox mount present; `test -f` pause path as uid 10001 **without** leaving a pause file; Slack units untouched; NR `check-live-helper` still PASS; StartedAt of web/auth/parking/AM unchanged |
| 5 | Dry-run adapter inventory only (no pause) | Catalog matches live unit names |
| 6 | Flag enable + one measured ordinary snapshot, only after a fresh authorized host inspect | Quiescence checks; encrypt after resume; user-facing HTTP up; pause file removed; writers back to pre-state |

### 4.3 Rollback

1. Unset the four flags.
2. Unlink `.export-paused` if present.
3. `systemctl start` the Slack units and, if they were running,
   `parkio-nr-log-source.service`, `parkio-nr-log-continuous.service`,
   `parkio-nr-log-continuous-guard.timer`.
4. Retarget the previous gateway digest (`7458e4fb...` as of 2026-09-23)
   and recreate only gateway-service.
5. Leave overlay pause env or revert it; empty env is inert on the old
   image.
6. Do not restart NR to "fix" a gateway replace; re-run
   `resolve_production_sources.sh --check-live-helper`.

### 4.4 Health / acceptance gates (later)

- Gateway `/actuator/health` UP; waitlist confirm still writes outbox
- `parkio.waitlist.ops.notifications_total{outcome=exported}` flat while
  paused; `recorded` still increments on confirm
- Inbox has no `.export-paused` after resume
- Slack consumer/worker active if they were
- NR helper attached to the new gateway id; collector/gate healthy
- Sealed archive verify; plaintext discarded
- Event-level Slack report; `auto_replay=[]`
- Production pause wall-clock recorded (900s budget / 1200s ceiling)

## 5. Remote-storage proposal

No provisioning, secret retrieval, `az login`, or remote write. Independent
Azure listing remains `AZ_LOGIN_SESSION_ABSENT` from the FU-1 note. FU-1
scheduled-backup acceptance stays **AWAITING**; not polled.

### 5.1 Verified facts

| Fact | Source |
|---|---|
| Offsite model is Azure Blob in `rg-parkio-backups`, region **westeurope**, VM in **francecentral** | backup-runbook, AZURE-BACKUP-AND-EXIT-PLAN |
| Documented account name `stparkiobakwesteu` | `.env.azure-hosted-beta.example`, invite-production-foundation (as the hosted-beta account, unused by invite) |
| Documented DB/MinIO container `parkio-backups` | example env |
| TLS 1.2+, SSE, versioning, 14-day lifecycle | same docs |
| Preferred auth: container SAS Read+Create+Write+List, **no Delete** | example env comment |
| At least one successful Civo upload was recorded | `docs/operations/gmp-release-pins.md` (manifest `uploaded` flag can lie; listing + COMPLETE is the real check) |
| Invite foundation uses a **different** account/container (`stparkioinvjcvgwc` / `invite-production-backups`) | invite-production-foundation |
| Existing `BACKUP_AZURE_CONTAINER` is for DB/MinIO stamps | backup-runbook |
| Immutability / object-lock | **UNKNOWN** |
| Live account/container/SAS on the host | **UNKNOWN**; not retrieved |

### 5.2 Proposal (not provisioned)

Reuse the known hosted-beta backup account as the failure domain. Do **not**
mix operational archives into `parkio-backups` COMPLETE stamps.

| Item | Proposal |
|---|---|
| Account | `stparkiobakwesteu` in `rg-parkio-backups` |
| New container | `parkio-ops-erasure` (dedicated; not a prefix inside `parkio-backups`) |
| Prefixes | `ops-state/<stamp>/` and `erasure-seals/` |
| Access | dedicated container SAS or a separate identity; `rcwl` only; no Delete; do not reuse the DB-backup SAS |
| Authenticity | SHA-256 + COMPLETE is not a signature. Keep checksums. Add a later signed seal or WORM version; until then mark authenticity **unowned** |
| Delete protection | account versioning already documented. Object-lock / MFA-delete **not verified**. No-delete SAS is the minimum. |
| Retention | 14-day lifecycle is wrong for erasure seals. Propose: no lifecycle on this container until legal/privacy sets a floor; if a number is required, 90 days for `erasure-seals/` and 14 days for `ops-state/` |
| Operator setup | create container; issue dedicated SAS or RBAC; do not put the token in git; do not enable flags until the container exists |
| First capture | local encrypt-after-resume only; remote upload stays unimplemented |

Invite storage is out of scope. Directory FileStore stays not off-host.

## 6. Decision table

### Ready now

- Isolated writer-control + coordinator at `e7a29c73`
- Required and dedicated CI on that head
- Exporter-only pause semantics in gateway tests (admission preserved)
- Production hook refusal
- Repo identity catalog vs last-observed host map
- Chosen pause path on the existing inbox bind
- NR stop compatibility verdict (systemd unit + timer first; not compose)
- Written remote-storage proposal
- Merge strategy: only #104, later, separately from enablement
- Source drafts #101 `2f04d016`, #102 `15b697fb`, #103 `cdc0d13c` still draft

### Implementation still needed (on #104 only; not this cycle)

- Production systemd writer-control adapter + NR timer latch
- Quiescence confirmation (tmp gone + poll wait + exported counter flat)
- Overlay env for the pause path
- Fail-closed coordinator write/stat; preflight that uid 10001 can see the file
- Gateway image containing `export-pause-file` (build later)
- Remote upload
- Host unit-file hash compare after an authorized inspect

### Operator input required

Ask only these. Pause path, NR stop shape, and "new container not prefix"
are already decided above.

1. **Host inspect identity.** This workstation cannot SSH. Authorize a later
   read-only session from an allowed key before any enablement.
2. **Azure container.** Confirm `parkio-ops-erasure` on `stparkiobakwesteu`,
   or name a different existing unused container. Do not mix into
   `parkio-backups`.
3. **Delete protection.** Versioning + no-delete SAS only, or enable
   object-lock/WORM on that container.
4. **Erasure-seal retention.** Accept "no lifecycle until legal sets a
   floor", or give a number.
5. **Authorize next implementation** of the production systemd adapter on
   existing #104 (still HOLD, still no deploy).

### Keep separate

| Track | Status |
|---|---|
| Repository merge readiness | Checks green; merge **not** authorized |
| Production enablement | **HOLD** |
| FU-1 scheduled-backup acceptance | **AWAITING**; do not poll |
| Source drafts | preserved |

## 7. Rollback of this decision package

Docs only. Flags remain unset. No service restart. Revert the doc commit
on #104 if needed. #101/#102/#103 untouched.
