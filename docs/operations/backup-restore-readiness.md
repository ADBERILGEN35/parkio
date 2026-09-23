# Backup and restore readiness (BRR-01)

**Assessed:** 2026-09-23 from `origin/api` at `1fd02394`.
**Target:** production host `parkio-civo-prod`.
**Scope:** repository inspection, prior dated read-only evidence and GitHub Actions
metadata. This document creates no backup and performs no restore.

> **Host evidence: UNAVAILABLE in this assessment.** The session's tool permissions
> refused read-only SSH to `parkio-civo-prod`, so no live cron, file, timer, Azure listing
> or database fact was collected on 2026-09-23. Host facts marked *(2026-09-22)* come
> from the earlier read-only inventory in
> `agent-tools/parkio-waitlist-slack-release-prep-02/20260922T161335Z/production-readonly-facts.md`
> (waitlist worktree). Everything else marked **UNKNOWN** stays unknown until the
> read-only probe in [restore-drill-01](restore-drill-01-isolated-database.md#0-read-only-facts-to-collect-first)
> runs.

Existing procedures remain canonical: [backup-runbook.md](backup-runbook.md),
[restore-runbook.md](restore-runbook.md), [backup-restore.md](backup-restore.md).
This document records what is actually proven, what is missing, and the first drill.

## 1. What production actually runs

`scripts/parkio-prod-compose.sh` reads `docker/compose.production.files`: base, apps,
hosted-beta, azure-hosted-beta, GMP pins, auth registration env, auth pin, web pin.
**`docker-compose.managed-db.yml` is not in that set.** All ten PostgreSQL databases run as
local containers on Docker named volumes on the Civo host. The 2026-09-22 facts confirm
this: the gateway datasource is `postgres-gateway:5432`, on PostgreSQL 16.15
(`postgres:16-alpine`). The Azure Flexible Server / PITR work
(ADR-PP-01A, `vm-parkio-invite-prod`) covers a separate environment and does not protect Civo.

Separately deployed host state, outside Compose:

- New Relic log pipeline (`/var/lib/parkio-nr-log-continuous/*`, own compose project and
  systemd units, present *(2026-09-22)*).
- Waitlist Slack relay (`/var/lib/parkio/slack-biz`, `/var/lib/parkio/waitlist-ops-inbox`),
  which was **not installed** *(2026-09-22)*.

## 2. Recovery scope and classification

Class key: **A** = authoritative data · **R** = regenerable data/cache · **O** = operational
state needed to avoid duplicates or budget resets · **S** = configuration/secret required for recovery.

| Component | Where | Class | In `backup-hosted-beta.sh`? | Notes |
|---|---|---|---|---|
| `parkio_auth` | `postgres-auth-data` | A | yes (pg_dump) | Accounts, roles, `erased_user_tombstones`, auth outbox |
| `parkio_gateway` | `postgres-gateway-data` | A + O | yes | Waitlist interest (V1–V5); `waitlist_ops_notification_outbox` status is what stops re-export |
| `parkio_user`, `parkio_gamification`, `parkio_moderation`, `parkio_notification`, `parkio_media`, `parkio_analytics`, `parkio_aivalidation` | per-service volumes | A + O | yes | Each has `outbox_events`; most have `inbox_events` / `idempotency_records` (consumer dedup) |
| `parkio_parking` (PostGIS) | `postgres-parking-data` | A + O | yes | Sessions, spots, WP-05 ledgers, municipal sync state. Image default `postgis/postgis:16-3.4`; **live PostGIS version UNKNOWN** |
| Erasure ledger | exported into each stamp as `erasure-tombstones.json` | A (privacy) | yes, but `|| true` | Must be replayed after any restore, and restore fail-closes without it |
| MinIO media bucket | `minio-data` | A | yes (`mc mirror` → `minio.tar.gz.enc`) | User photos. Restore is proven only in isolated CI |
| Kafka log | `kafka-data` | R (transport) | no | Outboxes are the source for republish. Consumer offsets live here, and consumer dedup is in DB `inbox_events` |
| Redis | `redis-data` | R | no | Cache, rate limits, request idempotency keys. Losing it widens the duplicate window for client retries |
| ClamAV signatures | `clamav-data` | R | no | Re-downloaded |
| Prometheus / Loki / Tempo / Grafana data | volumes | R | no | Dashboards and rules are in git; metric history is lost |
| Alertmanager silences | `alertmanager-data` | O (minor) | no | Silences are lost, so muted alerts may page again |
| Caddy ACME account/certs | `caddy-data` | R, but rate-limited | no | Re-issuance is possible but subject to CA rate limits (compose comment: *MUST persist*) |
| NR budget ledger | `/var/lib/parkio-nr-log-continuous/budget` (SQLite) | **O** | **no** | If missing, `budget_gate.py` silently creates a fresh ledger with 0 spent, **resetting the daily/monthly budget** |
| NR source cursors, Fluent Bit checkpoints | `/var/lib/parkio-nr-log-continuous/{source,collector}` | O | no | Loss can re-ship already-sent logs, and those bytes are charged to the budget |
| Slack relay queue, dedup, DLT (when installed) | `/var/lib/parkio/slack-biz` (SQLite) | **O** | **no** | Loss drops queued messages and 168 h dedup. Gateway rows already `EXPORTED` are not re-sent |
| Slack relay inbox (when installed) | `/var/lib/parkio/waitlist-ops-inbox` | O | no | Unconsumed envelopes |
| Compose `slack_biz_data` volume (opt-in profile) | named volume | O | no | Not the Civo relay path. Only relevant if that profile is ever used |
| Host env file | `/opt/parkio/docker/.env.azure-hosted-beta` | **S** | no (by design) | DB passwords, `PARKIO_JWT_PRIVATE_KEY_PEM`, `PARKIO_WAITLIST_HASH_SECRET`, gateway internal secret, provider keys, **`BACKUP_ENCRYPT_PASSPHRASE`** |
| Backup decryption passphrase | same env file | **S (critical)** | no | Without an off-host copy, **every offsite backup is undecryptable after host loss** |
| Waitlist HMAC secret | env | S | no | Stored waitlist hashes are keyed by it, so without the same value restored rows cannot be matched |
| JWT signing key | env | S | no | Loss forces a new key: every session is invalidated, but no data is lost |
| Slack / Alertmanager webhook, NR license | `/etc/parkio/*.secret.env`, env | S | no | Can be re-issued from the provider |
| Host-local ops inputs | `/opt/parkio/ops/data-wp-08/boundary`, `data-wp-19/district-topology`, `data-wp-02b` | S/R | no | Read-only mounts into parking-service. Source of truth is UNKNOWN (possibly only on host) |
| Host checkout drift | `/opt/parkio` at `bf9cad51` + 69 local changes *(2026-09-22)* | S | no | A rebuilt host from git would not match the running host (WSN-F7) |

## 3. Existing mechanisms and evidence

| Mechanism | Repository evidence | Production evidence |
|---|---|---|
| Nightly orchestrator `run-production-backup.sh` → `backup-hosted-beta.sh` (10 DBs + MinIO, AES-256-CBC-PBKDF2, `SHA256SUMS` + `COMPLETE`, Azure offsite, fail-closed in production mode) | Documented cron `30 3 * * *` with `flock` in [backup-runbook.md](backup-runbook.md) | **UNKNOWN** whether `/etc/cron.d/parkio-backup` exists on Civo |
| Offsite Azure Blob, `rg-parkio-backups` / westeurope, versioning, 14 d lifecycle | Documented as approved | [gmp-release-pins.md](gmp-release-pins.md) (committed 2026-09-21) records **at least one successful Azure upload** from Civo. The date, retention policy and immutability are UNKNOWN |
| Local retention 14 d (`find -mtime +14 … rm`) | Script | UNKNOWN |
| Failure reporting: `parkio_backup_*` textfile → node-exporter → 8 alert rules, including PA-16 missing-telemetry | `docker/prometheus/tests/backup-*.test.yml` (promtool) | Civo alert delivery accepted 2026-09-18 for FIRING. The RESOLVED Slack increment **FAILED** in the same run. Whether live backup series exist is UNKNOWN |
| Isolated CI drill (`backup-restore-drill.yml`): synthetic data, 10 DBs + MinIO + offsite round-trip + failure modes + erasure replay | Recent completed `pull_request`/`push` runs green (9 successes on 2026-09-22/23; one cancelled, one in progress) | **Scheduled weekly runs have FAILED for at least 4 weeks** (2026-08-31, 09-07, 09-14, 09-21). Cron runs use `master` @ `e5692428` (2026-07-30). That workflow predates the fix that starts `postgres-gateway`: `container 'parkio-postgres-gateway' not found` |
| Azure-offsite CI acceptance (`workflow_dispatch`, `azure_offsite`) | `workflow_dispatch` runs green 2026-08-13/14 (whether `azure_offsite` was enabled was not checked) | Proves the mechanics with CI secrets, not real stamps |
| Invite-production systemd backup timer (`infra/systemd/parkio-invite-backup.*`) | Tests exist | Separate Azure environment. Live status UNKNOWN |
| Deployment rollback artifacts (digest pins, `docker-compose.gmp-recovery-prior.yml`, `*.bak-*`) | Pins in git | **These are not data backups.** They restore code, not data |

**Nothing has ever restored a real production backup,** in any environment. Every restore
success so far used CI-generated synthetic data.

## 4. Evidence matrix by data class

| Data class | Backup evidence | Newest known successful backup | Off-host | Restore evidence | Consequence of loss |
|---|---|---|---|---|---|
| 10 PostgreSQL DBs | Script + documented cron | **UNKNOWN**: at least one successful offsite upload was recorded before 2026-09-21, time not recorded | Azure Blob (designed). Live listing UNKNOWN | Synthetic CI only. **Real stamp never restored** | Total loss of accounts, sessions, spots, waitlist, moderation history |
| Erasure ledger | Exported per stamp; export failure tolerated | Same as DBs | Same | Synthetic CI replay | Restore would resurrect erased users. Production restore blocks without it |
| MinIO media | `mc mirror` + sealed tar | UNKNOWN | Same | Synthetic CI (`restore-drill-minio.sh`) | Lost photos. Metadata in `parkio_media` would point at missing objects |
| Outbox/inbox/dedup tables | Inside DB dumps | Same as DBs | Same | Not asserted by any drill | A restore to time T re-publishes events that were pending at T. Consumers dedup through `inbox_events`, but only if their DB was restored to the same or a later point |
| NR budget ledger + cursors | **None** | n/a | **No** | None | Budget resets to 0 spent (possible monthly overspend of up to one monthly budget). Cursor loss re-ships logs |
| Slack relay state (not installed yet) | **None** | n/a | **No** | None | Queued business notifications lost, dedup lost |
| Kafka, Redis, TSDB, ClamAV | None (regenerable) | n/a | n/a | n/a | Cold start. Redis idempotency window lost |
| Caddy ACME | None | n/a | **No** | n/a | Re-issuance, with CA rate-limit risk after repeated rebuilds |
| Host env and secrets | None in repo (correct) | n/a | **UNKNOWN**: no escrow is documented | n/a | **Without the backup passphrase and the waitlist HMAC secret, data backups are unusable or degraded** |

## 5. Gaps ranked by recovery impact

1. **G1: backup passphrase custody is undocumented (critical).** `BACKUP_ENCRYPT_PASSPHRASE`
   is documented only as an operator env / Key Vault value. If it exists only in the host env
   file, losing the host also makes the offsite copies useless. The same applies to
   `PARKIO_WAITLIST_HASH_SECRET` and the DB/JWT secrets needed to rebuild. Action for the
   owner: confirm an off-host escrow (password manager or vault with two custodians) and
   record where it is, without the value.
2. **G2: no real backup has ever been restored.** Restorability, duration and version
   compatibility of the real encrypted stamps are unproven. The runbook's isolated step
   (`restore-drill.sh --from-dir`) asserts a drill-only canary row, and **would report FAIL on
   a genuine production stamp**. This PR adds real-data validators; see
   [restore-drill-01](restore-drill-01-isolated-database.md).
3. **G3: live backup execution is unverified on Civo.** Whether the cron exists, the newest
   `COMPLETE` stamp, the offsite listing, lifecycle and versioning, and the presence of
   `parkio_backup_*` series are all UNKNOWN in this assessment. This is the read-only probe
   in drill §0.
4. **G4: the scheduled CI drill has been red since at least 2026-08-31.** The weekly
   job runs on stale `master`, so the "continuously proven restorable" claim in
   `backup-restore-drill.yml` does not hold. The fix is a release-owner decision: advance
   `master`, or move the scheduled trigger's ref. This PR does not touch the shared workflow.
5. **G5: operational state outside the backup set.** The NR budget ledger resets silently
   when it is missing. Slack relay queue/dedup will have no backup once installed. Caddy
   ACME has no backup. Recommendation: add these small files to a host-state tarball in the
   same sealed stamp (NR ledger < 4 MiB by guard). Or, as a minimum, change the budget
   gate to refuse to start with a missing ledger unless a reset is explicitly acknowledged.
6. **G6: erasure ledger export failure is tolerated at backup time**
   (`parkio_export_erasure_tombstones … || true`). The resulting stamp is unrestorable in
   production mode, but the backup is reported as green. The stamp preflight in this PR catches it at
   restore time. The source-side fix belongs in a separate change.
7. **G7: the manifest `offsite.uploaded` flag is stale inside the stamp** (known defect,
   gmp-release-pins.md). The preflight treats it as a warning. Offsite listing is the proof.
8. **G8: host reproducibility.** Checkout drift (WSN-F7), host-only ops inputs under
   `/opt/parkio/ops/*`, and the unknown live PostGIS/Postgres image digests all mean a
   rebuilt host may not match production.
9. **G9: no RPO/RTO has been agreed.** Proposals are below.
10. **G10: RESOLVED alert delivery failed** in the 2026-09-18 Civo acceptance. A backup
    failure page would fire, but its resolution may not be visible in Slack.

## 6. Proposed targets (for operator review, not measured, not agreed)

| Data class | Proposed RPO | Proposed RTO | Basis |
|---|---|---|---|
| PostgreSQL (all 10) | 24 h (nightly), moving to ≤ 1 h only with WAL archiving/PITR | 4 h to an isolated, validated copy, 8 h to service | Nightly logical dump. RTO is **to be measured by drill 01** |
| MinIO media | 24 h | 8 h | Mirror size is UNKNOWN. Measure in drill 02 |
| NR budget ledger | 24 h (in stamp) | at restart | Prevents budget reset |
| Slack relay state | 24 h, or accept loss with a documented duplicate/loss policy | at restart | Low volume. Owner decides |
| Secrets | 0 (escrowed) | 1 h | Precondition for every other target |

## 7. Helpers added by this PR (secret-free, synthetic-tested)

| Helper | Purpose |
|---|---|
| `scripts/lib/restore-stamp-preflight.py` | Offline integrity of a retrieved stamp: `COMPLETE`↔`SHA256SUMS` binding, every checksum, no unlisted/plaintext files, OpenSSL salted-ciphertext magic, manifest schema, erasure-ledger shape (count only), age. Never decrypts |
| `scripts/lib/restore-dump-profile.py` | Profiles a decrypted plain dump stream: server/pg_dump version, extensions, roles referenced by GRANT, per-table row counts, Flyway head. Emits a count query and compares a restored database against the dump. No row contents |
| `scripts/lib/restore-drill-isolation-preflight.py` | Refuses a drill on a production host or with live outbound credentials, credential-shaped values, email/push providers not inert, sender/poller flags on, application containers running, or open egress. Prints names only |
| `scripts/test_restore_readiness.py` | 31 synthetic tests: tamper/missing/plaintext cases, compatibility with the real bash `parkio_backup_write_stamp_integrity`, a real `openssl`/`gzip` pipeline with a synthetic passphrase, and PII-leak assertions |

Run: `python3 scripts/test_restore_readiness.py` (no Docker, no network beyond localhost sockets).
