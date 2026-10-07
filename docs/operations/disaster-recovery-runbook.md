# Disaster recovery runbook (R5.2)

Single-VPS hosted-beta recovery. This is **not** multi-region HA.

**Production data recovery is BLOCKED on the current code.** The restore entrypoints refuse
to decrypt or apply a backup into production because there is no verified erasure coverage
(`verifiedCoverage=false`): a restored copy could bring back accounts and data that users
have since erased. A supported, erasure-safe recovery path is open work (U02: signed erasure
checkpoints, off-host record store, restore consumer with an expose gate, measured drill).
Until it lands, the paths below that restore data are marked **BLOCKED** with the guard that
refuses them. Do not work around a refusal (for example by lowering the cutoff, editing a
stamp or restoring outside the scripts).

## Scenarios

| Scenario | Status on current code | Path |
|----------|------------------------|------|
| Bad deploy | **Conditional** (no data restore): refused until a deploy made with the F-INV-3 change has recorded its manifest; then only when the schema gate passes | `scripts/rollback-hosted-beta.sh` (image tags, no DB restore). It refuses, exit 3, before anything changes, when the live schema has migrations the target release lacks, when the digest pins it would start differ from the deployed release's, or when the deployed release's recorded manifest is missing or unreadable (`parkio_assert_rollback_schema_compatible`, F-INV-3). A bad release that added a migration to a service the rollback changes cannot be rolled back; with the data restores below BLOCKED, its scripted recovery is a forward fix |
| Data corruption (one DB) | **BLOCKED** | `restore-database.sh` refuses production apply: `parkio_restore_refuse_standalone_database` (it does not replay erasures) and `parkio_restore_refuse_unverified_production`, exit 3 |
| Full host loss | **BLOCKED** for data | `restore-hosted-beta.sh` refuses a non-dry-run production restore: `parkio_restore_refuse_unverified_production`, exit 3. Stamp preflight and `--dry-run` still run |
| MinIO only | **BLOCKED** | `restore-hosted-beta.sh --only minio` is refused: `parkio_restore_refuse_unsupported_production_scope` (restored objects would not get erasures applied), exit 3 |
| Kafka/outbox poison | Supported | DLQ/outbox runbooks; no restore involved |

**One compose file set (CL-F12).**
- With the default hosted-beta profile, deploy, rollback and DR render `docker/compose.production.files` exactly, so they share one model: the production pins and settings.
- The rollback points the services that list builds back at the image tags its target manifest records, keeps this checkout's digest pins, and starts with `up --no-build`. A pin is rolled back by reverting its pin file, then deploying.
- Start the stack only through those scripts, never with a hand-written `-f` list.
- `scripts/test-canonical-production-file-set.sh` checks the file set.
- **The rollback schema gate (F-INV-3, owner decision 2026-10-05).**
  - **What it compares.** For each service the rollback re-points, every Flyway script that the deployed release's manifest lists in `migrationVersions` must also be in the target's list. It compares script names, not a maximum version.
  - **Digest pins.** On hosted-beta and azure-hosted-beta the rollback also starts this checkout's digest pins. A pinned image's migrations are recorded nowhere, so the rollback keeps the deployed release's pins: it refuses (exit 3) when this checkout's pins differ from the record's `pinnedImages`, or when the record has none. Roll a pin through a deploy, then roll back.
  - **Where the live schema comes from.** A deploy or a rollback records the manifest of the release it starts, before any container starts, outside any checkout, in one directory per host that every deployer shares (`deployed-manifest.json`):
    - invite-production: in its runtime root, `/opt/parkio/invite-production`;
    - hosted-beta and azure-hosted-beta: in `/var/lib/parkio/<profile>`. Every user who deploys or rolls back on the host must be able to write it, for example after `sudo install -d -m 2775 -g <deployers group> /var/lib/parkio/hosted-beta`. A live deploy or rollback refuses (exit 3) before it changes anything when it cannot;
    - local-dev (`--no-hosted-beta-overlay`, a developer machine): under `${XDG_STATE_HOME:-~/.local/state}/parkio/local-dev`.
    `PARKIO_DEPLOY_STATE_DIR` overrides them, and the scripts say so. `deploy-artifacts/current.json` is not used.
  - **Record order.** A rollback records its target, with the pins that run, before it activates a release or re-points an image. When it cannot, nothing has changed. When a later step fails before the start, the previous record is restored.
  - **Fail-closed.** A missing, unreadable or malformed record or target refuses the rollback with exit 3, before anything changes. There is no override.
  - **First rollback after this change.** Until a deploy made with this change has recorded its manifest, a live rollback is refused. On hosted-beta that first deploy needs the host directory above. Dry runs do not exercise the gate, and say so.

The refusals live in `scripts/lib/restore-safe-preflight.sh` and are explained in
[restore-safe-preflight.md](restore-safe-preflight.md). The only restores the scripts allow
are synthetic, destination-bound isolated fixtures (`--isolated-fixture` with a matching
ticket) and the separately authorized isolated drill in
[restore-drill-01-isolated-database.md](restore-drill-01-isolated-database.md), which does not
start any application.

## RPO / RTO

**Not measured, and no RPO or RTO is offered.** Backups run nightly, but while production
restore is BLOCKED no recovery time or recovery point can be promised. Numbers are recorded
here only after the U02 measured, disposable full-recovery drill (host/state loss → fresh
environment → erasure-safe recovery), together with the conditions they were measured under.
Proposed targets awaiting operator review are in
[backup-restore-readiness.md](backup-restore-readiness.md) §9; they are not commitments. RPO/RTO
approval remains NOT APPROVED ([backup-restore.md](backup-restore.md)).

**Measured disposable full-recovery drill (U02).** `scripts/recovery-drill.sh` (workflow
`recovery-drill.yml`) runs, on disposable infrastructure with synthetic data: a backup, erasures
after it, host loss, a fresh isolated environment and the stage-4 recovery
([restore-safe-preflight.md](restore-safe-preflight.md), Stage-4 isolated recovery). Its report
gives per-phase timings:
- RTO: from the end of the host loss to the expose gate OPEN on a COMPLETE replay;
- RPO (data): host loss minus the backup's stamp time;
- RPO (erasures): durably recorded erasures the replay did not apply.

| Measure | Value | Conditions |
|---------|-------|------------|
| RTO | _pending: from the final CI drill on the reviewed #296 head_ | |
| RPO (data) | _pending_ | |
| RPO (erasures) | _pending_ | |

These numbers describe the isolated drill only. They are not a production commitment: production
recovery stays BLOCKED, and Slack replay and the NR budget (#101/#103/#104, HOLD) are not covered.

| Asset | Recovery on current code |
|-------|--------------------------|
| Postgres (10 service DBs) | BLOCKED (see Scenarios); isolated drill only |
| MinIO media | BLOCKED (see Scenarios) |
| Kafka topics | Ephemeral; the outbox is the source of truth (redeploy / replay from outbox) |
| App images | Rollback by image tag / digest (no data restore) |

## Incident response while data recovery is BLOCKED

Data loss or host loss is at least SEV-1 ([incident-management.md](incident-management.md)).

1. **Contain.** Keep the affected services stopped or in maintenance. Do not bring an empty or
   partially restored stack back to users.
2. **Preserve evidence.** Keep the backup stamps (local and offsite) untouched: no re-sealing,
   no `COMPLETE` edits, no pruning. Record the git SHA, the stamp names and their `COMPLETE` /
   `SHA256SUMS` state, the time of the incident and what failed.
3. **Escalate.** Page the incident commander and the data/privacy owner. Restoring user data
   needs an explicit decision, and on the current code there is no supported way to carry it
   out in production.
4. **Isolated copies stay unexposed.** A copy restored for investigation (isolated drill or
   synthetic fixture) must never receive user traffic, start publishers, schedulers, relays or
   Slack, or be promoted to production.
5. **Unknown erasure tail = no exposure.** If it cannot be proven which erasures happened after
   the backup was taken, the copy must stay unexposed. Applying erasures "later", after users
   are back on a restored copy, is not an accepted procedure.

## Full host rebuild (outline)

1. Provision VPS (see `docs/operations/runtime-sizing.md`).
2. Install Docker, clone the repo at a known good tag, restore `docker/.env` from the secrets
   store. The host needs read access to the registry of the digest-pinned images (GHCR).
3. Copy the backup set from offsite (`BACKUP_MC_DEST`) to `BACKUP_DIR` and verify it
   (`sha256sum -c <stamp>/SHA256SUMS`, `test -f <stamp>/COMPLETE`).
4. Run the stamp preflight only:
   `restore-hosted-beta.sh --manifest <stamp>/backup-manifest.json --dry-run`.
5. **Stop.** Restoring the data into production is BLOCKED (see Scenarios); follow the incident
   response above. Do not start the application stack on empty databases for users.

## Evidence to keep

- `backup-artifacts/backup-*.json`
- `deploy-artifacts/deploy-*.json`
- Weekly `restore-drill.sh` CI result
- Post-incident: note git SHA, manifest timestamps, and root cause

## What backups do NOT cover

- In-flight Kafka messages not yet in outbox
- Ephemeral Redis state
- Grafana/Prometheus historical data (rebuilt from scratch)
- Secrets in `docker/.env` (store separately)
- SPA static assets in the `web` image (rebuild from git at known SHA)

## Exact commands

### Bad deploy (no data loss) — conditional (schema gate)

```bash
cd /opt/parkio
PARKIO_ENV_FILE=docker/.env ./scripts/rollback-hosted-beta.sh
```

It refuses (exit 3) until a deploy made with the F-INV-3 change has recorded its manifest, and
whenever the schema gate does not pass (see the rollback schema gate above).

### Single database corruption — BLOCKED

`restore-database.sh <service> <dump> --recovery-cutoff <ISO-8601-UTC>` exits 3 before decrypt
or apply on production (`parkio_restore_refuse_standalone_database`,
`parkio_restore_refuse_unverified_production`). Follow the incident response above.

**Gamification and user-service (U12): an operational limitation, not a guard.**
- **Invariant.** user-service's gamification projection keeps a per-value version. It needs
  gamification's restored row versions to be at least the ones user-service holds:
  - `user_level_progress.version` ≥ `points_version` and ≥ `level_version`;
  - `trust_scores.version` ≥ `trust_version`.
- **Backup order.** `scripts/backup-databases.sh` dumps `user` before `gamification`, which keeps
  that invariant between the two dumps. The order comes from its `SERVICES` list; it is not
  enforced or tested.
- **Unsupported.** Until a reviewed procedure exists to reconcile or reset user-service's projection
  versions, never:
  - restore or roll back the gamification database alone, or to an older backup set than the user
    database;
  - let events produced after the backup reach the restored user-service, through a DLT redrive or a
    consumer offset reset.
- **No consistency claim.** A restore from one backup set is not claimed to give a consistent
  projection. See "Backup and restore ordering (U12)" in `docs/architecture/event-contracts.md`.

### Full stack restore (same or new VPS) — BLOCKED for data

Only the stamp preflight runs:

```bash
cd /opt/parkio
PARKIO_ENV_FILE=docker/.env ./scripts/restore-hosted-beta.sh \
  --manifest /var/backups/parkio/<stamp>/backup-manifest.json --dry-run
```

A non-dry-run restore exits 3 (`parkio_restore_refuse_unverified_production`), even when the
recovery cutoff equals the stamp clock.

### MinIO-only restore — BLOCKED

`restore-hosted-beta.sh ... --only minio` exits 3
(`parkio_restore_refuse_unsupported_production_scope`).

## Contacts / secrets (owner)

- `docker/.env` on the VPS (not in git)
- Slack/webhook URLs for alerts
- `BACKUP_ENCRYPT_PASSPHRASE` if encryption enabled
- Offsite `mc` credentials for `BACKUP_MC_DEST`
