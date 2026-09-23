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

**Ordinary backup** (optional, after a COMPLETE stamp; live control
**NOT IMPLEMENTED**):

1. Record which writers are already running.
2. Pause only those writers. Configured budget **900s**. Hard ceiling
   **1200s** -- abort, discard incomplete artifacts, resume pre-existing
   running set only. 15 minutes is not a measured production expectation.
3. Snapshot and verify while paused. Encryption of the sealed archive is
   designed for that window. Remote upload is not implemented. After
   resume, new writes are outside the archive.
4. On success or failure: resume **only** the pre-existing running set.
   Never start Fluent Bit, Slack, or the NR gate if they were stopped.
5. Ops-snapshot failure does not retract the database COMPLETE stamp.

Writers in the pause list (if an adapter existed): `slack_worker`,
`fluent_bit`, `gateway_exporter`, `inbox_consumer`, `nr_source`,
`nr_gate`. User-facing HTTP is not paused.

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

Operator decisions still required (no secrets retrieved here):

1. Whether a **new** container/prefix is created vs an isolated prefix in
   the existing account. Existing `BACKUP_AZURE_CONTAINER` is for DB/MinIO
   stamps; do not mix journals into that prefix without a written decision.
2. Identity: already-authorized principal vs new SAS. Do not retrieve
   `BACKUP_AZURE_*` in this PR.
3. Object-lock / WORM vs versioning-only.
4. Freshness alert threshold for erasure `coveredThrough` and ops-archive
   age.
5. Who may decrypt operational archives and who may attest lock-protocol
   erasure seals.

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
