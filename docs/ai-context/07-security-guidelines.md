# 07 — Security Guidelines

## Authentication & authorization

- `auth-service` signs access tokens with RS256 and exposes public keys through
  JWKS. `gateway-service` authenticates requests at the edge using JWKS and
  forwards verified identity downstream. The RSA private key remains only in
  auth-service.
- Downstream services **authorize** per endpoint using roles/claims; never trust
  unauthenticated input. Validate tokens (signature, expiry, audience).
- Service-to-service calls carry a service identity/token; do not expose internal
  endpoints publicly (only `gateway` is public).
- Principle of least privilege for roles, DB users, and cloud credentials.
- Refresh tokens are opaque, stored only as hashes, rotated on use, and linked
  into token families. Reuse of an unexpired revoked token revokes the active
  family while returning only generic `401 INVALID_REFRESH_TOKEN` to the client.
- Public registration must not create a fully active session until email is
  verified. Store only hashed verification tokens, expire them, keep resend
  responses enumeration-safe, and never log raw verification links outside
  explicitly guarded dev/test configuration.

## Secrets & config

- **No secrets in code, configs, or git.** Inject via environment variables /
  secret manager. `.env` is git-ignored (`.env.example` only for documentation).
- Separate config per environment; production secrets never in dev.

## Input & data protection

- Validate and sanitize all inbound data (`presentation` layer). Reject oversized
  payloads; constrain uploads (type, size) in `media-service`.
- Use parameterized queries / JPA — never string-concatenated SQL.
- TLS for all external traffic; encrypt sensitive data at rest where applicable.
- **PII** stays in `auth`/`user` services; other services keep only IDs. Apply data
  minimization and retention policies.

## Media security

- Validate image content type and size; confirm magic bytes match the declared type.
- **Malware scan before serving (implemented).** `media-service` scans uploaded
  bytes with ClamAV (`clamd` `INSTREAM` over TCP) **before** they are stored, and
  media is only `READY`/servable after a clean scan. The scan is **fail-closed**: a
  scan that cannot complete returns `503` and stores nothing; an infected file
  returns `422`. Signed URLs are issued only for `READY` media, and `parking-service`
  rejects spot creation that references non-`READY` media (also fail-closed).
- Serve media via **time-limited signed URLs**; do not expose bucket internals.
- Strip or normalize EXIF appropriately — but note that **GPS for a spot comes from
  the app submission**, not implicitly trusted client EXIF. *(EXIF stripping /
  re-encoding is not yet implemented — future hardening.)*
- **Limitation:** malware scanning is **not** illegal/abusive-content classification
  (e.g. CSAM). That requires a managed content-safety provider and/or human
  moderation; AI image validation stays **advisory** unless explicitly enforced.
  Production should add a managed AV/content-safety provider.

## Abuse & rate limiting

- Rate-limit at `gateway-service` (per user/IP) and protect write/claim endpoints.
- Protect login in `auth-service` with Redis-backed per-account failed-attempt
  counters keyed by normalized email. Wrong password, unknown email and lockout
  responses must remain indistinguishable to avoid account enumeration.
- Registration passwords must be at least 12 characters, include lowercase,
  uppercase and a digit, and reject a maintainable common-password deny-list.
- Email verification resend is abuse-sensitive; throttle it in shared storage
  such as Redis rather than in-memory process state.
- Idempotency keys prevent duplicate-submission abuse (see `04`/`06`).
- Trust Score and moderation gate risky actions; suspended/banned users are blocked
  at auth/gateway.

## Operational security

- Log security-relevant events (auth failures, moderation actions) with `traceId`;
  **never log secrets, tokens, or full PII**.
- Keep dependencies patched (managed via the version catalog / BOM).
- Fail closed: on auth/permission uncertainty, deny.

## Privacy of locations

- A parking spot's location is community data, but treat **users' precise location
  history** as sensitive; do not expose one user's movement patterns to others.
