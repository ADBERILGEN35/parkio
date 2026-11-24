# Pin evidence — analytics-service

| Field | Value |
|---|---|
| source_sha | `0438b37542035778afe37a7cea38642ebed65db3` |
| candidate_images_run | [45348570783](https://github.com/ADBERILGEN35/parkio/actions/runs/45348570783) (verdict `accepted-candidate`) |
| candidate_publish_run | [45349988278](https://github.com/ADBERILGEN35/parkio/actions/runs/45349988278) |
| new_pin | `ghcr.io/adberilgen35/parkio/analytics-service@sha256:a235a0749a202450940096c9a678e363ad60e1f41466f3774d29e5131497897b` |
| digest_kind | platform manifest, linux/amd64 (`candidate-publish.yml` refuses a multi-platform index; config digest = image id) |
| image_id | `sha256:7ab9aa4deb3d21a93c907c3274460dd4c737d16d8a201f6d6a839373ffc6f83b` (the config digest) |
| config_digest | `sha256:7ab9aa4deb3d21a93c907c3274460dd4c737d16d8a201f6d6a839373ffc6f83b` |
| platform | `linux/amd64` |
| tags | `candidate-0438b3754203`, `sha-0438b37542035778afe37a7cea38642ebed65db3` |
| provenance | SLSA v1 provenance, attested keyless by `candidate-publish.yml` on `refs/heads/api` and verified with `cosign verify-attestation` in publish run 45349988278 (`verified attestation for ghcr.io/adberilgen35/parkio/analytics-service@sha256:a235a0749a202450940096c9a678e363ad60e1f41466f3774d29e5131497897b`) |
| scan | Trivy `aquasec/trivy:0.64.1` (security-ci container scan: CRITICAL fixed (all packages) + HIGH/CRITICAL fixed (libraries); ignorefile .trivyignore.yaml): fixed CRITICAL 0, fixed HIGH 3; critical gate pass, library gate pass, gate pass |
| rollback | host-local `parkio-analytics-service:rollback-f888a81b`: what production runs now (image id `sha256:86ffd98aab2871be…`, built from `f888a81b113c0c486ea0949067779443857e644d`, in no registry; tagged on the host on 2026-11-24, image id equal to the running container's) |
| rollback_provenance | the rollback image predates attestations, so a rollback runs outside the N-17 provenance gate, by the recorded commands in the deploy evidence |
| merge | pending: the pin PR for this release (a source change; merging it starts no deployment) |
| deploy | not deployed: deploying this pin is a separate, owner-authorized step |
