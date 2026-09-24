# Coordinated operational-state, erasure, and NR recovery (draft)

**Status:** source integration only. **HOLD. Production disabled.**
Default flags stay off: `PARKIO_RECOVERY_COORDINATOR_ENABLED`,
`PARKIO_OPS_STATE_BACKUP_ENABLED`, `PARKIO_OFFHOST_ERASURE_ENABLED`,
`PARKIO_NR_BUDGET_RECOVERY_MODE`.

Included identities (unchanged preparation PRs):

| PR | Head | Role |
|---|---|---|
| #101 | `2f04d01636ab23f2e0881bb2f8018fe3eb0ed815` | Slack/NR operational snapshot helper |
| #102 | `15b697fb047f53c7acb88bbcc9fea3a9befd6942` | Off-host erasure recover refusal |
| #103 | `cdc0d13c76c9c3a249c9e165d08f9c4cdde20983` | Existing-ledger NR budget guard |

This document is **standalone-tool + coordination-layer** acceptance, not
off-host durability, production enablement, or real restore acceptance.

## 1. Ordinary backup vs disaster recovery

SQLite, inbox files, Fluent Bit tail state, and the gateway PostgreSQL
outbox are **separate consistency domains**. Pairing uses
`gateway_outbox_backup_id` **and event IDs**. Aggregate counts are not
enough. This is not an atomic multi-store instant.

**Ordinary backup** (optional, after a COMPLETE stamp):

1. Fail before any pause if the isolated allowlisted adapter is missing
   docker compose or the exporter pause-file path.
2. Record which writers are already running.
3. Pause only those writers (`slack_worker`, `fluent_bit`,
   `gateway_exporter`, `inbox_consumer`, `nr_source`, `nr_gate`).
   Configured budget **900s**. Hard ceiling **1200s**.
4. Capture consistent **plaintext** files while paused. Resume the
   pre-existing running set. Encrypt after resume. Wipe plaintext on
   failure. Remote upload is not implemented.
5. Ops-snapshot failure does not retract the database COMPLETE stamp.

User-facing HTTP is not paused. `gateway_exporter` is a pause file, not
a gateway process stop. Production hook still refuses live host units.

**Later gateway deploy requirement:** set
`PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_PAUSE_FILE=/var/lib/parkio/waitlist-ops-inbox/.export-pause`
on the existing inbox bind (directory, 2770
`parkio-slackbiz:parkio-waitlist-inbox`). Coordinator writes `request`
with a `requestId`. The gateway writes `ack` with the same `requestId`
and a per-JVM `exporterInstanceId` only after in-flight export work
finishes and new `findDue` work is prevented. Missing ack, unreadable
control state, or timeout aborts capture. A flat counter, missing temp
files, or one poll interval is not drain proof. Do not flip
`ops-notifications.enabled`. Do not unlink leftover request/ack on
coordinator startup. Consumer glob remains `*.json`.

Production NR stop is `systemctl stop` of the guard timer, then
`parkio-nr-log-continuous.service` (ExecStop stops helper + both
containers). Per-service compose stop while the oneshot unit stays
active is incompatible with the one-minute guard. Isolated compose
control remains refused for production. See
`agent-tools/parkio-ops-erasure-nr-integration-01/PR104-PRODUCTION-INTEGRATION-DECISION.md`.

Isolated adapter only: `PARKIO_WRITER_CONTROL_ISOLATED=1` and compose
project prefix `parkio-writer-control-isolated-`. Documented production
projects (`parkio-nr-log-continuous`, `parkio`) are refused.

**Disaster recovery:**

1. Fluent Bit and Slack publishers remain stopped.
2. Stage and verify operational state. Reconcile **event IDs**. Never
   auto-replay `delivery_unknown` or `in_flight`.
3. Run #102 recover against the **requested** cutoff. Directory FileStore
   is **not** off-host storage. Do not lower the cutoff to obtain PASS.
   Unknown erasures after the last verified watermark **block exposing**
   recovered applications (exit 3 / `exposeApplications=false`).
4. Only then start the NR budget gate with
   `PARKIO_NR_BUDGET_RECOVERY_MODE=on` and a verified exhausted ledger.
5. An exhausted gate may acknowledge logs without forwarding. That is
   **not** lossless buffering. Do not start Fluent Bit.
6. Explicit spending reconciliation (NR and Slack event review) plus a
   documented release step are required before collection/forwarding.

## 2. Remote-storage configuration plan (no provisioning)

Reuse the documented Azure backup account pattern
(`rg-parkio-backups`, westeurope, TLS 1.2+, SSE, versioning, 14-day
lifecycle) **only as a model**. Do **not** assume a suitable container
or permissions exist for operational-state archives or erasure seals.

| Property | Database offsite today | Ops/erasure journals |
|---|---|---|
| Authenticity | SHA-256 + COMPLETE; not a signature | Same limit unless a later signed/WORM decision |
| Retention / delete protection | Documented 14-day lifecycle; immutability UNKNOWN | Need a **dedicated** prefix, versioning, MFA-delete or object lock |
| Freshness | Nightly stamp | Independent of nightly COMPLETE; watermark age is a separate SLA |

Written proposal (not provisioned; `BACKUP_AZURE_*` not retrieved).
2026-09-24 control-plane for `stparkiobakwesteu` is Succeeded/available;
Blob list of `parkio-backups` with `--auth-mode login` returned
`AccountIsDisabled` (request `26196423-601e-0081-45f1-4ba395000000`).
That does **not** verify historical lifecycle or object-lock. Do not
create containers or issue credentials until the data plane is usable.

- Intended ops container remains `parkio-ops-erasure` on that account,
  prefixes `ops-state/<stamp>/` and `erasure-seals/`, only after Blob
  access is restored.
- Dedicated `rcwl` SAS or identity; no Delete; do not reuse the DB SAS.
- Operator still chooses delete-protection mode and erasure retention.

Operator confirmations: why the account data plane is disabled; then
container name, delete-protection, retention. Do not retrieve
`BACKUP_AZURE_*` here.

## 3. Flags (all default off)

```
unset PARKIO_RECOVERY_COORDINATOR_ENABLED
unset PARKIO_OPS_STATE_BACKUP_ENABLED
unset PARKIO_OFFHOST_ERASURE_ENABLED
unset PARKIO_OFFHOST_STORE_DIR
unset PARKIO_NR_BUDGET_RECOVERY_MODE
```

`backup-hosted-beta.sh` calls the ordinary hook only when both coordinator
and ops-state flags are `1`. The hook labels production orchestration
**NOT IMPLEMENTED** and refuses live writer control.

## 4. Merge disposition

#104 contains #101/#102/#103 by **merge ancestry**. Chosen later strategy:
merge only #104; do not merge the source drafts independently; close them
as included after #104 lands. Not authorized now.

## 5. Rollback

Leave the flags unset. Revert this integration branch. Preparation PRs
#101/#102/#103 remain draft and unmodified.
