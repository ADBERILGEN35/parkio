# Parking photo moderation v1.0.0-rc7 - hosted-beta deployment evidence

Date: 2026-07-27
Operator: Cursor hosted-beta deployment operator (workstation)
Status: **HOSTED_BETA_RC7_DEPLOYMENT_BLOCKED**

Release engineering completed. No hosted-beta runtime mutation was performed from this workstation.

## Approved release inputs (unchanged)

| Field | Value |
|---|---|
| Release | v1.0.0-rc7 |
| Release commit | 550848277748cf086a738c7135f26f1ff27ae9e8 |
| Release workflow | 30267086295 - success |
| Deploy scope | ai-validation-service, parking-service only |
| Deploy order | ai-validation, compatibility gate, parking |

### Immutable digests

| Service | rc7 deploy digest | rc6 rollback digest |
|---|---|---|
| ai-validation-service | sha256:f347e9968da6df525d4e004588530d80385dacd6ae901b05fcf0aac9e521e6d4 | sha256:c16bfe790998d2f4c70a392435fd311e21e1c1cbd9af43e7b198452066f4cd8d |
| parking-service | sha256:a070d9b6e96170bbe88ee842953af9abdba912a6c77bddce80d8316ab362ba85 | sha256:c84ae5fb3692f779b4b43751828dc0ac5a5d618ef10e4097bab1cc362ba55881 |

Compose overrides: deploy-artifacts/docker-compose.hosted-beta-rc7-20260727.yml and deploy-artifacts/docker-compose.hosted-beta-rc6-20260727.yml

## Phase D0 - blocked

Attempt timestamp (UTC): 2026-07-27T14:30:00Z

SSH to parkio@api.parkio.dev, ubuntu@api.parkio.dev, azureuser@api.parkio.dev failed: Permission denied (publickey). Tried ~/.ssh/id_ed25519. Azure CLI not installed. docker/.env.azure-hosted-beta missing locally.

Remote probes: GET https://api.parkio.dev/actuator/health -> 200 UP; GET https://api.parkio.dev/actuator/info -> 200 empty.

Cannot verify rc6 digests on VPS, Flyway, backup, Kafka lag, Prometheus, or moderation metrics without SSH. D0 stop condition met; aborted before D1.

## Phases D1-D6

Not executed (D6 partial via this document).

## Final classification

HOSTED_BETA_RC7_DEPLOYMENT_BLOCKED
