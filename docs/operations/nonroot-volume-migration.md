# Non-root volume migration (CL-F29.3, owner decision B8)

The root-start services keep starting as root because their data volumes are owned by root or by
an image user (`container-hardening-inventory.md`, exception 3). To run such a service with a fixed
non-root `user:`, its volumes must first be owned by that uid:gid.

`scripts/nonroot-volume-migration.sh` prepares that change. Owner decision B8 authorizes the
tooling only. It has not been run against any live host, and running it there needs its own
authorization, a maintenance window and a backup.

## What it does

```
scripts/nonroot-volume-migration.sh plan    --project P [--service S]...
scripts/nonroot-volume-migration.sh apply   --project P --service S... --confirm-project P --evidence-dir D
scripts/nonroot-volume-migration.sh restore --project P --service S... --confirm-project P --evidence-dir D
```

- **plan** is read-only. It prints one line per volume: target, readiness, owner histogram and
  `COMPLIANT`, `NEEDS_CHOWN` or `ABSENT`.
- **apply** works through every selected volume in two passes.
  - The first pass checks them all, so a refusal never leaves a service half migrated.
  - The second pass then, for each volume:
    - writes `<volume>.before.manifest` (uid, gid, mode and path of every entry) and its `.sha256`;
    - runs `chown -R -h` to the target owner;
    - verifies that every path now has the target owner.
- **restore** reads that manifest after checking its checksum.
  - Paths the manifest lists get their recorded owner and mode back. Modes come second, because
    `chown` clears set-id bits.
  - Paths created since apply get the volume root's recorded owner.
  - Paths deleted since apply are skipped and counted.
  - It then verifies the result and reports `missing=` and `new=`.

**Volumes** are found by their Compose labels (`com.docker.compose.project`, `.volume`), never by
name.

**Helper container.** Every step runs in a throwaway busybox container, pinned by digest, with:
- no network and a read-only root;
- `cap_drop ALL`, and only `DAC_READ_SEARCH`, plus `CHOWN` and `FOWNER` when it writes.

**apply and restore refuse:**
- a missing or different `--confirm-project`;
- a volume that a running container mounts;
- a map row marked `blocked`;
- a path containing a newline, because the manifest is line based.

apply also refuses an evidence directory that already holds a manifest for the volume. A second
apply would otherwise record the migrated owners as the originals.

Exit codes: 0 done, 1 verification failed, 2 usage, 3 refused.

The manifests list every path in the volume, for example MinIO object keys, so store them as
restricted evidence.

## Targets (`scripts/lib/nonroot-volume-map.tsv`)

| Service | Volumes | Target | Readiness | Compose change once migrated |
|---|---|---|---|---|
| redis | redis-data | 999:1000 (`redis` in redis:7-alpine) | ready | run `redis-server` as `user: "999:1000"`; drop `cap_add: [DAC_OVERRIDE]` |
| minio | minio-data | 10001:10001 (no user in the image) | ready | `user: "10001:10001"`; no capabilities |
| caddy | caddy-data, caddy-config | 10001:10001 (no user in the image) | ready | `user: "10001:10001"` and **keep** `cap_add: [NET_BIND_SERVICE]` (below) |
| postgres-* except parking | postgres-*-data | 70:70 (`postgres` in postgres:16-alpine) | ready | `user: "70:70"`; drop CHOWN, DAC_OVERRIDE, FOWNER, SETGID, SETUID |
| postgres-parking | postgres-parking-data | 999:999 (`postgres` in postgis/postgis:16-3.4) | ready | as above, with 999:999 |
| clamav | clamav-data | 100:101 | blocked | its init needs root and a writable root filesystem (B8 exception) |
| promtail | promtail-positions | 10001:10001 | blocked | reads the Docker socket as root; see `docs/architecture/docker-socket-proxy-design.md` |

**Services with no volume to migrate.** minio-setup (mc keeps its config in `/tmp/.mc`) and
blackbox-exporter need only the `user:` change. web needs an unprivileged nginx image or
configuration, which is outside this tool.

**PostgreSQL volumes are normally compliant already.** The root-start entrypoint chowns `PGDATA`
to `postgres` before it drops privileges, so plan reports `COMPLIANT` for volumes it created.

**Caddy keeps `NET_BIND_SERVICE` for its exec, not for its bind.**
- `/usr/bin/caddy` carries the file capability `cap_net_bind_service=ep`. When that capability is
  missing from the bounding set, the kernel refuses to exec the binary ("operation not permitted").
- The bind itself needs no capability: Docker sets `net.ipv4.ip_unprivileged_port_start=0` in every
  container network namespace (Docker 29.8.1 here).

## Procedure (when a migration is authorized)

1. `plan` for the service, and keep the output.
2. Take a backup as the backup runbooks describe.
3. Stop the service and everything that mounts its volumes (`docker compose stop <service>`). Do
   not start it again until step 6. The running-container check cannot see a start that happens
   during apply.
4. `apply` with `--confirm-project` and a new `--evidence-dir`, then keep the directory with the
   change record.
5. Deploy the reviewed Compose change: `user:`, and the capabilities from the table above.
6. Start the service and check readiness and one write.

**Rollback.**
1. Stop the service.
2. Run `restore` from the same evidence directory.
3. Deploy the previous Compose file.
4. Start the service.

restore gives files created while the service ran non-root to the volume root's original owner,
so the root-start service can use them again.

## Validation

`scripts/test-nonroot-volume-migration.sh` runs against disposable volumes labelled with a random
project. It covers:
- plan output;
- every refusal;
- a refusal on a service's second volume, which leaves the first untouched;
- apply on two volumes, including a symlink to a path on the helper's read-only root;
- the manifest checksum;
- restore of mixed owners and a set-uid bit;
- restore after files were added and deleted;
- a second restore;
- a restore that cannot match, which must report it.

Local evidence is in `agent-tools/parkio-u18-nonroot-tooling/` (not committed).
- **Test suite:** 22/22.
- **Mutants:** 10 of 11 were killed. The survivor removes `-h`, which is equivalent: `chown -R`
  already defaults to `-P` in busybox and coreutils. A variant that traverses symlinks (`-R -L`) is
  killed.
- **Real images**, on disposable volumes created the way the root-start services create them:
  - redis: fails to start as non-root before apply ("Permission denied" on its AOF) and runs after
    it with no capabilities.
  - MinIO: fails before apply ("file access denied") and serves the old object and a new one after
    it. restore then gives the two new files to root.
  - caddy: logs "permission denied" on `/data/caddy/instance.uuid` before apply and serves without
    errors after it.
  - postgres:16-alpine: already compliant, and runs as 70:70 with no capabilities.
  - Every restore verified.
