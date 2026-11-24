# Pin evidence — media-service

| Field | Value |
|---|---|
| source_sha | `0438b37542035778afe37a7cea38642ebed65db3` |
| candidate_images_run | [45348570783](https://github.com/ADBERILGEN35/parkio/actions/runs/45348570783) (verdict `accepted-candidate`) |
| candidate_publish_run | [45349988278](https://github.com/ADBERILGEN35/parkio/actions/runs/45349988278) |
| new_pin | `ghcr.io/adberilgen35/parkio/media-service@sha256:4d514ec0231dda202416a2aec52a7b8be5ff107f357e8a34ecc3ac8f98f4f3e0` |
| digest_kind | platform manifest, linux/amd64 (`candidate-publish.yml` refuses a multi-platform index; config digest = image id) |
| image_id | `sha256:cbdbd42509501240d1c735dcbed80de0e2a1224dc916c56497eb9b24025c1d33` (the config digest) |
| config_digest | `sha256:cbdbd42509501240d1c735dcbed80de0e2a1224dc916c56497eb9b24025c1d33` |
| platform | `linux/amd64` |
| tags | `candidate-0438b3754203`, `sha-0438b37542035778afe37a7cea38642ebed65db3` |
| provenance | SLSA v1 provenance, attested keyless by `candidate-publish.yml` on `refs/heads/api` and verified with `cosign verify-attestation` in publish run 45349988278 (`verified attestation for ghcr.io/adberilgen35/parkio/media-service@sha256:4d514ec0231dda202416a2aec52a7b8be5ff107f357e8a34ecc3ac8f98f4f3e0`) |
| scan | Trivy `aquasec/trivy:0.64.1` (security-ci container scan: CRITICAL fixed (all packages) + HIGH/CRITICAL fixed (libraries); ignorefile .trivyignore.yaml): fixed CRITICAL 0, fixed HIGH 3; critical gate pass, library gate pass, gate pass |
| rollback | `ghcr.io/adberilgen35/parkio/media-service@sha256:62f49d04087fb7aa475db0a42c7f8d1e7d1511f84e6b496a58c5beda58f864ec`: what production runs now (source `4a9ba2184e867b8ea8b927f7c3f8bcb137c2760a`; host check 2026-11-23, re-read 2026-11-24) |
| rollback_provenance | the rollback image predates attestations, so a rollback runs outside the N-17 provenance gate, by the recorded commands in the deploy evidence |
| merge | pending: the pin PR for this release (a source change; merging it starts no deployment) |
| deploy | not deployed: deploying this pin is a separate, owner-authorized step |
