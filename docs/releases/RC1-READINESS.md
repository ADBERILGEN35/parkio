# RC1 Readiness Decision — v1.0.0-rc1

**Date:** 2026-07-06  
**Sprint:** RC1 — Release Candidate Preparation  
**Target tag:** `v1.0.0-rc1`  
**Baseline commit:** `ff602af` (+ RC1 documentation and packaging)

---

## Executive decision

| Track | Verdict |
|-------|---------|
| **Tag `v1.0.0-rc1`** | **GO** — conditional on checklist completion |
| **Hosted beta** | **GO** |
| **Public production** | **NO GO** |
| **Public open-source publication** | **NO GO** until LICENSE selected |

---

## Quality scores

| Dimension | Score | Notes |
|-----------|-------|-------|
| **Application quality** | 88/100 | Backend + frontend certification complete; mobile device smoke gap |
| **Infrastructure quality** | 62/100 | Compose beta solid; no HA/managed data plane |
| **Documentation quality** | 85/100 | Runbooks, beta docs, RC1 pack; `infra/` placeholder |
| **Security quality** | 84/100 | Fail-closed auth, RBAC, scanning, CI gates; no pen test |
| **Operator readiness** | 80/100 | Preflight, deploy runbook, VPS checklist; LICENSE gap |
| **Overall release score** | **82/100** | Appropriate for hosted-beta RC |

---

## Hosted beta readiness — GO

**Evidence:**

- Backend certification program completed (R-001 through Final Production Certification).
- Frontend FFINAL: 529 unit tests PASS; 7/7 web real-stack smoke PASS.
- Preflight regression: 36/36 assertions (`scripts/test-preflight-hosted-beta.sh`).
- Operator docs: `docs/beta/deploy-runbook.md`, `docs/operations/vps-hosted-beta-checklist.md`.
- Production-readiness plan: **conditional GO** for hosted beta.

**Operator caveats:**

- Run full [`RC1-CHECKLIST.md`](RC1-CHECKLIST.md) before tag and deploy.
- Execute mobile device smoke before widening mobile beta.
- No `CHANGE_ME` in production env.

---

## Public production readiness — NO GO

**Blockers (verified, not speculative):**

1. No managed Postgres PITR + tested restore
2. No Kafka RF≥3 / managed streaming
3. No secrets manager + rotation
4. No production CD with rollback
5. No load/security testing at production scale
6. On-call process not productized

Source: [`docs/architecture/production-readiness.md`](../architecture/production-readiness.md), FFINAL § public production.

---

## Remaining blockers before tag

| ID | Blocker | Severity | Owner | Action |
|----|---------|----------|-------|--------|
| B1 | **No LICENSE file** | High for OSS | Maintainers | Add LICENSE before public distribution |
| B2 | **FINAL-FRONTEND-CERTIFICATION.md untracked** | Medium | Maintainers | Commit with RC1 pack |
| B3 | **F2 certification doc encoding** | Low | Maintainers | Regenerated UTF-8 in RC1 sprint |
| B4 | **Re-run verification gates** | Medium | Maintainers | **DONE** — typecheck PASS, preflight 36/36, frontend tests PASS (RC1 sprint) |
| B5 | **Mobile device smoke** | Medium (beta) | Operators | Not blocking tag; blocking wide mobile beta |

**Not blockers for RC1 tag:**

- Gradle default `0.0.1-SNAPSHOT` (overridden by `-PparkioVersion` on tag)
- Notification fan-out TODO (accepted risk AR-01)
- F3.4 upload race (closed non-actionable)

---

## Phase 8 — Tag readiness scan (summary)

| Check | Result |
|-------|--------|
| `hooks.slack.com/services/` in committed files | **PASS** — fixtures use placeholder URLs |
| `CHANGE_ME` in runtime frontend paths | **PASS** — only in example env + preflight fixtures |
| Accidental secrets in git status | **PASS** — preflight fixtures are fabricated |
| `docs/releases/` pack | **PASS** — created in RC1 |
| CHANGELOG / SECURITY / CONTRIBUTING / SUPPORT | **PASS** |
| Debug `console.log` in production paths | Not exhaustively scanned — rely on lint/CI |
| Generated artifacts committed | **PASS** — none in release pack |

---

## Next milestone

**v1.0.0-rc2 or v1.0.0** (GA beta) when:

1. LICENSE added and legal sign-off
2. Mobile device runtime smoke recorded
3. Operator backup/restore drill documented with evidence
4. Optional: align default `parkioVersion` in docs to `1.0.0-rc1`
5. Public production track: close PP-* blockers in production-readiness plan

---

## References

- [RC1 Release Notes](RC1-RELEASE-NOTES.md)
- [RC1 Checklist](RC1-CHECKLIST.md)
- [Known Issues](KNOWN-ISSUES.md)
- [CHANGELOG](../../CHANGELOG.md)
- [FINAL Frontend Certification](../certification/FINAL-FRONTEND-CERTIFICATION.md)