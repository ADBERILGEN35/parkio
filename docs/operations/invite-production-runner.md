# Invite-production self-hosted runner

This runner is a repository-scoped execution boundary for the isolated
`invite-production` VM. It is not a shared CI runner and must never execute
untrusted pull-request code.

## Threat model and trust boundary

The runner executes repository code with Docker access, VM managed-identity
access, Key Vault metadata/secret-read access, and private network reachability
to managed PostgreSQL. Membership in the Docker group is effectively privileged
on this dedicated VM and is justified only because the approved deploy path
builds and starts Compose workloads. The VM must host no unrelated workload or
runner.

Only manual workflows dispatched from `refs/heads/api` at the exact current SHA
may target `parkio-invite-production`. Pull-request jobs run only on GitHub-hosted
runners. The production jobs use the `invite-production` GitHub environment,
repository-level runner registration, least-privilege `contents: read`, and a
single concurrency lock. Fork or arbitrary-ref code must never reach the runner.

## Host and account

- host: `vm-parkio-invite-prod`
- runner name: `parkio-invite-prod-01`
- repository scope: `ADBERILGEN35/parkio` only
- account: system user `parkio-runner`, locked password, shell
  `/usr/sbin/nologin`
- installation: `/opt/actions-runner/parkio-invite-production`
- work directory: runner-local `_work`; each job checks out into a unique
  `source-<run>-<attempt>` child and removes that child in an `always()` step
- labels: automatic `self-hosted`, `Linux`, `X64`, plus
  `parkio-invite-production`

The account has no sudo grant. It belongs to the Docker group solely for the
future separately authorized deploy job. It cannot read unrelated operator home
directories through any Parkio-created permission.

## Installation and registration

`scripts/azure/install-invite-production-runner.sh` pins the official GitHub
Actions runner Linux x64 package and published SHA-256 digest. Installation
downloads only from `github.com/actions/runner/releases`, verifies the digest,
and deletes the archive.

Registration uses a repository-level, one-hour token obtained through the
authenticated GitHub REST API. It is passed to the installer in memory, never
stored in Git, an env file, shell history, or runner config as the original
registration token, and is unset immediately after configuration. The runner's
normal GitHub credential is managed by the official runner application.

The official `svc.sh` installs the systemd unit. A drop-in enforces restart,
private `/tmp`, `NoNewPrivileges`, protected home directories, and umask 0077.
The runner opens no listening port; it establishes outbound HTTPS connections
to GitHub. Azure NSG ingress remains unchanged.

## Azure and Key Vault access

Jobs use the VM system-assigned managed identity with a per-job
`AZURE_CONFIG_DIR` under `/dev/shm`. No Azure client secret or OIDC credential is
stored on the runner. The managed identity is preferable to GitHub-to-Azure OIDC
here because the runner is itself the bound Azure VM and already has the narrow
production resource roles.

Live deploy jobs may materialize the ignored environment only under
`/dev/shm/parkio-invite-production-<run>-<attempt>.env`, mode 0600. It is never
uploaded. Logs are quarantined under `/dev/shm` until checked against the live
env values without printing them. Cleanup removes env, Azure CLI cache, log, and
the unique source directory on success or failure.

## Artifact classification

| Path or output | Classification | Rationale |
|---|---|---|
| legacy `compose-config.rendered.yml` | **SECRET-BEARING / forbidden** | resolved container environment values |
| `compose-structure.json` | **REDACTED** | names, images, ports, dependencies, and healthcheck metadata only |
| `deploy-*.json` / `current.json` | **SAFE** | SHA, image metadata, flags, DB hostname, rollback metadata, sanitized structure |
| rendered production env | **SECRET-BEARING / ephemeral** | mode 0600 in tmpfs, never uploaded, always deleted |
| quarantined deploy/rollback log | **UNKNOWN until scanned** | released to job log only after value-based scan passes |
| smoke log | **SAFE after scan** | status summaries only; response/token temp files are trapped and removed |
| workflow artifact upload | **SAFE** | explicit JSON allowlist; no directory-wide env/log upload |
| workflow output/summary | **SAFE** | SHA and non-secret status only; no env dump or debug trace |

`docker compose config --quiet` performs validation. The resolved JSON model is
piped directly through `sanitize-compose-config.mjs`; the unsanitized model is
never written to the workspace, artifacts, or logs.

## Acceptance and stop boundary

The manual runner-acceptance workflow verifies the exact `api` SHA, dedicated
user/host, Docker, VM managed identity, required Key Vault names only, private
PostgreSQL DNS, and certificate-validated PostgreSQL TLS negotiation. It does
not render the live env, start containers, authenticate to a database, or run
Flyway.

Successful runner acceptance does not authorize dark-runtime dispatch. The
deploy action remains a separate operator-approved PROD-DEPLOY-01A gate.
