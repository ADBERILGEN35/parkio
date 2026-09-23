# Backup and restore readiness (BRR-01)

**Assessed:** 2026-09-23. Repository reviewed at `origin/api` `1fd02394`.
**Target:** production host `parkio-civo-prod`.
**Scope:** preparation only. Nothing here creates a backup, downloads a real backup, or
restores real data.

Existing procedures remain canonical: [backup-runbook.md](backup-runbook.md),
[restore-runbook.md](restore-runbook.md), [backup-restore.md](backup-restore.md).
Separate follow-up changes: [backup-restore-follow-ups.md](backup-restore-follow-ups.md).
First drill: [restore-drill-01-isolated-database.md](restore-drill-01-isolated-database.md).

## Evidence provenance

Every live-state statement below carries one of these tags:

| Tag | Meaning |
|---|---|
| **[OBS 2026-09-22]** | A dated read-only observation of the host from the waitlist release preparation (`agent-tools/parkio-waitlist-slack-release-prep-02/20260922T161335Z/production-readonly-facts.md`, waitlist worktree). It is true as of that date and may have changed since. |
| **[OP 2026-09-23]** | A current operator report, not independently verified in this assessment. |
| **[REPO]** | Derived from repository code, configuration or documentation at the reviewed SHA. It describes intended behaviour, not proof that the host runs it. |
| **[CI]** | Actually executed in GitHub Actions, with the run id recorded in §8. |
| **UNKNOWN** | Live state not observed. This assessment had **no host access**: the session's tool permissions refused a bounded read-only SSH probe, and that denial was not retried or worked around. |

## 1. What production runs

- **[REPO]** `scripts/parkio-prod-compose.sh` reads `docker/compose.production.files`: base, apps,
  hosted-beta, azure-hosted-beta, GMP pins, auth registration env, auth pin and web pin.
  `docker-compose.managed-db.yml` is **not** in that set. All ten PostgreSQL databases are
  local containers on Docker named volumes. The Azure Flexible Server/PITR work (ADR-PP-01A,
  `vm-parkio-invite-prod`) belongs to a different environment and does not protect Civo.
- **[OBS 2026-09-22]** The gateway datasource is `postgres-gateway:5432`, PostgreSQL 16.15
  (`postgres:16-alpine`). The host checkout was `/opt/parkio` at `bf9cad51`, with 69 local changes (WSN-F7).
- **[OBS 2026-09-22]** The New Relic log pipeline was present (own compose project and
  systemd units, state under `/var/lib/parkio-nr-log-continuous/*`).
- **[OP 2026-09-23]** The waitlist Slack relay is **installed and active**, and named production
  delivery passed on 2026-09-23. This supersedes the 2026-09-22 observation that it was not
  installed. **[REPO]** Its state lives in `/var/lib/parkio/slack-biz` (SQLite in WAL mode) and
  `/var/lib/parkio/waitlist-ops-inbox`.

## 2. Recovery scope and classification

Class key: **A** = authoritative data · **R** = regenerable data/cache · **O** = operational
state needed to avoid duplicates or budget resets · **S** = configuration/secret required for recovery.
"In backup" refers to `backup-hosted-beta.sh` **[REPO]**.

| Component | Where | Class | In backup | Notes |
|---|---|---|---|---|
| `parkio_auth` | `postgres-auth-data` | A | yes | Accounts, roles, `erased_user_tombstones` (append-only: no delete path in code), auth outbox |
| `parkio_gateway` | `postgres-gateway-data` | A + O | yes | Waitlist (V1–V5). `waitlist_ops_notification_outbox` status (`PENDING/EXPORTED/FAILED`) is what prevents re-export |
| `parkio_user`, `_media`, `_gamification`, `_notification`, `_moderation`, `_analytics`, `_aivalidation` | per-service volumes | A + O | yes | `outbox_events.published`, plus consumer dedup in `inbox_events` / `idempotency_records` |
| `parkio_parking` (PostGIS) | `postgres-parking-data` | A + O | yes | Default image `postgis/postgis:16-3.4` **[REPO]**. Live PostGIS version **UNKNOWN** |
| Erasure ledger | `erasure-tombstones.json` in each stamp | A (privacy) | yes, export failure tolerated (FU-1) | A ledger only knows erasures up to its own stamp. See §5 |
| MinIO media | `minio-data` | A | yes (`minio.tar.gz.enc`) | User photos |
| Kafka log / offsets | `kafka-data` | R | no | Outboxes are the republish source |
| Redis | `redis-data` | R | no | Losing it loses the request-idempotency window |
| ClamAV, Prometheus, Loki, Tempo, Grafana | volumes | R | no | Rules and dashboards are in git |
| Alertmanager silences | `alertmanager-data` | O (minor) | no | Muted alerts may page again |
| Caddy ACME | `caddy-data` | R, rate-limited | no | Re-issuance is limited by CA rate limits |
| NR budget ledger | host bind → container `/var/lib/parkio-nr-budget/budget.db` (SQLite, rollback journal `TRUNCATE`) | **O** | **no** | See §6.2 |
| NR source cursors and Fluent Bit checkpoints | `/var/lib/parkio-nr-log-continuous/{source,collector}` | O | no | Loss re-ships logs, and those bytes are charged against the budget |
| **Slack relay queue, dedup, DLT** | `/var/lib/parkio/slack-biz` (SQLite, `journal_mode=WAL`, `synchronous=NORMAL`) | **O** | **no** | Active **[OP 2026-09-23]**. See §6.3 |
| Slack relay inbox | `/var/lib/parkio/waitlist-ops-inbox` | O | no | Envelopes not yet consumed |
| Host env file | `/opt/parkio/docker/.env.azure-hosted-beta` | **S** | no (by design) | DB passwords, JWT key, `PARKIO_WAITLIST_HASH_SECRET`, `BACKUP_ENCRYPT_PASSPHRASE` |
| Backup passphrase | env | **S (critical)** | no | Without an off-host copy, offsite backups cannot be decrypted after host loss |
| Waitlist HMAC secret | env | S | no | Stored waitlist hashes and outbox `dedup_key` values are keyed by it |
| Relay webhook, NR license | `/etc/parkio/*.secret.env` | S | no | Can be re-issued by the provider |
| Host-local ops inputs | `/opt/parkio/ops/data-wp-{08,19,02b}` | S/R | no | Source of truth UNKNOWN |

## 3. Existing mechanisms and evidence

| Mechanism | Repository | Live |
|---|---|---|
| Nightly `run-production-backup.sh` → `backup-hosted-beta.sh` (10 DBs + MinIO, AES-256-CBC-PBKDF2, `SHA256SUMS`/`COMPLETE`, Azure offsite, fail-closed in production mode) | **[REPO]** cron `30 3 * * *` + `flock` in backup-runbook | Cron presence **UNKNOWN** |
| Azure Blob offsite (`rg-parkio-backups`, westeurope, versioning, 14 d) | **[REPO]** documented as approved | **[REPO]** gmp-release-pins.md (committed 2026-09-21) records at least one successful upload from Civo. Time, lifecycle and immutability **UNKNOWN** |
| Failure reporting (`parkio_backup_*` → 8 alert rules) | **[REPO]** promtool tests | Live series **UNKNOWN**. The 2026-09-18 acceptance recorded FIRING delivered and RESOLVED **not** delivered |
| Canary drill `backup-restore-drill.yml` (synthetic, **canary-based**) | **[CI]** push/PR runs green | **Scheduled runs failed 2026-08-31, 09-07, 09-14, 09-21.** They run stale `master` (FU-2) |
| **Drill 01 procedure `restore-drill-01-procedure.yml` (synthetic, real-stamp format, no canary)** | New in this PR | **[CI]** executed; see §8 |
| Deployment rollback artifacts (digest pins, recovery-prior overlay) | **[REPO]** | These restore code, **not data** |

**No real-production restore evidence was found in the reviewed material.** Every restore
evidence reviewed or produced here used synthetic data.

The two synthetic drills prove different things and must not be conflated:

- **Canary drill** (`restore-drill.sh`, unchanged): proves the backup scripts round-trip data
  by asserting a drill-seeded canary row. `--from-dir` inherits that assertion, so it
  **cannot validate a real production stamp**, which has no canary row.
- **Drill 01 procedure** (`restore-drill-01.sh`): proves the real-stamp procedure without any
  canary. It checks:
  - integrity before decryption;
  - dump-to-restore row-count parity;
  - extensions and Flyway head;
  - an erasure set that reaches a cutoff;
  - replay before exposure;
  - outbox exposure.

## 4. Evidence matrix by data class

| Data class | Backup evidence | Newest known successful backup | Off-host | Restore evidence | Consequence of loss |
|---|---|---|---|---|---|
| 10 PostgreSQL DBs | **[REPO]** scripts and documented cron | **UNKNOWN** (at least one upload recorded before 2026-09-21, time not recorded) | Designed as Azure. Listing **UNKNOWN** | **[CI]** synthetic only (canary drill + drill 01). No real-production restore evidence found | Loss of accounts, sessions, spots, waitlist, moderation |
| Erasure set | Per-stamp ledger; export failure tolerated (FU-1) | Same as DBs | Same | **[CI]** drill 01: account erased **after** the restored stamp is not ACTIVE after replay | A restore can resurrect erased accounts. See §5 |
| MinIO media | **[REPO]** sealed tar | **UNKNOWN** | Same | **[CI]** synthetic (`restore-drill-minio.sh`) | Photos lost. Media metadata dangles |
| Outbox/dedup tables | In DB dumps | Same as DBs | Same | **[CI]** drill 01 records unpublished counts at the stamp | Rows still pending at the stamp are re-published after a restore (§6.3) |
| NR budget ledger + cursors | **None** | n/a | **No** | None | Budget undercount or reset (§6.2) |
| Slack relay state (**active**) | **None** | n/a | **No** | None | Queued messages lost, dedup lost, duplicates possible (§6.3) |
| Kafka, Redis, TSDB, ClamAV | None (regenerable) | n/a | n/a | n/a | Cold start |
| Caddy ACME | None | n/a | **No** | n/a | Re-issuance, rate-limit risk |
| Secrets | None in repo (correct) | n/a | Escrow **UNKNOWN** | n/a | Backups unusable without the passphrase. Waitlist hashes unusable without the HMAC secret |

## 5. Privacy-safe recovery: the erasure set

A restored database contains every account that existed at the stamp's time. The ledger
bundled with that stamp lists only erasures **up to that stamp**. Anyone erased after the
stamp would be restored as ACTIVE. Replaying only the bundled ledger is therefore not privacy-safe.

**Recovery cutoff.** The operator declares a UTC time *C*. Every erasure requested up to *C*
must be known before any restored data is exposed.
- For host loss, *C* is the incident time.
- For a drill, *C* is the newest retrievable stamp.

**Authoritative erasure set** (`scripts/lib/restore-erasure-ledger.py`) is the union of:
1. the data stamp's ledger;
2. the ledgers of every **newer** retrievable stamp, even stamps whose dumps are unusable
   (each ledger is the full, append-only tombstone table; a newer ledger that lacks an older
   identifier fails the build);
3. optionally, an **operator-compiled supplement** with an explicit `covered-through` time.
   Examples: erasure requests received through support channels after the newest stamp.
   It uses the same `{authUserId, erasedAt}` shape and is handled only on the drill host.

Coverage is the newest ledger's time, or the supplement's `covered-through` if later.
**If coverage < C, privacy-safe recovery is BLOCKED** (exit 3), and nothing is decrypted.
In an emergency with a known uncovered window, the copy can be restored
(`--allow-privacy-blocked`) only for validation, and it must be destroyed without exposure.

**Replay before exposure.** `restore-drill-01.sh` replays the full set into the restored auth
database. It then requires that no account in the set is ACTIVE, before anything else may touch the data.

**Remaining limit.** The DB-level replay blocks authentication. Purging PII in other services
needs the application path (`POST /internal/erasure/replay` or Kafka republish). Drill 01
starts no services, so that step is **not exercised**. For a real recovery, no service may
serve traffic until that path completes (drill 03).

**Current live gap.** With nightly stamps, erasures between the newest offsite stamp and an
incident exist only on the lost host. No off-host erasure record is documented. Until one
exists, or the operator can compile the supplement, **privacy-safe recovery after host loss
is BLOCKED for that window.**

## 6. Operational state recovery

### 6.1 Consistent SQLite backups

**Never copy a live SQLite database file.** With WAL, recent commits live in `-wal` until
checkpoint. Copying only `*.db` loses them, and copying `db` + `-wal` + `-shm` while writers
run can produce a torn, inconsistent set. Use SQLite's online backup, which gives a
transactionally consistent snapshot including WAL content:

```bash
python3 - <<'PY' SRC DST
import sqlite3, sys
src = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)
dst = sqlite3.connect(sys.argv[2])
with dst: src.backup(dst)
assert dst.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
PY
```

(`sqlite3 SRC ".backup DST"` or `VACUUM INTO 'DST'` are equivalent where the CLI exists.)
Seal the copy with the same passphrase as the DB dumps, and record the snapshot time. The
relay uses `synchronous=NORMAL`, so its last commits before a power loss may already be
missing on the host itself. No backup can recover those.

Adding these snapshots to the nightly stamp is a separate change, not in this PR.

### 6.2 New Relic budget ledger

- **Missing ledger** **[REPO]**: `budget_gate.py` silently creates a new ledger with 0 spent.
- **Older restored ledger:** spending between the snapshot and the loss is **not counted**.
  The gate then permits up to that much extra ingest in the current daily and monthly windows.
- A changed limit fails startup, but neither case above does.

Conservative recovery when later spending is unknown (documented only; nothing is changed):

1. Keep the gate and collector stopped (they are stopped after host loss).
2. Restore the newest consistent ledger snapshot, if any.
3. Unless the New Relic account's own ingest figures for the current month can be applied as
   an upper bound, mark the **current windows exhausted** before starting:

   ```sql
   UPDATE budget SET
     daily_window   = strftime('%Y-%m-%d','now'), daily_spent   = daily_limit,   daily_exhausted   = 1,
     monthly_window = strftime('%Y-%m','now'),    monthly_spent = monthly_limit, monthly_exhausted = 1
   WHERE id = 1;
   ```

   This fails closed: log shipping pauses until the next UTC month. Application services are
   unaffected. If NR usage is used instead, set `monthly_spent` to the larger of the restored
   value and the account-reported bytes. Account-wide usage is at least this pipeline's usage,
   so this errs high.
4. Cursors and checkpoints: restore them only from the **same** snapshot as the ledger, or start
   from the tail. Starting from the tail loses some logs; re-shipping would charge the budget again.

### 6.3 Slack relay queue and gateway outbox

The gateway outbox (in the nightly dump) and the relay SQLite (not backed up) are two stores
snapshotted at different times:

| Situation after restore | Effect |
|---|---|
| Gateway DB restored to *T*, relay state newer, within 168 h dedup | Rows that were `PENDING` at *T* and exported later are re-exported. Relay dedup (`dedup_key`) suppresses them, so **no duplicate** |
| Gateway DB restored to *T*, relay state lost or older than *T* | Re-exported rows reach an empty queue: **duplicate Slack messages** |
| Relay state older than the gateway outbox | Envelopes queued after the relay snapshot were already `EXPORTED` in the gateway DB: **lost**, never delivered |
| Inbox lost | Envelopes not yet consumed are **lost** |

Drill 01 records `waitlist_ops_notification_outbox` status counts at the stamp. Those counts are
the maximum re-export on restore. Recovery guidance:
- restore the relay state from a snapshot taken **after** the gateway dump;
- keep relay delivery disabled (`enabled=false` in its conf) until an operator compares the queue
  (`worker.py --list-unknown`) with the restored outbox;
- then choose accept-duplicates or discard;
- restore the same `PARKIO_WAITLIST_HASH_SECRET`, or `dedup_key` values will not match.

## 7. Gaps ranked by recovery impact

1. **G1: backup passphrase custody undocumented (critical).** The same applies to the waitlist HMAC and DB/JWT secrets.
2. **G2: privacy-safe recovery after host loss is BLOCKED** for erasures after the newest
   offsite stamp. No off-host erasure record exists (§5).
3. **G3: no real-production restore evidence found.** The procedure is proven on synthetic
   encrypted stamps in CI (§8). A real-stamp drill needs separate authorization.
4. **G4: live backup execution unverified.** Cron, newest `COMPLETE` stamp, offsite
   listing, lifecycle and telemetry are all **UNKNOWN**.
5. **G5: the active Slack relay and the NR budget have no consistent backup** (§6).
6. **G6: erasure export failure tolerated, and `COMPLETE` written despite DB failures** (FU-1).
7. **G7: scheduled canary drill red on stale `master`** (FU-2).
8. **G8: host reproducibility** (checkout drift, host-only inputs, unknown image digests).
9. **G9: participant PII re-erasure is not exercised** by a DB-only drill (drill 03).
10. **G10: no agreed RPO/RTO.** The stamp manifest's offsite flag is stale (known). RESOLVED alert delivery failed in the 2026-09-18 acceptance.

## 8. What is actually proven, and how

| Evidence | Kind | Result |
|---|---|---|
| `python3 scripts/test_restore_readiness.py` | Unit tests, synthetic fixtures | 49 cases. Linux CI is authoritative; Windows host skips openssl pipeline and cannot assert Unix 0600 / blocked egress |
| `restore-drill-01-procedure.yml` run `35883781803` on merge `29bbbdb0` of #94 | **Executed** on a GitHub-hosted runner | **FAIL** at restore auth: `invalid command \\restrict`. Dump-client was floating `postgres:16-alpine` (`pg_dump` emits `\\restrict`); restore-client was the older `psql` inside `postgis/postgis:16-3.4`. Commands were not stripped. |
| `restore-drill-01-procedure.yml` run `35887774967` on `0431dfd9` | **Executed** on a GitHub-hosted runner | Restrict accepted. Restore-client `psql` 16.10 (`postgres:16.10`); dump-client parking 16.4; target-server 16.4 (`postgis/postgis:16-3.4`); PostGIS 3.4.3. Auth/gateway/user parity PASS. **FAIL** row-count parity parking: dump COPY of `spatial_ref_sys` / `tiger.pagc_*` was 0 rows; `CREATE EXTENSION` reseeded 8500 / 835 / 2938 / 4354. Application tables were not the mismatch. SQL was not stripped. |
| `restore-drill-01-procedure.yml` run `35888589173` on `0e49d740` | **Executed** on a GitHub-hosted runner | **PASS.** Restore-client `psql` 16.10; auth dump-client 16.10 with `\\restrict` accepted (not stripped); parking dump-client 16.4; target-server 16.4; PostGIS 3.4.3. All 10 DBs application-table parity PASS. Parking `extensionCatalogsExcluded` records the reseeded catalogs. Erasure replay: 1 ACTIVE before, 0 after. Bundled-ledger-only cutoff is BLOCKED. Outbox inventory recorded without publishers. Evidence: `agent-tools/parkio-backup-restore-readiness-01/20260923T162700Z/ci-35888589173/`. |
| `bash -n` on runbook blocks | **Syntax check only.** Nothing is executed | OK |

What the CI execution covers. All data is synthetic; no application service, sender,
scheduler, relay or notification runs.

1. Real Flyway SQL for all 10 services, applied to a source stack.
2. Two stamps via `backup-hosted-beta.sh` in **production mode**. S is taken first; one
   account is then erased; L is taken after.
3. Upload to an ephemeral offsite, deletion of the local copies, retrieval with `backup-offsite-pull.sh`.
4. The source is destroyed. The target is PostGIS on an `--internal` network with no published
   ports; egress from that network is probed and denied.
5. Negative controls, which leave no database created:
   - a tampered stamp gives exit 1;
   - the bundled ledger alone with cutoff L gives exit 3 BLOCKED.
6. Restore of S with the erasure set through L. The following are asserted:
   - all 10 DBs pass application-table row-count parity (PostGIS catalogs
     reseeded by `CREATE EXTENSION` are recorded, not treated as app-data mismatches);
   - Flyway heads match;
   - PostGIS is present and a spatial query works;
   - the account erased after S was ACTIVE before replay and is not ACTIVE after it;
   - the unrelated account stays ACTIVE;
   - unpublished outbox and waitlist counts equal S's state, not L's;
   - only the target container runs;
   - the evidence contains no identifiers.

## 9. Proposed targets (for operator review, not measured, not agreed)

| Data class | Proposed RPO | Proposed RTO | Basis |
|---|---|---|---|
| PostgreSQL (all 10) | 24 h; ≤ 1 h only with WAL archiving/PITR | 4 h to a validated isolated copy, 8 h to service | RTO **to be measured** by a real-stamp drill 01 |
| Erasure set | 0 through the incident, via an off-host erasure record | Before exposure | G2 |
| MinIO | 24 h | 8 h | Drill 02 |
| NR budget | 24 h consistent snapshot; conservative exhaustion otherwise | At restart | §6.2 |
| Slack relay | 24 h snapshot taken after the DB dump; otherwise documented duplicate/loss acceptance | At restart | §6.3 |
| Secrets | 0 (escrowed) | 1 h | Precondition for everything |
