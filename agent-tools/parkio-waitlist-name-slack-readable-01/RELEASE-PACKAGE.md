# RELEASE PACKAGE — Waitlist full name + readable Slack

**Status:** draft PR only — no production migrate/deploy, no real Slack/email, no secret changes, registration stays CLOSED, provider=resend, NR untouched.

## Branch / SHAs

| Item | Value |
| --- | --- |
| Feature branch | `feat/waitlist-full-name-readable-slack` |
| Branch HEAD (pre-commit) | `bada97023af99f40b51d515e83e0599a19b25441` |
| Base | `origin/api` @ `bada97023af99f40b51d515e83e0599a19b25441` |
| Draft PR | (filled after gh pr create --draft) |

Exact post-commit SHAs are recorded in the PR description after push.

## Changed components

- **gateway-service**: Flyway V5 full_name + index; WaitlistFullName validator; full-name-required flag (default false); admin/CSV fullName; ops exporter contract v2 with fullName + counts
- **web/marketing** (Hostinger / parkio.dev): required Ad soyad / Full name field, i18n, client validation
- **frontend packages**: types / validation / api-client waitlist fullName
- **admin SPA**: waitlist name column + TR/EN missing-name labels
- **scripts/slack_biz**: dual-read v1/v2; dedicated readable waitlist renderer
- **Alertmanager** render-config.sh: Turkish readable titles/body; municipal/ISPARK annotation polish + absolute GitHub runbook URLs (no routing/threshold changes)
- **docs / privacy**: waitlist Slack contract v2 + name retention notes

## Before / after previews

See files in this directory:

- form-before-after.md
- waitlist_v1_nameless.txt, waitlist_v2_named.txt, waitlist_v2_nameless.txt, waitlist_nonprod.txt
- alertmanager-preview-warning.txt, alertmanager-preview-critical.txt, alertmanager-preview-resolved.txt
- waitlist-acceptance.json (mock Slack suite summary: {'PASS': 21, 'FAIL': 0})

## Migration notes (live V4 → V5)

- Additive only: full_name VARCHAR(100) NULL + supporting index (status, confirmed_at).
- Never edit V1–V4. Column remains nullable for rollback and legacy rows.
- Confirmed re-subscribe still does not overwrite rows (name cannot be changed after confirm).

## Rollout order

1. Gateway migrate V5 + optional-name API (full-name-required=false)
2. Deploy slack_biz dual-read v1/v2 consumer/worker
3. Gateway producer emits contract v2 + counts
4. Hostinger marketing upload (required full name on form)
5. Set PARKIO_WAITLIST_FULL_NAME_REQUIRED=true
6. App web pin if admin UI ships separately

## Rollback

- Flip full-name-required off
- Roll back Hostinger marketing to email-only form if needed
- Relay still accepts v1 envelopes
- Leave full_name column in place (nullable)

## Tests / CI

| Suite | Result |
| --- | --- |
| scripts/slack_biz/run_waitlist_acceptance.py | PASS (incl. W20/W21 v2 dual-read) |
| Gateway WaitlistFullNameTest + WaitlistOpsNotificationOutboxTest | PASS (local) |
| AdminWaitlistPage vitest | Added; not executed locally (no frontend node_modules) |
| Prometheus municipal annotation unit expectations | Updated for TR copy + absolute runbook URLs |

## Blockers / out of scope for this package

- No production deployment or Flyway migrate on live
- No merge until review
- No real Slack webhook or email sends
- Alert detection/threshold/routing unchanged
