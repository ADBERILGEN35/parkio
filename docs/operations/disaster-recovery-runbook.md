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
| Bad deploy | **Refused while every app service is pinned (U13)** (no data restore); recovery is a forward fix | `scripts/rollback-hosted-beta.sh` (image tags, no DB restore). Since U13 the production list pins every app service, so it has nothing to re-point and refuses, exit 3, before anything changes ("image plan builds no service"). Its F-INV-3 refusals stay: the live schema has migrations the target release lacks, the digest pins it would start differ from the deployed release's, or the deployed release's recorded manifest is missing or unreadable (`parkio_assert_rollback_schema_compatible`). With the data restores below BLOCKED, the scripted recovery from a bad release is a forward fix: a deploy of corrected pins. A deploy of earlier pins is not an accepted rollback unless schema compatibility with the live databases is proven first |
| Data corruption (one DB) | **BLOCKED** | `restore-database.sh` refuses production apply: `parkio_restore_refuse_standalone_database` (it does not replay erasures) and `parkio_restore_refuse_unverified_production`, exit 3 |
| Full host loss | **BLOCKED** for data | `restore-hosted-beta.sh` refuses a non-dry-run production restore: `parkio_restore_refuse_unverified_production`, exit 3. Stamp preflight and `--dry-run` still run |
| MinIO only | **BLOCKED** | `restore-hosted-beta.sh --only minio` is refused: `parkio_restore_refuse_unsupported_production_scope` (restored objects would not get erasures applied), exit 3 |
| Kafka/outbox poison | Supported | DLQ/outbox runbooks; no restore involved |

**One compose file set (CL-F12).**
- With the default hosted-beta profile, deploy, rollback and DR render `docker/compose.production.files` exactly, so they share one model: the production pins and settings.
- The rollback points the services that list builds back at the image tags its target manifest records, keeps this checkout's digest pins, and starts with `up --no-build`. Since U13 the list pins every app service, so there is nothing to re-point and the rollback is refused (exit 3). A pin moves back only by reverting its pin file and deploying; committed migrations are forward-fix only, so that is not an accepted rollback unless schema compatibility with the live databases is proven first.
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

## Per-service acceptable rollback (N-16)

Pinned application images on the hosted-beta production file set
([docker/compose.production.files](../../docker/compose.production.files)).
Current and prior digests below are copied from the pin files; they are not invented.
**Acceptable rollback** means an immutable prior digest that operations may treat as a recovery
target without a separate schema-compatibility proof. When no prior registry digest exists, or
the pin file labels the prior image unusable, the cell is **none / forward fix**.

Committed Flyway migrations are forward-fix only for every service that owns a database.
scripts/rollback-hosted-beta.sh refuses while every app service is digest-pinned (U13, exit 3).
Re-pointing any service to an earlier image remains **not an accepted rollback** unless schema
compatibility with the live databases is proven first (F-INV-3 above).

| Service | Pin file | Current digest | Prior digest recorded in pin comments | Acceptable rollback | Schema / notes |
|---------|----------|----------------|---------------------------------------|---------------------|----------------|
| gateway-service | [gmp-release-pins](../../docker/docker-compose.gmp-release-pins.yml) | ghcr.io/adberilgen35/parkio/gateway-service@sha256:ff21f4a6dbcd6baba9b2a69527a2d6006016c4d00e5390e1bce01b94e86cf026 | ghcr.io/adberilgen35/parkio/gateway-service@sha256:866a7fe09ccc5fd2148db893fc8abb013a2c2e8ac9567f85a4eb6c7c11f9bb48 | **none / forward fix** | Prior live digest exists; not an accepted automatic rollback without schema proof. Release order: gateway before web/auth. |
| media-service | same | ghcr.io/adberilgen35/parkio/media-service@sha256:db3065e39a7086e6c740980de0ad2b3c0abda14676e3398d65d43e720e9b7c72 | ghcr.io/adberilgen35/parkio/media-service@sha256:62f49d04087fb7aa475db0a42c7f8d1e7d1511f84e6b496a58c5beda58f864ec | **none / forward fix** | Same. After auth in release order. |
| parking-service | same | ghcr.io/adberilgen35/parkio/parking-service@sha256:3888df0564910477d376a7d8ed8f6c4991e02e66a643f67ca8f2de5a067f96a9 | ghcr.io/adberilgen35/parkio/parking-service@sha256:85da26542fe6729b72183a3593df42f4f51482ffeb7a0e930a48b64e3b027fb2 | **none / forward fix** | Same. After auth in release order. |
| user-service | [app-release-pins](../../docker/docker-compose.app-release-pins.yml) | ghcr.io/adberilgen35/parkio/user-service@sha256:0f9d34a68f33f41e45404c404e7e5aa0ca48e0f895b3e86bd63be0a8ae1aec21 | none (host builds never published) | **none / forward fix** | Six-app pins replaced unpublished host builds. |
| gamification-service | same | ghcr.io/adberilgen35/parkio/gamification-service@sha256:47e2425ff43d964de15e99a0218dae3f0fa7874525cd91e61ef0a23b03473753 | none | **none / forward fix** | Same. |
| notification-service | same | ghcr.io/adberilgen35/parkio/notification-service@sha256:ff822479048094fa688ae2f7ea27024cf57b9dd17af1562ed73c015a0c0e8942 | none | **none / forward fix** | Same. |
| moderation-service | same | ghcr.io/adberilgen35/parkio/moderation-service@sha256:e2b0ff6471f0098aab3f70495e53bd84459980bcc08c8bfc344098c2531d2e38 | none | **none / forward fix** | Same. |
| ai-validation-service | same | ghcr.io/adberilgen35/parkio/ai-validation-service@sha256:8a2b091f5bbc25f5f3e7721ff97521713bfc281d6c1f65c0551f94e67a53e640 | none | **none / forward fix** | Same. |
| analytics-service | same | ghcr.io/adberilgen35/parkio/analytics-service@sha256:ec01d727c0a06c6f4c7af3644ead17a1b79df022241e7c55be992e272a714d3a | none | **none / forward fix** | Same. |
| auth-service | [auth-release-pin](../../docker/docker-compose.auth-release-pin.yml) | ghcr.io/adberilgen35/parkio/auth-service@sha256:2091de2e7efed0b6814a312bf5d74690274d778e6438491eb3884d9d710f3616 | ghcr.io/adberilgen35/parkio/auth-service@sha256:a4410a45634b73246e13c3054dc3b1d0c8069baa7c0738258ecf9774ca72514e | **none / forward fix** | Prior live digest exists; not accepted without schema proof. Needs PARKIO_LOGIN_THROTTLE_HMAC_KEY. |
| web | [web-release-pin](../../docker/docker-compose.web-release-pin.yml) | ghcr.io/adberilgen35/parkio/web@sha256:08811c39cd5a0a483415b136d1f6bcfd7120e97a94b71ec77d20b63ac15cdea6 | ghcr.io/adberilgen35/parkio/web@sha256:4625a72e5dd3afd4cc6b2d2a62d8cdb999725942ed6471591d33b96cbd42b6a6 (blank map) | **none / forward fix** (prior **unusable**) | Previous live pin is explicitly a blank-map image. Do not pin 8d9bfca4 (CI synthetic MapTiler key) or pre-#324 digests named in the pin file. |

When pin digests change, update this table to match the pin files. Keep **Acceptable rollback** as **none / forward fix** unless an owner-approved, schema-proven immutable target is recorded.

## RPO / RTO

**No production RPO or RTO is offered.** Design matrix for the remaining production recovery coverage gap (F-27 / #365): [f27-production-recovery-coverage-design-matrix.md](f27-production-recovery-coverage-design-matrix.md). Isolated #296 evidence is not production acceptance; D-DP1 and #104 HOLD remain. Backups run nightly, but while production restore is
BLOCKED no recovery time or recovery point can be promised. The U02 measured, disposable
full-recovery drill (host/state loss → fresh environment → erasure-safe recovery) has run; its
numbers are below, with the conditions they were measured under, and describe that drill only.
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

Measured by the CI drill on the reviewed #296 head (run
[37634422144](https://github.com/ADBERILGEN35/parkio/actions/runs/37634422144), 2026-10-07, head
`fad96778`, PASS 46/46 checks):

| Measure | Value | Conditions |
|---------|-------|------------|
| RTO | 234 s (3 min 54 s) | End of host loss to the expose gate OPEN, through the fresh isolated environment (9 s), four negative evidence variants (15 s), the verified restore of 10 databases and the media bucket (91 s), starting the eight participants and the replay to COMPLETE (115 s) and the checks. It **includes the drill-only phases**: the negative variants and the checks (about 20 s). It **excludes the image build** (1081 s in this run), done before the host loss |
| RPO (data) | 165 s | **The drill's own backup-to-loss interval** (host loss minus the backup's stamp time), **not a production RPO**: in production the interval depends on the backup schedule and when the loss happens. Everything written after the backup is lost; in this drill that is only the two later erasures, which the evidence replays |
| RPO (erasures) | 0 | Durably recorded erasures the replay did not apply: none of the three. **Relative to the trusted evidence set only** ("erasure coverage verified through sequence 4": a checkpoint plus a pending record above it). No time-based coverage and no absence of later erasures is claimed |

Conditions of the run:
- **Runner:** GitHub-hosted ubuntu-24.04 with 4 vCPU and 15.6 GiB; Docker 28.0.4, Compose 2.38.2.
- **Images:** built from the head, never pushed. MinIO and mc are the pinned GHCR images. The databases run postgres:16.4-alpine, and postgis/postgis:16-3.4 for parking and for the isolated target.
- **Data:** synthetic. Four users, one row per participant and one media object each. Three were erased: one before the backup, two after it.
- **Evidence:** a single-node off-host evidence store (MinIO with object lock), exported as a bundle by the test-scope drill tool. A production export procedure is a later owner decision.

These numbers describe the isolated drill only, at this data volume. Real volumes, a production
host or a managed database would differ, so they are not a production commitment: production
recovery stays BLOCKED, `verifiedCoverage` stays false, and Slack replay and the NR budget
(#101/#103/#104, HOLD) are not covered. Re-measure with the drill when its executable inputs
change.

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
   store. The host needs read access to the registry of the digest-pinned images (GHCR): log in
   with the recovery credential from the secrets store and run
   `scripts/registry-recovery-images.sh pull`, which pulls and verifies every pinned private
   digest (see [registry-pull-recovery.md](registry-pull-recovery.md)). Every app service is
   pinned by digest (U13), so none is built on the new host.
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
- Container images: pulled again from GHCR with the recovery credential
  ([registry-pull-recovery.md](registry-pull-recovery.md)); every app service is pinned by digest

## Exact commands

### Bad deploy (no data loss) — forward fix; the rollback is refused while every app service is pinned

```bash
cd /opt/parkio
PARKIO_ENV_FILE=docker/.env ./scripts/rollback-hosted-beta.sh --manifest deploy-artifacts/deploy-<sha>-<time>.json
```

Since U13 the production list pins every app service, so this refuses (exit 3, "image plan builds
no service") before anything changes: there is nothing to re-point. (Without `--manifest` it stops
earlier, exit 2, as a usage error.) It also refuses until a deploy made with the F-INV-3 change has
recorded its manifest, and whenever the schema gate does not pass (see the rollback schema gate
above).

Recover with a forward fix: merge the corrected pins through a reviewed pin PR, then deploy them on
parkio-civo-prod with the canonical wrapper (`scripts/parkio-prod-compose.sh up -d --no-build
--no-deps <service>`, see [gmp-release-pins.md](gmp-release-pins.md)). Committed migrations are
forward-fix only, so a deploy of earlier pins is not an accepted rollback unless schema compatibility
with the live databases is proven first.

### Non-root volume gate on a fresh DR host (R-05 / #414)

When `scripts/parkio-prod-compose.sh up` includes the Civo non-root overlay, the volume gate runs
first. On a **fresh host** every overlay volume plans as `ABSENT`; that status is allowed so the
first `up` can create and initialise volumes. `NEEDS_CHOWN` still blocks until
`scripts/nonroot-volume-migration.sh apply` (or the owner break-glass
`PARKIO_SKIP_NONROOT_VOLUME_GATE=I_ACCEPT_UNMIGRATED_VOLUMES`). See
`docs/operations/nonroot-volume-migration.md`.



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
  - let events produced after the backup reach the restored user-service, through a DLT redrive, a
    consumer offset reset, or a consumer whose committed offset is older than those events.
- **No consistency claim.** A restore from one backup set is not claimed to give a consistent
  projection. See "Backup and restore ordering (U12)" in `docs/architecture/event-contracts.md`.
- **Coordinated restore prerequisite (U12-R decision, 2026-10-12).** If a production data restore
  is ever unblocked, the user and gamification databases are restored together from the same
  backup set, whose dumps run user before gamification. No event produced between that backup and
  the restore may reach the restored user-service: not through a DLT redrive, a consumer offset
  reset, or a consumer whose committed offset is older than those events and reads them normally.
  This is the only supported way. It is a precondition, not a consistency guarantee, and no
  procedure exists to reconcile or reset user-service's projection versions.

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
- Registry read-only recovery credential `parkio-ghcr-readonly` (user, token, expiry; custody:
  repository owner; see [registry-pull-recovery.md](registry-pull-recovery.md))
