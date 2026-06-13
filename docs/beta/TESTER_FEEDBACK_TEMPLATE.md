# Parkio Beta — Tester Feedback

Copy this template per tester (or per session) and fill it in. Keep it short — a
sentence per field is fine. For bugs, the **traceId + screenshot** are the most useful.

---

## Tester & environment

- Name / handle:
- Date:
- OS + version (e.g. Windows 11 / macOS 14):
- Docker Desktop version:
- Browser + version:
- Ran locally / shared host:

## Install & run

- Setup followed the runbook cleanly? (Y/N)
- Any step that was confusing or wrong:
- Time to first successful login:
- Install/startup errors (paste message + which step):

## Auth (register / login)

- Register worked? (Y/N)
- Noticed the 1–5 s provisioning wait? Was it clear?
- Login worked? (Y/N)
- `/auth/me` / profile loaded? (Y/N)
- Notes:

## Map & search

- Map loaded? (Y/N)
- Geolocation or manual coordinates used:
- Nearby search returned expected results? (Y/N)
- Notes:

## Upload & create spot

- Photo upload worked? (Y/N)
- Create spot worked? (Y/N)
- Form clarity (vehicle types, legal status, context):
- Notes:

## Photo / signed URL

- Spot photo rendered on the detail page? (Y/N)
- If not: did the URL contain `host.docker.internal`? Your OS/network:
- Notes:

## Verify / claim (second account)

- Verify worked? (Y/N) — resulting status:
- Claim worked? (Y/N) — resulting status:
- Notes:

## Profile / gamification

- Points/level updated after actions? (Y/N)
- Stats believable / clear? (Y/N)
- Notes:

## Moderation / analytics (only if granted MODERATOR/ADMIN)

- `/moderation` opened? (Y/N)
- `/analytics` opened? (Y/N)
- Notes:

---

## Bugs

Repeat this block per bug.

### Bug 1
- **Summary:**
- **Severity:** Blocker / Major / Minor / Cosmetic
- **Steps to reproduce:**
- **Expected:**
- **Actual:**
- **traceId (from the error message):**
- **Screenshot (attach / link):**
- **Relevant logs** (`docker compose ... logs <service> --tail 80`):

### Bug 2
- **Summary:**
- **Severity:**
- **Steps to reproduce:**
- **Expected:**
- **Actual:**
- **traceId:**
- **Screenshot:**
- **Logs:**

---

## UX confusion points

Where did you hesitate, misread a label, or not know what to do next?

-

## Feature requests / wishes

Ideas only — not commitments.

-

## Overall

- Did the core loop (register → create spot → verify/claim) work end to end? (Y/N)
- Overall impression (1–5):
- One thing to fix first:
