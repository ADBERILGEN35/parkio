# Pin evidence — auth-service

| Field | Value |
|---|---|
| source_sha | `0438b37542035778afe37a7cea38642ebed65db3` |
| candidate_images_run | [45348570783](https://github.com/ADBERILGEN35/parkio/actions/runs/45348570783) (verdict `accepted-candidate`) |
| candidate_publish_run | [45349988278](https://github.com/ADBERILGEN35/parkio/actions/runs/45349988278) |
| new_pin | `ghcr.io/adberilgen35/parkio/auth-service@sha256:290867b4e0a54ffec572e5f1074af3c6183f14e2e712f2a1d8e3577a933c4a5a` |
| digest_kind | platform manifest, linux/amd64 (`candidate-publish.yml` refuses a multi-platform index; config digest = image id) |
| image_id | `sha256:2b4c47650e2f4e48e1b71665fb095009c8cda643deb5ce7147d59561dd15268c` (the config digest) |
| config_digest | `sha256:2b4c47650e2f4e48e1b71665fb095009c8cda643deb5ce7147d59561dd15268c` |
| platform | `linux/amd64` |
| tags | `candidate-0438b3754203`, `sha-0438b37542035778afe37a7cea38642ebed65db3` |
| provenance | SLSA v1 provenance, attested keyless by `candidate-publish.yml` on `refs/heads/api` and verified with `cosign verify-attestation` in publish run 45349988278 (`verified attestation for ghcr.io/adberilgen35/parkio/auth-service@sha256:290867b4e0a54ffec572e5f1074af3c6183f14e2e712f2a1d8e3577a933c4a5a`) |
| scan | Trivy `aquasec/trivy:0.64.1` (security-ci container scan: CRITICAL fixed (all packages) + HIGH/CRITICAL fixed (libraries); ignorefile .trivyignore.yaml): fixed CRITICAL 0, fixed HIGH 3; critical gate pass, library gate pass, gate pass |
| rollback | `ghcr.io/adberilgen35/parkio/auth-service@sha256:2091de2e7efed0b6814a312bf5d74690274d778e6438491eb3884d9d710f3616`: what production runs now (source `504b9cb453aca31954800cb4f73a074f3b8f5ef6`; host check 2026-11-23, re-read 2026-11-24) |
| rollback_provenance | the rollback image predates attestations, so a rollback runs outside the N-17 provenance gate, by the recorded commands in the deploy evidence |
| merge | pending: the pin PR for this release (a source change; merging it starts no deployment) |
| deploy | not deployed: deploying this pin is a separate, owner-authorized step |
