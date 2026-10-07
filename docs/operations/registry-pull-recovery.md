# Registry pull recovery (U14, CL-N03)

Production images live in the private GitHub Container Registry (`ghcr.io/adberilgen35/parkio`).
After a host loss, a new host can start the pinned production set only if it can pull those
digests. This document is the inventory of what it must pull, the credential it uses, who holds
and renews that credential, and the proof that the credential alone is enough.

It does not cover the host's own deploy credential, a registry mirror, or GitHub being down.

## What a new host must pull

The inventory is derived, not maintained by hand:

```bash
scripts/registry-recovery-images.sh list
```

It reads `docker/compose.production.files`, takes every `image:` line that pins a
`ghcr.io/adberilgen35/parkio` digest (including the `${VAR:-default}` defaults of the base
compose file), adds `MINIO_IMAGE` and `MINIO_MC_IMAGE` from `docker/.env.example`, and refuses
(exit 3) if a pin file names an image without a digest. On the current `api` it lists seven
images:

| Image | Role | Pinned by |
|---|---|---|
| `auth-service@sha256:…` | auth | `docker/docker-compose.auth-release-pin.yml` |
| `web@sha256:…` | web SPA | `docker/docker-compose.web-release-pin.yml` |
| `gateway-service@sha256:…`, `media-service@sha256:…`, `parking-service@sha256:…` | GMP services | `docker/docker-compose.gmp-release-pins.yml` |
| `minio@sha256:…`, `mc@sha256:…` | object storage and its client (also used by backup and restore scripts) | `docker/docker-compose.yml` defaults and `docker/.env.example` |

Not in the inventory, on purpose:

- **user, moderation, gamification, notification, analytics and ai-validation** are not pulled.
  The deployment wrapper builds them from the checkout at deploy time (`parkio/<service>:${PARKIO_IMAGE_TAG}`).
  A fresh host needs the source at the pinned SHA and a build, not a registry pull. Giving them
  digest pins is the U13 "six app services" task; once they are pinned, the inventory grows by
  itself.
- `fluent-bit-nr` belongs to the optional New Relic log path, which is not in the production set.
- Rollback digests quoted in pin-file comments. Rolling back to an older pin is a deploy from
  that pin's file, which then puts the digest into this inventory.

## The recovery credential

| Item | Value |
|---|---|
| Name | `parkio-ghcr-readonly` |
| Type | A GitHub token whose only scope is `read:packages` (a classic personal access token with that single scope, or a fine-grained token if GitHub grants it package read access when it is created; check at creation and do not add any other scope). Pulls through it are read-only by construction: it can neither push nor delete a package |
| Issued by | the repository owner, on an account that has read access to the `ADBERILGEN35/parkio` packages |
| Expiry | 90 days, set at creation; GitHub refuses the token afterwards even if it is never rotated |
| Custody | the DR secrets store that already holds `BACKUP_ENCRYPT_PASSPHRASE` and the offsite `mc` credentials (see the DR runbook's "Contacts / secrets"), as one entry with the user name, the token and the expiry date. Role: repository owner. It is never written into git, `docker/.env.example`, a workflow file or a ticket |
| CI copy | repository secrets `PARKIO_REGISTRY_READONLY_USER` and `PARKIO_REGISTRY_READONLY_TOKEN`, used only by the **Registry pull recovery** workflow |
| Verified by | the same workflow, dispatched after every creation or rotation |

Why not `GITHUB_TOKEN`: it exists only inside a workflow run and cannot be handed to a host.
Why not the live host's own credential: it dies with the host.

### Creating or rotating it

1. Create the new token with only `read:packages`, expiry 90 days. Record the expiry date.
2. Put the user name, token and expiry into the DR secrets store entry.
3. Update the two repository secrets (`PARKIO_REGISTRY_READONLY_USER`, `PARKIO_REGISTRY_READONLY_TOKEN`).
4. Dispatch **Registry pull recovery** (`gh workflow run registry-pull-recovery.yml --ref api`).
   The `custody-credential-pull` job must pull and verify every inventory digest with that
   credential alone. Keep the run link as the dated proof.
5. Revoke the previous token in GitHub only after step 4 is green.
6. Put the next rotation in the calendar at least two weeks before the expiry.

A token that fails step 4 is not a recovery credential; fix or recreate it before revoking the
old one.

### Revoking it

Revoke it in GitHub (the owner's token settings). Any host that still relies on it stops being
able to pull; create and verify a replacement first unless the token is believed compromised,
in which case revoke immediately and then rotate.

## Fresh-host procedure

On the new host, before the "Full host rebuild" steps of the
[disaster recovery runbook](disaster-recovery-runbook.md) start containers:

```bash
git clone https://github.com/ADBERILGEN35/parkio.git /opt/parkio && cd /opt/parkio
git checkout <release SHA>                     # the pins of that release are the inventory
umask 077 && printf '%s' '<token>' > /root/.parkio-ghcr-token   # from the secrets store, never on a command line
docker login ghcr.io -u '<user>' --password-stdin < /root/.parkio-ghcr-token
shred -u /root/.parkio-ghcr-token
scripts/registry-recovery-images.sh pull       # every pin pulled, digest and linux/amd64 verified
docker logout ghcr.io                          # the deployment wrapper does its own login if it needs one
```

`pull` prints one line per image and a final `N/N images pulled with the pinned digest on
linux/amd64` line; any failure makes it exit non-zero. It never prints the credential.

## Proof in CI

Workflow `.github/workflows/registry-pull-recovery.yml` has no `packages` permission at all:

| Job | Runs on | Proves |
|---|---|---|
| `script-tests` | pull requests touching the pins, the script or this document; dispatch | the inventory and the pull check behave (fixtures and a fake docker; mutable pins refused; wrong digest or platform refused; `--expect-denied` semantics) and prints the current inventory |
| `scope-less-token-denied` | same | a `GITHUB_TOKEN` without `read:packages` cannot pull any inventory image (`pull --expect-denied` passes only when every pull is refused) |
| `custody-credential-pull` | `workflow_dispatch` only | a fresh `ubuntu-latest` runner, logged in with nothing but the recovery credential, pulls every inventory digest and verifies digest and platform. Without the two secrets it stops with `BLOCKED — RECOVERY REGISTRY CREDENTIAL NOT CONFIGURED` (exit 2) |

The dated acceptance for this document is a green `custody-credential-pull` run after the
credential was created. Its run id, the inventory it pulled and the custody owner (role) go into
the task evidence; the token never does.

## Limits

- A green run proves that the credential and the inventory work on that day. It does not prove
  GHCR availability during a GitHub outage, and there is no mirror.
- The six host-built services need source and a build on the new host; see the U13 pin task.
- The live host's deploy credential, and whether it is read-only, are outside this document.
