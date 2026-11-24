# Pin evidence — gateway-service

| Field | Value |
|---|---|
| source_sha | `0438b37542035778afe37a7cea38642ebed65db3` |
| candidate_images_run | [45348570783](https://github.com/ADBERILGEN35/parkio/actions/runs/45348570783) (verdict `accepted-candidate`) |
| candidate_publish_run | [45349988278](https://github.com/ADBERILGEN35/parkio/actions/runs/45349988278) |
| new_pin | `ghcr.io/adberilgen35/parkio/gateway-service@sha256:a311158567d7c69557ac61bbfa34704139003402a41f09417a2cad4fcabc58e5` |
| digest_kind | platform manifest, linux/amd64 (`candidate-publish.yml` refuses a multi-platform index; config digest = image id) |
| image_id | `sha256:f60a318d776e31954628fbf2f5de664b74a60c97241192fa2bcba5ddb99bc094` (the config digest) |
| config_digest | `sha256:f60a318d776e31954628fbf2f5de664b74a60c97241192fa2bcba5ddb99bc094` |
| platform | `linux/amd64` |
| tags | `candidate-0438b3754203`, `sha-0438b37542035778afe37a7cea38642ebed65db3` |
| provenance | SLSA v1 provenance, attested keyless by `candidate-publish.yml` on `refs/heads/api` and verified with `cosign verify-attestation` in publish run 45349988278 (`verified attestation for ghcr.io/adberilgen35/parkio/gateway-service@sha256:a311158567d7c69557ac61bbfa34704139003402a41f09417a2cad4fcabc58e5`) |
| scan | Trivy `aquasec/trivy:0.64.1` (security-ci container scan: CRITICAL fixed (all packages) + HIGH/CRITICAL fixed (libraries); ignorefile .trivyignore.yaml): fixed CRITICAL 0, fixed HIGH 3; critical gate pass, library gate pass, gate pass |
| rollback | `ghcr.io/adberilgen35/parkio/gateway-service@sha256:655219f54f2eb58558dbe01efcc043d2e3f8222bebb483c4b6879a53d8743872`: what production runs now (source `971ac1d2401ebfe8eb50dafc22e8241aa0781d76`; host check 2026-11-23, re-read 2026-11-24) |
| rollback_provenance | the rollback image predates attestations, so a rollback runs outside the N-17 provenance gate, by the recorded commands in the deploy evidence |
| merge | pending: the pin PR for this release (a source change; merging it starts no deployment) |
| deploy | not deployed: deploying this pin is a separate, owner-authorized step |
