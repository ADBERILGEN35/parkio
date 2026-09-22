# PR #81 + PR #82 coordinated web release-decision package

Snapshot: 2026-09-22. Preparation only; no merge, registry publication, production pin change, deployment, provider change, Slack change, New Relic change, or application restart was performed.

## Decision summary

The combined web candidate is ready for review and a separate publication/deployment decision. It contains PR #81's supported Nginx runtime/security-header refresh and PR #82's reviewed registration `lang=tr|en` handling plus enumeration-safe conditional resend feedback in both languages. Registration remains `CLOSED` and the provider remains `resend`.

The candidate runtime scan reports no CRITICAL, HIGH, MEDIUM, LOW, or UNKNOWN vulnerabilities under the recorded Trivy snapshot. This does **not** close the discarded Node builder exposure: `node:22-bookworm-slim` remains an open build-chain finding set of 5 CRITICAL / 62 HIGH / 99 MEDIUM / 73 LOW / 1 UNKNOWN, tracked separately as `WEB-BUILD-IMAGE-SECURITY-01`.

## Immutable identities

| Item | Identity |
| --- | --- |
| Integration base (`origin/api`) | `9e95fa64c3327c3f96c146453522a0e231497bf0` |
| PR #81 evidence tip brought into history | `678af1ab68f04a8aff986968f03abb917026cf56` |
| PR #81 runtime source (historical security-only candidate) | `748dd950697e2d369b523d49e258b541188f8a8b` |
| PR #82 reviewed tip | `e0fede576c039050011326ad013da3e33ceb23fe` |
| PR #82 latest code commit | `6d4272a12cd94510b6cf46df6ab54d573b7b12db` |
| Combined runtime source / image label | `c0b0451714e1591db79055f140b7d7006466b6aa` |
| Local OCI index / image digest | `parkio/web@sha256:5915693451ed8d7b6c84ceb795cd685e6cfb68b906d4f46473fc6ba8252dd21b` |
| `linux/amd64` platform manifest | `sha256:dfadf1d263463e889404d33e8ccefdc7f3507a862fec066bcbdc85dcf25f2acc` |
| Image config | `sha256:c98cb28ab408d7cbc2561a43673fb31a30ccaadb18f5039d0eb2e21d8ae4262f` |
| Runtime base | `nginx:1.30.5-alpine3.24@sha256:a5f2157a0302eb0c5e300415effb63a9e70ed1eb9c107283819bf6d149ab607c` |
| Build base (discarded stage) | `node:22-bookworm-slim@sha256:48e4b67d85f87bd551df43704e24d252f56cc5f8e9718841aace50f19948f0f9` |
| Reported production / rollback web image | `ghcr.io/adberilgen35/parkio/web@sha256:daba786490be9b6015572fbc2a5ba7efd72a4bcdad860372792c0f12b31b59be` |

The earlier PR #81 image `parkio/web@sha256:84767078ab08517c636fb276fb45f3a727442e50e7713dc7dc4c3198c3e10e19` is historical evidence only and is not the combined candidate. The combined runtime source is the merge commit above; this package and scan are later evidence-only changes and must not be confused with the image source revision.

## Configuration and behavior delta

The image was built from the canonical `docker/web-hosted-beta.release-bake.env`, using the existing secret-safe public MapTiler input recovery procedure. No value was recorded.

| Build/runtime property | Effective candidate value |
| --- | --- |
| `VITE_APP_ENV` | `hosted-beta` |
| `VITE_API_BASE_URL` | `https://api.parkio.dev/api/v1` |
| Public Explore | enabled |
| Municipal discovery | enabled |
| Smart return | enabled |
| Smart Parking Assistant | disabled |
| Waitlist intake | `api` |
| Registration bootstrap | `CLOSED` |
| Frontend error reporting | disabled |
| MapTiler style/key | `streets-v2`; key present, value not recorded |
| Auth provider | unchanged, `resend` |

PR #81 changes only the web runtime base and Nginx header inheritance. PR #82 adds allowlisted `lang=tr|en` invite handling, removes consumed invite/language query values from the browser URL, retains verification locale, and replaces definitive resend success wording with conditional enumeration-safe EN/TR feedback. Explore, municipal discovery, roadside coverage/source labels, smart-return, PR #68 verification-language behavior, and PR #76 in-memory-only phone/sessionStorage scrubbing remain in place.

## Acceptance evidence

- PR #82 exact tip `e0fede576c039050011326ad013da3e33ceb23fe`: all applicable GitHub checks terminal successful on 2026-09-22. This includes build/unit, Testcontainers integration, frontend typecheck/lint/test/build, config/script checks, Java/Kotlin and JavaScript/TypeScript CodeQL, dependency and secret scans, image build dry-run, Trivy, and service container scans. Production deploy/rollback jobs were skipped as intended.
- Focused combined-source Vitest: 6 files, 38/38 PASS. Covers locale parsing, opposite-persisted-locale EN/TR invite handoff, VerifyEmail locale, EN/TR conditional resend UI, registration resend assertion, and PR #76 pending-profile/account-preparing regressions.
- Canonical build/bundle gate: PASS on the exact image. Required API/MapTiler inputs were present without printing values; hosted-beta, Public Explore and municipal discovery guards passed. Registration-closed behavior was also observed in the actual image.
- Exact-image base acceptance: 36/36 PASS. Health, HTML/JS/CSS MIME, immutable asset caching, no-cache index, CSP and current security headers, `/login`, `/explore`, `/map`, `/verify-email`, current root-master/non-root-worker behavior, and 0755/0644 static permissions passed.
- Exact-image locale/resend Chromium acceptance: 12/12 PASS. `/register` EN-over-prior-TR and TR-over-prior-EN both applied the invite locale and stripped `invite`/`lang`; `/check-email` rendered conditional, non-definitive resend feedback in EN and TR. Registration-mode and resend API calls were mocked, external origins were blocked, and no account or email was created.
- Unchanged broad Explore/municipal evidence from PR #81 is preserved rather than rerun because neither integration conflict nor PR #82 changed those paths.
- Verified-account no-send and pending-account cooldown are covered by PR #82's isolated `AuthApplicationServiceTest` and terminal CI. They are not production observations.

Operator-observed limits remain explicit:

- Password reset + login: **PASS for the operator-confirmed account only**.
- Production pending-account cooldown: **NOT OBSERVED**.
- No claim is made that password reset/login was accepted in both locales.

## Exact-image security comparison

Scanner: Trivy `0.74.0`; vulnerability DB schema `2`, updated `2026-09-22T02:00:05.774028462Z`. Baseline and final candidate used this same DB snapshot, without `--ignore-unfixed`.

| Image | CRITICAL | HIGH | MEDIUM | LOW | UNKNOWN |
| --- | ---: | ---: | ---: | ---: | ---: |
| Reported production baseline | 0 | 23 | 40 | 10 | 0 |
| Combined runtime candidate | 0 | 0 | 0 | 0 | 0 |

The final runtime has 23 fewer HIGH, 40 fewer MEDIUM, and 10 fewer LOW findings, with no new runtime findings. Scan artifact: `20260922T193200Z/combined-candidate-trivy.json`. The result describes the shipped Alpine/Nginx runtime only; the non-shipping Node builder findings above remain unresolved and unaccepted.

## Proposed web-only rollout and rollback

After separate authorization, publish the exact combined runtime source once, record the immutable GHCR digest, update only the web image pin, and recreate only the web container with `--no-deps`. Verify health, headers, compiled flags, `/register` TR/EN invite handoff, and `/check-email` conditional copy at the public edge. Registration must stay `CLOSED`; provider must stay `resend`.

Rollback updates only the web pin to `ghcr.io/adberilgen35/parkio/web@sha256:daba786490be9b6015572fbc2a5ba7efd72a4bcdad860372792c0f12b31b59be` and recreates only web with `--no-deps`. This also rolls back the locale/resend UI corrections. It does not restart gateway, auth, parking, Slack, New Relic, or any unrelated application.

## Remaining decision gates

1. Review and terminal applicable CI on the later evidence/documentation head.
2. Separate authorization to publish the combined image and update only the production web pin.
3. Keep `WEB-BUILD-IMAGE-SECURITY-01` open for the discarded Node build stage; a zero-finding runtime scan is not acceptance of that exposure.
