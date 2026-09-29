# PARKIO-W01B — Waitlist integration & release readiness

## PACKAGE STATUS
SOURCE_FIXES_COMPLETE + ISOLATED_ACCEPTANCE_PASS.
PR #58 source may be merge-ready after green required CI on final head.
Publication is **not** READY_FOR_PUBLICATION_DECISION (external activation blockers remain).

## API CURRENT / PR58 BASE / FINAL FULL HEAD
Filled at commit time in `SHAS.txt`.

## PR57 STATE / BASELINE RECONCILIATION
- PR #57 MERGED at `297b02ab10b3096b186016f35675d3cc3aedbc1c` (2026-09-21T09:08:28Z).
- Delta from previously observed `api` tip `3da97afb…`: commits `10b6dd21` + merge `297b02ab` (GMP production compose digest pin overlay only). Verified via `git log 3da97afb..origin/api`, not assumed from abbreviation alone.
- PR #58 base = that merge commit. W01A branch fast-forwarded onto it before opening #58.

## GATEWAY PERSISTENCE / MIGRATION COMPATIBILITY
- Why gateway: waitlist already lived in gateway (`waitlist_interest`, public POST routes, Redis rate limit, admin export). No new service — lowest operational cost.
- Datasource: `spring.datasource` → Postgres DB `parkio_gateway` (local default port 5441).
- Flyway: `classpath:db/migration` — only V1 + V2 (no naming conflict).
- Disposable Postgres Flyway migrate V1→V2: PASS (see `acceptance.txt`).
- Permissions: gateway migrator/runtime roles already exist in invite-production env examples (`POSTGRES_GATEWAY_*`).

## TOKEN / DELIVERY / LOGGING SAFETY
Fixes in this W01B pass:
- Confirm is POST-only (GET → 405); UI confirm button required (scanner-safe).
- Tokens: 32-byte SecureRandom, URL-safe Base64; hashed at rest; purpose-separated verify/withdraw.
- Confirm keeps token hash for idempotent replay; expired tokens stay PENDING.
- Email failure after durable insert → 503 `WAITLIST_EMAIL_DELIVERY_FAILED`; row kept; retry refreshes tokens (cooldown skipped until successful send).
- Duplicate PENDING submit may resend (bounded); CONFIRMED/WITHDRAWN silent accept (no enumeration).
- Resend rotates verify+withdraw tokens and includes withdraw link.
- Logging provider: no raw email/tokens/links; blocked when `allow-logging-provider=false`.
- Resend provider: startup requires API key + from; omits empty `reply_to`; uses official `POST https://api.resend.com/emails`.

## ISOLATED POSTGRES + HTTP ACCEPTANCE
- Flyway on disposable Postgres: PASS.
- WaitlistControllerTest (Flyway on H2 PostgreSQL mode): PASS including duplicates, confirm/replay, expiry, delivery-failure retry, withdraw+re-register, rate limit, GET confirm blocked.
- Real production persistence / Resend: NOT_EXECUTED.

## CORS / RATE LIMIT / DUPLICATE RESULTS
- CORS allow-list via `PARKIO_CORS_ALLOWED_ORIGINS` (empty default = deny). Invite example now includes `https://parkio.dev`.
- Rate limits: Redis keys `waitlist:ip:` / `waitlist:email:` with TTL window; trusted proxies via existing `PARKIO_TRUSTED_PROXIES`.
- Duplicate email_hash uniqueness enforced in DB + DuplicateKeyException path.

## SOURCE FIXES
Gateway waitlist delivery/token/logging guards; marketing delivery error copy; invite-production env example waitlist+CORS; marketing deploy sequencing doc.

## CI RESULTS / ACTUAL CHECKOUT SHAS
See `SHAS.txt` and PR checks after push. Do not label SKIPPED checks as PASS.

## PR58 SOURCE MERGE READINESS
Pending green required CI on final head + human review. Draft until authorized.

## PUBLICATION READINESS / EXACT BLOCKERS
1. Merge authorization for PR #58 (no merge in this task).
2. Gateway image build/scan/registry publish + deliberate GMP gateway pin update.
3. Gateway deploy + Flyway V2 on production `parkio_gateway`.
4. CORS `https://parkio.dev` (+ keep app origin).
5. Resend account/domain/DNS + secrets (`ALLOW_LOGGING_PROVIDER=false`).
6. Authorized live test email acceptance.
7. Hostinger upload of approved `web/marketing/` SHA **after** API/email path is live.

## GATEWAY PIN / RELEASE DELTA / ROLLBACK PLAN
- Current GMP pin file pins gateway/media/parking digests from SHA `4a9ba218…` overlay.
- Future gateway waitlist release must update **only** gateway digest; preserve media/parking.
- Do not deploy latest `api` tip without reviewing full delta vs pinned release.
- Preserve BC 1.85, PA-06, default-off telemetry.
- Rollback: previous gateway digest pin and/or previous Hostinger static tree; no destructive down-migration of waitlist rows.

## CONSOLIDATED EXTERNAL SETUP REQUIREMENTS
See `SETUP-CHECKLIST.md`.

## PRODUCTION MUTATIONS = 0
## REAL EMAILS SENT = 0
## PRS MERGED = NO
## REAL USER TRACKING ACTIVATED = NO
## EVIDENCE
This directory.
