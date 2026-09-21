# PARKIO-W01A Evidence — 20260921T091004Z

## PACKAGE STATUS
SOURCE_COMPLETE + ISOLATED_ACCEPTANCE_PASS. Not READY_FOR_PUBLICATION_DECISION (external blockers remain).

## ACTUAL WEBSITE SOURCE / HOSTING / PUBLICATION PATH
- Canonical source: `web/marketing/` (static HTML/CSS/JS)
- Host: Hostinger `public_html` for parkio.dev (see `docs/releases/MARKETING-SOURCE-DEPLOYMENT.md`)
- App/API: Civo / `api.parkio.dev` — separate from marketing static upload
- Publish: upload `web/marketing/` contents into Hostinger `public_html` (no Node build)
- Waitlist API: `POST https://api.parkio.dev/api/v1/waitlist` (+ confirm/withdraw/resend)

## SOURCE BASE / FINAL FULL HEAD / DRAFT PR OR PREVIEW
- Worktree: `C:\Users\ADBERILGEN\Documents\parkio-w01a`
- Branch: `feat/w01a-bilingual-landing-waitlist`
- Base (api at PR creation): `297b02ab10b3096b186016f35675d3cc3aedbc1c`
- Final full head: `e4f784d632233e76802c51b74e5ed3afcbb4ec87`
- Draft PR: https://github.com/ADBERILGEN35/parkio/pull/58 (base `api`)
- Local preview: `node scripts/serve-marketing-site.mjs --port 5197` → http://127.0.0.1:5197/
- Mock waitlist only: `?waitlistMock=1` (NOT live provider)

## TURKISH / ENGLISH CONTENT COVERAGE
- Default locale: `tr` (`document.documentElement.lang`, meta title/description)
- Switch: visible TR/EN control; persistence `localStorage` key `parkio.marketing.locale`
- Translated: nav, hero, product, how/trust/business/roadmap/founder, waitlist form + feedback, footer, privacy waitlist section, confirm/unsubscribe pages, a11y labels
- Primary CTA remains Explore (`app.parkio.dev/explore`); waitlist is secondary registration-notify

## WAITLIST STORAGE / EMAIL PROVIDER
- Storage: gateway Postgres `waitlist_interest` (+ V2 double-opt-in columns)
- Email adapter: `PARKIO_WAITLIST_EMAIL_PROVIDER` default `logging`; optional `resend`
- Secrets never in browser; marketing posts to public gateway endpoints only
- Isolated UI mock: in-memory via `?waitlistMock=1` — classified NOT_EXECUTED for durable provider

## SUBMISSION / DURABILITY / DUPLICATE RESULTS
- Gateway unit/controller tests: PASS (Flyway V1+V2, submit/confirm/withdraw paths)
- Marketing adapter unit test: PASS (validation + mock isolation; no fetch)
- Playwright marketing suite: 9/9 PASS (TR default, EN switch+persist, mock success, responsive)
- Real durable write to production DB / real Resend delivery: **NOT_EXECUTED**

## DOUBLE OPT-IN / WITHDRAWAL RESULTS
- Implemented in gateway: PENDING → CONFIRMED; hashed single-use tokens; TTL; resend bounds; withdraw
- Marketing pages: `/waitlist/confirm/`, `/waitlist/unsubscribe/`
- Real email confirm/withdraw end-to-end on production: **NOT_EXECUTED**

## ABUSE PROTECTION / DATA MINIMIZATION
- Collects: email + consent timestamp + source + locale
- IP/email rate limits in `WaitlistProperties`
- Duplicate-safe responses do not reveal whether another address is registered
- No Slack export; analytics/autocapture/session replay not activated by this change

## ACTUAL-APP / RESPONSIVE / ACCESSIBILITY RESULTS
- Screenshots: `01` TR desktop, `02` EN desktop, `03` TR mobile waitlist, `04`/`05` EN waitlist
- Playwright responsive: 360/390/768/1440 overflow checks PASS
- Form: labeled email, consent checkbox, keyboard-capable submit; feedback region

## MOCKED VS REAL EXTERNAL VALIDATION
| Layer | Result |
|---|---|
| UI bilingual + form | PASS |
| Adapter / mock submit | PASS |
| Gateway waitlist tests | PASS |
| Real Hostinger publish | NOT_EXECUTED |
| Real CORS/prod gateway | NOT_EXECUTED |
| Real email (Resend) | NOT_EXECUTED |
| Real subscriber persistence (prod) | NOT_EXECUTED |

## EXACT PUBLICATION BLOCKERS
1. Authorized Hostinger upload of `web/marketing/` SHA to `public_html`
2. Gateway deploy including Flyway `V2__waitlist_double_opt_in.sql`
3. CORS allow origin `https://parkio.dev` on gateway for waitlist endpoints
4. Waitlist email provider secrets + verified sender domain (if not logging-only)
5. Operator live acceptance with an authorized test address

## REQUIRED EXTERNAL CONFIGURATION
Env names only (no values):
- `PARKIO_WAITLIST_HASH_SECRET` (or reuse `PARKIO_GATEWAY_INTERNAL_SECRET`)
- `PARKIO_WAITLIST_CONFIRM_URL` / `PARKIO_WAITLIST_WITHDRAW_URL`
- `PARKIO_WAITLIST_EMAIL_PROVIDER` (`logging` | `resend`)
- `PARKIO_WAITLIST_EMAIL_FROM` / `PARKIO_WAITLIST_EMAIL_REPLY_TO`
- `PARKIO_WAITLIST_RESEND_API_KEY` (when provider=resend)
- Rate-limit knobs: `PARKIO_WAITLIST_IP_RATE_LIMIT_*`, `PARKIO_WAITLIST_EMAIL_RATE_LIMIT_*`, `PARKIO_WAITLIST_MAX_RESENDS`, `PARKIO_WAITLIST_TOKEN_TTL`
- Gateway CORS must include `https://parkio.dev`

## PRODUCTION MUTATIONS
None in this task.

## REAL EMAILS SENT
None.

## REAL USER TRACKING ACTIVATED
None.

## PRS MERGED
None.

## EVIDENCE
Directory: `agent-tools/parkio-w01a-bilingual-landing-and-waitlist-01/20260921T091004Z/`
- Screenshots 01–05
- This report
- Validator/Playwright/gateway results recorded in session (marketing_validation=PASS; playwright 9/9; WaitlistControllerTest BUILD SUCCESSFUL)
