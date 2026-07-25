# Mobile release policy

**Status:** active
**Owner:** Parkio release engineering
**Last reviewed:** 2026-07-25

## Release target

`frontend/apps/mobile-v2` is the **canonical, supported Mobile application** and the
only mobile release artifact. This restates what `frontend/README.md` ("Mobile
foundation (WP-07)") already declares: *"Canonical Mobile app is `apps/mobile-v2`
(legacy `apps/mobile` is not the production target)."*

`frontend/apps/mobile` is **deprecated**. It is retained in the repository for
reference during retirement (screens and services are still being ported), it is
not built for distribution, and it is not part of any release or hosted-beta
rollout.

| App | Package | Release target | CI status |
|---|---|---|---|
| Mobile-v2 | `@parkio/mobile-v2` | yes — canonical | **blocking** (Mobile CI) |
| Mobile (legacy) | `@parkio/mobile` | no — deprecated | advisory (Mobile CI, `continue-on-error`) |

## CI consequences

1. **Mobile CI / `mobile-v2-checks` is blocking.** Typecheck, lint, the focused
   WP-07 + Task-08 suites, and the full jest suite must pass for a release to
   proceed.
2. **Mobile CI / `mobile-legacy-checks` is advisory.** It still runs on every
   qualifying change so retirement debt stays visible, but a deprecated app must
   not block releases of the canonical apps. It is marked `continue-on-error:
   true` and its job name carries the `(advisory)` suffix.
3. **Frontend CI does not run the Expo/React Native apps.** The aggregate
   `pnpm -r` typecheck/lint/test/build steps exclude `@parkio/mobile` and
   `@parkio/mobile-v2`, because:
   - a blocking legacy copy in Frontend CI would silently contradict rule 2, and
   - running the mobile-v2 jest suites twice in parallel with the web suites only
     added runner contention, which surfaced as React Native Testing Library
     `waitFor` timeouts (see `docs/operations/security-findings-2026-07-25.md`
     sibling triage notes in the rollout manifest).

   No coverage is lost: every excluded script runs in Mobile CI, where mobile-v2
   is blocking.

## Retiring legacy mobile

Deletion is intentionally **out of scope** of this policy — the port is not
finished. When it is, retirement means: remove `frontend/apps/mobile`, drop the
`mobile-legacy-checks` job, and remove the legacy path filters from Mobile CI.
Until then, advisory failures in legacy mobile are tracked as retirement debt, not
release blockers.

## Applies to

- `v1.0.0-rc3` and later releases.
- `v1.0.0-rc2` predates the policy being written down; the same intent was already
  documented in `frontend/README.md`.
