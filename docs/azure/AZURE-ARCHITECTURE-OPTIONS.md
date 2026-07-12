# Azure Architecture Options

## Option comparison

| Option | Feasibility and performance | Cost / credit | Operations, security, backup | Migration / lock-in | Decision |
|---|---|---|---|---|---|
| A. Full Compose on one VM | Technically matches repo; needs 8 vCPU and at least 24 GiB; 32-GiB Azure size is practical | `D8as_v5` compute alone is USD 299.52/30d | simplest existing path; single failure domain; current backup scripts fit | low Azure lock-in; high single-host risk | NO-GO for USD 200; valid after paid budget |
| B. One VM, reduced observability | Current app/data topology retained; disable Loki/Promtail/Tempo and unused Alertmanager | `D4as_v5` fixed baseline USD 167.76/30d including stated disks/IP | one operator; Caddy-only ingress; logical backups must leave VM | low lock-in; reversible | **CONDITIONAL GO and recommended** |
| C. VM apps + managed data | PostgreSQL/Redis can be externalized, but Kafka/Event Hubs and S3/Blob require config/compatibility work | D4 VM + B2ms PostgreSQL + storage + C0 Redis already exceeds USD 300 before Kafka | better data durability, more secrets/network/IaC, backup improves | moderate lock-in | NO-GO for credit; migration target later |
| D. Azure Container Apps | Compose is not directly portable: 10 always-on JVMs plus stateful data plane and Caddy/media assumptions need redesign | a hypothetical 10 x 0.5-vCPU/1-GiB always-active app floor is about USD 544/30d before data | per-service ingress/secrets/registries; stateful services externalized | medium-high Azure lock-in | NO-GO for this beta |
| E. AKS | no repo evidence requiring orchestration; stateful operators/ingress/registry/IaC absent | control plane/node/data cost and operator time exceed constraint | highest burden and attack surface | high migration effort | NO-GO |

## Recommended topology

```text
Hostinger DNS
  app.parkio.dev ─┐
  api.parkio.dev ─┼─> Azure Standard static IPv4
  media.parkio.dev┘       |
                       NSG: 80/443 public, 22 operator /32
                          |
                 Ubuntu 24.04 amd64 D4as_v5
                 Caddy :80/:443 only
                    | Docker private bridge
       web + gateway + 9 downstream JVM services
       10 service-owned PostgreSQL/PostGIS containers
       Redis + Kafka + MinIO + ClamAV
       Prometheus + Grafana + node/kafka/blackbox exporters
       (no Loki/Promtail/Tempo; Alertmanager only with receiver)
                          |
              Standard SSD LRS data disk
                          |
          encrypted logical dumps + MinIO mirror
             copied off-VM every backup cycle
```

## Why one VM

- It is the only topology the repository currently automates end to end: preflight, immutable image tags, deploy manifests, smoke, backup, restore, and rollback.
- Caddy already enforces the correct public trust boundary.
- One VM avoids Application Gateway, Load Balancer, NAT Gateway, private endpoints, managed Kafka, and cross-service bandwidth charges.
- It is reversible: databases use PostgreSQL, media is S3-compatible, Kafka contracts are standard, and clients point to DNS names.

This is a cost architecture, not an HA architecture. VM or disk failure stops the entire beta. It is acceptable only for named closed testers, explicit downtime/RPO acceptance, verified off-host backups, and a 30-day exit decision.

## Simplifications and safety

| Simplification | Risk | Reversibility | Configuration / validation |
|---|---|---|---|
| Disable Tempo | no traces | start container again | set `PARKIO_TRACING_ENABLED=false`; verify logs have no exporter errors |
| Disable Loki/Promtail | no centralized logs; Docker rotation retains limited logs | start both again with volumes | retain 50-MiB/container JSON logs; test incident workflow with `docker compose logs` |
| Disable Alertmanager without receiver | no proactive alert delivery | start and configure receiver | operator must review Prometheus/daily health; never claim alerting is active |
| Retain Prometheus/Grafana/exporters | about 1.66 GiB ceilings | can later externalize | use SSH tunnels; reduce retention only in an implementation phase |
| Keep local MinIO | same-host object-loss risk | S3-compatible export/import | nightly mirror off-VM; test one object restore |
| Keep Kafka | RF=1 event-loss risk | later managed Kafka migration | keep 7d/2-GiB bounds, monitor lag, use outbox replay procedures |
| Ten DB containers unchanged | memory overhead | later consolidate to one server with 10 DB/users | current backup/restore scripts remain valid; consolidation requires separate proof |
| Turn off optional AI/analytics | user-visible/API/event gaps may occur | restart services | **not approved by this audit** until a dedicated reduced-stack overlay and E2E tests exist |

## Migration-ready boundary

The environment is **portable but not migration-ready production**. It produces portable `pg_dump` files and MinIO objects, uses DNS indirection, and avoids Azure-specific APIs. It lacks IaC, managed HA/PITR, tested managed Kafka compatibility, an external secret manager, multi-host rollout, and live disaster recovery. Treat it as temporary validation with migration artifacts, not as the first production landing zone.
