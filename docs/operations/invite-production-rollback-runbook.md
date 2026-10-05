# Invite-production rollback boundaries

## Pre-write rollback

Before the managed databases receive any real-user write, rollback may stop the
candidate, restore the recorded previous image manifest, return secrets to the
old database, validate the old runtime, and remove maintenance. DNS may be
returned only by the authorized DNS operator. Synthetic foundation data can be
discarded after evidence is retained.

## Post-write recovery

After the managed databases receive real-user writes, simple connection reversal
is unsafe. Do not point the application back at the old database: that loses or
forks accepted writes and can resurrect erased data.

Choose one documented incident strategy with the incident commander:

- forward-fix the managed runtime while writes remain blocked;
- reconcile a precisely bounded set of writes through an audited one-time tool;
- or restore Azure PITR to a new server, validate, replay the erasure ledger, and
  switch forward to that server.

There are no dual writes. Every accepted account erasure is irreversible across
restore/rollback: tombstones are replayed before login or traffic is enabled.
Record timestamps, affected IDs/counts, backup/PITR reference, decision owner,
and final integrity proof in the incident record.

The repository rollback script handles only image/config rollback to a recorded
manifest. It must refuse to claim data rollback and does not reverse migrations
or erasure semantics.

### Running the image/config rollback from GitHub Actions (U13 CL-F06)

A rollback runs as a new workflow run, so it has to fetch the manifest from the
earlier deploy run.

1. Open the job summary of the deploy run you want to return to. Its deploy job
   prints a "Rollback reference" section, and only when the deploy succeeded:
   `RUN_ID/invite-production-manifest-<sha>@<manifest SHA-256>`.
2. Dispatch `invite-production-deploy.yml` on `api` with `action=rollback`, the
   current api SHA as `git_sha`, and that reference as `manifest_artifact`.

A deploy run from before this change prints no reference. For one of those,
build `RUN_ID/invite-production-manifest-<sha>` by hand; without `@SHA256` the
content pin is skipped, and every other check below still applies.

The rollback job then works in three steps:

- **Resolve** (`scripts/ci/resolve-rollback-manifest-run.sh`). It refuses any
  reference that is not a successful `workflow_dispatch` run of this workflow on
  `api` whose `Deploy invite-production` job succeeded and which holds exactly
  that unexpired artifact. A build-only run (deploy skipped) is refused.
- **Download.** It downloads the artifact by the id the resolver checked.
- **Verify** (`scripts/ci/verify-rollback-manifest.sh`). The manifest must have
  `schemaVersion` 1 and profile `invite-production`, a `gitSha` equal to that
  run's commit, and image references in its `imageTag`. With `@SHA256` in the
  reference, the file must also have that SHA-256.

Only then does it run the rollback. Manifest artifacts are kept for 90 days, the
maximum for a public repository, so a rollback can only return to an api deploy
from that window. `rollback-manifest-acceptance.yml` has the same precondition:
it fails closed when no such deploy exists, and warns two weeks before its source
manifest expires.
`hosted-beta-deploy.yml` (deprecated path) works the same way, with the
`deploy-manifest-live-<sha>` artifact of its `Deploy (self-hosted beta)` job.

### Compose file list (F-INV-2, owner decision 2026-10-05)

The rollback renders this checkout's compose file list against the target's
staged release (`PARKIO_COMPOSE_BASE_DIR`). It therefore refuses, with exit 3,
before it writes, activates or starts anything (dry runs included), when the
target manifest's `composeFiles` is:

- absent or malformed;
- different from the list it would render, including the same files in another order.

The message starts with `compose file list changed (...)` and names every added
and removed file. Its causes:

- **The list changed in the source**, for example when F-INV-1 added
  `docker-compose.auth-registration-env.yml`.
- **The env selects another edge mode or ACME setting** than the target deploy
  ran with: the dispatch inputs `invite_edge_mode` and `invite_acme_authorized`.

No file is left out silently, and there is no override.

To return to such a release, either:

- run the rollback from a checkout whose list matches, with the matching edge
  inputs; or
- deploy a compatible release.

A live rollback also refuses, before it activates the target release, when a
listed file is missing from that release.

**Rollback compatibility-guard acceptance.** `rollback-manifest-acceptance.yml`
checks this guard and performs no rollback. It dry-runs the guard twice:

- **Against a synthetic deploy manifest** that this checkout's writer produced
  with this checkout's list. That dry run must succeed.
- **Against the manifest of the newest qualifying api deploy run found in the API listings**, with that deploy's edge mode. The run is chosen by creation time: its deploy job succeeded, and it holds exactly one unexpired manifest. The candidates come from two complete listings, with and without the API's success filter, because the API sometimes serves a stale page. The listings can still lag, so the run is not guaranteed to be the most recent deploy. A newer run with any other artifact listing fails the check instead of being passed over.
  A compatible list must dry-run successfully. An incompatible one passes only
  when the guard itself refuses it, naming the files. The job summary then says
  "rollback to the newest qualifying deploy run found in the API listings at
  <time> (<run id>, commit …, created …) is NOT currently possible: compose file
  list changed (...); a post-change deploy is required."

**A green result does not mean that a rollback to the latest deployed release
currently works.** Operational rollback acceptance remains BLOCKED until an
authorized compatible release exists.

## Dark acceptance endpoint and backup scheduler (PROD-DEPLOY-01A-R3)

Two rollbacks are independent of image/config rollback and of each other.

**Dark gateway endpoint (D1).** Drop
`docker/docker-compose.invite-dark.yml` from the invite-production compose set in
`scripts/lib/deploy-common.sh` and redeploy. `gateway-service` returns to its
unpublished state, reachable only as `gateway-service:8080` inside the Docker
network, and Caddy is again the sole entrypoint. Dark acceptance is unavailable
after this — that is the intended trade, not a regression. The public boundary is
untouched either way: the overlay only ever binds loopback.

**Backup scheduler (D2).**

```bash
sudo scripts/azure/install-invite-production-backup-scheduler.sh --disable
```

This stops the service if it is mid-run and disables the timer. It deliberately
leaves the dedicated payload at `/opt/parkio/invite-production-backup` in place
so the previous revision stays auditable,
and it never touches `/var/backups/parkio` or the offsite container — valid
encrypted backups are never deleted by a rollback. To go back to an earlier
payload revision, re-run the installer from that revision's checkout; `VERSION`
records which revision is currently installed.

The application runtime root `/opt/parkio/invite-production` is not scheduler
state. Backup disable, upgrade, or rollback must never rename, replace, or delete
its `current/`, `releases/`, or `acceptance/` paths.

Rollback must never recreate a persistent plaintext production env. If one is
found at `/opt/parkio/docker/.env.invite-production`, it is residue from the
pre-R3 model: shred it. The installer refuses to run while it exists.
