# auth-service

Authentication, authorization and token issuance

- **Package:** `com.parkio.auth`
- **Default port:** `8081` (override with `SERVER_PORT`)

## Architecture

This service follows clean architecture. Source lives under
`src/main/java/com/parkio/auth`:

| Layer            | Responsibility                                                        |
|------------------|-----------------------------------------------------------------------|
| `domain`         | Enterprise rules: entities, value objects, domain services, ports.    |
| `application`    | Use cases / orchestration of domain logic.                            |
| `infrastructure` | Adapters: persistence, messaging, external clients, configuration.    |
| `presentation`   | Inbound adapters: REST controllers, request/response models.          |
| `shared`         | Cross-cutting helpers scoped to this service only.                    |

> This service owns its own models. Domain models are **not** shared across services.

## Run locally

From the repository root:

```bash
./gradlew :services:auth-service:bootRun
```

## Build & test

```bash
./gradlew :services:auth-service:build
```

## Docker

```bash
docker build -f services/auth-service/Dockerfile -t parkio/auth-service .
docker run -p 8081:8081 parkio/auth-service
```

## Roles

Roles are stored and carried in the JWT `roles` claim by their **unprefixed**
names — `USER`, `MODERATOR`, `ADMIN` (the `RoleName` domain enum). The Spring
Security `ROLE_` prefix is applied only when building authorities in
`JwtAuthenticationFilter`, so `hasRole("USER")` works downstream while tokens
stay clean.

## Registration fields

`email` is the **sole login identifier** — required, validated and unique.
auth-service stores no profile data; a phone number is **not** collected or
stored here (it belongs to user-service, per ai-context/03).

## Local development

Access tokens are signed with RS256. The private key has **no production
default**, and auth-service fails to start without it. Supply a PKCS#8 PEM through
`PARKIO_JWT_PRIVATE_KEY_PEM`; optionally set `PARKIO_JWT_KEY_ID` (default
`parkio-auth-rs256-1`). The private key is never returned by an API.

For local development, run
`SPRING_PROFILES_ACTIVE=dev ./gradlew :services:auth-service:bootRun`. The dev
profile explicitly generates an ephemeral 2048-bit RSA key on startup, so tokens
become invalid after a restart. Tests use the same ephemeral-only mechanism and
do not commit a static private key.

The public key is exposed at
`GET /api/v1/auth/.well-known/jwks.json` with `kty=RSA`, `alg=RS256`,
`use=sig`, `kid`, modulus (`n`) and exponent (`e`) only.

## Refresh tokens

Refresh tokens are opaque 256-bit random values. Only their SHA-256 hash is
persisted (`refresh_tokens.token_hash`); the raw value is returned to the client
exactly once. On `POST /api/v1/auth/refresh-token` the presented token is
**rotated**: the old row is revoked and a brand-new token is issued, atomically
in one transaction. `logout` revokes only the presented token (per-session).

## Security hardening backlog

Known, intentionally-deferred gaps — documented so they are not mistaken for
finished work. None is implemented yet.

- **Refresh-token reuse / theft detection.** Rotation revokes the old token, but
  replaying an already-rotated (revoked) token is treated as an ordinary invalid
  token (`INVALID_REFRESH_TOKEN`) rather than as a breach signal. Production
  hardening should detect reuse of a rotated token and revoke the entire token
  family / all of the user's active sessions (and ideally alert). This needs
  token-family/lineage tracking on `refresh_tokens` and is out of scope for the
  current foundation. See the `TODO(security-hardening)` in
  `AuthApplicationService.refresh`.
- **Global "log out everywhere".** Only single-token logout exists today; a bulk
  "revoke all tokens for user" operation/endpoint is not implemented.
- **Login throttling / lockout.** No service-level rate limiting on failed
  logins (gateway-level rate limiting is a separate concern).
