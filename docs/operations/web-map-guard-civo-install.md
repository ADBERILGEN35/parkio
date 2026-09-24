# Civo web-map-guard installation contract (#108 follow-up)

Activation on `parkio-civo-prod` is a separately authorized copy of four files.
This contract is the reviewed upgrade path from the wrapper that is actually
on the host. It is not a blanket hash-check override.

## Reviewed pre-image (host wrapper)

| Path | Required current SHA-256 | Notes |
|---|---|---|
| `scripts/parkio-prod-compose.sh` | `1df4f82641d4f524fa9db21c636ca04af060afbf3e05c8936ab7c1d4b8078ca6` | Equals committed `bf9cad51`. **Stop** if the live file differs. |

Compared against:

- package baseline `b5a86ed8` (`f8cfa8be…`): adds a text-only `up` guard that
  calls `scripts/guard-web-synthetic-map-deploy.sh` unless
  `PARKIO_SKIP_WEB_MAP_GUARD=1`. That guard file is **absent** on the host, so
  this baseline is not a viable intermediate install.
- merged `59a8e3d6` (`08492bc5…`): same compose file list, env file, optional
  `PARKIO_GMP_RECOVERY` overlay, `cd` to repo root, and argument forwarding on
  the skip path. Adds F-05 parse/bind only for invocations that can create web.

Isolated staging on the live host compose set (including
`docker-compose.waitlist-ops-inbox.yml`) showed the current wrapper and the
merged skip path render **identical** models: project `parkio`, 32 services,
matching `config --hash` for every service, waitlist inbox mount present.
The binding override does not change any service hash, including web.

Do **not** replace the wrapper solely because the checkout is old. Re-hash
immediately before copy. Any subsequent drift of the live wrapper is a stop.

## Installation set (sole source: merged #110 tree)

The four files come only from the merged #110 commit. There is no
`#108 or #110` choice.

Obtain files with `git show <merged-#110-SHA>:<path>` (LF blob bytes). Do not
use a Windows `git archive` of `*.py` (`text=auto` can rewrite CRLF). Re-hash
every byte in the staging directory; do not reuse an old `/tmp` tree.

Post-image SHA-256 is of the **git blob** (`git show <merged-#110-SHA>:<path>`),
not a Windows checkout of `text=auto` files.

| File | Action | Post-image SHA-256 | Mode after copy |
|---|---|---|---|
| `scripts/parkio-prod-compose.sh` | replace | `08492bc5ea9a8a2047ac9835212298998c042166b6ead6ae94262e7942b7e97a` | preserve live (`civo:civo` `775` observed) |
| `scripts/guard-web-synthetic-map-deploy.sh` | new | `e38a6cabdee35c78e003a22315423996db556a397259e6b5fc45dca622f37f7b` | `0644` (invoked via `bash`) |
| `scripts/lib/web-map-guard.sh` | new | `ed2d13474aa473a3341c704837bd9c90f71e9661cdb877d1246caa42f5149a68` | `0644`, owner of `scripts/lib/` |
| `scripts/lib/web_bundle_map_config.py` | new | `c02c4d4f13a2c20588f6f34cf7cdb85040b15dbd8acd433bdcba7e8acc17bf68` | `0644`, owner of `scripts/lib/` |

**Absent-file inventory (required before copy):**

- `scripts/guard-web-synthetic-map-deploy.sh` must not exist
- `scripts/lib/web-map-guard.sh` must not exist
- `scripts/lib/web_bundle_map_config.py` must not exist

If any of those three exists, stop.

`scripts/lib/deploy-common.sh` is **not** in this set. Deploy and rollback
scripts must run from a clean checkout of the merged release SHA.

The wrapper reads `docker/compose.production.files`. Host extras on that list
(including `waitlist-ops-inbox`) stay in place. This contract does not
authorize editing that list, pins, or env files.

## Backup, copy, rollback

```bash
BK=/var/backups/parkio-f05-$(date -u +%Y%m%dT%H%M%SZ)
install -d -m 0700 "$BK"
# metadata
{ hostname; date -u +%Y-%m-%dT%H:%M:%SZ; git -C /opt/parkio rev-parse HEAD;
  git -C /opt/parkio status --porcelain=v1 | wc -l; } >"$BK/HOST.txt"
stat -c '%U:%G %a %n' scripts/parkio-prod-compose.sh scripts scripts/lib \
  >"$BK/STAT-before.txt"
sha256sum scripts/parkio-prod-compose.sh >"$BK/SHA256SUMS-before.txt"
# absent-file inventory
for f in scripts/guard-web-synthetic-map-deploy.sh \
         scripts/lib/web-map-guard.sh \
         scripts/lib/web_bundle_map_config.py; do
  if [ -e "$f" ]; then echo "STOP exists $f" >&2; exit 1; fi
  echo "absent $f"
done | tee "$BK/ABSENT-before.txt"
cp -p scripts/parkio-prod-compose.sh "$BK/parkio-prod-compose.sh"
```

Copy only after the live wrapper still hashes to `1df4f826…` and the staged
four files still hash to the post-image column. Post-copy: rewrite
`SHA256SUMS-after.txt` and `STAT-after.txt`.

Rollback: `cp -p "$BK/parkio-prod-compose.sh" scripts/parkio-prod-compose.sh`
and `rm -f` the three new files. Confirm the wrapper hash returns to
`1df4f826…`. No container, image, pin or env change.

## Post-install checks (non-mutating)

- `bash -n` on the three shell files only.
- Python classifier: `python3 -c 'import ast,pathlib; ast.parse(pathlib.Path("scripts/lib/web_bundle_map_config.py").read_text())'`
  Do **not** run `bash -n` on the classifier. Do **not** use `python3 -m py_compile`
  on the production tree (it writes `.pyc`).
- `scripts/parkio-prod-compose.sh ps`
- Optional, where Compose supports it:
  `scripts/parkio-prod-compose.sh --dry-run up -d --no-build --no-deps web`

## Image identity (reporting)

On Docker 29 with the containerd snapshotter, `docker image inspect .Id` is the
**platform manifest digest**, not the OCI configuration digest. Keep these
separate:

- repository/index digest (registry; may be absent locally)
- platform manifest digest (`Descriptor.digest` / `RepoDigests`)
- image configuration digest (classic graphdriver: inspect `.Id` when it is not
  a known manifest digest; containerd: `config.digest` from the local OCI
  manifest of that same image)
- daemon image ID (inspect `.Id`)

Do not treat `.Id` as `configId` unless established. If the configuration
digest cannot be verified, the guard fails closed. Digest-pinned production
binding uses the requested `repo@sha256` reference and is unchanged.
