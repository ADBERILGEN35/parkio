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

Relative to invite-production after the R11E Tempo correction, Azure is:

```text
15872
+ 2816  ten local Postgres services
- 1792  Alertmanager, Loki, Promtail, Tempo absent in Azure
- 1088  Azure web/Caddy/Kafka/Prometheus/Grafana reductions
+   64  Azure minio-setup limit
= 15872 MiB
```

### 15,872 MiB: invite configured model

This is the full resolved 26-service invite-production model. It includes the
256 MiB Caddy and 128 MiB Promtail declarations for conservative approval-time
accounting even though the profile contract proves that neither is reachable
from the runtime targets. It includes `minio-setup` as a classified one-shot at
zero and excludes the inactive local Postgres services. No web/Caddy arithmetic
special case exists; their resolved limits are 128 and 256 MiB. The final
invite-dark overlay raises Tempo from the shared hosted-beta 512 MiB value to
1,024 MiB.

### 15,488 MiB: continuous runtime closure

This is the candidate-source sum for the 23 continuous services plus the
zero-memory `minio-setup` one-shot in the runtime dependency closure. It
includes dependency-started Loki and Tempo. It excludes Caddy and Promtail,
which are absent from the invite runtime:

```text
15872
- 256  caddy (ABSENT_BY_PROFILE)
- 128  promtail (ABSENT_BY_PROFILE)
= 15488 MiB
```

The contract therefore reports both `configuredMemoryMiB=15872` and
`continuousRuntimeMemoryMiB=15488`. The hard configured ceiling is 16,384 MiB,
so configured headroom is 512 MiB. This is configured resource headroom only;
it is not a claim about host RAM availability. ClamAV must remain exactly
3,072 MiB.

## Tempo minimum contract (R11D/R11E)

R11D captured the production Tempo process at the shared 512 MiB cgroup limit:
block flush/compaction pressure reached the exact ceiling, Docker emitted
`oom`, the process died with exit 137, and the kernel recorded
`CONSTRAINT_MEMCG`. Fourteen historical restarts had the same kernel signature;
host-wide OOM, Docker restart, WAL corruption, and filesystem failure were
excluded.

Invite-production therefore requires `tempo >= 1024 MiB`. The guard enforces
this as a service-specific minimum in addition to checking the aggregate total,
so moving 512 MiB to an unrelated service cannot disguise a Tempo regression.
The shared hosted-beta value remains 512 MiB, and Azure hosted-beta continues to
exclude Tempo; this remediation is isolated to the invite-dark overlay.

The corrected invite total is now numerically 15,872 MiB, but this does not
restore the removed Azure/source-only arithmetic. The value now comes from the
real six-file invite model (including the 1,024 MiB Tempo override); the wiring
regression continues to prohibit the legacy `sum + 64` source calculation.
