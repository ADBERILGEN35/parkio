# Private GHCR linux/amd64 MinIO pins

Upstream attribution: MinIO is AGPLv3. The server image license extracted from
layer `sha256:7eb10b7b511b26d3be66c93054882f00f38464bebba2026a18fed0d37af6a3f0`
is at `docs/third-party/minio/LICENSE`. CREDITS live in layer
`sha256:de7c323e2901c22ab0e3c4829895cfd314a947aa379b71c58957ba809cbb9f16`
(`licenses/CREDITS`). Official sources:
https://github.com/minio/minio and https://github.com/minio/mc.

This is an **index-to-amd64 pin**, not preservation of the Quay multi-platform
index.

| Role | Upstream index (Quay pin) | Published GHCR platform manifest |
| --- | --- | --- |
| server | `sha256:cd04ea408e185cb50076ea1c3988d444119b19aaae15aab45387ccf14b2a2f86` | `ghcr.io/adberilgen35/parkio/minio@sha256:efba309ba4dc89e48f37304db52a0b854c0e701ba944ca02205c4e292c1a756c` |
| mc | `sha256:a5399b66b88543efac8afb08eb2bdcce5904e548ea6fe1a921600cd74f766668` | `ghcr.io/adberilgen35/parkio/mc@sha256:456b1e641897329fc9491f9bc8b31df351d728af9a328bf5653707af62d0d6bf` |

Copy used the preserved local OCI extract (registry HTTP blob upload + exact
platform-manifest PUT). It did not use `crane` against the Docker daemon, did
not pull Quay again, and did not rebuild.

Architecture: hosted-beta ARM64 is **not verified**. Defaults pin linux/amd64.
Local non-amd64 development overrides `MINIO_IMAGE` / `MINIO_MC_IMAGE`. Compose
does not set `platform: linux/amd64`.

Authentication: same-repository PRs, `push`, `schedule`, and `workflow_dispatch`
log in with `GITHUB_TOKEN` and `packages: read` before any compose/Testcontainers
pull. Fork PRs fail closed (explicit error, not a hidden skip).
`pull_request_target` is not used.

Remaining access decision: GHCR user packages were published with a user token
and remain **private and unlinked** (`repository: null`). REST/GraphQL “connect
repository / inherit access” endpoints returned 404 for this credential.
`GITHUB_TOKEN` inside `ADBERILGEN35/parkio` workflows can read the packages only
after an operator uses the package Settings → **Connect repository** →
`ADBERILGEN35/parkio` (Actions read). Packages stay private. Do not make them
public.

The isolated offsite fixture image
`quay.io/minio/minio@sha256:7d80fd232a2f7108aa6f133fcfe5fade3f1626d92d31ae1318076e7aa61928a2`
is unchanged (different digest, not this republication).
