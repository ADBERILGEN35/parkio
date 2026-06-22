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
- Object-level reads must enforce visibility/ownership server-side. Parking spot
  details are visible to the owner, `MODERATOR`/`ADMIN`, or unrelated users only
  while the spot is publicly visible; hidden resources must use not-found semantics
  instead of `403` so IDs cannot be probed.
- AI validation findings are advisory/moderation data. Expose validation reads only
  to `MODERATOR`/`ADMIN`; do not use them as owner-facing media/spot status APIs.
- Principle of least privilege for roles, DB users, and cloud credentials.

### Role model & RBAC matrix (separation of duties)

Three roles, enforced fail-closed at multiple layers (edge gateway → controller →
application service). `MODERATOR` and `ADMIN` are **not** equivalent: moderators handle
content; only admins touch user accounts.

- **USER** — own resources only (own profile, spots, reports, appeals, analytics).
- **MODERATOR** — review content: view non-public spots/media, read AI findings and
  create manual AI validations, work the moderation queue (list/assign cases), and
  resolve cases with **content** outcomes (`APPROVE`, `REJECT`, `MARK_FILLED`,
  `MARK_RISKY`). May read the appeal queue. **Cannot** suspend/restore accounts, adjust
  trust/points, resolve appeals, manage roles, or view platform analytics.
- **ADMIN** — everything a moderator can do, plus account-level / destructive actions:
  resolve cases with sanctions/overrides (`SUSPEND_USER`, `RESTORE_USER`,
  `REDUCE_TRUST`, `DEDUCT_POINTS`), resolve appeals (reverses sanctions / restores
  accounts), view platform analytics, and role management (when built).

| Capability | USER | MODERATOR | ADMIN |
|---|---|---|---|
| Own profile / spots / reports / appeals / own analytics | ✅ | ✅ | ✅ |
| View non-public spot / others' media | ❌ | ✅ | ✅ |
| Read AI findings / create manual validation | ❌ | ✅ | ✅ |
| Moderation queue: list / assign cases | ❌ | ✅ | ✅ |
| Resolve case — content (`APPROVE`/`REJECT`/`MARK_*`) | ❌ | ✅ | ✅ |
| Resolve case — account sanction/override (`SUSPEND`/`RESTORE`/`REDUCE_TRUST`/`DEDUCT_POINTS`) | ❌ | ❌ | ✅ |
| Resolve appeals (reverse sanctions) | ❌ | ❌ | ✅ |
| Suspend / restore accounts | ❌ | ❌ | ✅ |
| Trust / point overrides | ❌ | ❌ | ✅ |
| Platform analytics (overview/daily/parking/metrics) | ❌ | ❌ | ✅ |
| Role management | ❌ | ❌ | ✅ |

Privilege boundaries / enforcement notes:

- **Defense in depth, fail closed.** The edge gateway role rules
  (`RouteAuthorizationRules`) are necessary but **not sufficient**: every privileged
  controller re-checks the gateway-injected `X-User-Roles`, and account-level rules are
  re-checked again in the application service. A missing/blank roles header denies.
- **Account-level effects have no HTTP admin surface.** Suspend/restore, trust and
  point changes are *not* their own endpoints — they originate from moderation
  `resolveCase` actions and propagate via Kafka to auth/user/gamification. The single
  chokepoint is therefore the action-level ADMIN gate in moderation-service
  (`ModerationAction.requiresAdmin()`), enforced in both controller and service.
- **Platform analytics is ADMIN-only** at the gateway (`/api/v1/analytics/**`) and in
  `AnalyticsController`; a user's own analytics (`/api/v1/analytics/users/{id}`) is
  carved out for the owner only.
- **Appeals**: moderators may read the queue, but *resolving* an appeal is ADMIN-only
  because acceptance reverses a sanction (can restore a suspended account).
- **Role management** has no API today (new accounts get `USER`; roles are DB-seeded).
  When introduced it must be ADMIN-only — prefer ADMIN whenever a capability's tier is
  uncertain.
- Refresh tokens are opaque, stored only as hashes, rotated on use, and linked
  into token families. Reuse of an unexpired revoked token revokes the active
  family while returning only generic `401 INVALID_REFRESH_TOKEN` to the client.
  Families are also bounded by an absolute session lifetime cap
  (`refresh-absolute-ttl`, default 90 days, anchored at `family_started_at` and
  preserved across rotations) on top of the sliding per-token TTL. Past the cap,
  refresh is rejected with the same generic error and the family is revoked
  (`EXPIRED_CLEANUP`), forcing re-authentication; the client cannot tell which
  limit it hit, and natural absolute expiry never triggers reuse detection.
- Access tokens carry a per-user `session_epoch` claim. After JWT validation the
  gateway checks the user's current epoch (internal auth-service endpoint, briefly
  cached) and rejects any token whose epoch is stale with `401 TOKEN_REVOKED`,
  failing **closed** (`503`) if the epoch cannot be confirmed. auth-service bumps the
  epoch on refresh-token reuse detection, `logout-all`, suspension, password reset
  and change-password, so those events invalidate already-issued access tokens within the
  cache TTL instead of leaving them valid until expiry. The epoch comes only from the
  signed claim — never client input; a missing claim (legacy token) is treated as
  epoch 0. Single-device logout is intentionally per-device and does not bump the epoch.
- Public registration must not create a fully active session until email is
  verified. Store only hashed verification tokens, expire them, keep resend
  responses enumeration-safe, and never log raw verification links outside
  explicitly guarded dev/test configuration.
- Password reset must be enumeration-safe: `forgot-password` returns the same
  success response for known, unknown, unverified, inactive and cooldown-limited
  accounts. Store only a SHA-256 hash of 256-bit random reset tokens, expire them
  after 1 hour by default, consume them on use, and revoke every refresh family plus
  bump `session_epoch` after a successful reset. Do not auto-login after reset.

## Secrets & config

- **No secrets in code, configs, or git.** Inject via environment variables /
  secret manager. `.env` is git-ignored (`.env.example` only for documentation).
- Separate config per environment; production secrets never in dev.
- CI secret scanning is mandatory. `.gitleaks.toml` may allow only exact local-dev
  placeholders, documentation examples, and test-only fake values. Do not add broad
  path allowlists for env files or source trees.
- If a real secret is committed, rotate/revoke it first and remove it from every
  runtime environment. Do not "fix" the pipeline by allowlisting the leaked value.
- Dependency and image vulnerability gates should start high-signal: fail on
  HIGH/CRITICAL dependency vulnerabilities and CRITICAL container-image
  vulnerabilities, then tighten as the baseline stays clean.
- CodeQL (SAST) and Trivy SARIF upload to GitHub Code Scanning are gated behind
  the `CODEQL_ENABLED` repository variable. On the current personal/private repo
  Code Scanning / GHAS is unavailable, so the gate stays off: the CodeQL job is
  skipped and scan results are kept as workflow artifacts. After the repo moves to
  an organization (or GHAS is enabled) and Code scanning is turned on, set
  `CODEQL_ENABLED=true` to activate CodeQL and SARIF upload — no workflow edits
  needed. Do not delete CodeQL to make CI green; gate it.
- Keep Trivy scanner versions pinned in CI and use direct Trivy CLI commands with
  the workspace cache. Do not leave Security CI on wrapper/action defaults.

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
- Strip and normalize EXIF before storage. `media-service` scans original upload
  bytes first, then decodes and re-encodes accepted images to server-generated
  JPEG bytes. Stored objects must not contain original EXIF/GPS/device metadata or
  original untrusted image structure. **GPS for a spot comes from the app
  submission**, not implicitly trusted client EXIF.
- Bound decoded image dimensions and total pixels; corrupt images or images that
  exceed configured limits fail closed and are not stored.
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
- Password reset must be enumeration-safe: store only reset-token hashes, expire
  tokens quickly (default 30 minutes), consume previous active tokens for the
  user when issuing a new one, and never log raw reset links outside explicit
  dev/test configuration. Successful reset/change-password revokes all refresh
  families and bumps `session_epoch`.
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
