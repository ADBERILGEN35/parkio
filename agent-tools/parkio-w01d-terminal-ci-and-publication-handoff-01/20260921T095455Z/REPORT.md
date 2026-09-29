# PARKIO-W01D — Terminal CI and publication handoff

## PACKAGE STATUS
**READY_FOR_USER_MERGE_DECISION** for source merge of PR #58 at the reviewed head
(required `api` protection checks green; invite dry-run PASS on this head).

**NOT ready for publication.** External activation + V2-compatible recovery artifact
remain blocking for deploy/Hostinger.

Draft status unchanged; this task did not undraft, approve, or merge.

## API / PR58 FINAL FULL HEAD
- `origin/api` / PR base: `297b02ab10b3096b186016f35675d3cc3aedbc1c`
- Final full head: `2b12e6c08383a4aa4616d7f3a7741ac7a64e233a`
- PR: https://github.com/ADBERILGEN35/parkio/pull/58 (draft)

## HEAD / BASE DRIFT
None. Local checkout matches PR head and recorded base.

## CI TERMINAL RESULTS / ACTUAL CHECKOUT SHAS
All results below are for head `2b12e6c0…` (not earlier heads). See `ci-checks.txt`.

### Required for merge into `api` (branch protection)
| Check | Result |
|---|---|
| Build & unit tests | PASS |
| Secret scan | PASS |

### Invite dry-run (previously failing on earlier head)
| Check | Result |
|---|---|
| Build images + secret-safe dry-run manifest | **PASS** (1m24s) |

### Other completed PASS (this head)
Backend integration Testcontainers; Frontend Typecheck/lint/test/build; Safety guards;
Backup→restore; Mobile-v2; Security CI summary; CodeQL (java-kotlin + js); Trivy;
Dependency vulnerability scan; all listed container scans including gateway-service.

### FAILED
| Check | Classification |
|---|---|
| Legacy mobile typecheck, lint, test & expo doctor (advisory) | **FAILURE** recorded. Cause: expo-doctor dependency mismatches on deprecated `apps/mobile` (`@expo/metro-runtime` / Expo SDK patch skew). Workflow uses `continue-on-error: true`; repo `docs/operations/mobile-release-policy.md` states legacy must not block canonical releases; Mobile-v2 **PASS**. Not a waitlist/candidate defect. Not in `api` required contexts. |

### SKIPPED
Deploy invite-production; Non-deploy production runner acceptance; Restore drill + evidence bundle; Migrate legacy workspace bind mounts; Rollback invite-production.

### PENDING (bounded poll still open at handoff; not redispatched)
Full Docker Compose runtime validation; k6 smoke against Docker Compose; Compose dependency recovery drill.

Operator note: these are **not** `api` required contexts. Confirm their final conclusions before merge if local policy treats them as applicable; do not invent PASS.

## INVITE DRY-RUN RESULT
**PASS** on `2b12e6c0…` after W01C cutover CORS guard fix (`app` or `app+parkio.dev`).

## FAILED / SKIPPED / PENDING CHECK CLASSIFICATION
See tables above. SKIPPED kept as SKIPPED. PENDING kept as PENDING. Legacy kept as FAILURE with non-blocking policy evidence beyond the name alone (`continue-on-error` + mobile-release-policy + Mobile-v2 PASS + not required).

## ACCEPTANCE EVIDENCE REUSED / SOURCE DELTA
Reused without re-run:
- W01C: actual gateway + Postgres 16 + Redis + email mock HTTP (`agent-tools/parkio-w01c-…/20260921T093722Z/`)
- W01C: V1→V2 legacy rows remain PENDING
- W01A/W01B: bilingual UI Playwright/validator evidence
- W01B setup checklist

Source delta since W01C acceptance reuse is valid for: V2 no-auto-confirm, Resend base URL, resend email-only body, cutover CORS allowlist — already on head `2b12e6c0…`. No further source change in W01D.

## PR58 SOURCE MERGE READINESS / EXACT BLOCKERS
**READY_FOR_USER_MERGE_DECISION** subject to human undraft + review + merge authorization.

Exact non-merge blockers for *publication* (not source merge):
1. No V2-compatible gateway recovery artifact built/accepted yet
2. Resend account/domain/DNS + production secrets not confirmed installed
3. Gateway image publish + gateway-only GMP pin update not done
4. Civo gateway deploy + Flyway V2 not authorized/executed
5. Authorized live email acceptance not executed
6. Hostinger upload not authorized (must follow live API/email)

## V2 RECOVERY STRATEGY / UNVERIFIED PREREQUISITES
See `V2-RECOVERY.md`. Pre-V2 gateway digest is **not** a safe rollback after migration.
**No V2-compatible recovery artifact has been built and accepted in this program** — deployment prerequisite.

## EXTERNAL SETUP INPUTS STILL REQUIRED
See `EXTERNAL-HANDOFF.md` (deltas only vs reused checklist).

## PUBLICATION AUTHORIZATIONS STILL REQUIRED
Merge auth; gateway registry publish + pin update; Civo deploy/migration; DNS (if domain unverified); Resend secret install; live test email send; Hostinger publish.

## PRODUCTION MUTATIONS = 0
## REAL EMAILS SENT = 0
## PRS MERGED = NO
## REAL USER TRACKING ACTIVATED = NO
## EVIDENCE
This directory.
