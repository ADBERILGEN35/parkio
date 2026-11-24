# Pin evidence — gamification-service

| Field | Value |
|---|---|
| source_sha | `0438b37542035778afe37a7cea38642ebed65db3` |
| candidate_images_run | [45348570783](https://github.com/ADBERILGEN35/parkio/actions/runs/45348570783) (verdict `accepted-candidate`) |
| candidate_publish_run | [45349988278](https://github.com/ADBERILGEN35/parkio/actions/runs/45349988278) |
| new_pin | `ghcr.io/adberilgen35/parkio/gamification-service@sha256:4e4ae5ba2f90fd0c0d14967fa3fd060c503d62a47f4cab49329b88cd87e04c90` |
| digest_kind | platform manifest, linux/amd64 (`candidate-publish.yml` refuses a multi-platform index; config digest = image id) |
| image_id | `sha256:c18cf623aa5697347c5b696df7acad9b036775d5f7c3e63f5c761144237efe5a` (the config digest) |
| config_digest | `sha256:c18cf623aa5697347c5b696df7acad9b036775d5f7c3e63f5c761144237efe5a` |
| platform | `linux/amd64` |
| tags | `candidate-0438b3754203`, `sha-0438b37542035778afe37a7cea38642ebed65db3` |
| provenance | SLSA v1 provenance, attested keyless by `candidate-publish.yml` on `refs/heads/api` and verified with `cosign verify-attestation` in publish run 45349988278 (`verified attestation for ghcr.io/adberilgen35/parkio/gamification-service@sha256:4e4ae5ba2f90fd0c0d14967fa3fd060c503d62a47f4cab49329b88cd87e04c90`) |
| scan | Trivy `aquasec/trivy:0.64.1` (security-ci container scan: CRITICAL fixed (all packages) + HIGH/CRITICAL fixed (libraries); ignorefile .trivyignore.yaml): fixed CRITICAL 0, fixed HIGH 3; critical gate pass, library gate pass, gate pass |
| rollback | host-local `parkio-gamification-service:rollback-f888a81b`: what production runs now (image id `sha256:adb2efbca96b1d84…`, built from `f888a81b113c0c486ea0949067779443857e644d`, in no registry; tagged on the host on 2026-11-24, image id equal to the running container's) |
| rollback_provenance | the rollback image predates attestations, so a rollback runs outside the N-17 provenance gate, by the recorded commands in the deploy evidence |
| merge | pending: the pin PR for this release (a source change; merging it starts no deployment) |
| deploy | not deployed: deploying this pin is a separate, owner-authorized step |
