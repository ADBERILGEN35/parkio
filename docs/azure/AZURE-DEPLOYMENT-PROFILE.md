# Azure Hosted-Beta Deployment Profile

## Contract

| Setting | Value |
|---|---|
| Profile | `azure-hosted-beta` |
| Selector | `PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta` |
| Environment | `docker/.env.azure-hosted-beta` copied from `.env.azure-hosted-beta.example` |
| Compose files | base -> apps -> images -> hosted-beta -> Azure overlay |
| Azure overlay | `docker/docker-compose.azure-hosted-beta.yml` |
| Runtime target | 32 services: 31 steady-running plus `minio-setup` one-shot |
| Excluded | `alertmanager`, `loki`, `promtail`, `tempo` |
| Public Compose ports | Caddy `80/tcp`, `443/tcp`, `443/udp`; all admin binds are loopback |
| API | `https://api.parkio.dev/api/v1` for web and mobile |
| Architecture | `linux/amd64` enforced on every targeted service |
| Configured memory | 14,336 MiB including one-shot setup; 2,048 MiB below a 16-GiB host |

## Deterministic mechanism

Compose cannot delete an inherited service definition. The Azure profile therefore uses both:

1. The Azure overlay assigns the four excluded services to the inactive `azure-disabled-observability` Compose profile and replaces Grafana's dependencies with Prometheus only.
2. `scripts/lib/deploy-common.sh` supplies an explicit 32-service target to both deploy and rollback.

A plain Azure deploy cannot start an excluded service accidentally through Grafana dependencies. Enabling `azure-disabled-observability` manually is outside this profile and invalidates the 16-GiB sizing.

The base `hosted-beta` profile is unchanged. Existing CI and non-Azure operators that omit the selector retain the previous four-file stack.

## Exact commands

```bash
cp docker/.env.azure-hosted-beta.example docker/.env.azure-hosted-beta
chmod 600 docker/.env.azure-hosted-beta
$EDITOR docker/.env.azure-hosted-beta

export PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta
export PARKIO_ENV_FILE=docker/.env.azure-hosted-beta

./scripts/preflight-hosted-beta.sh
./scripts/validate-hosted-beta-compose.sh
./scripts/deploy-hosted-beta.sh --dry-run
./scripts/deploy-hosted-beta.sh --operator <OPERATOR>
./scripts/smoke-hosted-beta.sh
./scripts/backup-hosted-beta.sh --dry-run
./scripts/backup-hosted-beta.sh
./scripts/restore-hosted-beta.sh --manifest backup-artifacts/backup-current.json --dry-run
./scripts/rollback-hosted-beta.sh --manifest deploy-artifacts/<PREVIOUS>.json --dry-run
```

Deploy and rollback manifests record `deploymentProfile`, `runtimeServices`, `disabledServices`, and the exact Compose files. Rollback fails if the selected profile differs from its manifest. Backup manifests record the profile; restore rejects a mismatch.

## Environment inventory

### Hostname consistency matrix

| Platform | Variable | Local value | Azure hosted-beta value | Production value/requirement | Source |
|---|---|---|---|---|---|
| Web | `VITE_API_BASE_URL` | `http://localhost:8080/api/v1` | `https://api.parkio.dev/api/v1` | explicit HTTPS release URL; no implicit production fallback | `frontend/apps/web/.env.example`, `frontend/apps/web/src/config/env.ts`, Azure env template |
| Mobile | `EXPO_PUBLIC_API_BASE_URL` | Android emulator `http://10.0.2.2:8080/api/v1` | `https://api.parkio.dev/api/v1` | current EAS production profile uses the canonical URL; production-like builds fail if unset | `frontend/apps/mobile/eas.json`, `frontend/apps/mobile/src/config/env.ts` |
| Caddy/backend edge | `PARKIO_DOMAIN` | operator/local Compose domain as selected | `api.parkio.dev` | explicit public API hostname | `docker/caddy/Caddyfile`, Azure env template |
| Media edge | `PARKIO_MEDIA_DOMAIN` | local/internal MinIO as selected | `media.parkio.dev` | explicit public media hostname | `docker/caddy/Caddyfile`, Azure env template |
| Static Hostinger landing | `VITE_WAITLIST_INTAKE_MODE` | mode selected by developer | `api` only in the Azure web image | `disabled` in the existing Hostinger packaging workflow | web Dockerfile/Compose and existing Hostinger package script |

These variables are public routing/build configuration, never secret carriers. Test-only `api.beta.parkio.dev` fixture values exercise generic hosted-beta validation and are not deployment targets. The retired `beta-api.parkio.dev` string remains only in negative regression guards.

| Group | Variables | Requirement / visibility |
|---|---|---|
| Profile | `PARKIO_DEPLOYMENT_PROFILE` | Required; Azure-specific; server/operator-only |
| Public hosts | `PARKIO_DOMAIN`, `PARKIO_WEB_DOMAIN`, `PARKIO_MEDIA_DOMAIN`, `PARKIO_ACME_EMAIL` | Required; shared public configuration |
| Web client | `VITE_API_BASE_URL`, `VITE_APP_ENV`, `VITE_WAITLIST_INTAKE_MODE`, map/smart-return/error flags | Required/defaulted; client-visible; never secrets |
| Mobile client | `EXPO_PUBLIC_API_BASE_URL`, `EXPO_PUBLIC_APP_ENV`, smart-return flag | Build-time client-visible; never secrets |
| Browser boundary | CORS credentials/origins, trusted proxies, upload size | Required/defaulted; server-only except declared origins |
| JWT/gateway | private PEM, key id/issuer/audience, gateway current/accepted secret | Required secrets except identifiers; server-only |
| Waitlist | `PARKIO_WAITLIST_HASH_SECRET` | Required secret; independent of gateway secret; server-only |
| Databases | ten DB/user/password triplets | Required; passwords secret/server-only; shared across profiles |
| Redis/Kafka | Redis password; Kafka cluster id and retention bounds | Required secrets/settings; server-only |
| MinIO/media | root credentials, bucket, internal/public endpoints, scanner settings | Credentials secret; public endpoint client-visible; rest server-only |
| Providers | Resend and Expo keys, sender/reply-to | Keys required secrets; addresses non-secret |
| Metrics | Grafana credential, Prometheus/Grafana/exporter loopback ports | Grafana password required secret; ports defaulted/server-only |
| Disabled telemetry | `PARKIO_TRACING_ENABLED=false`, sampling `0.0` | Required Azure setting; overlay also forces false per JVM |
| Safety | OpenAPI false, token logging false, test hooks false | Required server-only toggles |
| Backup | directory, encryption passphrase, optional remote destination, retention | Passphrase secret; destination optional; server/operator-only |

The template uses quoted placeholders so shell sourcing is valid. It intentionally fails preflight until every secret/operator placeholder is replaced.

## Resource changes

| Service | Hosted-beta | Azure | Rationale / risk |
|---|---:|---:|---|
| web | 128 MiB | 64 MiB | static nginx only; verify under asset concurrency |
| Caddy | 256 MiB | 96 MiB | three low-traffic hosts; TLS issuance spike must be observed |
| Kafka | 1280 MiB, heap 768 | 1024 MiB, heap 640 | closed-beta event rate and bounded retention; RF=1 remains a risk |
| MinIO setup | no limit | 64 MiB | one-shot bucket policy job; fails deploy if cap is insufficient |
| Prometheus | 1024 MiB, 15d | 576 MiB, 7d | reduced retention and beta cardinality; monitor RSS/TSDB |
| Grafana | 384 MiB | 224 MiB | single SSH-tunnel operator; measured prior run was about 71 MiB |
| Alertmanager/Loki/Promtail/Tempo | 128/512/128/512 MiB | disabled | no alerts/log aggregation/traces; use bounded Docker logs and daily checks |
| JVMs, DBs, Redis, MinIO, ClamAV, exporters | existing limits | unchanged | no evidence supports shrinking safety-critical paths |

JVMs retain `MaxRAMPercentage=65`, `InitialRAMPercentage=40`, capped metaspace, G1, and exit-on-OOM. Kafka retains room outside its 640-MiB heap. Build stages are not covered by runtime cgroup totals; cold builds on the 4-vCPU VM remain a live validation gate.

## Re-enable criteria

Do not re-enable excluded observability on the 16-GiB profile. Resize to at least the repository's full-stack recommendation, restore the original retention/caps, provide a real alert receiver, render Compose, and repeat startup/load/disk validation first.
