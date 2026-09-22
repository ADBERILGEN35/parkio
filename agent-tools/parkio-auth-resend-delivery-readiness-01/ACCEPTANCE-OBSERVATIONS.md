# Production acceptance observations (operator)

Date: 2026-09-22 (UTC)

| Step | Result | Notes |
|---|---|---|
| Password reset + login with new password | **PASS** | Confirmed for the tested account only; do not assume both locales |
| Pending-account resend cooldown | **NOT OBSERVED** | Verified-account resend is not cooldown evidence |
| Verified-account check-email resend UI | Misleading definitive email-sent copy (fixed in PR #82) | Actual new delivery from that action not confirmed |
| Provider | resend (retained) | |
| Registration | CLOSED; invite creation false | |
