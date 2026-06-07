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
- **strips** any client-supplied identity headers (`X-User-Id`, `X-User-Email`,
  `X-User-Roles`) — a client must never control identity;
- **injects** trusted identity headers after successful validation
  (`X-User-Id` = `sub`, `X-User-Email` = `email`, `X-User-Roles` = comma-separated
  `roles`). Downstream services trust these only because they are reachable solely
  on the internal network, behind this gateway;
- manages a request correlation id (`X-Correlation-Id`): forwards a client-supplied
  one or generates it, propagates it downstream, echoes it on the response, and
  includes it as `traceId` in error bodies.

It holds **no business state and no database**.

## Routes

| Path prefix               | Target                  | Access     |
|---------------------------|-------------------------|------------|
| `/api/v1/auth/**`         | `auth-service`          | mixed¹     |
| `/api/v1/users/**`        | `user-service`          | protected  |
| `/api/v1/parking/**`      | `parking-service`       | protected² |
| `/api/v1/media/**`        | `media-service`         | protected² |
| `/api/v1/gamification/**` | `gamification-service`  | protected² |
| `/api/v1/notifications/**`| `notification-service`  | protected² |
| `/api/v1/moderation/**`   | `moderation-service`    | protected² |

¹ Public: `POST /api/v1/auth/register`, `login`, `refresh-token`, `logout`. Any
other auth path is protected. Actuator `health`/`info` are public.
² Placeholder routes for services not yet implemented; the edge contract is wired
now and the targets answer once each service is built.

Downstream URIs are externalized (`PARKIO_<SERVICE>_SERVICE_URI`), defaulting to
local dev ports.

## Security

- **JWT (HS256)** validated with the same secret/issuer as `auth-service`
  (`PARKIO_JWT_SECRET`, `PARKIO_JWT_ISSUER`). The secret has **no default** — the
  gateway fails to start without it (fail closed).
- **CORS** is configured via `parkio.gateway.cors.*` (origins empty by default →
  no cross-origin browser access until configured per environment).
- **Rate limiting** is a documented backlog item: Spring Cloud Gateway's
  `RequestRateLimiter` needs Redis, which is not yet a project dependency. See the
  commented block in `application.yml` for how to enable it.

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
