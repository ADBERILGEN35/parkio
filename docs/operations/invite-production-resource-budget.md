# Invite-production resource-budget contract

The approval-time metric is the sum of effective `mem_limit` values for every
classified service in the resolved invite-production Compose model. It is a
configured ceiling, not measured usage, a reservation, or live host available
memory. The runtime dependency closure is calculated and reported separately.

The production file order is owned by `scripts/lib/deploy-common.sh`:

1. `docker-compose.yml`
2. `docker-compose.apps.yml`
3. `docker-compose.images.yml`
4. `docker-compose.hosted-beta.yml`
5. `docker-compose.managed-db.yml`
6. `docker-compose.invite-dark.yml`

`assert-invite-production-resource-budget.sh` resolves that exact chain. The
shared Node helper consumes the resolved JSON directly, so overridden values
are already effective and inactive Compose-profile services are absent. It
accepts positive whole-byte values and integer `m`/`g` values, normalizes them
to MiB, and rejects every other unit. A missing limit is an error except for the
explicit invite `minio-setup` one-shot policy, where it is zero.

## Canonical invite inventory

| Classification | Services |
| --- | --- |
| `CONTINUOUS_RUNTIME` | `redis`, `kafka`, `kafka-exporter`, `blackbox-exporter`, `node-exporter`, `minio`, `clamav`, `prometheus`, `grafana`, `alertmanager`, `loki`, `tempo`, `auth-service`, `user-service`, `parking-service`, `media-service`, `gamification-service`, `notification-service`, `moderation-service`, `ai-validation-service`, `analytics-service`, `gateway-service`, `web` |
| `ONE_SHOT` | `minio-setup` (resolved without `mem_limit`, counted as zero) |
| `ABSENT_BY_PROFILE` | `caddy`, `promtail` (present in the merged model but unreachable from the invite runtime targets) |
| Omitted by inactive Compose profile | ten `postgres-*` services from the managed-DB overlay |

Loki and Tempo are continuous even though they are not explicit runtime roots:
Grafana pulls them into Compose's dependency closure. Caddy is omitted to keep
the dark runtime ACME-free. Promtail is not a runtime root or dependency. The
guard requires the exact classified model, the exact runtime roots, and the
exact dependency closure; unknown, missing, or newly reachable services fail.

## Three-number reconciliation

### 15,872 MiB: Azure hosted-beta

This is the actual five-file `azure-hosted-beta` merged model and its 32-service
runtime target, including the ten local Postgres services and the 64 MiB
`minio-setup`. Alertmanager, Loki, Promtail, and Tempo are excluded by the Azure
profile. The Azure overlay resolves web/Caddy/Kafka/Prometheus/Grafana to
64/96/1024/576/224 MiB. The former source-only AWK calculation reconstructed
these overrides from `docker-compose.hosted-beta.yml`; it did not resolve the
merged model and has been removed. Azure now uses the shared parser with its own
inventory.

Relative to invite-production, Azure is:

```text
15360
+ 2816  ten local Postgres services
- 1280  Alertmanager, Loki, Promtail, Tempo absent in Azure
- 1088  Azure web/Caddy/Kafka/Prometheus/Grafana reductions
+   64  Azure minio-setup limit
= 15872 MiB
```

### 15,360 MiB: invite configured model

This is the full resolved 26-service invite-production model. It includes the
256 MiB Caddy and 128 MiB Promtail declarations for conservative approval-time
accounting even though the profile contract proves that neither is reachable
from the runtime targets. It includes `minio-setup` as a classified one-shot at
zero and excludes the inactive local Postgres services. No web/Caddy arithmetic
special case exists; their resolved limits are 128 and 256 MiB.

### 14,976 MiB: live/runtime closure

This is the sum of `HostConfig.Memory` across the observed 24 production
containers: 23 running continuous services plus exited `minio-setup` at zero.
It includes dependency-started Loki and Tempo. It excludes Caddy and Promtail,
which have no containers in the invite runtime:

```text
15360
- 256  caddy (ABSENT_BY_PROFILE)
- 128  promtail (ABSENT_BY_PROFILE)
= 14976 MiB
```

The contract therefore reports both `configuredMemoryMiB=15360` and
`continuousRuntimeMemoryMiB=14976`. The hard configured ceiling is 16,384 MiB,
so configured headroom is 1,024 MiB. This is configured resource headroom only;
it is not a claim about host RAM availability. ClamAV must remain exactly
3,072 MiB.
