# MinIO GHCR publication package (NOT AUTHORIZED)

Status: preparation complete. Publication, visibility changes, and new credentials are **not** authorized by the current instruction. Remaining operator action is one reviewed authorization to copy already-local linux/amd64 platform content to GHCR and retarget pins.

Baseline pins in compose/CI/Testcontainers (99497e51 / #113 head 334c56f6):

| Image | Current pin | What the digest is |
| --- | --- | --- |
| server | quay.io/minio/minio@sha256:cd04ea408e185cb50076ea1c3988d444119b19aaae15aab45387ccf14b2a2f86 | **OCI image index** (pplication/vnd.oci.image.index.v1+json, 3117 bytes) |
| mc | quay.io/minio/mc@sha256:a5399b66b88543efac8afb08eb2bdcce5904e548ea6fe1a921600cd74f766668 | **OCI image index** (pplication/vnd.oci.image.index.v1+json, 3117 bytes) |

Do **not** claim exact-index preservation. Local docker save contains only **linux/amd64** platform content plus the amd64 in-toto attestation. arm64, ppc64le, s390x platform manifests and their attestations are **missing**. This package is a **reviewed linux/amd64 pin change**.

## 1. Source evidence binding local content to the pins

docker inspect on this host:

- minio/minio:RELEASE.2024-09-13T20-26-02Z Id = sha256:cd04ea408e185cb50076ea1c3988d444119b19aaae15aab45387ccf14b2a2f86
- RepoDigests: minio/minio@sha256:cd04ea40… and quay.io/minio/minio@sha256:cd04ea40…
- MediaType reported: pplication/vnd.oci.image.index.v1+json
- Architecture/OS of the runnable image: amd64 / linux

- minio/mc:RELEASE.2024-09-16T17-43-14Z Id = sha256:a5399b66b88543efac8afb08eb2bdcce5904e548ea6fe1a921600cd74f766668
- RepoDigests: minio/mc@sha256:a5399b66… and quay.io/minio/mc@sha256:a5399b66…
- MediaType reported: pplication/vnd.oci.image.index.v1+json

docker save OCI index.json for each image lists the **same pin digest** as pplication/vnd.oci.image.index.v1+json. The blob at lobs/sha256/<pin> is that index (size 3117). This is stronger than a tag, a config digest, or a runnable container.

Public anonymous pulls of the Quay/Hub digest refs still fail (401 / insufficient_scope). That is why CI is BLOCKED.

## 2. Index contents vs locally available blobs

### Server index sha256:cd04ea408e185cb50076ea1c3988d444119b19aaae15aab45387ccf14b2a2f86

| Descriptor | Digest | Local? |
| --- | --- | --- |
| linux/arm64 manifest | sha256:e59adda0d74f6baf7a53643ddf3c5688d1eb643190000360f27bf0bdc22cff42 | MISSING |
| **linux/amd64 manifest** | sha256:efba309ba4dc89e48f37304db52a0b854c0e701ba944ca02205c4e292c1a756c (2011 B) | PRESENT |
| linux/ppc64le manifest | sha256:8918862e9b16d109aa379542697f534db47dc6e2cb5c54528571b013ea654c16 | MISSING |
| linux/s390x manifest | sha256:8a227d56b7acaff080b85f28845daf63778b0ad9f4deae79ba4f281546a3327d | MISSING |
| attestation → arm64 | sha256:ae116b3e5d36e8a738d767dcdeb566b8f4d5b564b6846dfda18a8a7c624651b8 | MISSING |
| attestation → amd64 | sha256:cebb349a169150166be604e1a1b2b4769dbb9cb511063025113b6b8d17b0c05f | PRESENT |
| attestation → ppc64le | sha256:0596bd907aa95c6cf59805efa2f72b19b576fea54c5e95b28907b42ac62f4f31 | MISSING |
| attestation → s390x | sha256:886957de471e1e3f8e0396979875c22d65ee8ef08e1cd1180e1593e4aa65bc58 | MISSING |

linux/amd64 platform manifest efba309b…:

- config pplication/vnd.oci.image.config.v1+json sha256:f70d57b0cb059cfafc12b4bae7de27a873c02d94b9241458519aca0873ace820 (8458 B)
- layers (tar+gzip):
  1. sha256:55360c0b72d681ef9173ea809fdba2f8b7cfcc396d99531d43d8d927cbf7387f 7285285
  2. sha256:f2f8f30a646af9b050a5a82bb5f3a6c1ce08a422ab0a31194b1bb221f14f0783 1638810
  3. sha256:4fc5fcb7e9ca0cab8874770d3f6518aa4c3e9a77e22f3bd0e74ff4e33c5a654c 126708
  4. sha256:53140b7a7f04040ffbca073178ddcbdc207aea7a146e6e03fdca91701eeb1749 37361696
  5. sha256:2b8c182be72c7d142e6e4643401b98f79c92e4c8a491397d8854d871518c4b9c 10064477
  6. sha256:4363be478988c0ad60043bf5d3907f3e3330e6d463ff469dc30a840249b75af3 2368483
  7. sha256:de7c323e2901c22ab0e3c4829895cfd314a947aa379b71c58957ba809cbb9f16 183927
  8. sha256:7eb10b7b511b26d3be66c93054882f00f38464bebba2026a18fed0d37af6a3f0 11876
  9. sha256:3d82d69301e257ce188bd95e41062478300e12e996239a7d2f0e4ae8d984ed41 499

amd64 attestation: config sha256:6bd0bb629e3a82dd55320d5da431011f8a05824ba696a13c1bebe57f1014fc6f; in-toto layer sha256:c4d65ca8e98225940e679f5f9280ccddf206d0fe24a049c4de8d2335ca91a915.

### mc index sha256:a5399b66b88543efac8afb08eb2bdcce5904e548ea6fe1a921600cd74f766668

| Descriptor | Digest | Local? |
| --- | --- | --- |
| linux/arm64 manifest | sha256:81b761da153921f52ef0c67a7c11235ad12bec3a26a1a73a31fef763f3b69ba8 | MISSING |
| **linux/amd64 manifest** | sha256:456b1e641897329fc9491f9bc8b31df351d728af9a328bf5653707af62d0d6bf (1438 B) | PRESENT |
| linux/ppc64le manifest | sha256:5ba7279e1d6b967995cd02bea6441305d4cf513c27da107b5da39b5ddde57d16 | MISSING |
| linux/s390x manifest | sha256:570b1740de80c13e477cda531f6b46692d9b6bf1125c8f19cc2bc32596fe6978 | MISSING |
| attestation → arm64 | sha256:cccf995583c468236b6d418c7ea70c6f945d497c43ab29bda046210586aada43 | MISSING |
| attestation → amd64 | sha256:a6856c1dc3e0354a8c4d8482db1bd7783375f4fd269207ad41f0cd9ae34b057b | PRESENT |
| attestation → ppc64le | sha256:e606c67755ae48544cf75f829ba1b2e0fb74438827744cbef81794e39186a738 | MISSING |
| attestation → s390x | sha256:e4dd813a6fceb6368461c41e16ebaf5743fa9722bb4890b3aadb05a2a89add6e | MISSING |

linux/amd64 platform manifest 456b1e64…:

- config sha256:40940f5a34d9b25a08a67360f3bf68e0b1783458c090be1c267ca34f1982470a (5852 B)
- layers:
  1. sha256:55360c0b72d681ef9173ea809fdba2f8b7cfcc396d99531d43d8d927cbf7387f 7285285 (shared MinIO base)
  2. sha256:020556cb2740106304ae605278b76a96318cc49c43bde8d4ab80b5ac99ae2afd 129919
  3. sha256:5f9665cb7cf02096c1fd125f3e4ce791648579b614d78f35a00edd2c6061095a 68830
  4. sha256:2e47722e1257f8d8ae8e26d1160d9f875698b2025bcfb43109398802b64b7c84 11874
  5. sha256:ee70e41b4c954ada38a5ff32a070ebe9f0cf4a0cdc683e9566ca47f9dddbf971 10066498
  6. sha256:d77f5efe883733f5e7c5010b3bfca3bde72926ade3a9dc71bb339a4f316c8bf3 10066507

amd64 attestation: config sha256:f770b889a68b8f1f8959cf28bebd18fde148fc102a6b317f86d014553d6f2d4e; in-toto layer sha256:14ba3f25b6d6680b9ccdb7b1b5b2328e38a0110ced5184d1630f6266f1d211ab.

## 3. Proposed GHCR repositories (do not create yet)

Use the existing org/repo package namespace already used by invite-production (ghcr.io/adberilgen35/parkio/<name>):

| Role | Proposed repository | Visibility | Notes |
| --- | --- | --- | --- |
| server | ghcr.io/adberilgen35/parkio/minio | **private**, grant ADBERILGEN35/parkio Actions read | not a rebuild |
| mc | ghcr.io/adberilgen35/parkio/mc | **private**, grant ADBERILGEN35/parkio Actions read | not a rebuild |

Tags after copy (informational; **pins must be digest**):

- RELEASE.2024-09-13T20-26-02Z-linux-amd64
- RELEASE.2024-09-16T17-43-14Z-linux-amd64

Destination identity to preserve: the **platform manifest** digests above (efba309b…, 456b1e64…), plus identical config and layer digests. A docker push of a retagged local image may mint a **new** index and must be rejected unless crane/oras copy of the platform manifest keeps efba309b / 456b1e64.

## 4. Authorized copy commands (run only after written authorization)

Requires: logged-in GHCR credential with write:packages on ADBERILGEN35, crane or oras. Do not rebuild. Do not docker build. Do not retarget to latest.

`ash
# After GHCR login. Copy ONLY the linux/amd64 platform manifest + its config/layers.
# crane will fail closed if a referenced blob is missing — do not fall back to index copy.

crane copy \
  --platform linux/amd64 \
  minio/minio@sha256:cd04ea408e185cb50076ea1c3988d444119b19aaae15aab45387ccf14b2a2f86 \
  ghcr.io/adberilgen35/parkio/minio:RELEASE.2024-09-13T20-26-02Z-linux-amd64

crane copy \
  --platform linux/amd64 \
  minio/mc@sha256:a5399b66b88543efac8afb08eb2bdcce5904e548ea6fe1a921600cd74f766668 \
  ghcr.io/adberilgen35/parkio/mc:RELEASE.2024-09-16T17-43-14Z-linux-amd64
`

Because source registries 401 anonymously, this copy must run on the host that already has the blobs (this workstation) **or** after a one-time authenticated pull that is separately authorized. crane copy of the **index** digest must not be used: it would request arm64/ppc64le/s390x and fail or silently omit platforms.

After copy, record:

`ash
crane digest ghcr.io/adberilgen35/parkio/minio:RELEASE.2024-09-13T20-26-02Z-linux-amd64
crane digest ghcr.io/adberilgen35/parkio/mc:RELEASE.2024-09-16T17-43-14Z-linux-amd64
crane manifest ghcr.io/adberilgen35/parkio/minio@sha256:efba309ba4dc89e48f37304db52a0b854c0e701ba944ca02205c4e292c1a756c
crane manifest ghcr.io/adberilgen35/parkio/mc@sha256:456b1e641897329fc9491f9bc8b31df351d728af9a328bf5653707af62d0d6bf
`

Accept only if destination platform-manifest digest is exactly efba309b… / 456b1e64… and config + all layer digests match section 2.

## 5. Destination identity + fresh-runner pull verification

On a **new** GitHub-hosted ubuntu-latest job (no local cache):

`yaml
permissions:
  contents: read
  packages: read
# GITHUB_TOKEN must be allowed to read ghcr.io/adberilgen35/parkio/minio and /mc
`

`ash
docker pull ghcr.io/adberilgen35/parkio/minio@sha256:efba309ba4dc89e48f37304db52a0b854c0e701ba944ca02205c4e292c1a756c
docker pull ghcr.io/adberilgen35/parkio/mc@sha256:456b1e641897329fc9491f9bc8b31df351d728af9a328bf5653707af62d0d6bf
docker buildx imagetools inspect --raw ghcr.io/adberilgen35/parkio/minio@sha256:efba309ba4dc89e48f37304db52a0b854c0e701ba944ca02205c4e292c1a756c
`

Compare inspect JSON: mediaType platform manifest, config digest 70d57b0…, nine layer digests in order. Same for mc (40940f5a… + six layers). Fail if the pulled digest is an index, a different platform, or any layer differs.

Also: a logged-out anonymous pull should fail (private package). That is expected.

## 6. Exact pin changes after identity is verified

Replace the **index** Quay pins with **platform** GHCR pins (reviewed change, not identity-preserving index mirror):

`
FROM quay.io/minio/minio@sha256:cd04ea408e185cb50076ea1c3988d444119b19aaae15aab45387ccf14b2a2f86
TO   ghcr.io/adberilgen35/parkio/minio@sha256:efba309ba4dc89e48f37304db52a0b854c0e701ba944ca02205c4e292c1a756c

FROM quay.io/minio/mc@sha256:a5399b66b88543efac8afb08eb2bdcce5904e548ea6fe1a921600cd74f766668
TO   ghcr.io/adberilgen35/parkio/mc@sha256:456b1e641897329fc9491f9bc8b31df351d728af9a328bf5653707af62d0d6bf
`

Files that scripts/ci/assert-minio-image-pins.sh currently locks (must change together):

- docker/docker-compose.yml
- docker/.env.example
- .github/workflows/backup-restore-drill.yml
- .github/workflows/runtime-validation.yml
- .github/workflows/performance-smoke.yml
- .github/workflows/chaos-validation.yml
- services/media-service/src/test/java/com/parkio/media/infrastructure/storage/MediaInfrastructureIntegrationTest.java
- scripts/backup-minio.sh
- scripts/restore-drill-minio.sh
- scripts/restore-drill-offsite.sh
- scripts/restore-hosted-beta.sh
- scripts/lib/backup-common.sh
- scripts/staging/verify-minio-roundtrip.sh
- scripts/staging/run-wp062b-restored-stack-verification.sh

Also update (same strings, not in the assert script today):

- .github/workflows/restore-drill-01-procedure.yml
- add those two workflow env pins to ssert-minio-image-pins.sh

Parity check after the pin PR:

`ash
# update EXPECTED_SERVER / EXPECTED_MC in scripts/ci/assert-minio-image-pins.sh first
bash scripts/ci/assert-minio-image-pins.sh
git grep -n 'quay.io/minio' -- ':!agent-tools'
`

No Hub minio/minio:RELEASE.* short tags. Do not apply pin changes until destination pull on a fresh runner succeeds.

## 7. Exact remaining operator authorization

One written authorization that covers **all** of:

1. Create private GHCR packages dberilgen35/parkio/minio and dberilgen35/parkio/mc (or confirm they may be created by the first push).
2. Grant ADBERILGEN35/parkio Actions 
ead on both packages (so GITHUB_TOKEN on ubuntu-latest can pull).
3. Perform the two crane copy --platform linux/amd64 commands above from the host that already holds the blobs (or a separately authorized authenticated source pull).
4. Confirm destination platform-manifest digests are efba309b… and 456b1e64… with matching config/layers.
5. Then authorize the pin-change PR that retargets compose/CI/Testcontainers/assert script.

This message does **not** authorize any of those steps.

CI workflows still blocked on Quay 401 must not be blindly rerun until step 5 lands.
