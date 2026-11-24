# Pin evidence — moderation-service

| Field | Value |
|---|---|
| source_sha | `0438b37542035778afe37a7cea38642ebed65db3` |
| candidate_images_run | [45348570783](https://github.com/ADBERILGEN35/parkio/actions/runs/45348570783) (verdict `accepted-candidate`) |
| candidate_publish_run | [45349988278](https://github.com/ADBERILGEN35/parkio/actions/runs/45349988278) |
| new_pin | `ghcr.io/adberilgen35/parkio/moderation-service@sha256:3aaf4360bad2a1edc979dcd534e8d3bb3052499a8dd4e8ddef9550d58036e8b9` |
| digest_kind | platform manifest, linux/amd64 (`candidate-publish.yml` refuses a multi-platform index; config digest = image id) |
| image_id | `sha256:a53625b80500d4325e121b0264c97ef994f7189c1b7b53603ddfb6cdb883f315` (the config digest) |
| config_digest | `sha256:a53625b80500d4325e121b0264c97ef994f7189c1b7b53603ddfb6cdb883f315` |
| platform | `linux/amd64` |
| tags | `candidate-0438b3754203`, `sha-0438b37542035778afe37a7cea38642ebed65db3` |
| provenance | SLSA v1 provenance, attested keyless by `candidate-publish.yml` on `refs/heads/api` and verified with `cosign verify-attestation` in publish run 45349988278 (`verified attestation for ghcr.io/adberilgen35/parkio/moderation-service@sha256:3aaf4360bad2a1edc979dcd534e8d3bb3052499a8dd4e8ddef9550d58036e8b9`) |
| scan | Trivy `aquasec/trivy:0.64.1` (security-ci container scan: CRITICAL fixed (all packages) + HIGH/CRITICAL fixed (libraries); ignorefile .trivyignore.yaml): fixed CRITICAL 0, fixed HIGH 3; critical gate pass, library gate pass, gate pass |
| rollback | host-local `parkio-moderation-service:rollback-f888a81b`: what production runs now (image id `sha256:0d77f5cbab954d74…`, built from `f888a81b113c0c486ea0949067779443857e644d`, in no registry; tagged on the host on 2026-11-24, image id equal to the running container's) |
| rollback_provenance | the rollback image predates attestations, so a rollback runs outside the N-17 provenance gate, by the recorded commands in the deploy evidence |
| merge | pending: the pin PR for this release (a source change; merging it starts no deployment) |
| deploy | not deployed: deploying this pin is a separate, owner-authorized step |
