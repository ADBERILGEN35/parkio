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

Registration passwords must be 12-100 characters, include at least one lowercase
letter, one uppercase letter and one digit, and must not match the service-local
common-password deny-list. Symbols are allowed but not required. The policy is
implemented in `PasswordPolicy` so future password-reset hooks can reuse the
same server-side validation.

## Login brute-force protection

auth-service applies Redis-backed per-account protection keyed by normalized
email. This complements the gateway's Redis token-bucket rate limits, which
remain per authenticated user or anonymous client IP.

Policy:

- 5 failed attempts: lock the normalized-email key for 30 seconds.
- 10 failed attempts: lock for 5 minutes.
- 20 failed attempts: lock for 1 hour.
- Successful login clears the failed-attempt counter and any lock key.

The failed-attempt and lock keys live in Redis (`auth:login:failures:*`,
`auth:login:lock:*`), so the policy works across multiple auth-service
instances. Configure Redis with `SPRING_DATA_REDIS_HOST` and
`SPRING_DATA_REDIS_PORT`; production Redis should use network isolation plus
AUTH/TLS where the provider supports it.

Wrong password, unknown email and locked-account responses all return the same
generic `401 INVALID_CREDENTIALS` shape. This intentionally avoids revealing
whether an email exists or whether the account key is locked. Internally, logs
record lock events without raw email addresses, and Micrometer exposes:

- `login_failures_total`
- `login_lockouts_total`
- `login_success_total`

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

## Token claims: issuer and audience

Issued access tokens carry `iss`, `aud`, `sub` (user id), `email`, `roles`,
`status`, `iat` and `exp`.

- **Issuer (`iss`)** identifies *who signed* the token: `PARKIO_JWT_ISSUER`
  (default `parkio-auth`).
- **Audience (`aud`)** identifies *who the token is for*: `PARKIO_JWT_AUDIENCE`
  (default `parkio-api`, intended for local/dev only). The gateway validates the
  audience and rejects tokens whose `aud` is missing or different, so both
  services must be configured with the **same** value — set it explicitly per
  environment in production. A blank audience fails closed at startup.

Clock-skew tolerance for `exp` validation is applied by the *consumer* of the
token (the gateway, `PARKIO_JWT_CLOCK_SKEW_SECONDS`, default 30s) — auth-service
issues exact `iat`/`exp` timestamps from its own clock. Refresh tokens are
unaffected (opaque, validated against the database, not JWTs).

## Refresh tokens

Refresh tokens are opaque 256-bit random values. Only their SHA-256 hash is
persisted (`refresh_tokens.token_hash`); the raw value is delivered only as an
HttpOnly cookie and is never serialized in JSON. On
`POST /api/v1/auth/refresh-token` the presented cookie token is **rotated**: the
old row is revoked and a brand-new token is issued, atomically in one
transaction. The replacement keeps the same `token_family_id` and points to its
predecessor through `parent_token_id`.

Presenting an unexpired token that was already revoked is treated as refresh-token
reuse. auth-service marks the replayed token, revokes every active token in that
family with reason `REUSE_DETECTED`, and writes a security warning containing only
the user ID and family ID. The client still receives the generic
`401 INVALID_REFRESH_TOKEN` response; token theft or family state is not disclosed.
Expired or unknown tokens receive the same generic response without triggering
family revocation.

Optimistic locking prevents two concurrent refreshes of the same token from
creating two valid children. `logout` remains idempotent and revokes only the
presented token with reason `LOGOUT`; there is no logout-all behavior.

## Email verification lifecycle

Public registration creates an auth user in `PENDING_VERIFICATION` with
`email_verified=false`. The raw verification token is generated once, hashed with
the same one-way token hasher used for refresh tokens, and only the hash plus
expiry metadata are stored. Tokens expire after
`parkio.security.email-verification.token-ttl` (24 hours by default).

`POST /api/v1/auth/register` returns the existing auth response envelope, but for
pending accounts the token fields are `null` and no refresh cookie is set. The
user cannot log in until email verification succeeds; attempted login returns
`403 ACCOUNT_NOT_VERIFIED` without issuing access or refresh tokens.

`POST /api/v1/auth/verify-email` accepts the raw token, hashes it, checks the
stored hash and expiry, then marks the account `ACTIVE` and verified. Repeating a
valid verification for an already verified account is idempotent. Invalid or
expired tokens return the safe `INVALID_VERIFICATION_TOKEN` error.

`POST /api/v1/auth/resend-verification` accepts an email address and always
returns `202 Accepted`. It is enumeration-safe: unknown, already verified and
cooldown-limited requests are not distinguishable to the caller. For pending
accounts outside the cooldown window, the old verification hash is replaced with
a newly generated token hash.

The default sender is a local/dev-safe logging implementation. It logs the raw
verification link only when `parkio.security.email-verification.log-token=true`
(enabled in dev/test, disabled by default). Hosted beta and production must keep
token logging disabled and replace or wrap this sender with an SMTP/provider
adapter before public registration is enabled.

## Refresh-token transport and CSRF boundary

The raw refresh token is still generated and rotated by the application service,
but it is no longer serialized in JSON responses. The controller sets it as an
HttpOnly `Secure` `SameSite=Strict` cookie named `parkio_refresh`, scoped to
`/api/v1/auth/refresh-token` and `/api/v1/auth/logout`. Login/register/refresh
responses for verified sessions return only the access token, expiry metadata and
user/session shape. Registration for pending accounts returns no session tokens
and does not set the refresh cookie.

Refresh and logout read the token from the cookie, not the request body. Refresh
rotation keeps the existing reuse-detection semantics: the presented token is
revoked, the child token is issued, and both cookie paths receive the rotated
value. Logout revokes the current cookie token and clears both cookie paths.

Because refresh/logout use an ambient cookie, the presentation layer validates
`Origin` or `Referer` against `parkio.security.refresh-cookie.allowed-origins`
(wired from `PARKIO_CORS_ALLOWED_ORIGINS` in compose). Cross-site refresh/logout
requests are rejected before the cookie token is used. SameSite=Strict and the
narrow cookie paths are defense-in-depth, not the only CSRF control.

Local development can set `PARKIO_REFRESH_COOKIE_SECURE=false` via the `dev`
profile so browsers send the cookie over local HTTP. Hosted beta and production
must keep `PARKIO_REFRESH_COOKIE_SECURE=true`.

## Moderation status sync (suspend / restore)

auth-service consumes `UserSuspended` / `UserRestored` from
`parkio.moderation.action` (group `parkio.auth`, local DTOs — no shared models,
inbox idempotency by `eventId`, manual ack after the transaction commits, poison
records → `parkio.dlt.auth`):

- **`UserSuspended`** sets `auth_users.status = SUSPENDED` and revokes **every
  active refresh token across all of the user's families** with reason
  `ADMIN_REVOKED`, in the same transaction. Login and refresh both call
  `ensureCanAuthenticate()`, so a suspended user can neither log in nor mint new
  access tokens by refreshing.
- **`UserRestored`** sets the status back to `ACTIVE` so future logins succeed.
  Tokens revoked during the suspension stay revoked — restoration never
  resurrects old sessions.
- **Ordering:** `auth_users.status_changed_at` records the `occurredAt` of the
  last applied status event; an event is applied only when
  `occurredAt >= status_changed_at`, so a stale out-of-order restore cannot lift
  a newer suspension. Other moderation action types (e.g.
  `ParkingSpotRejectedByModerator`) are ignored and acked.

This complements (does not replace) the gateway's per-request status check
against user-service: the gateway blocks tokens already issued, while auth-service
prevents suspended users from obtaining new tokens at all.

## Security hardening backlog

Known, intentionally-deferred gaps — documented so they are not mistaken for
finished work. None is implemented yet.

- **Global "log out everywhere".** Only single-token logout exists today; the bulk
  "revoke all tokens for user" operation exists internally (used by moderation
  suspension) but is not exposed as a user-facing endpoint.
