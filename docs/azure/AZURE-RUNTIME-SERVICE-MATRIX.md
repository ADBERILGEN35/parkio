# Azure Runtime Service Matrix

This matrix reflects the five-file `azure-hosted-beta` Compose model and the explicit runtime list in `scripts/lib/deploy-common.sh`.

## Enabled and one-shot services

| Service | State | Purpose / dependency chain | Port exposure | Storage / health | Limit | Principal environment |
|---|---|---|---|---|---:|---|
| web | Enabled | hosted SPA -> API | internal 80 via Caddy | image; HTTP health | 64 MiB | baked `VITE_*`, waitlist `api` |
| caddy | Enabled | TLS -> web/gateway/MinIO | **public 80,443 TCP; 443 UDP** | cert/config volumes; admin health | 96 MiB | web/API/media hosts, ACME, upload cap |
| gateway-service | Enabled | edge -> auth/user/all domain services; Redis + gateway DB | private 8080 | gateway DB; readiness | 640 MiB | CORS, JWT/JWKS, route URIs, gateway/waitlist secrets |
| auth-service | Enabled | auth; Kafka + auth DB + Redis | private 8081 | auth DB; readiness | 768 MiB | JWT private key, email, Redis |
| user-service | Enabled | profiles; Kafka + user DB | private 8082 | user DB; readiness | 640 MiB | DB, smart return |
| parking-service | Enabled | spots/search; Kafka + PostGIS + Redis + media | private 8083 | parking DB; readiness | 768 MiB | DB, Redis, media/geocoding |
| media-service | Enabled | uploads; Kafka + media DB + MinIO setup + ClamAV | private 8084 | DB/objects; readiness | 768 MiB | MinIO, scanner, upload limits |
| gamification-service | Enabled | scores; Kafka + DB + Redis | private 8085 | DB; readiness | 640 MiB | DB, Redis |
| notification-service | Enabled | in-app/email/push; Kafka + DB | private 8086 | DB; readiness | 640 MiB | DB, Expo, smart-return worker |
| moderation-service | Enabled | content safety; Kafka + DB | private 8087 | DB; readiness | 640 MiB | DB |
| ai-validation-service | Enabled | advisory validation; Kafka + DB | private 8088 | DB; readiness | 640 MiB | DB |
| analytics-service | Enabled | beta evidence/read model; Kafka + DB | private 8089 | DB; readiness | 640 MiB | DB |
| postgres-auth | Enabled | auth ownership | none | named volume; `pg_isready` | 320 MiB | auth DB/user/password |
| postgres-gateway | Enabled | waitlist ownership | none | named volume; `pg_isready` | 256 MiB | gateway DB/user/password |
| postgres-user | Enabled | user ownership | none | named volume; `pg_isready` | 256 MiB | user DB/user/password |
| postgres-parking | Enabled | PostGIS ownership | none | named volume; `pg_isready` | 384 MiB | parking DB/user/password |
| postgres-media | Enabled | media metadata | none | named volume; `pg_isready` | 256 MiB | media DB/user/password |
| postgres-gamification | Enabled | scoring ownership | none | named volume; `pg_isready` | 256 MiB | gamification DB credentials |
| postgres-notification | Enabled | notification ownership | none | named volume; `pg_isready` | 256 MiB | notification DB credentials |
| postgres-moderation | Enabled | moderation ownership | none | named volume; `pg_isready` | 256 MiB | moderation DB credentials |
| postgres-analytics | Enabled | analytics ownership | none | named volume; `pg_isready` | 320 MiB | analytics DB credentials |
| postgres-ai-validation | Enabled | advisory ownership | none | named volume; `pg_isready` | 256 MiB | AI DB credentials |
| redis | Enabled | rate limits/cache/session state | none | AOF volume; authenticated ping | 384 MiB | password |
| kafka | Enabled | current async contracts/outbox fan-out | none | log volume; broker health | 1024 MiB | cluster id, 640-MiB heap, retention |
| minio | Enabled | media bytes | private 9000; public reads only through Caddy media host | object volume; live health | 512 MiB | root credentials/bucket |
| minio-setup | One-shot | creates private bucket/policy after MinIO | none | no persistent state; successful exit | 64 MiB | MinIO credentials/bucket |
| clamav | Enabled | fail-closed upload malware scan | none | signature volume; clamd health | 1536 MiB | scanner defaults |
| prometheus | Enabled | service/host/Kafka/backup metrics | loopback 9090 | TSDB volume; health | 576 MiB | 7-day retention |
| grafana | Operator-only | private dashboards; depends only on Prometheus | loopback 3000/SSH tunnel | Grafana volume; health | 224 MiB | admin credentials |
| kafka-exporter | Enabled | Kafka lag/broker metrics | loopback 9308 | none; Prometheus `up` | 128 MiB | broker address |
| blackbox-exporter | Enabled | HTTP reachability metrics | loopback 9115 | config; container health | 64 MiB | probe config |
| node-exporter | Enabled | VM CPU/RAM/disk/inode metrics | loopback 9100 | read-only host mounts; Prometheus `up` | 64 MiB | textfile collector |

Steady state is 31 running containers. `minio-setup` exits successfully after bucket initialization. All ten PostgreSQL databases and MinIO are included in backup/restore manifests.

## Disabled services

| Service | Reason / trade-off | Re-enable condition |
|---|---|---|
| alertmanager | no assumed real alert channel; operator performs daily Prometheus review | configure receiver, resize/recalculate memory, validate delivery |
| loki | saves memory/disk; only bounded Docker JSON logs remain | larger host and retention/disk test |
| promtail | Loki absent; avoids Docker socket log shipping | re-enable with Loki after security/resource review |
| tempo | saves memory/disk; distributed traces unavailable | larger host, tracing sampling/retention test |

Every disabled service carries only the inactive `azure-disabled-observability` profile and is absent from the explicit runtime list. Distributed tracing is forced to `false` in all ten JVM containers.

## Exposure contract

- Internet: Caddy only on `80/tcp`, `443/tcp`, and optional HTTP/3 `443/udp`.
- Host loopback: Grafana, Prometheus, and exporters; access with SSH forwarding.
- Docker private network: gateway, services, databases, Redis, Kafka, MinIO, and ClamAV.
- `media.parkio.dev` terminates TLS at Caddy and proxies to private `minio:9000`; signed URLs must use that exact host.

