# RC1 Pre-Tag Checklist — v1.0.0-rc1

Complete before creating git tag `v1.0.0-rc1` and pushing to origin.

---

## 1. Repository hygiene

- [ ] Working tree clean (no uncommitted release artifacts)
- [ ] `docs/releases/*` present and reviewed
- [ ] `CHANGELOG.md` updated for `1.0.0-rc1`
- [ ] `docs/certification/FINAL-FRONTEND-CERTIFICATION.md` committed
- [ ] No real secrets in tree (`gitleaks` / manual grep)
- [ ] Preflight fixtures use non-secret placeholder URLs only
- [ ] **LICENSE** — maintainer decision recorded (required for public OSS)

---

## 2. Build & tests

- [ ] `./gradlew build` (or `gradlew.bat build`) — **PASS**
- [ ] `./gradlew integrationTest -Pparkio.integrationTest.requireDocker=true` — **PASS** (Docker required)
- [ ] `cd frontend && pnpm install --frozen-lockfile` — **PASS**
- [ ] `pnpm -r typecheck` — **PASS**
- [ ] `pnpm -r lint` — **PASS**
- [ ] `pnpm -r test` — **PASS**
- [ ] `pnpm --filter @parkio/web build` — **PASS**
- [ ] Mobile: `pnpm --filter @parkio/mobile test` — **PASS**
- [ ] Mobile: `npx expo-doctor` — **PASS** (21/21)
- [ ] `bash scripts/test-preflight-hosted-beta.sh` — **PASS**

---

## 3. Docker & Compose

- [ ] `docker compose -f docker/docker-compose.yml config` validates
- [ ] Hosted-beta overlay config validates with example env
- [ ] Service Dockerfiles build (or rely on release workflow)
- [ ] Health endpoints respond after `docker compose up` (smoke)

---

## 4. Smoke (operator / maintainer)

- [ ] Gateway `GET /actuator/health` — UP
- [ ] Web SPA loads (preview or nginx image)
- [ ] Login → refresh cookie → authenticated API call
- [ ] USER denied `/moderation`; MOD sees queue; ADMIN sees analytics
- [ ] Upload → READY → spot create (web real-stack or manual)
- [ ] Optional: mobile dev-client login on device

---

## 5. Secrets & configuration

- [ ] Production `.env` has no `CHANGE_ME` (preflight enforces)
- [ ] `PARKIO_JWT_PRIVATE_KEY_PEM` set (unique per env)
- [ ] `PARKIO_GATEWAY_INTERNAL_SECRET` set (unique per env)
- [ ] `PARKIO_CORS_ALLOWED_ORIGINS` matches SPA URL
- [ ] Database passwords not default in production
- [ ] Resend / email API key configured (if email enabled)
- [ ] `PARKIO_EXPO_ACCESS_TOKEN` or push credentials (if push enabled)

---

## 6. TLS, DNS, ingress

- [ ] TLS certificate valid for public hostname (Caddy or reverse proxy)
- [ ] DNS A/AAAA records point to VPS
- [ ] Gateway is only public ingress; internal services not exposed
- [ ] HSTS considered for production-like beta

---

## 7. Observability & alerts

- [ ] Prometheus scraping targets UP
- [ ] Grafana dashboards load
- [ ] Loki receiving logs (if enabled)
- [ ] Alertmanager configured
- [ ] Slack or `PARKIO_ALERT_WEBHOOK_URL` tested (test alert fires)

---

## 8. Backups & recovery

- [ ] Backup script/cron documented and running
- [ ] Restore drill executed per [`backup-runbook.md`](../operations/backup-runbook.md)
- [ ] Disaster recovery contacts documented

---

## 9. Rollback plan

- [ ] Previous image tag or commit SHA recorded
- [ ] Database backup taken before deploy
- [ ] Rollback procedure: redeploy prior `PARKIO_IMAGE_VERSION` + compose down/up
- [ ] Kafka/DB rollback limitations understood (forward-only Flyway)

---

## 10. Tagging & GitHub Release

- [ ] Version decision: `v1.0.0-rc1`
- [ ] Tag message references [`RC1-RELEASE-NOTES.md`](RC1-RELEASE-NOTES.md)
- [ ] `git tag -a v1.0.0-rc1 -m "Parkio 1.0.0-rc1 — hosted beta release candidate"`
- [ ] `git push origin v1.0.0-rc1`
- [ ] Release workflow completes (build, tests, SBOM, draft release)
- [ ] Human reviews draft release before publish
- [ ] Set `vars.PUBLISH_IMAGES=true` only when ready to push GHCR images

---

## 11. Post-tag communication

- [ ] Operators receive [`RC1-RELEASE-NOTES.md`](RC1-RELEASE-NOTES.md) + [`KNOWN-ISSUES.md`](KNOWN-ISSUES.md)
- [ ] Beta users informed of mobile device smoke requirement
- [ ] Public production remains **NO GO** until PP-* blockers close

---

## Sign-off

| Role | Name | Date | OK |
|------|------|------|-----|
| Maintainer | | | |
| Operator | | | |