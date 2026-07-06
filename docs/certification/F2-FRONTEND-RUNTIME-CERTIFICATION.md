# Parkio F2 — Frontend Runtime Certification (Web + Mobile)

**Date:** 2026-07-06  
**Evidence root:** `.codex/f2-frontend-runtime` (logs and Playwright artifacts where present)

---

## 1. Executive Summary

| Decision | Verdict |
|----------|---------|
| Web hosted beta | **GO** (with gaps) |
| Mobile hosted beta | **NOT CERTIFIED (NOT EXECUTED)** |
| Public production | **NO GO** |

Playwright real-stack E2E on production preview: **11 passed, 1 skipped**. All verification gates PASS. Only E2E harness fixes; no product code changes.

---

## 2. Environment

Docker 29.5.3, Compose v5.1.4, Node v24.11.1, pnpm 9.15.0, Java 17.0.12. Gateway UP at `:8080`. Web: vite build + preview on `http://localhost:5173` (CORS must match gateway dev origins).

See `.codex/f2-frontend-runtime/environment.txt`.

---

## 3. Web Runtime Matrix

| Area | Status |
|------|--------|
| Auth login/logout/refresh/reload | PASS (Playwright) |
| Register pending | PASS |
| Upload READY + spot create | PASS |
| Map search + spot detail | PASS |
| RBAC USER/MOD/ADMIN | PASS |
| Notifications + SR deeplink | PASS (browser) |
| Profile edit/password/prefs/vehicle | NOT EXECUTED |
| Verify/claim/report | NOT EXECUTED |
| Offline/slow/double-submit | NOT EXECUTED |
| CSP/open-redirect pen test | NOT EXECUTED |

**Status:** PARTIALLY CERTIFIED

---

## 4. Mobile Runtime Matrix

All scenarios **NOT EXECUTED** (no adb device).

**Status:** NOT CERTIFIED

---

## 5–11. Runtime areas

Authentication, upload, notifications, Smart Return, security, accessibility, and performance runtime areas are **PARTIALLY CERTIFIED** or **NOT EXECUTED** as detailed in FFINAL. See [`FINAL-FRONTEND-CERTIFICATION.md`](FINAL-FRONTEND-CERTIFICATION.md).

---

## 12. Bugs Found

E2E harness only (F2-E2E-1.5, F2-OPS-1 CORS origin). No P0/P1 product defects.

---

## 13. Bugs Fixed

`frontend/apps/web/e2e-real/real-stack.real.spec.ts` — selectors, unique OLG, map flow, analytics heading.

---

## 14–16. Files, tests, evidence

E2E spec + optional fixtures + this report. No product code. Evidence under `.codex/f2-frontend-runtime/`.

---

## 17. Verification Gates

All PASS at F2 sprint: gradle clean build, integrationTest, pnpm install/typecheck/lint/test/build, mobile doctor 21/21, e2e-real 11/11.

---

## 18–19. Gaps and decision

Mobile device runtime; web profile/password/offline; push; full a11y/security/performance runtime.

| Layer | Status |
|-------|--------|
| Web runtime | PARTIALLY CERTIFIED |
| Mobile runtime | NOT CERTIFIED |
| Verification gates | FULLY CERTIFIED |

Superseded for final decision by [`FINAL-FRONTEND-CERTIFICATION.md`](FINAL-FRONTEND-CERTIFICATION.md) and [`FINAL-PRODUCTION-CERTIFICATION.md`](FINAL-PRODUCTION-CERTIFICATION.md).