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

- Validate image content type and size; scan/limit uploads.
- Serve media via **time-limited signed URLs**; do not expose bucket internals.
- Strip or normalize EXIF appropriately — but note that **GPS for a spot comes from
  the app submission**, not implicitly trusted client EXIF.

## Abuse & rate limiting

- Rate-limit at `gateway-service` (per user/IP) and protect write/claim endpoints.
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
