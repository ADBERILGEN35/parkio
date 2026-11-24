# Pin evidence — web

| Field | Value |
|---|---|
| source_sha | `0438b37542035778afe37a7cea38642ebed65db3` |
| candidate_images_run | [45348570783](https://github.com/ADBERILGEN35/parkio/actions/runs/45348570783) (verdict `accepted-candidate`) |
| candidate_publish_run | [45349988278](https://github.com/ADBERILGEN35/parkio/actions/runs/45349988278) |
| new_pin | `ghcr.io/adberilgen35/parkio/web@sha256:cb23b8749ac6b04b52137a6f7a97d3131d24d3f5831ee152f2a02c3c64a9a6a4` |
| digest_kind | platform manifest, linux/amd64 (`candidate-publish.yml` refuses a multi-platform index; config digest = image id) |
| image_id | `sha256:7a9e42eda300d7568a2c7e924e238b90bf48ca22d016e2c7fc2e7843f261082d` (the config digest) |
| config_digest | `sha256:7a9e42eda300d7568a2c7e924e238b90bf48ca22d016e2c7fc2e7843f261082d` |
| platform | `linux/amd64` |
| tags | `candidate-0438b3754203`, `sha-0438b37542035778afe37a7cea38642ebed65db3` |
| provenance | none: `candidate-publish.yml` attests only `*-service` images, and publish run 45349988278 logs `skip attestation (not *-service): web`. The N-17 deploy provenance gate skips web too; the map guard owns web verification (digest bind and `pull_policy: never`) |
| scan | Trivy `aquasec/trivy:0.64.1` (security-ci container scan: CRITICAL fixed (all packages) + HIGH/CRITICAL fixed (libraries); ignorefile .trivyignore.yaml): fixed CRITICAL 0, fixed HIGH 1; critical gate pass, library gate pass, gate pass |
| rollback | `ghcr.io/adberilgen35/parkio/web@sha256:08811c39cd5a0a483415b136d1f6bcfd7120e97a94b71ec77d20b63ac15cdea6`: what production runs now (source `f888a81b113c0c486ea0949067779443857e644d`; host check 2026-11-23, re-read 2026-11-24) |
| rollback_provenance | web is outside the N-17 provenance gate (the map guard owns it), and the rollback image predates attestations; a rollback runs by the recorded commands in the deploy evidence |
| merge | pending: the pin PR for this release (a source change; merging it starts no deployment) |
| deploy | not deployed: deploying this pin is a separate, owner-authorized step |
