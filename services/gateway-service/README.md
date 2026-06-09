# gateway-service

API gateway and edge routing for all Parkio services

- **Package:** `com.parkio.gateway`
- **Default port:** `8080` (override with `SERVER_PORT`)

## Architecture

This service follows clean architecture. Source lives under
`src/main/java/com/parkio/gateway`:

| Layer            | Responsibility                                                        |
|------------------|-----------------------------------------------------------------------|
| `domain`         | Enterprise rules: entities, value objects, domain services, ports.    |
| `application`    | Use cases / orchestration of domain logic.                            |
| `infrastructure` | Adapters: persistence, messaging, external clients, configuration.    |
| `presentation`   | Inbound adapters: REST controllers, request/response models.          |
| `shared`         | Cross-cutting helpers scoped to this service only.                    |

> This service owns its own models. Domain models are **not** shared across services.

## Responsibilities

The gateway is the **only public ingress** for backend APIs. It:

- validates JWT access tokens at the edge (signature, issuer, expiry);
- routes `/api/v1/**` requests to the owning downstream service;
- **role-gates** privileged routes at the edge (see the role matrix above), rejecting
  unauthorized callers with a `403` before they reach any service;
- **enforces live account status** on protected routes (see below): a valid token
  proves identity, not that the account is still active, so the gateway checks the
  caller's current status from user-service and blocks suspended/banned accounts;
- **rate-limits** every route per caller (Redis token bucket; see Security below);
- **strips** any client-supplied identity headers (`X-User-Id`, `X-User-Email`,
  `X-User-Roles`) on *every* request — a client must never control identity;
- **injects** trusted identity headers after successful validation
  (`X-User-Id` = `sub`, `X-User-Email` = `email`, `X-User-Roles` = comma-separated
  `roles`), overriding anything the client sent. Downstream services trust these only
  because they are reachable solely on the internal network, behind this gateway;
- **stamps a shared internal secret** (`X-Gateway-Auth`) onto every routed request
  (`GatewayAuthHeaderGlobalFilter`), stripping any client-supplied copy first. Every
  downstream service requires this secret and returns `401` (`GATEWAY_AUTH_REQUIRED`)
  without it — so a directly-reachable service cannot be called without the gateway,
  enforcing the trust boundary in code (not deployment alone). The secret is
  externalized (`PARKIO_GATEWAY_INTERNAL_SECRET`, no production default → fail closed);
  the gateway's own user-status `WebClient` sends it too;
- manages a request correlation id (`X-Correlation-Id`): forwards a client-supplied
  one or generates it, propagates it downstream, echoes it on the response, and
  includes it as `traceId` in error bodies.
- passes the client `Idempotency-Key` header through unchanged. Parking
  create/claim/verify and media upload validate and persist idempotency in their
  owning service databases; the gateway holds no idempotency state.

It holds **no business state and no database**.

> **Trust boundary — downstream services must be private.** The `X-User-*` identity
> headers are an *internal trust* contract: they are believable only because the
> gateway is the sole ingress and downstream services are **not publicly reachable**.
> In every environment, bind service ports to the internal network only (Docker
> network / Kubernetes `ClusterIP`, never a public `LoadBalancer`/host port for a
> backend service) and expose **only** the gateway. The `X-Gateway-Auth` shared secret
> is a second line of defence (a directly-exposed service still rejects un-gatewayed
> calls with `401`), **not** a substitute for network isolation — anyone who obtains the
> secret could forge requests, so keep services private regardless. See `docker/README.md`.

## Account status enforcement

A JWT proves **identity**, but it stays valid until it expires — so a user suspended
or banned *after* their token was issued would otherwise keep access until expiry. To
close that gap, after JWT validation the gateway checks the caller's **current**
account status on every protected route:

1. JWT validated → `authUserId` (`sub`) extracted and injected as `X-User-Id`.
2. `AccountStatusGlobalFilter` calls user-service
   `GET /internal/users/{authUserId}/status` (non-blocking `WebClient`,
   `UserStatusClient`). That endpoint returns only `{ userId, status }` — no profile
   data — and is **internal-only** (the gateway routes only `/api/v1/**`, so
   `/internal/**` is never publicly reachable).
3. **Only `ACTIVE` is allowed through.** Any other status (`SUSPENDED`/`BANNED`/…), or
   an unknown/not-yet-provisioned account (user-service `404`), is rejected with a
   `403` `ApiError` (`code: ACCOUNT_NOT_ACTIVE`). A single code is used so the edge
   does not leak whether an account exists or its exact moderation state.
4. **Fail closed.** If the status cannot be determined (user-service unreachable,
   timeout, or unexpected error) the request is rejected with `503`
   (`code: USER_STATUS_UNAVAILABLE`) rather than being let through on an unknown status.
5. **Brief cache.** Resolved statuses are cached in-memory for a small TTL (default
   `30s`, `UserStatusCache`) to keep the check cheap; `404`/unavailable results are
   never cached (so a freshly-provisioned account activates immediately and outages are
   re-checked next request). Trade-off: a status change takes effect within the TTL.

Public auth routes (`login`/`register`/`refresh-token`/`logout`) and actuator carry no
identity and are **never** status-checked. Config lives under
`parkio.gateway.user-status.*` (`base-url`, `cache-ttl`, `request-timeout`;
env-overridable via `PARKIO_USER_SERVICE_URI`, `PARKIO_USER_STATUS_CACHE_TTL`,
`PARKIO_USER_STATUS_TIMEOUT`).

> This is deliberately a lightweight status check, **not** a heavyweight token
> introspection service — it answers one question ("is this account active right now?")
> against the service that owns account status (user-service, ai-context/03).

## Routes

| Path prefix                 | Target                  | Access            |
|-----------------------------|-------------------------|-------------------|
| `/api/v1/auth/**`           | `auth-service`          | mixed¹            |
| `/api/v1/users/**`          | `user-service`          | authenticated     |
| `/api/v1/parking/**`        | `parking-service`       | authenticated     |
| `/api/v1/media/**`          | `media-service`         | authenticated     |
| `/api/v1/gamification/**`   | `gamification-service`  | authenticated     |
| `/api/v1/notifications/**`  | `notification-service`  | authenticated     |
| `/api/v1/moderation/**`     | `moderation-service`    | mixed²            |
| `/api/v1/ai-validations/**` | `ai-validation-service` | mixed³            |
| `/api/v1/analytics/**`      | `analytics-service`     | `MODERATOR`/`ADMIN` |

¹ Public: `POST /api/v1/auth/register`, `login`, `refresh-token`, `logout`. Any
other auth path is protected. Actuator `health`/`info` are public.
² See the role matrix below: user-facing report/appeal endpoints need only an
authenticated user; case/appeal management requires `MODERATOR`/`ADMIN`.
³ Read-only lookups need an authenticated user; `POST /api/v1/ai-validations/manual`
requires `MODERATOR`/`ADMIN`.

Downstream URIs are externalized (`PARKIO_<SERVICE>_SERVICE_URI`), defaulting to
local dev ports.

### Edge role matrix

Authentication (a valid JWT) is enforced for every non-public route. On top of that,
the gateway role-gates privileged surfaces at the edge (`RouteAuthorizationRules` +
`AuthorizationGlobalFilter`) so an ordinary `USER` can never reach them — defense in
depth, independent of each service's own per-endpoint checks. Rules are first-match-wins:

| Method + path                          | Required role            |
|----------------------------------------|--------------------------|
| `POST /api/v1/moderation/reports`      | any authenticated user   |
| `GET  /api/v1/moderation/reports/me`   | any authenticated user   |
| `POST /api/v1/moderation/appeals`      | any authenticated user   |
| `*    /api/v1/moderation/**` (else)    | `MODERATOR` or `ADMIN`   |
| `*    /api/v1/analytics/**`            | `MODERATOR` or `ADMIN`   |
| `POST /api/v1/ai-validations/manual`   | `MODERATOR` or `ADMIN`   |
| `GET  /api/v1/ai-validations/**` (else)| any authenticated user   |
| everything else                        | any authenticated user   |

A request lacking the required role is rejected at the edge with a consistent `403`
`ApiError` (`code: FORBIDDEN`) carrying the correlation id as `traceId`; it never
reaches the downstream service.

## Security

- **JWT (HS256)** validated with the same secret/issuer as `auth-service`
  (`PARKIO_JWT_SECRET`, `PARKIO_JWT_ISSUER`). The secret has **no default** — the
  gateway fails to start without it (fail closed).
- **CORS** is configured via `parkio.gateway.cors.*` (origins empty by default →
  no cross-origin browser access until configured per environment). Lock origins
  down per environment (env var `PARKIO_CORS_ALLOWED_ORIGINS`).
- **Rate limiting** runs at the edge on every route via Spring Cloud Gateway's
  `RequestRateLimiter` (a Redis token bucket). The bucket **key** is the authenticated
  `userId` when present (so a logged-in caller is limited as an individual across
  IPs), else the client IP for anonymous endpoints (`RateLimitConfig.userOrIpKeyResolver`).
  Limits are tiered and env-overridable (conservative defaults):

  | Tier (routes)                        | replenish/s | burst | env vars |
  |--------------------------------------|------------:|------:|----------|
  | auth login/register (`/auth/**`)     | 5           | 10    | `PARKIO_RL_AUTH_REPLENISH` / `_BURST` |
  | media upload (`/media/**`)           | 2           | 5     | `PARKIO_RL_MEDIA_REPLENISH` / `_BURST` |
  | parking create/verify/claim (`/parking/**`) | 10   | 20    | `PARKIO_RL_PARKING_REPLENISH` / `_BURST` |
  | general authenticated APIs           | 30          | 60    | `PARKIO_RL_DEFAULT_REPLENISH` / `_BURST` |

  **Redis dependency / failure behaviour.** Rate limiting requires Redis
  (`spring.data.redis.*`; in compose it is the health-gated `redis` service). In
  production, run Redis as a hard dependency and keep limiting **on** — i.e. *fail
  closed*: do not disable the limiter to "stay up" if Redis is down. For **local dev**,
  start Redis from `docker/docker-compose.yml` (`docker compose up -d redis`, exposed
  on `localhost:6379`); the `dev`/default config points there. Note: Spring Cloud
  Gateway's `RedisRateLimiter` itself fails *open* on a Redis error (it lets the
  request through rather than 500), so Redis must be treated as part of the edge's
  availability, not an optional add-on.

### Backlog: asymmetric signing

The HS256 shared secret means any service holding it could mint tokens. Before
wider rollout, migrate to **RS256/ES256 + a JWKS endpoint**, so the gateway
verifies with a public key and only `auth-service` holds the private key. This must
be coordinated with `auth-service` so both switch together. Not implemented now to
stay compatible with the current auth-service contract.

## Run locally

For local development run under the `dev` profile (supplies a non-production JWT
secret that matches `auth-service`'s dev secret) or export `PARKIO_JWT_SECRET`:

```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew :services:gateway-service:bootRun
```

## Build & test

```bash
./gradlew :services:gateway-service:build
```

## Docker

```bash
docker build -f services/gateway-service/Dockerfile -t parkio/gateway-service .
docker run -p 8080:8080 parkio/gateway-service
```
