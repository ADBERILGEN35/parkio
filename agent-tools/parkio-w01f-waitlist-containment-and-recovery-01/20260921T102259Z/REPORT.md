# PARKIO-W01F — Waitlist containment and recovery

## PACKAGE STATUS
**READY_FOR_REVIEW** — focused draft PR with server-side admissions containment,
docs correction, and isolated V2-compatible recovery artifact acceptance.
Publication remains blocked.

## API BASELINE / FINAL FEATURE HEAD / DRAFT PR
- Baseline `origin/api`: `f40e18a95ec9fb09732f28f44d3cb731b801f9af`
- Feature branch: `feat/w01f-waitlist-containment-recovery`
- Final feature head / draft PR: recorded in `SHAS.txt` after commit/push

## PR58 POST-MERGE CI TERMINAL RESULTS
Checkout `f40e18a95ec9fb09732f28f44d3cb731b801f9af` (not historical PR base):

| Workflow | Result |
|---|---|
| Runtime validation (Full Docker Compose) | **PASS** |
| Performance smoke (k6) | **PASS** |
| Chaos validation (Compose recovery) | **PASS** |
| Backend CI / Secret scan / Invite dry-run / Integration / CodeQL / Frontend / Mobile-v2 | PASS (prior W01E) |
| Legacy mobile advisory | FAILURE (policy non-blocking) |
| Deploy invite-production | SKIPPED |

## CONTAINMENT CONFIG / DEFAULT / ACTIVATION MECHANISM
- Property: `parkio.waitlist.admissions-enabled`
- Env: `PARKIO_WAITLIST_ADMISSIONS_ENABLED`
- **Default: false** (safe)
- Gates `submit` and `resend` before rate-limit DB work, insert, or confirmation email
- Response: HTTP 503 `WAITLIST_ADMISSIONS_DISABLED`
- Confirm: still allowed (existing tokens; no new outbound confirmation mail)
- Withdraw: still allowed (withdrawal notice may still attempt provider call)
- **Restart required** to change the setting (startup-bound `@ConfigurationProperties`)
- In-flight requests that already passed the check may still mutate/send after an
  operator decides to disable; restart loads the new value

## DIRECT-HTTP BLOCKING / WITHDRAWAL RESULTS
Isolated gateway + PG16 `:55443` + Redis `:56380` + email mock `:18081`:
- Disabled submit/resend → 503, 0 rows, 0 mock sends (PASS)
- Enabled register/confirm/withdraw → PASS
- Re-disable + restart → blocks new admission; prior PENDING/CONFIRMED/WITHDRAWN
  preserved; withdraw of pre-issued token still PASS
- Health up throughout
- See `http-phase-*.json`

## CANDIDATE / RECOVERY ARTIFACT IDENTITIES
- bootJar SHA-256: `A352CF240165D4FFD68A2A57ECDF75ABDB6322EA52BC46802331393BFE6F58F3`
- Local image: `parkio/gateway-service:w01f-*` digest
  `sha256:ea7e560c91e1d0d82c868679a12589d78f57d2bb3cde55907fc57379d7f675b7`
- **Same V2-aware image** is the candidate and the recovery artifact (no registry publish)
- Pre-V2 production digest is **not** an accepted post-V2 rollback target

## V2 UPGRADE / DATA PRESERVATION / RECOVERY RESULTS
- Disposable V1 seed (3 rows) → baselineOnMigrate v1 → candidate jar applied V2
- Result: `PENDING=3`, `non_pending=0`, flyway versions `1` then `2` (`v1-to-v2-upgrade.txt`)
- Containment/restart exercise preserved counts across admissions toggles

## RECOVERY FAILURE CLASSES COVERED / LIMITATIONS
| Mode | Covers | Does not cover |
|---|---|---|
| Containment (`admissions=false` + restart) | New writes/confirmation email | Defective jar; already in-flight admits |
| Restart same artifact | Process crash / config reload | Bad application build |
| Application recovery (redeploy this V2 digest) | Bad config / need known-good V2 gateway | Pre-V2 pin; schema down-migrate; media/parking |

Same binary with different config is containment/config recovery — **not** general app rollback.

## IMAGE SCAN RESULTS
Trivy 0.64.1 image scan (HIGH,CRITICAL): **0** vulnerabilities on ubuntu/jar/gobinary targets.
Scope: local image only. See `trivy-gateway-w01f.txt`.

## CURRENT-CANDIDATE CI / CHECKOUT SHAS
Collected on draft PR after push (see `candidate-ci.txt` / PR checks).

## SOURCE MERGE READINESS
Draft PR ready for human review. Do not merge in this task.
GMP pins unchanged.

## EXACT EXTERNAL INPUTS / PUBLICATION BLOCKERS
See `EXTERNAL-HANDOFF.md`. Logging-only cannot satisfy live acceptance (docs corrected;
PR #58 outdated “if not logging-only” wording noted, description not edited).

## PRODUCTION MUTATIONS = 0
## REAL EMAILS SENT = 0
## REGISTRY PUBLICATIONS = 0
## PRS MERGED = NO
## REAL USER TRACKING ACTIVATED = NO
## EVIDENCE
This directory.
