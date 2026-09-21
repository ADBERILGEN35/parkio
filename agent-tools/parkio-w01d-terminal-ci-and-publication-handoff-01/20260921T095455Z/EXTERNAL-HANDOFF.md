# External activation handoff (unresolved only)

Reuses W01B `SETUP-CHECKLIST.md`. No new discovery loop.

## Technical prerequisites (still open)
| Item | Status |
|---|---|
| Resend account already available? | **Unknown / not confirmed** from repo or authorized read-only access. Env examples still use `REPLACE_ME_*` placeholders. |
| Sender / reply-to selection | Operator must confirm final values for `PARKIO_WAITLIST_EMAIL_FROM` and `PARKIO_WAITLIST_EMAIL_REPLY_TO` (examples: `Parkio <verify@parkio.dev>`, `support@parkio.dev`). |
| DNS records | Exact SPF/DKIM(/any provider-required) records **once Resend domain is added** in the provider dashboard — do not invent. |
| Secrets (names only) | `PARKIO_WAITLIST_RESEND_API_KEY` (or `PARKIO_RESEND_API_KEY`), `PARKIO_WAITLIST_HASH_SECRET`, plus existing gateway internal secret. Install via operator Key Vault / env facility — never commit. |
| `PARKIO_WAITLIST_ALLOW_LOGGING_PROVIDER=false` | Required in production. |
| CORS | `PARKIO_CORS_ALLOWED_ORIGINS=https://app.parkio.dev,https://parkio.dev` |
| Confirm/withdraw URLs | `PARKIO_WAITLIST_CONFIRM_URL`, `PARKIO_WAITLIST_WITHDRAW_URL` pointing at parkio.dev paths |
| Gateway artifact + gateway-only GMP pin | Not published/updated for this candidate |
| V2-compatible recovery digest | Not built/accepted (see `V2-RECOVERY.md`) |
| Isolated post-migration restore proof | NOT_EXECUTED |

## User authorizations still required
- Undraft + merge PR #58
- Gateway image publish + pin update
- Civo gateway deploy + Flyway V2
- DNS changes (if domain not already verified at provider)
- Production secret installation
- Real live test-email send to an authorized disposable address
- Hostinger `web/marketing/` upload (only after API/email path live)

## Publication vs merge
Missing provider/DNS/secrets/recovery artifact **blocks publication**, not an
otherwise accepted default-safe **source merge** decision once required CI is green.
