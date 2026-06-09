# docker — Parkio local development infrastructure

Production-shaped local environment for Parkio. It provides every backing
service the microservices need, following the rules in
[`/docs/ai-context`](../docs/ai-context): **database-per-service**, externalized
config, Kafka for async events, Redis for cache/locks/idempotency, and MinIO for
media bytes.

## Layout

```
docker/
├── docker-compose.yml          # Infrastructure (databases, Redis, Kafka, MinIO, Prometheus, Grafana)
├── docker-compose.apps.yml     # The 10 application services (overlay on the infra file)
├── .env.example                # Copy to .env and edit
├── prometheus/prometheus.yml   # Scrape config
└── grafana/provisioning/       # Auto-provisioned datasource + dashboard provider
```

## What's included

| Component                | Container                     | Host port(s)    | Purpose                                   |
|--------------------------|-------------------------------|-----------------|-------------------------------------------|
| PostgreSQL (auth)        | `parkio-postgres-auth`        | 5432            | `auth-service` database                   |
| PostgreSQL (user)        | `parkio-postgres-user`        | 5433            | `user-service` database                   |
| PostgreSQL **+ PostGIS** | `parkio-postgres-parking`     | 5434            | `parking-service` database (geospatial)   |
| PostgreSQL (media)       | `parkio-postgres-media`       | 5435            | `media-service` database                  |
| PostgreSQL (gamification)| `parkio-postgres-gamification`| 5436            | `gamification-service` database           |
| PostgreSQL (notification)| `parkio-postgres-notification`| 5437            | `notification-service` database           |
| PostgreSQL (moderation)  | `parkio-postgres-moderation`  | 5438            | `moderation-service` database             |
| PostgreSQL (analytics)   | `parkio-postgres-analytics`   | 5439            | `analytics-service` database              |
| Redis                    | `parkio-redis`                | 6379            | Cache, locks, idempotency, rate limiting  |
| Kafka (KRaft)            | `parkio-kafka`                | 29092 (host)    | Async events; in-network listener `kafka:9092` |
| MinIO                    | `parkio-minio`                | 9000 / 9001     | S3 API / web console                      |
| Prometheus               | `parkio-prometheus`           | 9090            | Metrics scraping                          |
| Grafana                  | `parkio-grafana`              | 3000            | Dashboards                                |

> **Database-per-service:** every service owns a *separate* PostgreSQL instance.
> Services never share a database or read another service's tables
> (`ai-context/01`, `ai-context/05`). `gateway-service` and `ai-validation-service`
> own no database by design.

> **Kafka uses KRaft mode** (no Zookeeper) — the modern, single-process setup,
> fewer containers to run locally.

## Prerequisites

- Docker Engine 24+ and the Docker Compose v2 plugin (`docker compose ...`).

## Quick start (infrastructure only)

The common dev loop: run infra in Docker, run the services you're working on from
your IDE.

```bash
cd docker
cp .env.example .env          # then edit passwords if you like
docker compose up -d          # starts databases + Redis + Kafka + MinIO + observability
docker compose ps             # all should become "healthy"
```

`docker compose` automatically reads `./.env` when run from this directory.

## Full stack (infrastructure + all services)

```bash
cd docker
docker compose -f docker-compose.yml -f docker-compose.apps.yml up -d --build
```

Service images build from the repository root (each service's `Dockerfile`
compiles the whole monorepo via the Gradle wrapper), so the first build is slow.

## Endpoints

| Tool            | URL                              | Credentials                          |
|-----------------|----------------------------------|--------------------------------------|
| MinIO console   | http://localhost:9001            | `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` |
| Prometheus      | http://localhost:9090            | —                                    |
| Grafana         | http://localhost:3000            | `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` |
| Kafka (from host)| `localhost:29092`               | —                                    |
| Kafka (in-net)  | `kafka:9092`                     | —                                    |

Connecting to a database from the host, e.g. parking:

```bash
psql "postgresql://parkio_parking:parking_local_dev_pw@localhost:5434/parkio_parking"
```

## How services connect (when run via the apps overlay)

Connection details are injected as environment variables — no values are baked
into service code (`ai-context/01`):

- `SPRING_DATASOURCE_URL` → `jdbc:postgresql://postgres-<svc>:5432/<db>` (own DB only)
- `SPRING_KAFKA_BOOTSTRAP_SERVERS` → `kafka:9092`
- `SPRING_DATA_REDIS_HOST` → `redis`
- `media-service` storage → `PARKIO_STORAGE_*` pointing at `http://minio:9000`

These variables are inert until a service adds the matching Spring starter; they
document the intended wiring without implying any business logic.

## Health & startup ordering

- Every infrastructure container defines a **healthcheck** (`pg_isready`,
  `redis-cli ping`, Kafka API versions, MinIO `/minio/health/live`,
  Prometheus `/-/healthy`, Grafana `/api/health`).
- Application services wait for their dependencies via `depends_on:
  condition: service_healthy`, so they only start once the databases, Kafka, etc.
  are actually ready. `media-service` also waits for the one-shot `minio-setup`
  job that creates the media bucket.

## Networks

Two dedicated bridge networks isolate planes:

- `parkio-backend` — databases, Redis, Kafka, MinIO and the services.
- `parkio-observability` — Prometheus and Grafana (Prometheus also joins
  `parkio-backend` so it can scrape service metrics).

## Ingress & exposure — the gateway is the only public entrypoint

`gateway-service` (`:8080`) is the **only** component that should ever be reachable
from outside the cluster. It authenticates every request, role-gates privileged
routes, rate-limits, and injects the trusted `X-User-*` identity headers that
downstream services rely on. Those headers are believable **only** because backend
services are not directly reachable — a directly-exposed service would let a client
forge `X-User-Id`/`X-User-Roles` and bypass authentication and edge authorization
entirely (`ai-context/07`; see `services/gateway-service/README.md`).

> **Local dev vs production.** In `docker-compose.apps.yml` each backend service maps
> a host port (`8081`–`8089`) purely for local convenience (hit a service directly
> while developing). **This is not a production layout.** In production:
> - publish **only** the gateway (`8080`) to the public network / load balancer;
> - keep every other service on the internal `parkio-backend` network with **no host
>   port / no public ingress** (Kubernetes: `ClusterIP`, never a public `LoadBalancer`
>   or `NodePort` for a backend service);
> - keep databases, Redis, Kafka and MinIO internal-only as well.

## Persistence

Each stateful component has a named volume (e.g. `postgres-parking-data`,
`kafka-data`, `minio-data`, `grafana-data`). Data survives `docker compose down`.

```bash
docker compose down       # stop, keep data
docker compose down -v    # stop and DELETE all volumes (fresh start)
```

## Metrics note

Prometheus is preconfigured to scrape each service at `/actuator/prometheus`.
Targets show as **down** until a service adds `micrometer-registry-prometheus` and
exposes the endpoint — expected at the current scaffold stage.

## Single-image builds

```bash
# build context is the repository root
docker build -f ../services/auth-service/Dockerfile -t parkio/auth-service ..
```
