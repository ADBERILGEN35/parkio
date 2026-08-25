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

## Stable runtime root and permission model

The runner service is hardened with `UMask=0077`, so everything
`actions/checkout` writes into `_work` is mode `0600` and every directory it
creates is `0700`. Containers that drop to a non-root UID cannot read those
files. That is the correct hardening, and it is not relaxed.

The consequence is that the long-lived runtime must not be served out of the
workspace at all. Non-secret runtime configuration is staged into an immutable,
SHA-addressed release under a stable root that is provisioned once, as root, by
`scripts/azure/install-invite-production-runtime-root.sh`:

```
/opt/parkio                                    parkioops   0755  (traversal only)
/opt/parkio/certs                              parkioops   0750  (unchanged)
/opt/parkio/deploy-artifacts                   parkioops   0750  (unchanged)
/opt/parkio/invite-production                  parkio-runner 0755
/opt/parkio/invite-production/releases/<sha>/  parkio-runner 0755  dirs
    docker/**                                                0644  files
    docker/**/*.sh                                           0755  scripts
/opt/parkio/invite-production/current -> releases/<sha>
/opt/parkio/invite-production-backup           root         0750
    VERSION, MANIFEST.sha256, docker/, scripts/              audited payload
/var/backups/parkio                            root         0700  backup data
```

- `/opt/parkio` is `0755` so non-root container UIDs can traverse to a release.
  Only the directory listing is exposed; the secret-bearing children keep `0750`
  and the provisioning script re-asserts that on every run.
- Release modes are set explicitly with `install`, never inherited from the
  umask, so the runner's `0077` cannot silently re-tighten a file a container
  must read.
- Releases contain no env material. `.env*` is excluded at staging time and the
  absence is asserted; secrets stay in the per-job `/dev/shm` env file and are
  interpolated by Compose at `up` time.
- The deploy runner still has no sudo. It can write releases because the root
  was pre-created with runner ownership — it can never create or re-permission
  the root itself.
- The root-owned scheduler payload is structurally separate from both the
  application release root and backup data. Its installer must never rename or
  remove `/opt/parkio/invite-production`, and scheduler upgrades never operate
  on `/var/backups/parkio`.

Releases are immutable: staging an existing SHA is a no-op rather than a
rebuild, because running containers bind-mount directly into the release tree.
Retention keeps the most recent releases and never reclaims the active release,
the release named by `current`, or one still mounted by a running container.

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

The host does not provide a mutable user-profile Node installation. Every
invite-production workflow job provisions the exact repository version from
`.node-version` with `actions/setup-node@v4`, then runs
`verify-invite-production-toolchain.sh` before any secret materialization or
runtime action. The verifier rejects a missing or different Node version and
checks the remaining deploy executables plus Docker/Compose availability. The
GitHub-hosted dry-run uses the same `.node-version` contract. No npm dependency
cache is configured, so the shared runner tool cache contains the pinned Node
distribution only and cannot ingest production env or job output.

Because the self-hosted jobs check out into `source-<run>-<attempt>` rather
than the workspace root, action inputs that name that directory must stay
workspace-relative. `actions/setup-node` resolves `node-version-file` with
`path.join(GITHUB_WORKSPACE, input)`, which concatenates instead of letting an
absolute input win, so an absolute path produces a duplicated workspace prefix
and the step fails before any toolchain check. The jobs therefore publish
`PARKIO_SOURCE_PATH` (workspace-relative) for `actions/checkout`,
`actions/setup-node`, and `actions/download-artifact`, and keep the absolute
`PARKIO_SOURCE_DIR` for shell `working-directory` and cleanup, which resolve
absolute paths correctly. `test_invite_production_node_runtime.py` pins both
halves of that contract against the real runner workspace.

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
user/host, exact repository-pinned Node runtime, the complete deploy toolchain,
Docker, VM managed identity, required Key Vault names only, private PostgreSQL
DNS, and certificate-validated PostgreSQL TLS negotiation. It does not render
the live env, start containers, authenticate to a database, or run Flyway.

Successful runner acceptance does not authorize dark-runtime dispatch. The
deploy action remains a separate operator-approved PROD-DEPLOY-01A gate.
