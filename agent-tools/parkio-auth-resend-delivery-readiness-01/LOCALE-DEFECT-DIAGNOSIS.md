# Locale defect diagnosis + source fix (2026-09-22)

## Acceptance record (operator-confirmed)

| Check | Result |
|---|---|
| Inbox receipt (both addresses) | **PASS** (Resend delivered) |
| English verification email acceptance | **FAIL** (TR copy + lang=tr links) |
| Verification / resend / reset acceptance | **not completed** |
| Provider state retained | **
esend** (delivery succeeded; locale defect is product path, not provider failure) |

## Gate after both accounts

- Registration mode: **CLOSED** (403 REGISTRATION_CLOSED)
- Invite creation: **disabled** (alse)
- INVITE window: **not** reopened
- Slack + NR continuous: left running

## Nominated accounts (preferred_locale only)

| Slot | preferred_locale | email_verified | status |
|---|---|---|---|
| INBOX_TR | 	r | false | PENDING_VERIFICATION |
| INBOX_EN | 	r | false | PENDING_VERIFICATION |

## Diagnosis

### 1) Handoff URLs
Both REGISTER_URL values contained invite= only. **No lang= query parameter** was present (UI_LANG was metadata in the handoff file only).

### 2) RegisterPage + persisted browser language
RegisterPage previously sent locale: i18n.language === "en" ? "en" : "tr" and only consumed invite from the URL. Persisted Turkish UI language therefore won for both registrations.

### 3) Registration request
Frontend serialized ambient i18n language into POST /api/v1/auth/register → backend persisted that as preferred_locale.

### 4) Backend persistence
Correct relative to the request: both rows stored preferred_locale=tr. Not a DB corruption issue. Email suffix was not used.

### 5) Root cause classification
**Primary:** handoff generation omitted lang= **and** frontend ignored/missing URL lang (browser locale override).  
**Not:** request serialization bug independent of locale source; **not** backend inventing locale from email.

### Resend note (recovery blocker)
Public 
esendVerification **intentionally** uses stored preferred_locale and ignores request locale (
egisterPersistsPreferredLocaleAndResendIgnoresRequestLocaleOverride). Changing the browser language now does **not** change resend language for these accounts.

## Source fix (branch ix/register-invite-lang-locale)

- RegisterPage reads lang, applies locale store, strips lang like invite, and sends that locale on register (overrides persisted browser language).
- Shared localeFromSearchParam extracted for VerifyEmailPage reuse.
- Focused tests: EN register from prior TR browser; TR register from prior EN browser.
- **Does not** touch rontend/apps/web/Dockerfile (coordinates with ix/web-image-security-refresh).
- Focused vitest: RegisterPage **14/14 PASS**.

Future invite handoffs must emit:
https://app.parkio.dev/register?invite=...&lang=en (and lang=tr).

## EN account recovery (no DB edit / no reopen)

Supported options requiring **additional authorization** (not executed this step):

1. **Authorized one-shot preferred_locale correction** for INBOX_EN only (	r → en), then public resend (invalidates prior tokens), then verify latest EN message.  
2. Product change to make public resend honor request locale (conflicts with current intentional unit test) + auth deploy — separate decision.

Until (1) or (2) lands, EN verification-email acceptance cannot pass via resend while preferred_locale remains 	r.

### Before any resume of verification (both accounts)
1. Wait for resend cooldown (~5 min) if recently sent.
2. Use check-email **Resend** (supported path) to rotate tokens — do **not** reuse screenshot-exposed links.
3. Confirm prior links fail (INVALID_VERIFICATION_TOKEN).
4. Complete TR path with TR messages; EN path only after locale recovery above.

## Provider rollback decision

Delivery to both inboxes **PASS** → retain **PARKIO_EMAIL_PROVIDER=resend**. Locale FAIL does not meet the delivery-failure rollback condition.
