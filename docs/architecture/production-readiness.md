# Parkio — Production Deployment Readiness Plan

> **Status:** planning document. Describes what is required to move Parkio from
> local Docker beta to a hosted deployment. No cloud resources are provisioned by
> this document — it is a proposal. It does not change application business logic
> or public APIs.

## Executive verdict

| Question | Answer |
|----------|--------|
| **GO / NO-GO for hosted (closed/invite) beta** | **GO — conditional** on the *hosted-beta blockers* below. The application layer is ready; the deployment/ops layer needs a thin, well-scoped hardening pass. |
| **GO / NO-GO for public production** | **NO-GO** until the *public-production blockers* are closed (HA + backups/PITR for data, secrets manager + rotation, CD with rollback, alerting + on-call, load/security testing). |
| **Recommended deployment path** | **Phase 1 (hosted beta):** Docker Compose on a single right-sized VPS, gateway behind a TLS reverse proxy. **Phase 2 (public prod):** managed container platform + **managed Postgres (PITR + PostGIS)** + **managed Kafka/Redpanda (RF≥3)** + **managed S3-compatible storage**. **Defer Kubernetes** until team/scale justify it (see §2). |

### What is already strong (do not redo)

The codebase is unusually deployment-aware for its stage:

- **Externalized config + fail-closed secrets.** No production defaults for the JWT
  private key or the gateway internal secret — services refuse to start without them
  (`PARKIO_JWT_PRIVATE_KEY_PEM`, `PARKIO_GATEWAY_INTERNAL_SECRET`).
- **Database-per-service**, schema owned by **Flyway**, Hibernate `ddl-auto: validate`,
  `open-in-view: false`.
- **Kafka reliability**: `acks=all`, idempotent producer, manual ack, `ErrorHandlingDeserializer`,
  per-service **DLT topics**, transactional **outbox/inbox** with **DLQ / poison-row dead-lettering**,
  and **replication factor externalized** (`parkio.kafka.replication-factor`, default 1).
- **Edge security**: gateway-only ingress, Redis-backed per-tier **rate limiting**, **CORS allow-list**
  (empty default = deny), **RS256 + JWKS** validation, live **account-status** check (fail-closed 503),
  **session-epoch access-token revocation** (fail-closed 503), `X-Gateway-Auth` internal secret backstop.
- **Actuator locked down** to `health,info,prometheus` only; Prometheus + Grafana provisioned;
  documented metric catalogue (`observability-metrics.md`).

### The deployment-layer gap in one sentence

> The **app** is production-shaped; the **platform** is local-only — no IaC, no TLS, single-broker Kafka,
> single-node Postgres/MinIO with no backups/PITR, no CD, no alerting, no tracing aggregation.

---

## 1. Environment model

Three environments. Keep them **identical in topology** and different only in scale,
data, and secrets ("dev/prod parity").

| Aspect | `local` | `staging` | `production` |
|--------|---------|-----------|--------------|
| **Purpose** | Dev loop, IDE + Docker infra | Pre-prod verification, integration/smoke target, demos | Real users |
| **Infrastructure** | `docker-compose.yml` (+apps overlay) on a laptop | Same shape as prod, smaller (1 small VPS or small managed tier) | Managed data services + managed compute (see §2) |
| **Data persistence** | Named Docker volumes; disposable | Managed (or VPS volumes) with **daily backups**; restorable, non-sensitive seed data | Managed Postgres **PITR**, Kafka **RF≥3**, S3 versioned; tested restores |
| **Secrets** | git-ignored `.env`, local-dev values | Secrets manager (staging scope), unique values | Secrets manager (prod scope), unique values, **rotation** |
| **Observability** | Prometheus + Grafana containers | Same + **Alertmanager** (test alerts) | Same + alerts wired to on-call + log aggregation + tracing |
| **Deployment** | `docker compose up` | CD auto-deploy on merge to `main` | CD with **manual approval** + rollback |
| **TLS** | none (HTTP localhost) | real cert (staging subdomain) | real cert, HSTS |
| **Ingress** | all ports exposed for convenience | gateway-only public; rest private | gateway-only public; rest private |

**Config mechanism.** Add Spring profiles `staging` and `prod` (config via env vars only,
per existing pattern). Create `infra/environments/{staging,prod}.env.example` listing every
required variable (no values). Production must set, at minimum: `PARKIO_JWT_PRIVATE_KEY_PEM`,
`PARKIO_JWT_KEY_ID`, `PARKIO_JWT_ISSUER`, `PARKIO_JWT_AUDIENCE`, `PARKIO_GATEWAY_INTERNAL_SECRET`,
`PARKIO_CORS_ALLOWED_ORIGINS`, `PARKIO_KAFKA_REPLICATION_FACTOR=3`, all `SPRING_DATASOURCE_*`,
`SPRING_KAFKA_BOOTSTRAP_SERVERS`, `PARKIO_MEDIA_STORAGE_*`, and `PARKIO_*_SERVICE_URI`.

---

## 2. Compute / deployment architecture

**Footprint reality:** 10 Spring Boot JVMs + gateway + 9 Postgres + Kafka + Redis + MinIO +
Prometheus + Grafana. Each JVM is ~0.3–0.5 GB. This is a *heavy* topology for an early product —
the dominant decision driver.

| Option | Pros | Cons | Verdict for Parkio now |
|--------|------|------|------------------------|
| **A. Docker Compose on a VPS** | Cheapest; you already have the compose files; one box to reason about; fast to ship | Single point of failure; manual scaling; you operate Kafka/Postgres/MinIO yourself; restarts = downtime | **Best for hosted beta.** Lowest cost & complexity. |
| **B. Managed container platform** (ECS Fargate, Fly.io, Render, Hetzner+Nomad) | No node ops; rolling deploys; per-service scaling; integrates with managed data | More $ than a VPS; some lock-in; 10 services = non-trivial config | **Best for public production.** Right amount of power without k8s overhead. |
| **C. Kubernetes (EKS/GKE/k3s)** | Most flexible; self-healing; HPA; standard | High ops burden; needs a dedicated operator; overkill at this scale/team | **Defer.** Not justified yet — revisit at 90 days if scaling/multi-region/team>1 ops. |

**Recommended path:**

1. **Hosted beta → Option A.** One VPS (start ~8 GB RAM for infra + a subset of services; ~16 GB to run all 10 comfortably; e.g. Hetzner CCX/CPX, Hetzner is cost-leading). Put **Caddy or Traefik** in front of the gateway for automatic TLS (Let's Encrypt). Only 443 (and 80→443 redirect) is public.
2. **Public production → Option B**, and **move stateful components off the box to managed services** (§3–§5). Compute runs the 11 stateless app containers; data lives in managed Postgres/Kafka/S3. This is where reliability is bought.
3. **Kubernetes only later** — keep `infra/kubernetes/` as a future overlay; do not build it now.

---

## 3. Database strategy (production)

PostGIS is required by `parking-service`; everything else is plain Postgres 16.

- **Topology — cost-aware compromise.** The architectural invariant is *no shared schema, no
  cross-service table access, separate credentials* — **not necessarily separate servers**. For
  production:
  - **Recommended:** 1–2 **managed Postgres clusters** hosting **9 logical databases** with **9
    distinct roles**, each role granted only its own database. This preserves database-per-service
    isolation (separate DB + separate creds, no cross-DB grants) at a fraction of the cost of 9
    managed instances.
  - **parking** must run on a cluster whose provider supports the **`postgis` extension** (AWS RDS,
    Aiven, Supabase, Crunchy, Neon all do). If the chosen managed provider can't enable PostGIS on a
    shared cluster, isolate **parking on its own PostGIS-capable instance** and co-locate the rest.
  - Keep separate instances only where load profiles diverge sharply (e.g. analytics write-heavy).
- **Backups + PITR.** Enable provider automated backups with **Point-In-Time Recovery** (WAL
  archiving), retention ≥ 7 days (beta) / ≥ 30 days (prod).
  - **VPS beta interim — implemented; restore drill automated.** `scripts/backup-databases.sh`
    (nightly cron) dumps all nine service DBs (timestamped gzip; optional AES-256 + offsite via `mc`),
    `scripts/restore-database.sh` performs a guarded single-DB restore, and
    `scripts/verify-backup.sh` proves a dump is restorable by restoring into a **disposable temp
    database** (no live-data risk). The end-to-end **restore drill is now a single script**
    (`scripts/restore-drill.sh`) **and runs in CI** (`.github/workflows/backup-restore-drill.yml`,
    weekly + on PRs touching the backup scripts / compose / parking migrations + on demand): it
    seeds a canary row into every service DB, runs the real backup, restores each dump into a
    disposable `*_drill_*` DB, and **asserts the canary survives** — and for `parking` asserts the
    real PostGIS objects restore (the `postgis` extension, the `idx_parking_spots_location` GiST
    index, the `trg_parking_spots_sync_location` trigger) and that a live spatial query still works
    (applying the real `V*.sql` migrations first when the schema is absent). This proves
    restorability on real postgres/postgis images on every run. **Still recommended before relying
    on it in production:** run the drill once **on the actual VPS host** against the live data path,
    and verify a dump pulled back from the **offsite** copy (`BACKUP_MC_DEST`), not just the local
    file. Runbook in `docker/README.md`.
  - **RPO/RTO (beta):** RPO ≈ 24h (nightly logical dumps; no point-in-time recovery between dumps —
    shorten the interval to reduce it); RTO ≈ minutes per DB. Managed PITR + Multi-AZ failover is
    still required for public production (logical dumps are a stop-gap, not HA).
  - **Test the restore** (and the *offsite* copy, not just the local file) before relying on it.
- **Migrations.** Already Flyway-owned, `validate` at runtime. Run migrations as a **pre-deploy
  step** (init container / deploy job) — never let two app replicas race migrations on boot. Gate
  deploys on migration success.
- **Connection pooling.** HikariCP is in use (Spring default). Set explicit per-service
  `spring.datasource.hikari.maximum-pool-size` (small, e.g. 5–10) sized to the DB's connection
  budget; with many services on one cluster, total connections matter — consider **PgBouncer**
  (transaction pooling) in front of shared managed clusters.
- **HA / failover.** Use the managed provider's **standby/replica with automatic failover**
  (Multi-AZ). Not needed for beta; **required** for public production.
- **PostGIS** continuity: verify the extension version, GiST index, and the location trigger
  migrate cleanly on the managed instance (the existing `ParkingPostgisIntegrationTest` validates
  this against `postgis/postgis:16-3.4` — keep the managed version aligned).

---

## 4. Kafka strategy (production)

Current local: **single combined broker (KRaft), RF=1, `min.insync.replicas=1`, auto-create off**.
This is the single biggest data-durability gap for events.

- **Managed first.** Self-hosting an HA Kafka/Redpanda cluster is real ops work. Recommend a
  **managed broker**: **Redpanda Cloud** (Kafka-compatible, lean) or **Aiven for Kafka** (cost-friendly,
  good for small teams), with **AWS MSK / Confluent Cloud** as larger-scale alternatives. Redpanda/Aiven
  are the pragmatic, cost-aware picks for an early product.
- **Durability settings (production):**
  - **`replication.factor = 3`** — set `PARKIO_KAFKA_REPLICATION_FACTOR=3` (already externalized; topic
    provisioning will honor it).
  - **`min.insync.replicas = 2`** (broker/topic level) so a single broker loss doesn't stall producers
    while still tolerating one failure. Producers already use `acks=all` + idempotence — together this
    gives no-data-loss-on-single-broker-failure semantics.
- **Topic provisioning.** Each service creates its own `NewTopic` beans (`KafkaTopicsConfig`,
  partitions=3, retention set, RF externalized). Keep `auto.create.topics.enable=false`. On managed
  brokers where apps lack admin rights, **pre-create topics** via the provider/Terraform and set
  `parkio.kafka.provision-topics=false`.
- **DLT topics.** Already present per consumer (`parkio.dlt.<service>`) via
  `DeadLetterPublishingRecoverer`. Ensure they are created with the same RF and **never auto-purged**
  before inspection — set a generous retention.
- **Retention.** Per-topic retention is configured in code; review event-topic retention (default in
  `KafkaTopicsConfig`) vs. consumer recovery needs. DLT retention should be longer than the on-call
  response window.
- **Consumer lag + DLT depth monitoring.** Apps deliberately do **not** export lag; monitor at the
  broker: managed providers expose lag/partition metrics, or run a **Kafka exporter** / `kafka-lag-exporter`
  scraped by Prometheus. Alert on lag and on **DLT message count > 0** (see §8).

---

## 5. Object storage (media)

Current local: **single-node MinIO**, private bucket (`mc anonymous set none`), split
endpoint/public-endpoint for SigV4-signed GET URLs.

- **Production: use managed S3-compatible storage**, not single-node MinIO. Cost-aware picks:
  **Cloudflare R2** (no egress fees — attractive for image serving) or **Backblaze B2**; **AWS S3**
  if already on AWS. The `media-service` already speaks S3/SigV4, so this is a config change
  (`PARKIO_MEDIA_STORAGE_ENDPOINT`, `_PUBLIC_ENDPOINT`, `_ACCESS_KEY`, `_SECRET_KEY`, `_BUCKET`),
  not a code change. (Keep MinIO only if you must self-host; then run it **distributed/HA**, not single-node.)
- **Private bucket + signed URLs.** Keep the bucket private; serve media exclusively via
  **presigned GET URLs**. Critical: SigV4 signs the `Host` header, so `PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT`
  **must be the exact host the browser uses** (e.g. `https://media.parkio.app` or the R2/S3 public host).
  Get this wrong → broken images (this is already called out in the runbook/`.env.example`).
  Spot photo access must remain parking-mediated: owners and staff may see their
  own/hidden spots, but unrelated users receive signed URLs only for currently
  public spots. Hidden/expired/rejected resources should return not-found semantics
  rather than revealing existence.
- **CORS** on the bucket: allow only the production web origin(s) for `GET` (and the upload method/path
  the client uses), not `*`.
- **Lifecycle cleanup.** Add a lifecycle rule to expire orphaned/temp uploads and abort incomplete
  multipart uploads; align with any media-retention policy in the domain.
- **Backup/retention + versioning.** Enable **object versioning** (protects against accidental
  delete/overwrite) and, for prod, cross-region or provider backup per RPO. Restrict the access key to
  the single bucket (least privilege).
- **Malware scan before serving (implemented).** Uploads are scanned by **ClamAV** (`clamd` over TCP)
  *before* they are stored; media is `READY`/servable only after a clean scan, and the scan is
  **fail-closed** (a scan that cannot complete → `503`, nothing stored; infected → `422`). Signed URLs
  are issued only for `READY` media and `parking-service` refuses to attach non-`READY` media to a spot.
  The hosted-beta compose adds a private `clamav` service; `media-service` depends on it being healthy.
- **Image normalization before storage (implemented).** After a clean malware scan, uploads are decoded,
  checked against configured width/height/pixel limits, and re-encoded to server-generated JPEG bytes.
  The stored checksum, content type, object extension, and signed URL metadata all describe the
  normalized bytes; original upload bytes and EXIF/GPS/device metadata are not stored. WebP input
  requires a runtime ImageIO WebP reader and otherwise fails closed.
  **Known limitation:** this is malware scanning, **not** illegal/abusive-content (CSAM) detection —
  for public production add a **managed AV / content-safety provider** and/or human moderation.
- **AI validation findings.** Treat advisory validation output as moderation data:
  read endpoints are `MODERATOR`/`ADMIN` only. Do not expose another user's media or
  parking validation findings to arbitrary authenticated users.
- **RBAC — separation of duties (`MODERATOR` ≠ `ADMIN`).** Moderators handle *content*
  (review spots/media, read AI findings, work the case queue, resolve cases with
  content outcomes). Account-level and destructive actions are **`ADMIN`-only**:
  suspend/restore accounts, trust/point overrides (`SUSPEND_USER`/`RESTORE_USER`/
  `REDUCE_TRUST`/`DEDUCT_POINTS` on `resolveCase`), appeal resolution, platform
  analytics, and role management. Enforced fail-closed at three layers (edge gateway →
  controller → application service). Account effects have **no HTTP admin surface** —
  they flow from moderation `resolveCase` actions over Kafka, so the action-level ADMIN
  gate in moderation-service is the single chokepoint. Full matrix and boundaries:
  `docs/ai-context/07-security-guidelines.md`. **Caveat:** role assignment is currently
  DB-seeded (no admin role-management API yet); build it `ADMIN`-only when needed.

---

## 6. Secrets and config

Today: secrets live only in git-ignored `.env`. Good hygiene, but not a production secret system.

| Secret | Storage (prod) | Rotation |
|--------|----------------|----------|
| **JWT RSA private key** (`PARKIO_JWT_PRIVATE_KEY_PEM`) | Secrets manager (e.g. AWS Secrets Manager / GCP Secret Manager / Doppler / SOPS-encrypted) | **Implemented — key-id rollover.** auth signs with the active `PARKIO_JWT_KEY_ID`; previous public keys go in `PARKIO_JWT_ADDITIONAL_PUBLIC_KEYS_JSON` so the JWKS (and auth's own verifier) accept old tokens until they expire. Gateway picks up the new `kid` via JWKS cache TTL. Runbook in `docker/README.md`. |
| **Gateway internal secret** (`PARKIO_GATEWAY_INTERNAL_SECRET`) | Secrets manager | **Implemented — dual-accept window** via `PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS` (see §7). |
| **DB credentials** (per-service roles) | Secrets manager; injected as `SPRING_DATASOURCE_*` | Rotate per provider; update secret + rolling restart. |
| **S3/MinIO credentials** | Secrets manager | Rotate access keys; scope to one bucket. |
| **Grafana admin / Redis (if AUTH) / broker SASL creds** | Secrets manager | Periodic. |

- **Policy:** secrets are **environment variables injected at runtime from a secrets manager** —
  never baked into images, never committed. The codebase already enforces *no production defaults*
  for the two most sensitive secrets; keep that discipline.
- **Add a startup config-validation check** (or document a deploy preflight) that asserts all required
  prod env vars are present and non-default before traffic is accepted.
- **Redis** currently has no password and no TLS — in production require **AUTH + TLS** (managed Redis)
  since it backs rate-limiting and idempotency.

---

## 7. Network / security

- **TLS at ingress.** Terminate TLS at the reverse proxy/ingress (Caddy/Traefik for the VPS beta;
  the platform's managed LB for prod). Redirect 80→443, enable HSTS. Backend stays HTTP on the
  private network.
- **Gateway-only public exposure.** Only `gateway-service:8080` is reachable publicly. **Remove the
  per-service host port mappings** (`8081–8089`) from any hosted compose — they exist only for local
  dev. Backends, Postgres, Kafka, Redis, MinIO stay on the private network with **no public ingress**
  (k8s: `ClusterIP`). This is the foundation that makes the injected `X-User-*` identity headers
  trustworthy.
- **CORS.** Set `PARKIO_CORS_ALLOWED_ORIGINS` to the exact production web origin(s); never `*`
  (default is empty = deny, which is safe).
- **Rate limiting behind a proxy / `X-Forwarded-For` trust.** *(Implemented.)* The gateway rate-limits
  by userId else **client IP**. Behind a TLS proxy the socket IP is the proxy, so the client IP is
  derived from `X-Forwarded-For` — **but only from a trusted proxy**, via `ClientIpResolver`:
  - `SERVER_FORWARD_HEADERS_STRATEGY=framework` enables Spring's forwarded-headers handling for
    scheme/host;
  - `parkio.gateway.trusted-proxies` (`PARKIO_TRUSTED_PROXIES`) lists the proxy CIDRs/IPs whose
    `X-Forwarded-For` may be trusted; empty = trust nothing (key on the socket peer);
  - the client is the **right-most non-proxy** hop, so a forged left-most value is never selected
    (spoofing-resistant even if the proxy appends); malformed input fails closed to the socket peer.
  - For public prod with a managed LB, add the LB's egress range to `PARKIO_TRUSTED_PROXIES`.
- **Internal gateway-secret rotation.** *(Implemented.)* Rotate `PARKIO_GATEWAY_INTERNAL_SECRET`
  without downtime via a **dual-accept** window: set `PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS`
  (comma-separated previous secrets) on the downstream services first so they accept old *or* new,
  flip the gateway to emit the new secret, then clear the accepted list. Comparisons are
  constant-time; a blank current secret still fails closed. Step-by-step runbook in `docker/README.md`.
- **Headers.** Hosted beta Caddy now sets SPA security headers including HSTS,
  `Content-Security-Policy`, `X-Content-Type-Options`, `Referrer-Policy`, and a
  conservative `Permissions-Policy`. Keep `connect-src` aligned with the API,
  media and geocoding origins configured for the environment.

## Auth token storage

Sprint 1 hardening moves refresh tokens out of browser storage. auth-service
sets the rotated refresh token as an HttpOnly `Secure` `SameSite=Strict` cookie
scoped to the refresh/logout auth paths; the SPA stores only the short-lived
access token in memory. Login/refresh JSON responses no longer expose the raw
refresh token; registration for pending email verification does not issue a
session. Refresh/logout additionally validate `Origin`/`Referer` against the
trusted frontend origin allow-list before using the ambient cookie.

Refresh-token families are bounded by **two** limits: a *sliding* per-token TTL
(`refresh-token-ttl`, default 30 days, reset on each rotation) and an *absolute*
session lifetime cap (`refresh-absolute-ttl`, default 90 days) anchored at
`family_started_at` and preserved across rotations. Once a family passes the absolute
cap, refresh is rejected with the generic `INVALID_REFRESH_TOKEN` and the family is
revoked (`EXPIRED_CLEANUP`) — so an active session cannot be rotated indefinitely and
every login is forced to re-authenticate after the maximum session age. **Operational
impact:** users are signed out and must log in again at most `refresh-absolute-ttl`
after they first logged in, regardless of activity; tune the env var
`PARKIO_JWT_REFRESH_ABSOLUTE_TTL` per environment (must be ≥ the sliding TTL).

Stateless access tokens (RS256 JWTs, 15-minute TTL) are revoked early via a per-user
**session epoch**. Every access token carries a `session_epoch` claim; after JWT
validation the gateway compares it against the user's current epoch (read from an
internal auth-service endpoint, cached ~30 s) and rejects a stale token with `401`
(`TOKEN_REVOKED`), **failing closed** with `503` (`SESSION_EPOCH_UNAVAILABLE`) if the
epoch can't be confirmed — consistent with the live account-status check. auth-service
bumps the epoch on refresh-token reuse detection, `logout-all`
(`POST /api/v1/auth/logout-all`), suspension, password reset and change-password. **Operational
impact:** the **access-token revocation window** drops from the 15-minute token TTL to the
epoch cache TTL (`PARKIO_SESSION_EPOCH_CACHE_TTL`, default `PT30S`); the gateway makes one
extra cached internal call to auth-service per protected request, and if auth-service is
unreachable protected requests fail closed (`503`) until it recovers. A missing claim
(token issued before the feature shipped) is treated as epoch 0, so no users are forced
out on deploy.

## Auth brute-force and password hardening

Sprint 2 adds auth-service account-level brute-force protection on top of the
gateway's Redis token-bucket limits. Failed login attempts are tracked in Redis
by normalized email so protection works across multiple auth-service instances:
5 failures locks the account key for 30 seconds, 10 failures for 5 minutes, and
20 failures for 1 hour. Successful login clears the account counter. Client
responses for wrong password, unknown email and lockout stay the same generic
`INVALID_CREDENTIALS` shape; logs and Micrometer counters distinguish failures
and lockouts internally.

Registration now enforces a 12-character minimum with at least one lowercase
letter, one uppercase letter and one digit, plus a maintainable deny-list for
obvious weak passwords such as `password123`, `qwerty123` and `admin123`.
Symbols are allowed but not required. The frontend mirrors the same guidance
with live validation feedback.

## Auth email verification

Sprint 3 changes public registration from immediate full activation to a
verified-email lifecycle. auth-service creates new public accounts as
`PENDING_VERIFICATION`, stores only a hash of the email verification token, and
does not issue access/refresh tokens or a refresh cookie until the user verifies
the email address. Login for pending accounts returns `403 ACCOUNT_NOT_VERIFIED`
without minting a session. Verified accounts become `ACTIVE`; suspended accounts
remain a separate moderation state and are not conflated with verification.

Verification tokens expire after 24 hours by default. Resend is Redis-throttled,
rotates the stored token hash for pending users, and returns the same accepted
response for unknown, already verified, throttled and pending accounts so the
endpoint cannot be used for account enumeration.

Transactional auth email now has provider wiring. Local/dev defaults to the
logging adapter, which logs only email hashes unless raw-token logging is
explicitly enabled for local testing. Hosted beta and production should use
Resend:

```dotenv
PARKIO_EMAIL_PROVIDER=resend
PARKIO_RESEND_API_KEY=...
PARKIO_EMAIL_FROM="Parkio <verify@example.com>"
PARKIO_EMAIL_REPLY_TO=support@example.com
```

When Resend is selected, missing API key or from address fails startup. With the
`prod` profile active, selecting the logging provider or enabling raw-token email
logging also fails startup. Delivery is observable through
`email_sent_total`, `email_failed_total`, and `email_verification_sent_total`.

## Auth password reset

Password reset is implemented as an enumeration-safe credential-rotation flow.
`POST /api/v1/auth/forgot-password` always returns `200 OK`; known verified active
accounts receive a one-hour reset link and all other states get the same client
response. Reset tokens are generated from 256 bits of randomness, stored only as
SHA-256 hashes, invalidated when superseded, and single-use. `POST
/api/v1/auth/reset-password` enforces the same password policy as registration,
marks the reset token consumed, revokes every active refresh-token family, and
bumps `session_epoch` so already-issued access tokens are rejected by the gateway.
The frontend redirects to login after reset and never auto-logs the user in.

---

## 8. Observability

Today: Prometheus scrapes `/actuator/prometheus`; Grafana datasource + dashboard provider are
provisioned; rich custom metric catalogue exists. **Missing: alert rules, Alertmanager, log
aggregation, and distributed tracing export.**

- **Prometheus**: add `rule_files` + an **Alertmanager** target. Retention 15d locally is fine;
  prod use managed (Grafana Cloud free tier / Amazon Managed Prometheus) or longer TSDB retention.
- **Grafana dashboards**: commit JSON dashboards (currently only a provider is wired) for: outbox/inbox
  backlog + dead-lettered, consumer lag, gateway 5xx + rate-limit rejections, auth login failures,
  media upload failures, DB connections, JVM.
- **Logs**: services log structured lines with `traceId` (MDC). Ship stdout to an aggregator
  (Grafana Loki — cheapest; or provider logs). Required for prod debugging across 10 services.
- **Tracing / OpenTelemetry**: `traceId` exists in logs but there is **no span export**. Add the
  **OTel Java agent** (or Micrometer Tracing + OTLP exporter) → an OTel collector → Tempo/Jaeger.
  High value across an event-driven system; can follow shortly after beta.
- **Alerting — key alerts (wire to on-call for prod):**

  | Alert | Signal | Why |
  |-------|--------|-----|
  | **Outbox dead-lettered > 0** | `parkio.outbox.deadlettered.count` (per service) | A poison event is stuck — needs human redrive (newly added DLQ metric). |
  | **Outbox backlog / age rising** | `parkio.outbox.unpublished.count`, `...oldest.unpublished.age.seconds` | Relay or broker is failing to publish. |
  | **Consumer lag high** | broker/exporter lag | Consumers falling behind; user-visible staleness. |
  | **DLT depth > 0** | `parkio.dlt.<service>` message count | Poison messages on the consumer side. |
  | **5xx rate** | gateway HTTP 5xx ratio | User-facing breakage. |
  | **Auth failures spike** | auth login-failure counter | Credential stuffing / outage. |
  | **Media upload failures** | media upload counter | Storage/SigV4/bucket misconfig. |
  | **DB connections near pool max** | Hikari active/max | Pool exhaustion / leak. |
  | **Kafka broker health / under-replicated partitions** | broker metrics | Cluster degradation (esp. with RF≥3). |
  | **Service down / probe failing** | `up == 0`, health | Liveness. |

---

## 9. CI/CD

Today: GitHub Actions covers the fast backend unit-test gate (`backend-ci.yml`), frontend gate
(`frontend-ci.yml`), Docker-backed Testcontainers integration tests (`backend-integration.yml`),
backup/restore drills (`backup-restore-drill.yml`), and security scanning (`security-ci.yml`).
Security CI runs on PRs, pushes to `master`, weekly, and on demand:

- **Secret scanning:** gitleaks with `.gitleaks.toml`, blocking all detected secrets except exact
  documented local-dev placeholders and test-only fake values.
- **SAST:** CodeQL for Java/Kotlin and JavaScript/TypeScript, uploading SARIF to GitHub code scanning.
- **Dependency scanning:** Trivy filesystem scan over dependency manifests, blocking HIGH/CRITICAL
  library vulnerabilities.
- **Container scanning:** gateway/auth/media images are built and scanned with Trivy. HIGH findings are
  reported; CRITICAL image vulnerabilities block CI.

CodeQL and Trivy SARIF upload require repository code scanning to be enabled:
**GitHub repository Settings → Code security and analysis → Code scanning**. Enable
CodeQL/code scanning there before treating `security-ci.yml` as a required check.
Private repositories may need GitHub Advanced Security enabled at the repository or
organization level. The workflow permissions are already least-privilege for this
path: `contents: read`, `security-events: write`, and `actions: read`.

False positives must be handled narrowly. Prove the value is fake, then add the smallest exact
allowlist/config entry. If a real secret is committed, rotate/revoke it and remove it from deployment
environments; do not allowlist it. Local equivalents:

```bash
gitleaks detect --source . --config .gitleaks.toml --redact
trivy fs --scanners vuln --vuln-type library --severity HIGH,CRITICAL --ignore-unfixed .
docker build -f services/media-service/Dockerfile -t parkio/media-service:local-scan .
trivy image --severity HIGH,CRITICAL --ignore-unfixed parkio/media-service:local-scan
cd frontend && pnpm audit --audit-level high
```

Still missing for a full CD pipeline: publishing immutable images, staging deploys, smoke tests,
protected-environment approvals, and production deploy/rollback automation.

Proposed pipeline (no secrets in CI beyond a deploy token in protected environments):

1. **Build images.** On merge to `main`, build each service image (multi-stage Dockerfiles already
   exist) and push to a registry (GHCR is free for the repo).
2. **Tag strategy.** Tag with **immutable** `git-sha` (e.g. `:sha-<short>`) + a moving `:staging` /
   `:prod` channel tag. Never deploy `:latest`. Frontend built and deployed similarly (static host/CDN).
3. **Deploy to staging.** Auto-deploy `:sha` to staging on merge. Run **migrations as a gated step**
   first.
4. **Run integration + smoke tests against staging.** Reuse `./gradlew integrationTest` (already
   CI-wired, Docker-required guard added) for component ITs; add a thin **post-deploy smoke test**
   (the BETA runbook flow: health → JWKS → register → login → create spot → media render) as an
   automated script hitting staging.
5. **Manual approval → production.** Use a GitHub **Environment** with required reviewers; promote the
   *same* `:sha` image that passed staging (no rebuild).
6. **Rollback.** Because images are immutable per sha and DBs are migrated forward-only, rollback =
   redeploy the previous known-good `:sha`. Keep migrations **backward-compatible** (expand/contract)
   so a redeploy of the prior image still runs against the new schema. Document a DB restore/PITR
   path for the rare destructive case.

Keep least-privilege permissions and protected environments; no long-lived cloud creds in CI — use
OIDC federation to the cloud provider where possible.

---

## 10. Cost-aware recommendation (early product)

**Do not overengineer; do not compromise data/security reliability.**

- **Hosted beta (cheapest credible):** one VPS running the existing compose, Caddy/Traefik TLS, only
  443 public. Accept single-broker Kafka (RF=1) and single Postgres nodes **only because** you add
  **automated, tested backups** and document the failure modes. Monthly cost: a single mid VPS +
  domain + cheap object storage. **Spend nothing on k8s.**
- **Public production (buy reliability where it matters):** move **data** to managed services
  (Postgres PITR + Multi-AZ; Kafka/Redpanda RF≥3; S3-compatible like R2). Run **compute** on a
  managed container platform. This is the line where "real users' data" justifies spend; everything
  else stays lean.
- **Deliberately deferred:** Kubernetes, multi-region, service mesh, autoscaling beyond fixed
  replicas, self-hosted HA Kafka. Revisit at the 90-day mark.

---

## 11. Deliverables

### 11.1 Production-readiness gap table

| Area | Current | Required for hosted beta | Required for public prod | Severity |
|------|---------|--------------------------|--------------------------|----------|
| IaC | `infra/` is placeholder READMEs | Hosted compose + env templates (no secrets) | Reproducible IaC (compose or platform config; Terraform for managed resources) | High |
| TLS | none (HTTP) | TLS at proxy in front of gateway | TLS + HSTS + security headers | **Blocker** |
| Public exposure | all service ports mapped | gateway-only; drop `8081–8089` | gateway-only; backends private | **Blocker** |
| Secrets | git-ignored `.env` | unique strong values, off-repo | secrets manager + **rotation** *(zero-downtime rotation now implemented for JWT keys + gateway secret)* | High |
| JWT/issuer/aud/CORS | dev defaults | set real values per env | set + verified; **key rotation runbook (done — `docker/README.md`)** | **Blocker** |
| Postgres durability | single node, volume only | **automated backups + CI-proven restore drill** *(`scripts/backup-databases.sh` + `restore-database.sh` + `verify-backup.sh` + `restore-drill.sh`, the drill asserting data + parking PostGIS survive a real restore in `backup-restore-drill.yml`; run once on the VPS + verify the offsite copy before relying on it)* | managed PITR + Multi-AZ failover | **Blocker** |
| Kafka durability | 1 broker, RF=1, ISR=1 | documented risk; backups of source-of-truth (outbox in DB) | managed RF≥3, `min.insync.replicas=2` | High (beta) / **Blocker** (prod) |
| Object storage | single MinIO | private bucket + signed URLs (already) + backup | managed S3-compatible, versioning, lifecycle, CORS | High |
| Redis | no auth/TLS | acceptable on private net | managed Redis + AUTH + TLS | Medium |
| Migrations in deploy | run on app boot | gated pre-deploy step | gated, backward-compatible (expand/contract) | High |
| Observability: alerts | none | alert on dead-letter + service-down + 5xx | full alert set + on-call | High |
| Tracing | traceId in logs only | optional | OTel spans → collector | Medium |
| Log aggregation | stdout only | optional | centralized (Loki/provider) | High (prod) |
| CD pipeline | none (CI only) | manual deploy ok | build→stage→smoke→approve→prod + rollback | High (prod) |
| `X-Forwarded-For` trust | not configured for proxy | configure forwarded-headers + trusted proxy | same, verified | **Blocker** (with proxy) |
| Resource limits | none in compose | set memory limits per container | requests/limits + sized replicas | Medium |
| Load/security test | none | basic smoke | load test + dependency/secret scan + pen-test pass | High (prod) |

### 11.2 Recommended architecture (text diagram)

**Phase 1 — Hosted beta (single VPS):**

```
                Internet (HTTPS :443)
                        │
                        ▼
            ┌───────────────────────┐
            │  Caddy / Traefik      │  TLS termination, 80→443, security headers
            │  (reverse proxy)      │  trusted X-Forwarded-For
            └───────────┬───────────┘
                        ▼
            ┌───────────────────────┐
            │  gateway-service:8080 │  authN, rate limit (Redis), CORS,
            │  (only public app)    │  JWKS validate, X-User-* injection
            └───────────┬───────────┘
        ┌───── private docker network (parkio-backend) ─────┐
        ▼          ▼          ▼            ▼           ▼
   auth  user  parking  media  gamification  notification  moderation
   ai-validation  analytics      (no public ports)
        │            │             │            │
        ▼            ▼             ▼            ▼
   Postgres ×9   Kafka(1)      Redis        MinIO (private bucket)
   (volumes + automated pg_dump → object storage; tested restore)
        └──── parkio-observability: Prometheus + Grafana + Alertmanager ────┘
   Frontend SPA → static host / CDN, calls https://api.parkio… (gateway)
```

**Phase 2 — Public production (managed data + managed compute):**

```
              Internet (HTTPS)              Browser ── media GET ──► Managed S3-compatible
                    │                                              (R2/B2/S3, private + presigned)
                    ▼
            Managed Load Balancer (TLS, WAF optional)
                    │
                    ▼
            gateway-service (N replicas)  ── private network ──►  backend services (N replicas each)
                    │                                                   │
                    │                                                   ▼
                    │                                   Managed Postgres (PITR, Multi-AZ)
                    │                                     • shared cluster(s), 9 DBs + 9 roles
                    │                                     • parking on PostGIS-capable instance
                    │                                                   │
                    └──► Managed Redis (AUTH+TLS)        Managed Kafka / Redpanda (RF≥3, ISR=2)
                                                          • DLT topics, lag exporter
        Observability: Prometheus/Grafana (or Grafana Cloud) + Loki logs + Tempo/Jaeger traces + Alertmanager → on-call
        CD: GitHub Actions → GHCR images (:sha) → staging → smoke → manual approve → prod (rollback = redeploy prior :sha)
```

### 11.3 30 / 60 / 90-day infra roadmap

**Days 0–30 — Ship a safe hosted beta (Option A).**
- Add a hosted compose overlay that **removes backend host ports** and adds **memory limits**.
- Put Caddy/Traefik in front of the gateway (TLS); configure forwarded-headers + trusted proxy.
- Generate prod-grade secrets; set CORS/issuer/audience; store secrets off-repo.
- Automated nightly `pg_dump` per database → encrypted object storage; **perform a test restore**.
- Minimal alerting: service-down, **outbox dead-lettered > 0**, gateway 5xx.
- Provision a registry + a build-and-push workflow (manual deploy acceptable this phase).

**Days 31–60 — De-risk data + automate delivery.**
- Stand up **staging**; CD: build → deploy staging → run integration + smoke → manual approve.
- Migrate Kafka to **managed RF≥3 / ISR=2** (`PARKIO_KAFKA_REPLICATION_FACTOR=3`), pre-create topics.
- Move media to **managed S3-compatible** (R2/B2) with versioning + lifecycle + bucket CORS.
- Centralized logs (Loki) + Grafana dashboards committed; consumer-lag + DLT-depth exporter.
- Gate migrations as a pre-deploy step (backward-compatible/expand-contract policy).

**Days 61–90 — Production hardening.**
- Migrate to **managed Postgres** (PITR + Multi-AZ; PostGIS for parking; PgBouncer if needed).
- Adopt **managed container platform** (Option B) with fixed replicas per service.
- Full alert set → on-call; OpenTelemetry tracing → Tempo/Jaeger.
- Secrets **rotation** runbooks (JWT key-id rollover, gateway-secret dual-accept) — **done** (see
  `docker/README.md`); wire them to the secrets manager once adopted.
- **Load test** + dependency/secret scanning + a security review; document RPO/RTO and DR restore.
- **Then** make the public-production GO decision.

### 11.4 Immediate next tasks (this week)

> **Phase 1 delivered (hosted-beta Compose hardening).** Items 2–5 below are now shipped:
> - `docker/docker-compose.hosted-beta.yml` — overlay that drops all backend/data host ports
>   (gateway, services, DBs, Redis, Kafka, MinIO) and binds Prometheus/Grafana to loopback only.
> - `docker/caddy/Caddyfile` + a `caddy` service — TLS reverse proxy (ACME, HTTP→HTTPS, forwarded
>   headers, HTTP/3) as the only public entrypoint; private MinIO served via a media subdomain with
>   SigV4 presigned GET URLs (bucket stays private).
> - Gateway forwarded-headers via `SERVER_FORWARD_HEADERS_STRATEGY=framework` (config-only), plus a
>   **trusted-proxy-aware client-IP resolver** (`ClientIpResolver` + `PARKIO_TRUSTED_PROXIES`) so
>   anonymous (login/register) rate limiting keys on the real client IP behind Caddy — only when the
>   socket peer is a configured trusted proxy, and spoofing-resistant (right-most non-proxy hop).
> - `docker/.env.hosted-beta.example` — complete env template (domains, TLS, CORS, public media endpoint).
> - `scripts/backup-databases.sh` — nightly per-DB `pg_dump` with optional encryption + off-box upload,
>   retention pruning, plus `restore-database.sh` (guarded single-DB restore) and `verify-backup.sh`
>   (restore into a disposable temp DB). Plumbing validated; **still TODO: run the live restore drill
>   on the VPS** (real Docker + Postgres, incl. the PostGIS `parking` DB).

1. Create `infra/environments/{staging,prod}.env.example` (every var, **no values**).
2. ~~Add a hosted Compose overlay: no backend host ports, restart policy.~~ **Done** (`docker-compose.hosted-beta.yml`). Follow-up: add memory limits per service.
3. ~~Add a TLS reverse proxy config for the gateway.~~ **Done** (`caddy/Caddyfile`).
4. ~~Configure gateway forwarded-headers + trusted-proxy (config-only).~~ **Done** (framework strategy; see rate-limit caveat).
5. Write the **DB backup + restore** scripts and **restore drill** — **Done** (`backup-databases.sh` + `restore-database.sh` + `verify-backup.sh` + `restore-drill.sh`); the drill is **automated in CI** (`backup-restore-drill.yml`) and asserts data + parking PostGIS survive a real restore. Follow-up: run it once **on the VPS** and verify the **offsite** copy.
5b. **Harden the Docker build context** — **Done** (`.dockerignore` at the repo root): every backend image builds from the repo root with `COPY . .`, so the ignore excludes `**/.env*`, `backups/`, `.git`, `node_modules`, `frontend/`, build output and docs — preventing secrets being baked into image layers and shrinking the context.
5c. **Frontend CI gate** — **Done** (`frontend-ci.yml`): typecheck + lint + unit tests + production build for the pnpm workspace, path-filtered to `frontend/**`.
6. Add a GitHub Actions **build-and-push** job (GHCR, `:sha` tags).
7. ~~Add Prometheus alert rules for the beta alerts.~~ **Done** (`docker/prometheus/alerts.yml`:
   5 critical + 5 warning rules, wired via `rule_files`; hosted-beta Grafana dashboard bundled).
   **Still TODO:** add an Alertmanager service for actual notifications (rules currently only
   surface in the Prometheus `/alerts` UI), plus Kafka/node exporters for lag & disk alerts.
8. Decide and sign off the managed providers for Phase 2 (Postgres, Kafka, object storage).

### 11.5 Risks and tradeoffs

- **Single-broker Kafka in beta (RF=1).** A broker-disk loss loses un-consumed events. *Mitigation:*
  the source of truth for produced events is the **transactional outbox in Postgres** (relay re-publishes),
  and consumers are idempotent — so DB backups substantially bound the blast radius. Still, treat
  RF≥3 as a **public-prod blocker**.
- **Shared managed Postgres cluster for 9 logical DBs.** Saves cost but is a shared failure domain and
  shared connection budget. *Mitigation:* separate roles/DBs (isolation preserved), PgBouncer, and
  split out hot DBs (parking/analytics) if load demands.
- **10 JVMs are memory-hungry.** Beta on one VPS may be RAM-bound. *Mitigation:* `MaxRAMPercentage`
  is already set; set container memory limits; scale the box or move to Option B sooner if needed.
- **`X-Forwarded-For` spoofing** if the proxy hop isn't locked down → bypassed rate limits / poisoned
  logs. *Mitigation:* explicit trusted-proxy config (a blocker, listed above).
- **MinIO single-node** has no redundancy. *Mitigation:* move to managed S3-compatible before public prod.
- **Operational complexity is real.** Even the beta requires someone to own backups, TLS renewal
  (automated via ACME), and alert response. Be honest: there is no zero-ops path for a 10-service
  event-driven system — the plan minimizes, not eliminates, that burden.
