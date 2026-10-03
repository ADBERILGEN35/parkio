# Internal service identity and Kafka authentication (design, CL-F16)

> **Status: direction approved (owner decision A4, 2026-10-03); not implemented.** Design and
> threat model only (Asana U18 / CL-F16). The approval covers the direction in section 6:
> short-lived per-service signed tokens with endpoint permissions enforced by each callee,
> segmentation, dedicated credentials and per-participant erasure ACK topics. `SASL_PLAINTEXT`
> is limited to isolated development and test networks; transport security in production is an
> explicit rollout gate (section 10, item 4). Section 10 lists which decisions are taken and
> which are still open. Nothing here is implemented, and no secret, credential or
> infrastructure changes with this document; implementation tasks are created separately.
> Inventory baseline: `api` at `f3778407` (2026-10-02). The erasure rows (2.2, 2.3, 11) and the
> consumer group ids (6) were re-checked on `api` at `fd3b81f0` (2026-10-02, after U05). Where
> this document and the code disagree, the code is authoritative.

## 1. Problem

Every backend service accepts one shared value, `X-Gateway-Auth`
(`PARKIO_GATEWAY_INTERNAL_SECRET`), as proof that a request came through the gateway. The
same value is reused for service-to-service calls and operator scripts, it protects every
`/internal/**` endpoint, and once it matches, downstream controllers trust the
`X-User-Id` / `X-User-Roles` headers. Kafka listeners are `PLAINTEXT` without
authentication or ACLs. One leaked or stolen value, or one compromised container on the
backend network, is therefore enough to act as any service, any user and any role.

The goal is an approved design for per-caller identity, endpoint scoping and Kafka
authentication/authorization, with a phased migration and a test strategy. mTLS alone is
not a complete answer (it identifies a peer, but carries neither user context nor
endpoint permissions).

## 2. Current state (from code)

### 2.1 Shared secret mechanics

| Piece | Where | Behaviour |
|---|---|---|
| Gateway stamping | `gateway-service` `GatewayAuthHeaderGlobalFilter` | Strips any inbound `X-Gateway-Auth`, sets the configured secret on **every** routed request (public and protected routes). |
| Downstream check | `GatewayAuthFilter` in auth, user, parking, media, gamification, notification, moderation, ai-validation, analytics | Requires the header on every path except `/actuator/**`, `/v3/api-docs`, `/swagger-ui`; constant-time comparison; `401 GATEWAY_AUTH_REQUIRED` otherwise. |
| Rotation | `PARKIO_GATEWAY_INTERNAL_ACCEPTED_SECRETS` | Downstream services also accept previous values during a rotation; the gateway sends only the current one. |
| Gateway routes | `gateway-service` `application.yml` | Only `/api/v1/**` paths are routed; no route forwards `/internal/**`. |
| Existing boundary checks | `runtime-validation.yml`, [`security-boundaries.md`](../operations/security-boundaries.md) | Gateway path traversal returns `400 INVALID_REQUEST_PATH`; a direct call to a service without the secret returns `401 GATEWAY_AUTH_REQUIRED`. Kafka is documented as not zero-trust. |
| Purpose reuse | `gateway-service` `application.yml` (`parkio.waitlist.hash-secret`) | The waitlist e-mail hash key falls back to the internal secret when `PARKIO_WAITLIST_HASH_SECRET` is unset. |

### 2.2 Caller → endpoint matrix

Internal endpoints (all guarded only by the shared secret unless noted):

| Endpoint | Owner | Callers found in code/scripts | Effect | Extra guard |
|---|---|---|---|---|
| `GET /internal/auth/users/{userId}/session-epoch` | auth | gateway (`SessionEpochClient`) | read session epoch | – |
| `POST /internal/auth/registration-invites` | auth | operator (`scripts/create-registration-invite.sh`) | create invites | `X-Parkio-Registration-Invite-Operator-Token` |
| `POST /internal/auth/admin/bootstrap-super-admin` | auth | operator (`scripts/bootstrap-super-admin.sh`) | grant SUPER_ADMIN | `X-Parkio-Admin-Bootstrap-Token` + enable flag |
| `POST /internal/erasure/acks` | auth | none in the services since U05 (all eight participants acknowledge on Kafka); the endpoint still exists | mark a participant's erasure step done | – |
| `POST /internal/erasure/replay` | auth | operators (restore runbooks) | republish erasure requests | – |
| `GET /internal/users/{id}/status` | user | gateway (`UserStatusClient`) | read account status | – |
| `GET /internal/users/{id}/preferred-locale` | user | notification (`UserLocaleClient`) | read locale | – |
| `POST /internal/users/smart-return/due-prompts`, `…/due-return-checks`, `…/{id}/notification-sent`, `…/{id}/return-check-completed` | user | notification (`SmartReturnUserClient`) | list due users, record sends | – |
| `POST /internal/media/{id}/access-url` | media | parking (`MediaServiceClient`) | signed URL for **any** media | – |
| `GET /internal/media/{id}/status` | media | parking (`MediaReadinessClient`) | read status | – |
| `GET /internal/media/{id}/metadata`, `GET /internal/media/{id}/content` | media | ai-validation (`MediaContentHttpClient`) | raw bytes of **any** media | – |
| `POST /internal/notifications/smart-return/trigger-*` | notification | none (test hooks) | run the scheduler | only exists with `parkio.smart-return.scheduler.test-hooks.enabled=true` |

Public API paths called service-to-service with a user identity header:

| Call | Caller | Header contract |
|---|---|---|
| `GET /api/v1/places/favourites/parking/status` on user | parking (`UserFavouritesClient`) | `X-Gateway-Auth` + `X-User-Id` |
| `GET /api/v1/parking/spots/nearby` on parking | notification (`SmartReturnParkingClient`, scheduled, no user token) | `X-Gateway-Auth` + `X-User-Id` |
| `GET /api/v1/auth/.well-known/jwks.json` on auth | gateway (`RemoteJwksKeyResolver`) | `X-Gateway-Auth` (public key material) |

User context downstream: controllers in user, parking, media, gamification, notification,
moderation, ai-validation and analytics read `X-User-Id`; `X-User-Roles` gates
admin/moderator operations in ai-validation, analytics, media, moderation and parking
(imports, municipal sync, registry-link review), and in auth's `AdminController` until
draft PR #159 (CL-F16 admin authority from the JWT principal) is merged.

### 2.3 Kafka

- Broker: `confluentinc/cp-kafka:7.7.1` (KRaft). Listeners `PLAINTEXT://:9092`
  (containers), `PLAINTEXT_HOST://:29092` (published on the host by the base Compose file
  for development; the hosted-beta overlay removes it with `ports: !reset []`) and
  `CONTROLLER`. No SASL, no TLS, no authorizer: any client that reaches the broker can
  produce to or consume from any topic, move consumer-group offsets, and create or delete
  topics.
- Every service creates the topics it uses at startup (`KafkaTopicsConfig`, `KafkaAdmin`,
  `parkio.kafka.provision-topics`).
- Producers and consumers (from `KafkaTopicsConfig` and `@KafkaListener`; DLTs omitted):

| Topic | Producer | Consumers |
|---|---|---|
| `parkio.auth.user` | auth | user |
| `parkio.user.profile` | user | – |
| `parkio.parking.spot` | parking | ai-validation, analytics, gamification, moderation, notification |
| `parkio.parking.session` | parking | analytics, notification |
| `parkio.media.media` | media | ai-validation, moderation |
| `parkio.gamification.score` | gamification | analytics, notification, user |
| `parkio.notification.notification` | notification | analytics |
| `parkio.moderation.case` | moderation | gamification, notification |
| `parkio.moderation.action` | moderation | auth, gamification, notification, parking, user |
| `parkio.aivalidation.result` | ai-validation | moderation, parking |
| `parkio.privacy.erasure` | auth (requests) **and** all eight participants: user, parking, media, gamification, moderation, notification, ai-validation, analytics (`UserErasureAcknowledged`, via each service's outbox) | every service |

- `parkio.privacy.erasure` is multi-writer. Each acknowledgement carries a self-declared
  `serviceName`, which auth's `ErasureAckKafkaConsumer` records; nothing binds it to the
  producer.
- Network: services, Kafka, Postgres, Redis, MinIO, ClamAV and the exporters share one
  flat `parkio-backend` Compose network.

## 3. Threat model

Assets: account and personal data, media objects, erasure completeness, moderation
decisions, admin authority, the integrity of event streams.

| # | Scenario | Today |
|---|---|---|
| T1 | A service is compromised (RCE, SSRF, dependency) | It holds the shared secret, so it can call every internal endpoint of every service and every public API as any user and role: forge erasure ACKs (data kept while recorded as erased), read any media bytes, mint signed media URLs, read account status and locale. Invite creation and super-admin bootstrap keep their extra operator tokens. |
| T2 | A non-service container on `parkio-backend` is compromised (e.g. ClamAV, which parses untrusted uploads; Redis, MinIO, exporters) | No shared secret, but Kafka is open: forge `parkio.moderation.action` (auth suspends or restores accounts, parking approves or rejects spots), forge erasure requests or ACKs, read PII events (`parkio.auth.user` carries e-mail), reset consumer offsets, create or delete topics. |
| T3 | The secret leaks (logs, rendered config, backups, operator workstation) | One value unlocks everything; rotation restarts every service inside the overlap window. |
| T4 | Purpose reuse | The waitlist hash key defaults to the internal secret: rotating the internal secret silently changes waitlist hashes, and every holder of the secret can compute them. |
| T5 | Host exposure | Development publishes Kafka on 29092. Production must keep it unpublished; live network isolation is a compensating control that was verified only in a private read-only record. |

Out of scope: end-user authentication (access/refresh tokens) and the public edge
(Caddy, gateway routing, CORS), which have their own controls.

## 4. Requirements

- **R1 Per-caller identity:** every internal call proves which service (or operator)
  makes it; no bearer value is shared between services.
- **R2 Endpoint scoping:** every internal endpoint lists its allowed callers; deny by
  default; CI detects drift between code and the list.
- **R3 Audience binding:** a credential presented to one service is useless at another.
- **R4 User context integrity:** user id and roles reach a service in a form only the
  gateway (or the user's own token) can produce; calls on behalf of a user without a user
  token are explicit and scoped.
- **R5 Kafka:** one authenticated principal per service; WRITE only on owned topics and
  the own DLT, READ only on subscribed topics and the own consumer group; no topic
  creation or deletion by services in production; an erasure ACK's producer is bound to
  the participant it claims to be.
- **R6** Rotation without downtime; fail closed when credentials are missing; no single
  issuer on the request path.
- **R7** Works with the current Compose deployments without a service mesh; denials are
  observable without logging credentials.
- **R8** Reversible phases with an observe-only step before enforcement.

## 5. Options

### 5.1 Service-to-service HTTP

| Option | Summary | Meets | Cost / limits |
|---|---|---|---|
| A. Per-caller static secrets | Caller sends its id plus its own secret; the callee keeps an allowlist per endpoint. | R1, R2 | Low effort; still bearer secrets (one per caller, or per pair for R3); user context stays in headers. |
| B. Self-issued signed service tokens | Each service holds its own signing key (ES256/RS256) and signs a short-lived JWT per call: `iss`/`sub` = caller, `aud` = callee, `exp` ≤ 60 s, `kid`. Callees verify with the callers' public keys (configuration or a static key set) and check the endpoint matrix. | R1–R3, R6 | Medium effort (sign + verify in nine services); no issuer at runtime; keys rotate by `kid` overlap. |
| C. Central token service | Callers obtain tokens from auth (client credentials). | R1–R3 | Adds auth to every internal call path and concentrates risk in auth; caching needed. Not proposed now. |
| D. mTLS | Per-service certificates (Spring SSL bundles or sidecars); authorization by certificate identity. | R1, R3 (+ encryption) | Needs a CA, issuance, rotation and revocation; no user context or endpoint scope by itself. A later complement, not the first step. |
| E. Network segmentation | Compose networks per trust zone (edge, app, data, scanning, messaging). | reduces T2 | Low effort, independent of the others; does not help once a legitimately connected service is compromised. |

### 5.2 User context

| Option | Summary |
|---|---|
| U1. Forward the user's access token | The gateway passes `Authorization: Bearer …` through; downstream services validate it with the auth JWKS (resource server) and stop trusting `X-User-Id` / `X-User-Roles`. |
| U2. Gateway-signed user assertion | The gateway mints a short-lived JWT (`aud` = target, `sub` = user, roles) per routed request. |
| U3. Headers inside service tokens | Keep headers, but accept them only together with a gateway service token (option B). |

Calls without a user token (the smart-return scheduler in notification) need an explicit
internal endpoint that takes the user id as a parameter and is scoped to that caller,
instead of impersonating the user on a public API.

### 5.3 Kafka

| Option | Summary | Cost / limits |
|---|---|---|
| K1. SASL/SCRAM-SHA-512 + ACLs | One SCRAM user per service; KRaft `StandardAuthorizer`, `allow.everyone.if.no.acl.found=false`, `super.users` = an admin principal only. `SASL_PLAINTEXT` on the isolated network first, `SASL_SSL` later. | Credentials per service; Spring Kafka JAAS config; SCRAM users created at bootstrap. |
| K2. mTLS client authentication | Certificate principals. | Same PKI cost as D. |
| K3. SASL/OAUTHBEARER | Reuse signed tokens from option B. | Complex broker and client configuration; not a first step. |
| K4. Managed Kafka with IAM/ACL policies | Suggested in [`security-boundaries.md`](../operations/security-boundaries.md) for public production. | Changes the deployment model and its cost; an owner decision independent of the ACL design, which carries over. |

Erasure ACK binding, either:
- per-participant ACK topics (`parkio.privacy.erasure-ack.<service>`, WRITE only for that
  service's principal; auth reads all of them), or
- keep one topic and have auth derive the participant from the record's topic or a
  verifiable signature instead of the self-declared `serviceName`.

## 6. Approved direction

Approved by the owner on 2026-10-03 (decision A4) as written below, with the Kafka
transport limitation in section 10, item 4.

1. **HTTP:** option B for service-to-service calls, with the endpoint matrix enforced by
   each callee; U1 for user-initiated requests; the two user-impersonating calls replaced
   by scoped internal endpoints. The shared gateway secret stays only as an interim
   "routed by the gateway" marker and is removed at the end.
2. **Segmentation (E) early,** as a cheap compensating control: data stores, ClamAV and
   Kafka on networks reachable only by their users.
3. **Kafka:** K1 with per-service ACLs, per-participant erasure ACK topics and topic
   provisioning by a separate admin job (services lose CREATE/DELETE/ALTER in
   production; development keeps auto-provisioning behind its flag). SCRAM over
   `SASL_PLAINTEXT` is allowed only on isolated development and test networks. Production
   transport security (`SASL_SSL` or K2) is an explicit rollout gate before P5 reaches
   production.
4. **Operators:** keep the extra operator tokens; give operator scripts their own
   credential instead of the services' value.
5. **Waitlist:** a dedicated `PARKIO_WAITLIST_HASH_SECRET` without fallback, with a plan
   for existing hashes (they were computed with whatever key was active).

ACL sketch for K1 (prefixes are literal topic names unless marked):

| Principal | WRITE | READ (topics) | READ (group) |
|---|---|---|---|
| auth | `parkio.auth.user`, `parkio.privacy.erasure`, `parkio.dlt.auth` | `parkio.moderation.action`, `parkio.privacy.erasure-ack.*` (prefixed) | `parkio.auth`, `parkio.auth.erasure` |
| user | `parkio.user.profile`, `parkio.privacy.erasure-ack.user`, `parkio.dlt.user` | `parkio.auth.user`, `parkio.gamification.score`, `parkio.moderation.action`, `parkio.privacy.erasure` | `parkio.user`, `parkio.user.erasure` |
| parking | `parkio.parking.spot`, `parkio.parking.session`, `parkio.privacy.erasure-ack.parking`, `parkio.dlt.parking` | `parkio.aivalidation.result`, `parkio.moderation.action`, `parkio.privacy.erasure` | `parkio.parking`, `parkio.parking.erasure` |
| … | one row per service, derived from the topic table in 2.3 | | |
| provisioning job | CREATE, ALTER_CONFIGS on `parkio.` (prefixed) | – | – |
| kafka-exporter | – | DESCRIBE on topics and groups | – |
| DLT redrive operator | the source topics named in a redrive | own DLT | dedicated group |

Consumer group ids are set explicitly on every `@KafkaListener` (`groupId = GROUP`), which
overrides `spring.kafka.consumer.group-id: ${spring.application.name}`; ACLs must name these
groups, not the application names. On `api` `fd3b81f0` (32 listeners):

| Service | Groups |
|---|---|
| auth | `parkio.auth`, `parkio.auth.erasure` |
| user | `parkio.user`, `parkio.user.erasure` |
| parking | `parkio.parking`, `parkio.parking.erasure` |
| gamification | `parkio.gamification`, `parkio.gamification.erasure` |
| moderation | `parkio.moderation`, `parkio.moderation.erasure` |
| notification | `parkio.notification`, `parkio.notification.erasure` |
| analytics | `parkio.analytics`, `parkio.analytics.erasure` |
| ai-validation | `parkio.aivalidation`, `parkio.ai-validation.erasure` (two different prefixes) |
| media | `parkio.media.erasure` only |

Literal group ACLs per service (or one prefixed ACL `parkio.<svc>` per service, with an extra
literal ACL for `parkio.ai-validation.erasure`) cover them. Granting the application names
instead would make every consumer fail with `GroupAuthorizationException` once
`allow.everyone.if.no.acl.found=false` is set.

## 7. Migration phases

Each phase is reversible by configuration or a Compose revert and has its own acceptance
evidence.

| Phase | Change | Rollback | Acceptance |
|---|---|---|---|
| P0 Guardrails | Checked-in internal endpoint matrix and a CI test that every `/internal` mapping is listed; denial metrics; a rendered-Compose check that production never publishes Kafka. | remove the test | CI fails on an unlisted endpoint |
| P1 Segmentation | Compose networks per trust zone. | Compose revert | runtime validation green; reachability probes from data/scan containers to services and Kafka fail |
| P2 Service tokens, observe-only | Keys per service; callers send a service token next to `X-Gateway-Auth`; callees verify and record allowed/denied against the matrix but still accept the shared secret. | flag off | zero unexplained denials over an agreed window |
| P3 Enforce `/internal/**` | A valid token from an allowed caller is required; the shared secret alone is refused there; operator scripts use operator credentials. | flag off | allowed callers 2xx, others 403 in CI and runtime validation |
| P4 User context | The gateway forwards the user token; services validate it and ignore user headers; the two impersonating calls move to scoped internal endpoints. | flag off per service | header-only requests rejected; user flows green |
| P5 Kafka | Add a SASL listener next to PLAINTEXT, create SCRAM users, move services one at a time; ACLs first with `allow.everyone.if.no.acl.found=true` while authorizer denials are observed, then `false`; per-participant ACK topics with auth reading old and new during the move; provisioning job; remove PLAINTEXT. `SASL_PLAINTEXT` only on isolated development and test networks; production waits for the transport-security gate (section 10, item 4). | re-enable PLAINTEXT listener | wrong-principal produce/consume and topic creation denied; erasure end-to-end green; in production, the chosen transport security is in place |
| P6 Retire the shared secret | Remove it from business services and scripts; rotate all credentials; drop the accepted-secrets path. | redeploy previous release | no service accepts `X-Gateway-Auth` alone |

Sequencing constraints: P3 needs the U05 participant ACK work settled (erasure ACKs move
between HTTP and Kafka there); P5's ACK topics change the erasure contract
(`docs/architecture/erasure-ack-outbox-contract.md`) and need the same review.

## 8. Test strategy

- **Static:** matrix drift test (every `@RequestMapping("/internal…")` method appears in
  the matrix and every matrix row has a test); client inventory test (every client with
  an internal base URL obtains its credential from the identity component).
- **Unit, per service:** the verifier accepts an allowed caller and rejects a wrong
  audience, an expired token, an issue time beyond the skew budget, an unknown `kid`, a
  disallowed caller (403) and a missing token (401); credentials never appear in logs.
- **HTTP slices, per internal endpoint:** allowed caller succeeds, any other caller gets
  403.
- **Runtime validation (Compose, CI):** calls between real containers with and without
  tokens; Kafka: produce as a non-owner (`TopicAuthorizationException`), consume with a
  foreign group (`GroupAuthorizationException`), write another participant's ACK topic,
  create a topic as a service — all denied.
- **Rotation drill:** old and new keys overlap; after removal, tokens signed with the old
  key are rejected.
- **Failure behaviour:** missing key material stops the service at startup (fail
  closed); bounded clock skew.
- **Regression:** existing runtime validation (including the path-traversal and
  direct-access checks), the erasure end-to-end path and the backup and restore drills
  stay green.

## 9. Operational cost

| Mechanism | New material | Rotation | Runtime dependency |
|---|---|---|---|
| Service tokens (B) | one key pair per service (nine plus gateway); public keys in configuration | add new `kid`, deploy, remove old | none |
| User token forwarding (U1) | none (auth JWKS already exists) | follows JWT key rotation | JWKS fetch, cached |
| Segmentation (E) | none | – | none |
| Kafka SCRAM + ACLs (K1) | one SCRAM credential per service, admin and exporter principals | per credential, overlap by creating the new one first | broker authorizer |
| Operator credentials | one per operator role | per role | none |

## 10. Decisions

Owner decision A4 (2026-10-03) approved the direction in section 6. Item by item:

1. **HTTP direction: approved.** B + U1 + scoped internal endpoints: short-lived
   per-service signed tokens, with the endpoint matrix enforced by each callee.
2. **Shared verification code versus the "no shared module" rule** in
   [`kafka-transport.md`](kafka-transport.md): **open.** Choose between a small verifier
   duplicated per service and one security library.
3. **Token lifetime and clock-skew budget: open.**
4. **Kafka: approved with a gate.** SCRAM with per-service ACLs (K1). `SASL_PLAINTEXT` only on
   isolated development and test networks. Transport security in production (`SASL_SSL` or
   mTLS) is an explicit rollout gate: P5 does not reach production until it is chosen and
   in place.
5. **Erasure ACK binding: approved.** Per-participant ACK topics.
6. **Operator endpoints: approved as section 6, item 4.** Operator scripts get dedicated
   credentials instead of the services' value.
7. **Waitlist hash key separation and existing hashes: open.**
8. **Sharing the private live network-isolation record with reviewers: open.**

Items 2, 3, 7 and 8 need owner decisions before the phases that depend on them (P2 for 2
and 3). Segmentation (E) is approved as part of section 6.

## 11. Code references

- Gateway: `services/gateway-service/.../security/GatewayAuthHeaderGlobalFilter.java`,
  `.../client/SessionEpochClient.java`, `.../client/UserStatusClient.java`,
  `.../security/RemoteJwksKeyResolver.java`, `src/main/resources/application.yml`
  (`parkio.gateway.internal-secret`, `parkio.waitlist.hash-secret`, routes).
- Downstream guard: `services/*/src/main/java/.../infrastructure/web/GatewayAuthFilter.java`.
- Internal controllers: auth `InternalAuthController`, `InternalRegistrationInviteController`,
  `InternalAdminBootstrapController`, `InternalErasureController`; user
  `InternalUserController`; media `InternalMediaController`; notification
  `InternalSmartReturnController`.
- Clients (the `AuthErasureAckClient`s of media and notification were removed by U05);
  notification `UserLocaleClient`,
  `SmartReturnUserClient`, `SmartReturnParkingClient`; parking `MediaServiceClient`,
  `MediaReadinessClient`, `UserFavouritesClient`; ai-validation `MediaContentHttpClient`.
- Kafka: `docker/docker-compose.yml` (`kafka` service), `docker/docker-compose.hosted-beta.yml`,
  each service's `KafkaTopicsConfig`, auth `ErasureAckKafkaConsumer`,
  [`kafka-transport.md`](kafka-transport.md), [`event-contracts.md`](event-contracts.md),
  [`security-boundaries.md`](../operations/security-boundaries.md).
- Related work: Asana U18 admin authorization (draft PR #159), U05 participant ACK outbox.
