# RC1 Pre-Tag & Publication Checklist — v1.0.0-rc1

**Status as of RC1.3** (commit `4cb7ed1`, tag `v1.0.0-rc1` created locally).

---

## 1. Repository hygiene

- [x] Working tree clean (no uncommitted release artifacts)
- [x] `docs/releases/*` present and reviewed
- [x] `CHANGELOG.md` updated for `1.0.0-rc1`
- [x] `docs/certification/FINAL-FRONTEND-CERTIFICATION.md` committed
- [x] `docs/certification/FINAL-BACKEND-CERTIFICATION.md` committed (RC1.3)
- [x] `docs/certification/FINAL-PRODUCTION-CERTIFICATION.md` committed (RC1.3)
- [x] No real secrets in tree (preflight fixtures use placeholders)
- [x] Preflight fixtures use non-secret placeholder URLs only
- [ ] **LICENSE** — maintainer decision (required for public OSS)
- [ ] **NOTICE / CODEOWNERS / CODE_OF_CONDUCT** — optional; recommended for OSS

---

## 2. Build & tests (verified at `4cb7ed1`, RC1.1)

- [x] `./gradlew clean build` — **PASS**
- [x] `./gradlew integrationTest "-Pparkio.integrationTest.requireDocker=true"` — **PASS**
- [x] `cd frontend && pnpm install --frozen-lockfile` — **PASS**
- [x] `pnpm -r typecheck` — **PASS**
- [x] `pnpm -r lint` — **PASS** (0 errors)
- [x] `pnpm -r test` — **PASS** (~529 tests)
- [x] `pnpm -r build` — **PASS**
- [x] `pnpm --filter @parkio/mobile run doctor` — **PASS** (21/21)
- [x] `bash scripts/test-preflight-hosted-beta.sh` — **PASS** (36/36)

---

## 3. Docker & Compose

- [x] `docker compose -f docker/docker-compose.yml config` validates
- [ ] Hosted-beta overlay smoke on target VPS — **operator**
- [ ] Service images built from tag — **operator / release workflow**
- [ ] Health endpoints after `docker compose up` — **operator**

---

## 4. Smoke (operator)

- [ ] Gateway health UP
- [ ] Web SPA loads
- [ ] Login → refresh → authenticated API
- [ ] RBAC smoke (USER/MOD/ADMIN)
- [ ] Upload → READY → spot create
- [ ] Mobile dev-client login on device

---

## 5. Secrets & configuration

- [ ] Production `.env` has no `CHANGE_ME` (preflight enforces)
- [ ] JWT key + gateway secret unique per environment
- [ ] CORS, media public endpoint, Resend, Expo, alerts configured

---

## 6. TLS, DNS, ingress

- [ ] TLS certificate valid
- [ ] DNS records point to VPS
- [ ] Gateway-only public ingress

---

## 7. Observability & alerts

- [ ] Prometheus / Grafana UP
- [ ] Alertmanager webhook tested

---

## 8. Backups & recovery

- [ ] Backup cron running
- [ ] Restore drill executed ([`restore-runbook.md`](../operations/restore-runbook.md))

---

## 9. Rollback plan

- [ ] Prior image tag / SHA recorded
- [ ] Rollback procedure understood ([`rollback-runbook.md`](../beta/rollback-runbook.md))

---

## 10. Tagging & GitHub Release

- [x] Version: `v1.0.0-rc1`
- [x] Annotated tag created at `4cb7ed1`
- [ ] `git push origin master`
- [ ] `git push origin v1.0.0-rc1`
- [ ] Release workflow completes; review **draft** GitHub Release
- [ ] `vars.PUBLISH_IMAGES=true` only when ready for GHCR

---

## 11. Post-publication

- [ ] Operators receive release notes + known issues
- [ ] Mobile device smoke before wide mobile beta
- [ ] Public production remains **NO GO**

---

## Sign-off

| Role | Name | Date | OK |
|------|------|------|-----|
| Maintainer | | | |
| Operator | | | |
