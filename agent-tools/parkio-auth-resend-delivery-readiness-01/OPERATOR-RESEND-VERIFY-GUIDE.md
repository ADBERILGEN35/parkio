# Operator guide ? resume verification after EN locale correction

Date: 2026-09-22 (UTC)

## State before you start

- Registration: **CLOSED**; invite creation: **false**
- Provider: **resend** (retained)
- INBOX_EN `preferred_locale`: corrected **tr ? en** (one-row guarded SQL)
- INBOX_TR remains `preferred_locale=tr`
- Do **not** open exposed verification links from screenshots
- Successful **Resend** rotates the stored verification hash (see `resendVerificationRotatesTokenAndIsEnumerationSafeWhenLimitedOrUnknown`) ? previous links become invalid
- Resend uses **stored** preferred_locale (request locale ignored by design)
- Cooldown: ~PT5M
- You drive check-email Resend; agent does not send mail

## Checklist (record PASS/FAIL; unobserved stays unset)

### 1) TR ? oguzhantasyaran+parkio-tr@outlook.com
1. https://app.parkio.dev/check-email
2. Enter TR email ? Resend verification
3. Confirm Turkish verification mail; From verify@parkio.dev; link host app.parkio.dev with lang=tr
4. Record provider / inbox / language

### 2) EN ? oguzhantasyaran+parkio-en@outlook.com
1. check-email with EN email ? Resend once
2. Confirm English verification mail and lang=en
3. Record separately

### 3) Cooldown
1. Immediate second Resend on one account
2. Expect cooldown (no new distinct mail)
3. Record only if observed

### 4) Verify + login (each)
1. Open only the **latest** verification message for that inbox
2. Verify ? log in with registration password
3. Record per account

### 5) Password reset (each locale)
1. Forgot password; matching UI language
2. Reset from email ? log in with new password
3. Record per locale
