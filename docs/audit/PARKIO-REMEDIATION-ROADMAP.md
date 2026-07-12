# Parkio Remediation Roadmap (Sprint A1 audit)

**Date:** 2026-07-12 · Finding IDs reference [PARKIO-FINDINGS.md](PARKIO-FINDINGS.md).
Effort: XS <½ day · S ½–1 day · M 2–3 days · L 4–7 days · XL >1 week.

## Horizon P0 — Immediate blockers (before any deploy from this tree)

| # | Item | Findings | Effort | Parallel? | Risk of delay | Verification |
|---|------|----------|--------|-----------|---------------|--------------|
| P0.1 | Commit the (already-made) waitlist test fix, re-run the full web suite, and rebuild the landing artifact from the committed tree (or deploy intake-disabled) | WEB-001 | XS | yes | Deploying the stale zip that predates the fix | full `pnpm --filter @parkio/web test` green on the commit; zip rebuilt; one live submit 202 |
| P0.2 | Rerun the Docker-blocked verification on a Docker-capable host (integrationTest with requireDocker, compose config ×2, validate-hosted-beta-compose) | TEST-001 | XS | yes | Unknown integration regressions | All commands PASS recorded in test-evidence doc |
| P0.3 | Remove `registry=http://…`, `strict-ssl=false` from the dev machine `~/.npmrc` | DX-001 | XS | yes | Poisoned future lockfile entries | `pnpm config get registry` → https |
| P0.4 | Commit or shelve the in-flight landing/waitlist changes so master is release-clean | WEB-001 | XS | — | Untracked drift between artifact and repo | `git status` clean; CI green on the commit |

## Horizon P1 — Before closed hosted beta

| # | Item | Findings | Effort | Parallel? | Risk of delay | Verification |
|---|------|----------|--------|-----------|---------------|--------------|
| P1.1 | Wire Alertmanager receiver env + external uptime monitor on web/api/media domains; consider preflight-enforcing the webhook var | OPS-001, INFRA-002 | XS | yes | Silent outages during beta | Test alert received; synthetic downtime alarms |
| P1.2 | Operator deploy per runbook with preflight + smoke evidence recorded in RC2 report | (repo's own gate) | S | no | No live proof exists | RC2-HOSTED-BETA-DEPLOYMENT-REPORT filled with real output |
| P1.3 | Live-host backup + restore drill (not just CI drill) | INFRA-001, DOC-001 | S | after P1.2 | Unrestorable backups discovered during an incident | Restore produces a working stack from yesterday's dump |
| P1.4 | Mobile physical-device smoke (login, camera upload, push) per docs/beta/mobile-release.md | MOB-001 | S | yes | Tester-facing breakage on day one | Recorded smoke evidence |
| P1.5 | Distinct `PARKIO_WAITLIST_HASH_SECRET`; preflight check that it differs from the gateway secret | PRIV-004 | XS | yes | Hash continuity breaks on secret rotation | Preflight fails on identical/unset secrets |
| P1.6 | Beta-scope legal text replacing placeholders + waitlist removal runbook | PRIV-003 | S | yes | Collecting PII under placeholder terms | Pages updated; removal executed once as drill |
| P1.7 | Fix mobile flaky test (deterministic waits) | TEST-002 | XS | yes | Red-CI fatigue | Suite green 3× in constrained env |

## Horizon P2 — Before external hosted beta

| # | Item | Findings | Effort | Parallel? | Risk of delay | Verification |
|---|------|----------|--------|-----------|---------------|--------------|
| P2.1 | Account deletion: interim manual erasure runbook (all 9 DBs + MinIO + Redis), then the real event-driven flow | PRIV-001 | S (runbook) + L (flow) | flow parallelizable per service | Legal exposure with real users | Erasure drill leaves no PII; integration test for the flow |
| P2.2 | Retention job coverage for `parking_spot_search_logs` / `view_logs` (e.g. 90d) + privacy-page statement | PRIV-002 | S | yes | Sensitive location dataset accretes | Old rows provably deleted on schedule |
| P2.3 | Chaos-verify Redis-down posture (edge limiter fail-open? login fail-closed?) and codify the decision + alert | SEC-001, SEC-002 | S | yes | Unknown abuse window during outages | Chaos run recorded; alert fires |
| P2.4 | SHA-pin all GitHub Actions + Dependabot `github-actions` ecosystem | SUP-001 | XS | yes | CI supply-chain exposure | `uses:` all 40-char SHAs |
| P2.5 | Lat/lng CHECK constraints migration | DATA-001 | XS | yes | Garbage location data persists | Out-of-range insert fails |

## Horizon P3 — Before public beta

| # | Item | Findings | Effort | Notes |
|---|------|----------|--------|-------|
| P3.1 | Write-path load test (upload + spot create + ClamAV) with recorded headroom | PERF-001 | M | Extend existing k6 harness |
| P3.2 | Nearby-notification fan-out (or remove the promise from copy) | BE-001 | M | Product decision first |
| P3.3 | Digest-pin container base images | SUP-002 | XS | With Renovate/Dependabot bumps |
| P3.4 | Real on-call arrangement + alert routing tiers | OPS-001 | S | Process more than code |
| P3.5 | Analytics date-range pagination | BE-002 | XS | |

## Horizon P4 — Before public production

| # | Item | Findings | Effort |
|---|------|----------|--------|
| P4.1 | Managed Postgres (PITR, PostGIS) + managed/HA Kafka + S3 + managed Redis migration | INFRA-001 | XL |
| P4.2 | Secrets manager + rotation (PP-03) | — | M |
| P4.3 | CD with approval gate + tested rollback (PP-04) | — | M |
| P4.4 | Production pen test + full load test (PP-06) | — | L |
| P4.5 | Minimal IaC for the managed topology | INFRA-002 | L |

## Horizon P5 — Strategic

| # | Item | Findings | Effort |
|---|------|----------|--------|
| P5.1 | Promote transport plumbing (outbox relay, retention job, gateway-auth filter) into `parkio-platform` | ARCH-002 | M |
| P5.2 | Service consolidation decision with beta load data (analytics+ai-validation+gamification candidates) | ARCH-001 | XL (only if data supports) |
| P5.3 | License selection → unlocks OSS track (maintainer/legal, not engineering) | OSS-001 | XS |

## Top 20 actions in recommended order

P0.1, P0.4, P0.2, P0.3, P1.1, P1.2, P1.3, P1.4, P1.6, P1.5, P1.7, P2.4, P2.5, P2.2, P2.3, P2.1(runbook), P2.1(flow), P3.1, P4.2, P4.1.

## Quick wins (XS, high leverage)
WEB-001 test fix · Alertmanager webhook + uptime ping · Actions SHA-pinning · lat/lng CHECKs · waitlist secret separation · ~/.npmrc fix.

## High-leverage, moderate effort
Live restore drill · device smoke · erasure runbook · Redis chaos verification.

## High-risk / high-effort (do not start casually)
Managed data-plane migration (P4.1) · account-deletion saga (P2.1 full) · service consolidation (P5.2).

## Deliberately NOT recommended now (overengineering to avoid)
Kubernetes/Helm (repo's own deferral is correct) · multi-region · MFA (design tokens exist; beta doesn't need it) · splitting more services · rewriting the duplicated plumbing before beta (P5.1 can wait) · building the full deletion saga before a closed beta with informed testers (runbook first).
