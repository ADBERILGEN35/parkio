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

## 5. Authentication Runtime

FULLY CERTIFIED for exercised paths (login, logout, logout-all, HttpOnly refresh, no localStorage tokens).

---

## 6. Upload Runtime

PARTIALLY CERTIFIED (real upload, ClamAV READY, spot create; INVALID_IMAGE and PUBLIC_MEDIA_URL enforcement correct).

---

## 7. Notification Runtime

PARTIALLY CERTIFIED (inbox, filters, SR deeplink, no coordinates in text).

---

## 8. Smart Return Runtime

PARTIALLY CERTIFIED (notification deeplink only).

---

## 9. Security Runtime

PARTIALLY CERTIFIED (token storage, RBAC, upload fail-closed). CSP/open redirect NOT EXECUTED.

---

## 10. Accessibility Runtime

PARTIALLY CERTIFIED (labels/headings in E2E). Full keyboard/screen reader NOT EXECUTED.

---

## 11. Performance Runtime

PARTIALLY CERTIFIED (build PASS; map library >823KB chunk warning). Timed metrics NOT EXECUTED.

---

## 12. Bugs Found

E2E harness only (F2-E2E-1.5, F2-OPS-1 CORS origin). No P0/P1 product defects.

---

## 13. Bugs Fixed

`frontend/apps/web/e2e-real/real-stack.real.spec.ts` — selectors, unique OLG, map flow, analytics heading.

---

## 14. Files Changed

E2E spec + optional fixtures + this report. No product code.

---

## 15. Tests Added

None (E2E harness corrections only).

---

## 16. Runtime Evidence

`.codex/f2-frontend-runtime/playwright-real-e2e-final.log`, `runtime-evidence.txt`, `environment.txt`, gateway logs.

---

## 17. Verification Gates

All PASS: gradle clean build, integrationTest, pnpm install/typecheck/lint/test/build, mobile doctor 21/21, e2e-real 11/11.

---

## 18. Remaining Gaps

Mobile device runtime; web profile/password/offline; push; full a11y/security/performance runtime.

---

## 19. Certification Decision

| Layer | Status |
|-------|--------|
| Web runtime | PARTIALLY CERTIFIED |
| Mobile runtime | NOT CERTIFIED |
| Verification gates | FULLY CERTIFIED |

Superseded for final decision by [`FINAL-FRONTEND-CERTIFICATION.md`](FINAL-FRONTEND-CERTIFICATION.md).