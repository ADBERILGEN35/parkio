# PP-01B-SPIKE-03 — Private networking, DNS and TLS connectivity

| Field | Value |
|-------|-------|
| Spike ID | **PP-01B-SPIKE-03** |
| Mode A status | **COMPLETE — PASS WITH NON-BLOCKING NOTES** |
| Mode B status | **READY WITH CONDITIONS** (not executed) |
| Verification date | **2026-08-04** |
| ADR | [ADR-PP-01A](adr/ADR-PP-01A-managed-postgresql.md) — **unchanged** |
| Registry | [pp-01b-spike-registry.md](pp-01b-spike-registry.md) |
| Evidence index | [evidence/pp-01b-spike-03/README.md](evidence/pp-01b-spike-03/README.md) |

**Authorization boundary:** Mode A does **not** authorize Azure provisioning, IaC generation, production apply, SPIKE-02 Mode B, PP-01C, or PP-02…PP-06. PP-01 remains **open**. Public production **NO-GO**. Municipal production **disabled**. No Azure resource provisioned. No Azure credential used.

---

## 1. Mode A decision

**PASS WITH NON-BLOCKING NOTES**

Local repository contract, TLS positive/negative matrix, hostname verification, DNS hostname contract, Hikari pool recovery, and production private-only guard all **PASS**. Azure Private DNS / failover / managed certificate chain remain **UNKNOWN** until Mode B.

---

## 2. Frozen connectivity contract (recommended)

| Rule | Contract |
|------|----------|
| Clusters | Two Flexible Server FQDNs — **core** and **parking** (placeholders only; no frozen production hostnames) |
| Addressing | DNS names only — **no** hard-coded private IPs |
| Public access | **Disabled** |
| Reachability | Private network only |
| TLS | **Required** — client `sslmode=verify-full` |
| Trust | Azure Database for PostgreSQL CA bundle via `sslrootcert` / JVM trust material (PP-03 owns distribution) |
| Databases / roles | 10 databases / 10 application login roles; parking → parking cluster only |
| Migrator vs runtime | Repository today shares one datasource identity with Flyway (**FACT**). Separate migrator/runtime privilege is **PP-01C / PP-03** ownership — not silently invented here |
| Secrets | No password in Git, images, Vite public vars, or logs — **PP-03** |
| Ingress | No direct DB exposure via Caddy / gateway / public ingress |
| Failover | Apps reconnect via DNS + bounded Hikari timeouts; do not cache private IPs in config |

---

## 3. TLS policy

| Mode | Production | Local Mode A |
|------|------------|--------------|
| `disable` / `allow` / `prefer` | **Forbidden** | Forbidden on TLS-required test target |
| `require` | Insufficient (no identity) | Bootstrap-only exception inside harness |
| `verify-ca` | Staged exception only (WARN) | Not preferred |
| `verify-full` | **Required target** | Proven locally with ephemeral CA + `DNS:localhost` SAN |

**EXTERNAL VERIFICATION (2026-08-04):** Microsoft Learn documents Azure Flexible Server TLS and recommends `verify-full` / `verify-ca` with Azure root CAs. Revalidate before Mode B paid apply:

- https://learn.microsoft.com/en-us/azure/postgresql/security/security-tls-how-to-connect
- https://learn.microsoft.com/en-us/azure/postgresql/security/security-tls

---

## 4. Azure private-network documentation (EXTERNAL VERIFICATION)

| Claim | Class | Source | Revalidation |
|-------|-------|--------|--------------|
| Private access = VNet integration (delegated subnet) | EXTERNAL | [concepts-networking-private](https://learn.microsoft.com/en-us/azure/postgresql/network/concepts-networking-private) (ms.date ~2026-07-13) | Before Mode B |
| Private DNS zone required; suffix `.postgres.database.azure.com` | EXTERNAL | same | Before Mode B |
| Client VNets need Private DNS zone links for cross-VNet resolve | EXTERNAL | same + Tech Community DNS patterns | Before Mode B |
| Public access can be disabled under private posture | EXTERNAL | Networking docs | Before Mode B |
| Private endpoint vs VNet integration are distinct models; create-time choice | EXTERNAL | Networking overview | Before Mode B |
| Parkio live private DNS / failover | **UNKNOWN** | — | Mode B only |
| Managed cert chain pin for Parkio JVM | **UNKNOWN** | — | Mode B only |

Do **not** treat EXTERNAL docs as live Parkio proof.

---

## 5. Ten-service connectivity matrix (REPOSITORY FACT)

| Service | Database | Login role (default) | Cluster | Env | Pool max (default) | TLS in repo defaults | Notes |
|---------|----------|----------------------|---------|-----|--------------------|----------------------|-------|
| auth-service | `parkio_auth` | `parkio_auth` | core | `SPRING_DATASOURCE_*` | 8 | none (`sslmode` absent) | Flyway shares runtime DS |
| gateway-service | `parkio_gateway` | `parkio_gateway` | core | same | 4 | none | |
| user-service | `parkio_user` | `parkio_user` | core | same | 8 | none | |
| parking-service | `parkio_parking` | `parkio_parking` | **parking** | same | 8 | none | PostGIS Compose image |
| media-service | `parkio_media` | `parkio_media` | core | same | 8 | none | |
| gamification-service | `parkio_gamification` | `parkio_gamification` | core | same | 8 | none | |
| notification-service | `parkio_notification` | `parkio_notification` | core | same | 8 | none | |
| moderation-service | `parkio_moderation` | `parkio_moderation` | core | same | 8 | none | |
| analytics-service | `parkio_analytics` | `parkio_analytics` | core | same | 8 | none | |
| ai-validation-service | `parkio_aivalidation` | `parkio_aivalidation` | core | same | 8 | none | |

Shared Hikari defaults (most services): connection-timeout **2000** ms, validation-timeout **1000** ms, idle-timeout **600000** ms, max-lifetime **1800000** ms, minimum-idle **2** (gateway min-idle **1**).

**Aggregate theoretical max (app pools only):** \(9 \times 8\) + \(1 \times 4\) = **76** connections, plus Flyway startup bursts, ops/admin reserve, and Mode B SKU limits — **UNKNOWN** exact Flexible Server `max_connections` until SKU chosen (SPIKE-01 / Mode B). Guardrail: stop if planned aggregate \> 70% of SKU `max_connections`.

**Gaps (honest):** no production `sslmode` in YAML defaults; Compose uses plaintext Docker DNS + published ports (local/hosted-beta, not production certification); migrator/runtime roles not separated.

---

## 6. Local TLS target

| Item | Value |
|------|-------|
| Image | `postgres:16-alpine@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777` |
| PostgreSQL | 16.x (alpine tag) |
| Hostname under test | `localhost` (SAN); production FQDN contract separate |
| CA / keys | Ephemeral OpenSSL material; temp dir; gitignored; deleted after suite |
| Harness | `PostgresTlsConnectivityIT` |
| JDBC driver | `org.postgresql:postgresql:42.7.11` |

---

## 7. JDBC / DNS / pool / guard results (Mode A)

| Case | Result |
|------|--------|
| Plaintext / `sslmode=disable` | FAIL (rejected) — PASS |
| Untrusted CA + `verify-full` | FAIL — PASS |
| Hostname mismatch (`127.0.0.1` vs `DNS:localhost`) | FAIL — PASS |
| Trusted CA + correct hostname | PASS |
| Wrong password | Auth fail, no secret leak — PASS |
| Role isolation (auth ↛ user DB) | PASS |
| Migrate role TLS connect | PASS (contract probe; repo still shares Flyway identity) |
| DNS hostname contract (no IP literals in prod-shaped URLs) | PASS (local) |
| Azure DNS/failover | **UNKNOWN** until Mode B |
| Hikari recovery after backend terminate | PASS |
| Production private-only guard | PASS |
| Required tests skipped | **0** |

---

## 8. Secret boundary (PP-03)

SPIKE-03 defines only the contract: no secrets in Git/images/Vite/logs; per-service credentials; rotation-compatible URLs; runtime injection; trust material delivery. **No** Key Vault implementation in this package.

---

## 9. Operator connectivity assumptions

| Path | Current assumption (FACT) | Remediation owner |
|------|---------------------------|-------------------|
| Flyway | Same `SPRING_DATASOURCE_*` as runtime | PP-01C / PP-03 |
| Compose / local | Docker service DNS, often plaintext | Local allowed; prod guard forbids |
| Hosted-beta | Public DB port resets possible | Classify non-prod; PP-01D/E |
| Backup/restore / `psql` / smoke | Often localhost / `docker exec` / superuser | PP-01D / PP-01E / PP-05 |
| Production private DNS + TLS ops | Not implemented | PP-01B Mode B + PP-03 |

---

## 10. Mode B readiness

**READY WITH CONDITIONS**

Smallest board-approved sandbox must prove: private access + Private DNS, public disabled, `verify-full` with managed chain, in-network connect / out-of-network deny, two-cluster DNS names, role isolation, pool recovery after controlled failover (where safe), no secrets committed, cleanup + cost recorded.

Prerequisites: board approval, cost ceiling, region/SKU revalidation, naming, cleanup deadline, no production data/credentials/traffic.

---

## 11. Program status after Mode A

| Item | Status |
|------|--------|
| PP-01B-SPIKE-03 Mode A | **COMPLETE — PASS WITH NON-BLOCKING NOTES** |
| PP-01B-SPIKE-03 Mode B | **READY WITH CONDITIONS** — not executed |
| PP-01B-SPIKE-02 Mode B | Separate — **not executed** |
| PP-01 | **OPEN** |
| Public production | **NO-GO** |
| Municipal production | **DISABLED** |
| PP-01C / PP-02…PP-06 | **Not started** |
