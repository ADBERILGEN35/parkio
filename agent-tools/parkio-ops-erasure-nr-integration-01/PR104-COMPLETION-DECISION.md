# PR #104 completion decision (draft -- production HOLD)

**PR:** https://github.com/ADBERILGEN35/parkio/pull/104
**Implementation ancestors:** `e7a29c73` (isolated adapter), `740d1fb4` (prior decision docs)
**This package:** see git head after this commit
**Decision:** **HOLD.** Draft only. No merge. Production flags unset.
Source drafts #101/#102/#103 stay open.

## Phase A -- access, FU-1, Azure

### SSH

Codex timeout and the earlier Cursor `Permission denied` on
`civo@185.224.137.213:65002` are different stages. This workstation has
**no** `~/.ssh/config`. Documented identity from prior authorized work is
`civo@api.parkio.dev` (host `parkio-civo-prod`), default ed25519
`SHA256:NjmWBfnrQhZD+KRDyQSmfOv65tVY59O3YMMXKfTJ5y4`.

One bounded retry with that identity, `BatchMode`, `IdentitiesOnly`,
`StrictHostKeyChecking=yes`, 15s connect: **succeeded**. Remote
`hostname` = `parkio-civo-prod`. FU-1 read-only acceptance ran as `civo`.
No SSH config was changed. No alternate keys were searched.

Public HTTP (does not prove SSH or backup): `https://parkio.dev/` 200,
`https://app.parkio.dev/` 200, `https://api.parkio.dev/actuator/health` 200.

### FU-1 scheduled run (read-only)

Install cutoff `2026-09-23T19:01:12Z`. First cron `2026-09-24T03:30:00Z`.
Observed `2026-09-24T06:55:03Z`. **NOT RUNNING.** Lock free (mtime is the
install flock).

| Gate | Result |
|---|---|
| Post-install stamp | `2026-09-24T03-30-01Z` **COMPLETE** |
| Pre-install `2026-09-23T03-30-01Z` | context only; **not** acceptance |
| 10 DB dumps | present `*.sql.gz.enc`; log all 10 **OK**; `databasesFailed=0` |
| MinIO | `minio.tar.gz.enc` + `minio-encryption.json` present; 2 objects |
| Erasure ledger | file present; JSON **list length 0** (structure only; no entries printed) |
| SHA256SUMS | 23 OK; **2 FAILED open or read** as user `civo`: `./minio-encryption.json`, `./minio.tar.gz.enc` (exist; not readable to this identity) |
| Telemetry | textfile mtime 03:30:25Z; `last_success=1`, `databases_failed=0`, `offsite_last_success=1`, `production_mode=1`, `last_timestamp_seconds=1790220625` |
| Manifest `offsite.uploaded` | **false** (known stamp-status defect) |
| Uploader log | claimed `azure://parkio-production-backups/2026-09-24T03-30-01Z` auth=SAS |
| Independent remote | **not proven** (see Azure) |
| Web / Alertmanager | StartedAt unchanged; not restarted |

Do not treat uploader progress bars or `offsite_last_success=1` as remote
proof. Do not use the pre-install stamp.

### Azure (existing `az` login only)

Same subscription `2b3abb5c-8a52-4c03-91c7-f53fd440a7e9`, tenant
`4e7622aa-0148-4627-bda5-96e98b768136`, account **`stparkiobakwesteu`**,
RG **`rg-parkio-backups`**.

| Plane | Observation |
|---|---|
| Control plane | `provisioningState=Succeeded`, `statusOfPrimary=available`, westeurope, StorageV2, Standard_LRS, HTTPS only |
| Resource Health | `Unknown` / Not Monitored (Azure Monitor issue), reported `2026-09-24T06:54:14Z` |
| Activity log 14d | advisor recommendations only; **no disable operation found** |
| Blob `parkio-backups` | `ErrorCode=AccountIsDisabled` request `26196423-601e-0081-45f1-4ba395000000` time `2026-09-24T06:53:59Z` |
| Blob `parkio-production-backups` | same account, same `AccountIsDisabled` request `661809e0-001e-0087-6bf2-4b902a000000` time `2026-09-24T06:59:42Z` |

**Observed error vs proven cause:** data-plane `AccountIsDisabled` is
verified on this account. Control-plane success is **not** Blob
availability. Why it is disabled is **not proven** (no matching activity
event). `BACKUP_AZURE_*` was not retrieved. No container was created.

Remote storage for #104 stays **provisional**. Proposed later container
`parkio-ops-erasure` is not provisioned.

## Phase B -- #104 implementation (live host refused)

- Production-shaped systemd adapter (`systemd_control.py`) using documented
  unit names. Requires isolated flag + disposable `fake_systemctl`. Refuses
  host `systemctl`, `PARKIO_ENVIRONMENT=production`, and hostname
  `parkio-civo-prod`.
- Records pre-state including the guard timer. Stops
  `parkio-nr-log-continuous-guard.timer` before
  `parkio-nr-log-continuous.service`. Slack uses `systemctl stop` only.
- Resume only previously active units. Partial-pause failure resumes
  pre-state. DR `start(nr_gate)` without Fluent Bit is an explicit
  **deployment gate** (production oneshot cannot start the gate alone).
- Correlated exporter handshake: `request` / `ack` with `requestId`.
  Ack only after in-flight export finishes. Stale ack and JVM instance
  id do not certify a new request. Missing/unreadable/timeout abort
  capture. Flat counters and temp-file absence are not drain.
- Overlay + install-relay create
  `/var/lib/parkio/waitlist-ops-inbox/.export-pause` (2770) on the
  existing inbox bind. Consumer still reads `*.json` only.
- Recovery safeguards unchanged: no auto Slack replay; event-level
  reconciliation; Fluent Bit stays down in DR; exhausted gate may ack
  without forward; existing NR ledger guard; cutoff not lowered;
  FileStore is not off-host.

## Remaining production gates

1. Azure data-plane disable: operator restores Blob access or explains
   the disable. Then confirm container/WORM/retention.
2. Gateway image + overlay env deploy (not now).
3. Authorized production adapter enablement (flags still off).
4. FU-1 MinIO SHA files unreadable as `civo`; offsite presence unproven.
5. DR gate-only start path does not exist on the live oneshot unit.

## Smallest next authorized action

Decide whether to restore or replace `stparkiobakwesteu` Blob access.
Do not enable #104 flags. Do not merge.

## Rollback

Unset flags. Revert this PR if it were merged. No service restart.
#101/#102/#103 stay draft.
