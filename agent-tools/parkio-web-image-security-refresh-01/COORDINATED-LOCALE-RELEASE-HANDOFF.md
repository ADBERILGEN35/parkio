# Coordinated web security + registration locale release handoff

Snapshot: 2026-09-22 (updated after PR #82 resend-copy follow-up). Coordination plan only — not a combined candidate record.

## Current release boundary

PR #81 remains a completed, security-only draft. Preserve these historical identities:

- PR #81 evidence head: `96bf8d5afb507bf9782d69fda38ecc2b8938eb36`.
- PR #81 runtime source: `748dd950697e2d369b523d49e258b541188f8a8b`.
- PR #81 local-only image: `parkio/web@sha256:84767078ab08517c636fb276fb45f3a727442e50e7713dc7dc4c3198c3e10e19`, `linux/amd64`.
- PR #81 platform manifest: `sha256:3fd448b599600fddab049cd19ed996ab5899580b029b8883fba75d27ac36f333`.
- Reported production/rollback image: `ghcr.io/adberilgen35/parkio/web@sha256:daba786490be9b6015572fbc2a5ba7efd72a4bcdad860372792c0f12b31b59be`.

The PR #81 image is **not** the coordinated candidate. Do not publish, pin, or deploy it because it predates the registration-locale correction and the check-email resend copy fix.

## Locale + check-email dependency (PR #82)

Cursor owns branch `fix/register-invite-lang-locale` → PR #82 (`https://github.com/ADBERILGEN35/parkio/pull/82`).

At this snapshot the branch carries:

1. Invite handoff `lang=tr|en` so registration UI and `preferred_locale` match the invite language.
2. Enumeration-safe check-email / RegisterPage resend feedback: conditional TR/EN copy (no definitive "email sent"), with sign-in guidance if already verified.
3. Isolated auth unit coverage: verified accounts issue no new verification email/token; pending-account cooldown suppresses repeat delivery (`sendCount` + token identity).

Auth HTTP public responses are unchanged. No production deploy, Slack, or New Relic changes are part of this dependency.

### Exact readiness dependency

Combined preparation is blocked until:

1. PR #82 has a pushed immutable remote head with terminal applicable CI;
2. focused evidence is green for that exact head:
   - locale parser / RegisterPage / VerifyEmailPage invite-lang tests;
   - `CheckEmailPage.test.tsx` EN/TR conditional resend copy;
   - RegisterPage resend assertion against the conditional EN string;
   - `AuthApplicationServiceTest` verified no-send + limited pending cooldown;
3. either a normal merge to `api`, or an explicitly reviewed remote head that can be normally merged into an isolated integration branch without rebase or force-push.

Do not reconstruct locale or copy changes from a dirty worktree.

## Required TR/EN invite handoff

The combined release must carry an explicit, allowlisted UI-locale handoff:

- Turkish: `https://app.parkio.dev/register?invite=<opaque>&lang=tr`
- English: `https://app.parkio.dev/register?invite=<opaque>&lang=en`

Requirements:

- only `tr` and `en` are accepted; unsupported/missing values follow the reviewed fallback behavior;
- `lang` controls the registration UI and the locale serialized in the registration request, even if the browser previously persisted the other language;
- invite tokens remain opaque and must not be printed in CI, evidence, PR text, or release artifacts;
- the consumed `invite` and `lang` parameters are removed according to the reviewed page behavior;
- verification links retain the appropriate explicit locale;
- registration remains CLOSED outside the separately authorized invite flow;
- provider selection and stored-locale recovery for already-created accounts remain outside this web-image release;
- check-email / resend UI must use the conditional enumeration-safe TR/EN copy (not definitive send confirmation).

## One combined candidate procedure

After the dependency above is ready:

1. Create a new isolated worktree and integration branch from freshly fetched `origin/api`. Do not reuse PR #81's worktree or Cursor's locale worktree.
2. Bring both reviewed changes in through normal repository history: PR #81's Nginx runtime/header commits and the exact reviewed PR #82 head. Do not rebase or force-push.
3. Verify the integration diff contains only the expected web runtime/header, registration locale, check-email resend copy, focused tests, invite-handoff script, and release-evidence files. Do not modify shared Compose, production pins, gateway, parking-service, Slack, or New Relic. Auth-service changes in the combined image are not required for the web candidate (auth unit proofs stay on PR #82); do not alter auth HTTP contracts.
4. Build one new `linux/amd64` image from the combined runtime source using the unchanged canonical `docker/web-hosted-beta.release-bake.env` and the existing secret-safe public MapTiler input procedure.
5. Record a new source SHA, OCI image/index identity, platform manifest/config identities, resolved Nginx base digest, and scan timestamp. Never reuse PR #81's candidate digest as the combined identity.
6. Add coordinated evidence only after the runtime source is fixed. Keep any later documentation-only head distinct from the combined runtime source.

## Configuration invariants

The combined image must compile and verify the same release values as PR #81 except for the reviewed request-time locale behavior and check-email copy:

| Input | Required value |
| --- | --- |
| `VITE_APP_ENV` | `hosted-beta` |
| `VITE_API_BASE_URL` | `https://api.parkio.dev/api/v1` |
| `VITE_PUBLIC_EXPLORE_ENABLED` | `true` |
| `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED` | `true` |
| `VITE_SMART_RETURN_ENABLED` | `true` |
| `VITE_SMART_PARKING_ASSISTANT_ENABLED` | `false` |
| `VITE_WAITLIST_INTAKE_MODE` | `api` |
| `VITE_MAPTILER_STYLE` | `streets-v2` |
| `VITE_FRONTEND_ERROR_REPORTING` | `disabled` |
| `VITE_REGISTRATION_MODE` | `closed` |
| `VITE_MAPTILER_KEY` | present; value not recorded |

Server-side Explore remains enabled with the current IZUM allowlist. Municipal discovery, roadside coverage and source/access labels, smart-return behavior, verification-language handling, and PR #76's in-memory-only phone behavior must remain intact.

## Evidence reuse and required reruns

Preserve PR #81's baseline scan JSON, official compatibility rationale, baseline CVE inventory, rollback digest, and historical test reports. They remain evidence for the unchanged production baseline and remediation choice.

Rerun on the combined runtime source/image:

- locale parser tests and the reviewed RegisterPage/VerifyEmailPage tests;
- explicit prior-TR-browser to EN-invite and prior-EN-browser to TR-invite cases;
- `CheckEmailPage` EN/TR conditional resend feedback (no definitive "email sent");
- invite-generation script tests or synthetic acceptance proving both `lang=tr` and `lang=en` URLs without exposing tokens;
- PR #76 pending-profile phone/sessionStorage regression tests;
- compiled release-bundle flag guards;
- actual-image startup/health and Chromium SPA acceptance for `/login`, `/register`, `/check-email`, `/explore`, `/map`, and `/verify-email` with production API mocked and other external origins blocked;
- JS/CSS MIME, cache policy, CSP and the other security headers, plus root-master/non-root-worker behavior;
- exact combined-image Trivy scan without `--ignore-unfixed`, using a recorded DB snapshot and reporting all severities.

The PR #81 results that are unrelated to locale/copy source changes can be referenced as historical evidence, but the final combined image identity, bundle guards, headers/SPA acceptance, and scan must be fresh.

## Operator acceptance notes (not image evidence)

See `agent-tools/parkio-auth-resend-delivery-readiness-01/ACCEPTANCE-OBSERVATIONS.md`:

- Password reset + login with new password: **PASS** (tested account only; do not assume both locales).
- Pending-account resend cooldown in production: **NOT OBSERVED** (verified-account resend is not cooldown evidence).

## Open build-chain exposure and separate task

PR #81's discarded `node:22-bookworm-slim` scan remains open: `5 CRITICAL / 62 HIGH / 99 MEDIUM / 73 LOW / 1 UNKNOWN`. These findings do not ship in the Nginx runtime, but they are not accepted or resolved by this coordination plan.

Track a separate repository document/task named `WEB-BUILD-IMAGE-SECURITY-01` with this narrow scope:

- compare currently supported Node 22 builder variants and official patched images;
- separate Debian OS findings from bundled npm/Corepack tooling findings;
- apply only compatible builder/tooling updates without changing shared/root lockfiles or broad SPA dependencies;
- prove deterministic bundle equivalence and existing release-gate behavior;
- scan the exact builder and final runtime under one DB snapshot;
- keep any builder-only remediation out of the coordinated locale/runtime release unless independently reviewed.

## Proposed rollout and rollback (not authorized here)

After review and separate authorization, publish only the final combined image, record its immutable GHCR digest, update only the web pin, and recreate only the web container with `--no-deps`. Verify both synthetic TR/EN invite handoffs plus health, headers, deep links, and compiled flags at the public edge.

Rollback restores only the web pin to `ghcr.io/adberilgen35/parkio/web@sha256:daba786490be9b6015572fbc2a5ba7efd72a4bcdad860372792c0f12b31b59be` and recreates only web with `--no-deps`. No gateway, Slack, New Relic, auth provider, database, or unrelated application restart is part of either procedure.
