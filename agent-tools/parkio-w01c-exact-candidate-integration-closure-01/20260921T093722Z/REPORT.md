# PARKIO-W01C exact candidate integration closure

## PACKAGE STATUS
ACTUAL_GATEWAY_HTTP_ACCEPTANCE_PASS + SOURCE_FIXES_PUSHED.
Publication still blocked on external activation. Source merge readiness depends on terminal CI for the new head after this push.

## API / PR58 BASE / FINAL FULL HEAD
See `SHAS.txt` (filled at push time).

## HEAD / BASE DRIFT
At task start: head `1b3ee8c7…` matched PR #58; base `297b02ab…` matched `origin/api`. No unrelated tip drift. This closure adds commits on the same branch.

## TERMINAL CI / ACTUAL CHECKOUT SHAS
Recorded for head `1b3ee8c7…` (pre-fix push):
- PASS: Backend Build & unit tests; Integration tests (Testcontainers); Frontend CI; Security CI summary; CodeQL (java+js); container scans incl. gateway; Safety guards; Backup restore; Mobile-v2; Secret scan; Dependency vuln; Trivy
- FAIL: Build images + secret-safe dry-run manifest (invite-production public-cutover edge guard rejected CORS including `https://parkio.dev` — fixed in this closure)
- FAIL (advisory label in workflow name): Legacy mobile typecheck… — recorded as FAILURE, not reclassified
- SKIPPED: Deploy invite-production; Non-deploy production runner; Restore drill bundle; Migrate legacy binds; Rollback invite-production
- PENDING at snapshot time: Compose dependency recovery drill; k6 smoke; Full Docker Compose runtime validation
Post-fix CI for new head: collect after push (do not treat SKIPPED as PASS).

## ACTUAL GATEWAY + POSTGRES + REDIS HTTP RESULTS
Live `gateway-service` bootJar (Java 21) + disposable Postgres 16 `:55442` + Redis 7 `:56379` + local Resend mock `:18080`.
All assertions in `http-acceptance.json`: PASS (registration, concurrent duplicate, provider 503+retry, GET 405, POST confirm+replay, expiry, superseded token, withdraw+reregister, Redis rate limit, CORS allow/deny, restart durability 15→15).

## V1 DATA → V2 MIGRATION RESULTS
Seeded two synthetic V1 rows then applied fixed V2: both remain `PENDING`, `verification_token_hash` NULL, `confirmed_at` NULL (no invented confirmed consent). Evidence: `v1-to-v2.txt`.

## DELIVERY / TOKEN / DUPLICATE / RESTART RESULTS
Covered in HTTP suite. Gateway logs scanned for `@` / `token=` literals in acceptance windows: 0 hits.

## PRODUCTION CONFIGURATION GUARDS
Exact bindings from source `application.yml`:
- `parkio.waitlist.email.provider` ← `PARKIO_WAITLIST_EMAIL_PROVIDER` (default `logging`)
- `parkio.waitlist.email.allow-logging-provider` ← `PARKIO_WAITLIST_ALLOW_LOGGING_PROVIDER` (default `true` locally; invite example `false`)
- `parkio.waitlist.email.from` ← `PARKIO_WAITLIST_EMAIL_FROM` / `PARKIO_EMAIL_FROM`
- `parkio.waitlist.email.reply-to` ← `PARKIO_WAITLIST_EMAIL_REPLY_TO` / `PARKIO_EMAIL_REPLY_TO`
- `parkio.waitlist.email.resend-api-key` ← `PARKIO_WAITLIST_RESEND_API_KEY` / `PARKIO_RESEND_API_KEY`
- `parkio.waitlist.email.resend-base-url` ← `PARKIO_WAITLIST_RESEND_BASE_URL` (default `https://api.resend.com`)
- `parkio.waitlist.hash-secret` ← `PARKIO_WAITLIST_HASH_SECRET` / `PARKIO_GATEWAY_INTERNAL_SECRET`
- `parkio.waitlist.confirm-base-url` ← `PARKIO_WAITLIST_CONFIRM_URL`
- `parkio.waitlist.withdraw-base-url` ← `PARKIO_WAITLIST_WITHDRAW_URL`
- `parkio.gateway.cors.allowed-origins` ← `PARKIO_CORS_ALLOWED_ORIGINS`
- Tracing: `management.tracing.enabled` ← `PARKIO_TRACING_ENABLED` (keep default-off policy in production env as already governed)
Logging provider refuses start when `allow-logging-provider=false`. Resend refuses start if API key or from missing.

## ROLLBACK COMPATIBILITY / LIMITATIONS
- Static Hostinger rollback independent of gateway.
- After Flyway V2 is applied, rolling back to a pre-V2 gateway image is **not** schema-compatible for waitlist writes/reads that expect only V1 columns/semantics; V2 columns and rows remain. Do not down-migrate. Preserve subscriber rows. Prefer forward fix or V2-aware gateway pin rollback only among V2-capable digests.
- Media/parking pins unchanged.

## SOURCE CHANGES
- V2: stop auto-confirming legacy V1 rows
- Resend base URL override for isolated mock; RestClient builder bean
- Resend request: email-only (drop required source)
- Cutover edge guard: allow CORS `app` or `app+parkio.dev`
- Invite example already had waitlist prod guards from W01B

## PR58 SOURCE MERGE READINESS / EXACT BLOCKERS
Merge-ready only when required CI is green on final head after this push, draft cleared by humans, and review complete.
Not publication-ready.

## EXTERNAL ACTIVATION INPUTS / AUTHORIZATIONS STILL REQUIRED
Reuse W01B `SETUP-CHECKLIST.md`. Still needed: merge auth; gateway image publish + gateway-only GMP pin; Resend domain/DNS + secrets; Civo gateway deploy+migration; authorized live test email; Hostinger upload after API/email live.

## PRODUCTION MUTATIONS = 0
## REAL EMAILS SENT = 0
## PRS MERGED = NO
## REAL USER TRACKING ACTIVATED = NO
## EVIDENCE
This directory.
