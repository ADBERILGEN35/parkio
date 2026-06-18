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

**Two expiry limits bound a session.** Each token carries a *sliding* per-token TTL
(`parkio.security.jwt.refresh-token-ttl`, default **30 days**) — every rotation issues
a fresh token valid that long from issue. On top of that, each family records when it
started (`refresh_tokens.family_started_at`, preserved unchanged across rotations) and
is subject to an *absolute* session lifetime cap
(`parkio.security.jwt.refresh-absolute-ttl`, default **90 days**). Refresh is allowed
only while `now <= family_started_at + refresh-absolute-ttl`. Past the cap, even a
still-valid current token is rejected: the family's active tokens are revoked with
reason `EXPIRED_CLEANUP` and the client receives the **same generic
`401 INVALID_REFRESH_TOKEN`** as a sliding-expired token — a client cannot tell which
limit it hit. This stops a single login from being silently rotated forever and forces
a fresh authentication after the maximum session age. Absolute expiry is a *natural*
expiry and never triggers reuse detection.

Presenting an unexpired token that was already revoked is treated as refresh-token
reuse. auth-service marks the replayed token, revokes every active token in that
family with reason `REUSE_DETECTED`, and writes a security warning containing only
the user ID and family ID. The client still receives the generic
`401 INVALID_REFRESH_TOKEN` response; token theft or family state is not disclosed.
Expired or unknown tokens receive the same generic response without triggering
family revocation.

Optimistic locking prevents two concurrent refreshes of the same token from
creating two valid children. `logout` remains idempotent and revokes only the
presented token with reason `LOGOUT` (per-device); `logout-all` (below) revokes
every family and immediately invalidates outstanding access tokens.

## Session epoch (access-token revocation)

Access tokens are stateless RS256 JWTs valid until they expire (15 min by default), so
without help a logout, logout-all, reuse detection or suspension would leave an
already-issued access token usable for up to that window. Each `auth_users` row carries a
`session_epoch` (a token version, default `0`), and every issued access token includes it
as a `session_epoch` claim. The gateway compares the token's epoch against the user's
current epoch and rejects a token whose epoch is stale, cutting the revocation lag from
the 15-minute token TTL down to the gateway's short cache TTL (default 30 s).

The epoch is **bumped** (incremented, same write/transaction as the related state change)
on security-sensitive session invalidation:

- **refresh-token reuse detection** — a compromised family also invalidates its
  outstanding access tokens (in addition to revoking the refresh family);
- **`logout-all`** — see below;
- **suspension** (`UserSuspended`) — alongside revoking every refresh family;
- **password reset/change** — every credential rotation revokes all refresh
  families and invalidates outstanding access tokens.

Single-device `logout` deliberately does **not** bump the epoch: revoking one device must
not log the user out of their other devices. The bump is monotonic and never reset, so a
restore after suspension does not resurrect old access tokens.

`POST /api/v1/auth/logout-all` (authenticated; identified by the access token, not the
refresh cookie) revokes every active refresh-token family for the caller with reason
`LOGOUT`, bumps the session epoch, and clears both refresh cookie paths. It is idempotent.

The gateway reads the current epoch from an **internal** endpoint,
`GET /internal/auth/users/{userId}/session-epoch`, which returns only
`{ userId, sessionEpoch }`. Like other `/internal/**` routes it is never routed publicly
and is guarded by the `X-Gateway-Auth` shared secret (Spring Security permits
`/internal/**` precisely because that filter already authenticates it). See the
gateway-service README for the edge enforcement and fail-closed behavior.

## Account recovery and credential rotation

`POST /api/v1/auth/forgot-password` accepts an email address and always returns
`202 Accepted`. Unknown users, pending-verification users, inactive users and
cooldown-limited requests are indistinguishable to the caller. For verified active
accounts outside the cooldown window, auth-service consumes any previous active
reset tokens for that user, generates a new secure random token, stores only its
one-way hash in `password_reset_tokens`, and sends the raw token through the
password reset email sender. Reset tokens expire after
`parkio.security.password-reset.token-ttl` (30 minutes by default). Request
cooldown is Redis-backed through `parkio.security.password-reset.request-cooldown`
(5 minutes by default), keyed by normalized email.

`POST /api/v1/auth/reset-password` accepts the raw reset token and a new password.
The token is hashed before lookup, must be unexpired and unused, and is marked
consumed in the same credential-rotation flow. The existing `PasswordPolicy` is
enforced, so reset cannot weaken registration rules. On success, auth-service
updates the password hash, revokes every active refresh-token family with reason
`PASSWORD_CHANGED`, bumps `session_epoch`, and clears refresh-cookie paths. Old
access tokens are rejected by the gateway once its epoch cache refreshes. Reusing
or presenting an expired reset token returns the generic `INVALID_RESET_TOKEN`;
the raw token and hash are not included in responses or production logs.

`POST /api/v1/auth/change-password` is authenticated. It verifies the current
password, applies the same password policy to the new password, then performs the
same session invalidation as reset-password: all refresh families are revoked,
`session_epoch` is bumped, and refresh cookies are cleared. The current browser
must sign in again after a successful change; this intentionally avoids leaving a
possibly compromised current session alive.

Pending-verification accounts do not receive reset emails and cannot complete
password reset. They must finish email verification first or request a new
verification link.

Transactional email is provider-backed in hosted beta/production. Local/dev uses
the logging fallback by default and logs only an email hash unless
`parkio.security.password-reset.log-token=true` is explicitly enabled for local
testing. Hosted beta and production must use `PARKIO_EMAIL_PROVIDER=resend`,
`PARKIO_RESEND_API_KEY`, `PARKIO_EMAIL_FROM`, and keep raw-token logging disabled.

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

The sender is selected by `parkio.email.provider`:

- `logging` (default/local dev): logs only an email hash unless raw-token logging
  is explicitly enabled for local testing.
- `resend`: sends verification and reset emails through Resend via the existing
  sender ports. Auth logic does not depend on a Resend SDK.

Required hosted-beta/production variables:

```dotenv
PARKIO_EMAIL_PROVIDER=resend
PARKIO_RESEND_API_KEY=...
PARKIO_EMAIL_FROM="Parkio <verify@example.com>"
PARKIO_EMAIL_REPLY_TO=support@example.com
PARKIO_EMAIL_VERIFICATION_URL=https://app.example.com/verify-email
PARKIO_PASSWORD_RESET_URL=https://app.example.com/reset-password
PARKIO_EMAIL_VERIFICATION_LOG_TOKEN=false
PARKIO_PASSWORD_RESET_LOG_TOKEN=false
```

With `PARKIO_EMAIL_PROVIDER=resend`, missing API key or from address fails
startup. With the `prod` profile active, auth-service also fails startup if the
logging provider is selected or raw-token logging is enabled. Delivery counters
are exported at `/actuator/prometheus` as `email_sent_total`,
`email_failed_total`, and `email_verification_sent_total`.

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

- ~~**Global "log out everywhere".**~~ **Done.** `POST /api/v1/auth/logout-all` revokes
  every active refresh-token family for the caller and bumps the session epoch, so
  outstanding access tokens are also invalidated at the gateway (see _Session epoch_
  above). Single-device `logout` remains per-device by design.
