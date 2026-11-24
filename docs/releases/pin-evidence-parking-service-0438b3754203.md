# Pin evidence — parking-service

| Field | Value |
|---|---|
| source_sha | `0438b37542035778afe37a7cea38642ebed65db3` |
| candidate_images_run | [45348570783](https://github.com/ADBERILGEN35/parkio/actions/runs/45348570783) (verdict `accepted-candidate`) |
| candidate_publish_run | [45349988278](https://github.com/ADBERILGEN35/parkio/actions/runs/45349988278) |
| new_pin | `ghcr.io/adberilgen35/parkio/parking-service@sha256:c0fb4a7f58a6ca15fdded16b06d944d6e520755e5f435a4ccccc2618e35438dd` |
| digest_kind | platform manifest, linux/amd64 (`candidate-publish.yml` refuses a multi-platform index; config digest = image id) |
| image_id | `sha256:af8c088016305e4cf1269de9f593ce64b8a7b85d53282bac9e9f5b4245e3f0e3` (the config digest) |
| config_digest | `sha256:af8c088016305e4cf1269de9f593ce64b8a7b85d53282bac9e9f5b4245e3f0e3` |
| platform | `linux/amd64` |
| tags | `candidate-0438b3754203`, `sha-0438b37542035778afe37a7cea38642ebed65db3` |
| provenance | SLSA v1 provenance, attested keyless by `candidate-publish.yml` on `refs/heads/api` and verified with `cosign verify-attestation` in publish run 45349988278 (`verified attestation for ghcr.io/adberilgen35/parkio/parking-service@sha256:c0fb4a7f58a6ca15fdded16b06d944d6e520755e5f435a4ccccc2618e35438dd`) |
| scan | Trivy `aquasec/trivy:0.64.1` (security-ci container scan: CRITICAL fixed (all packages) + HIGH/CRITICAL fixed (libraries); ignorefile .trivyignore.yaml): fixed CRITICAL 0, fixed HIGH 3; critical gate pass, library gate pass, gate pass |
| rollback | `ghcr.io/adberilgen35/parkio/parking-service@sha256:3888df0564910477d376a7d8ed8f6c4991e02e66a643f67ca8f2de5a067f96a9`: what production runs now (source `504b9cb453aca31954800cb4f73a074f3b8f5ef6`; host check 2026-11-23, re-read 2026-11-24) |
| rollback_provenance | the rollback image predates attestations, so a rollback runs outside the N-17 provenance gate, by the recorded commands in the deploy evidence |
| merge | pending: the pin PR for this release (a source change; merging it starts no deployment) |
| deploy | not deployed: deploying this pin is a separate, owner-authorized step |
