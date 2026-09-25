# Consolidated external setup checklist (W01B)

Do not paste secrets into chat. Install via the operator-controlled env facility.

## Email provider
- Chosen provider: **Resend** (`PARKIO_WAITLIST_EMAIL_PROVIDER=resend`)
- Account access: authorized Resend workspace for Parkio (operator-owned)
- DNS: verify sending domain in Resend dashboard (exact records shown there once domain is added — typically SPF/DKIM CNAME/TXT; do not invent records here)
- Sender / reply-to (operator must confirm final addresses):
  - `PARKIO_WAITLIST_EMAIL_FROM` (example shape: `Parkio <verify@parkio.dev>`)
  - `PARKIO_WAITLIST_EMAIL_REPLY_TO` (example: `support@parkio.dev`)
- API key secret name: `PARKIO_WAITLIST_RESEND_API_KEY` (or shared `PARKIO_RESEND_API_KEY`)
- Block silent logging in production: `PARKIO_WAITLIST_ALLOW_LOGGING_PROVIDER=false`

## Waitlist crypto / links
- `PARKIO_WAITLIST_HASH_SECRET` (≥32 chars; distinct preferred from other secrets)
- `PARKIO_WAITLIST_CONFIRM_URL=https://parkio.dev/waitlist/confirm/`
- `PARKIO_WAITLIST_WITHDRAW_URL=https://parkio.dev/waitlist/unsubscribe/`

## CORS
- Exact origins to allow: `https://parkio.dev` and `https://app.parkio.dev`
- Env: `PARKIO_CORS_ALLOWED_ORIGINS=https://app.parkio.dev,https://parkio.dev`

## Live acceptance
- Authorized disposable test inbox under operator control
- Confirm: submit → pending row → Resend message → POST confirm → CONFIRMED
- Confirm withdraw link; confirm duplicate submit does not create a second active row
- Confirm logs contain no raw email/token/URL

## Hostinger
- Operator Hostinger access to upload `web/marketing/` into `public_html`
- Only after gateway waitlist + email path accepted

## Authorizations still required
- PR #58 merge authorization
- Gateway image publish + GMP gateway pin update authorization
- Civo gateway deploy / migration authorization
- DNS change authorization (sender domain) if not already verified
- Hostinger publication authorization
- Real test email send authorization
