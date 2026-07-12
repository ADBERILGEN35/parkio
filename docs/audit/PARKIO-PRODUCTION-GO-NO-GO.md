# Parkio GO / NO-GO Decisions (Sprint A1 audit)

**Date:** 2026-07-12 · **Commit:** `f5efa0dd` · Evidence base: [PARKIO-TEST-EVIDENCE.md](PARKIO-TEST-EVIDENCE.md), [PARKIO-FINDINGS.md](PARKIO-FINDINGS.md)

Decisions are independent per track. "CONDITIONAL GO" means GO once the listed conditions are met and evidenced; nothing else blocks.

| # | Track | Decision |
|---|-------|----------|
| 1 | Static public landing page | **CONDITIONAL GO** |
| 2 | Real waitlist collection | **CONDITIONAL GO** |
| 3 | Internal developer demo | **GO** |
| 4 | Closed hosted beta (invited, informed testers) | **CONDITIONAL GO** |
| 5 | External hosted beta (strangers sign up) | **NO-GO** |
| 6 | Public beta | **NO-GO** |
| 7 | Public production | **NO-GO** |
| 8 | AWS Activate technical proof | **GO** |
| 9 | AWS hosted-beta deployment | **NOT VERIFIED** |
| 10 | Open-source public repository | **NO-GO** |

---

## 1. Static public landing page — CONDITIONAL GO

**Evidence for:** Landing bundle builds (web build PASS, 22s); dedicated static intake-disabled mode exists (`VITE_WAITLIST_INTAKE_MODE=disabled` renders a no-collection notice); SEO/OG/robots/sitemap assets and tests present; CSP-compatible; self-hosted fonts.
**Blocking:** WEB-001 — the waitlist submit test failed at the audit snapshot; it was fixed later the same day, but the fix is uncommitted and `parkio-hostinger-landing.zip` was built **before** the fix.
**Conditions:** commit the fix, re-run the full web suite green, rebuild the zip from the committed tree (or deploy with intake mode `disabled`), and confirm the intended `VITE_WAITLIST_INTAKE_MODE` in the artifact.

## 2. Real waitlist collection — CONDITIONAL GO

**Evidence for:** Gateway waitlist path is well-engineered: HMAC-hashed IP/UA (raw IP never stored), consent timestamp, email normalization + unique hash, dual IP/email rate limits (fail-closed), ADMIN-only export, public POST allow-listed, Flyway-managed schema.
**Blocking:** PRIV-003 (placeholder legal pages while collecting email PII; no deletion/retention procedure), WEB-001 (unverified submit path), plus it needs a hosted gateway+Postgres+Redis (not yet live by the repo's own account).
**Conditions:** finalized beta-scope privacy text; a documented waitlist-removal procedure; WEB-001 green; hosted stack deployed with preflight PASS.

## 3. Internal developer demo — GO

**Evidence:** 712/712 backend unit tests re-executed green; typecheck/lint/build green; compose stack documented and previously benchmarked end-to-end (p221 report ran the full stack with all health checks UP). No conditions beyond a Docker-capable machine.

## 4. Closed hosted beta — CONDITIONAL GO

**Evidence for:** This matches the repo's own claim ("GO 84/100"), and this audit **substantiates the application layer**: security architecture verified in code (SEC-004), event reliability design sound, operator tooling (preflight/deploy/rollback/smoke/backup/restore) present and syntax-clean, runtime sizing measured not guessed.
**Blocking findings:** OPS-001 (alerts route to null; no uptime check), MOB-001 (no device smoke), TEST-001 (integration suite not independently re-verified — CI must be green on the deploy commit), INFRA-001 (accepted risk — requires backup discipline).
**Conditions before first invite:**
1. Deploy via `HOSTED-BETA-RUNBOOK.md` with `preflight-hosted-beta.sh` PASS and `smoke-hosted-beta.sh` PASS recorded (the repo itself demands this evidence — it does not exist yet).
2. Alertmanager receiver wired + external uptime monitor on the three domains (OPS-001).
3. First backup taken and one restore drill executed **on the live host**.
4. Mobile testers only after the device smoke of `docs/beta/mobile-release.md` (MOB-001).
5. Beta consent/disclaimer shown to testers (legal placeholders acceptable only for informed, invited testers).

## 5. External hosted beta — NO-GO

Open registration to strangers requires everything in #4 **plus**: PRIV-001 (account deletion — at minimum a tested manual erasure runbook, preferably the real flow), PRIV-002 (retention on location logs), PRIV-003 (real legal pages), SEC-001/SEC-002 posture verified under Redis chaos, and abuse/moderation staffing. None of these are met today.

## 6. Public beta — NO-GO

All of #5, plus PERF-001 (write-path load evidence), OPS-001 upgraded to a real on-call arrangement, and INFRA-001 at least partially mitigated (managed Postgres or PITR-capable setup). Matches the repo's own PP-01..06 NO-GO list; this audit confirms those blockers are real and open.

## 7. Public production — NO-GO

Confirmed blockers (all documented in-repo and verified open by this audit): no HA data plane/PITR (PP-01/02), no secrets manager+rotation (PP-03), no CD with rollback+approval (PP-04), on-call partial (PP-05), no production-scale load/pen test (PP-06), plus PRIV-001/002/003. The repo does not overclaim here; neither does this audit.

## 8. AWS Activate technical proof — GO

The technical package is credible for an application: real microservice implementation, measured benchmarks, honest readiness docs, AWS mapping already drafted (`docs/startup/11-aws-activate-package.md`, production-readiness §2–3). Nothing in Activate review requires a live deployment. No conditions.

## 9. AWS hosted-beta deployment — NOT VERIFIED

No IaC exists (`infra/` is a stub, HB-03) and no AWS environment was reachable from this audit. The architecture maps cleanly (see main report §AWS), but a decision requires: chosen topology (single EC2 running the compose stack is the only cost-credible near-term option vs ~$400+/mo for ECS+RDS+MSK), Terraform or documented manual build, and a deployed preflight+smoke PASS. Until an operator produces that evidence: NOT VERIFIED.

## 10. Open-source public repository — NO-GO

OSS-001: `LICENSE` explicitly reserves all rights pending selection. Also missing NOTICE/attributions review (OSS-02 in KNOWN-ISSUES). The repo already declares this NO GO; confirmed. Single condition: maintainer selects and commits a license (then re-review NOTICE + third-party attribution needs).

---

### Minimum path to change each status

| Track | Minimum remediation |
|-------|--------------------|
| 1 → GO | Fix WEB-001 or deploy intake-disabled (XS) |
| 2 → GO | WEB-001 + PRIV-003 minimal legal + removal runbook (S) + hosted stack live |
| 4 → GO | Operator deploy evidence + OPS-001 (XS) + live restore drill (S) |
| 5 → CONDITIONAL GO | PRIV-001 runbook-level (S) or full flow (L); PRIV-002 (S); PRIV-003 (S); chaos-verify SEC-001/002 (S) |
| 7 → reconsider | Managed data plane migration (XL) + secrets manager (M) + CD rollback (M) + load/pen test (L) |
| 10 → GO | License selection (XS, maintainer decision) |
