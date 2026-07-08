# RC2 — Hosted Beta Deployment Report

**Date:** 2026-07-07  
**Sprint:** RC2 — Hosted Beta Deployment  
**Release tag:** `v1.0.0-rc1` (`57b761664424d81fd0315a13176144df0ad1f4ce`)  
**Auditor environment:** Windows developer workstation (not the production VPS)  
**Method:** Repository script validation + DNS probe + local env inspection. **No fabricated VPS runtime evidence.**

---

## 1. Executive verdict

| Question | Verdict |
|----------|---------|
| **Hosted beta deployed on VPS** | **NO** — not executed |
| **Hosted beta GO** | **NO GO** — deployment prerequisites incomplete |
| **Ready to retry** | **YES** — after operator completes §8 blockers |

---

## 2. Deploy status

| Phase | Status | Evidence |
|-------|--------|----------|
| VPS prerequisites audit | **NOT VERIFIED** | No SSH access to operator VPS from this environment |
| `docker/.env` hosted-beta preparation | **NOT DONE** | Local `docker/.env` has `PARKIO_ENVIRONMENT=local` (dev), not hosted-beta domains/secrets |
| Preflight (`preflight-hosted-beta.sh`) | **PASS (fixture only)** | `PARKIO_ENV_FILE=scripts/preflight-fixtures/valid.env` → 48/48 checks at tag `v1.0.0-rc1` |
| Deploy (`deploy-hosted-beta.sh`) | **NOT EXECUTED** | Requires VPS + real `.env`; local dry-run aborted (`jq` missing in Git Bash) |
| HTTPS / application smoke | **NOT EXECUTED** | No live stack |
| Operations smoke | **NOT EXECUTED** | No live stack |

---

## 3. VPS prerequisites (§1 — NOT VERIFIED on target host)

These checks must be run **on the VPS** by the operator:

```bash
# Ubuntu
lsb_release -a

# Docker / Compose
docker version
docker compose version   # require v2.24.4+

# Resources
free -h
df -h /

# Firewall (only 22, 80, 443 public)
sudo ufw status verbose

# Time sync
timedatectl status

# DNS (from laptop or VPS)
dig +short A api.<your-domain>
dig +short A app.<your-domain>
dig +short A media.<your-domain>
```

**DNS probe from auditor workstation (2026-07-07):**

| Host | Result |
|------|--------|
| `api.beta.parkio.dev` | **NXDOMAIN** |
| `app.beta.parkio.dev` | **NXDOMAIN** |

Fixture domains in `scripts/preflight-fixtures/valid.env` are **not live DNS** — operator must use real FQDNs in `docker/.env`.

---

## 4. Environment preparation (§2 — operator required)

On the VPS:

```bash
git clone https://github.com/ADBERILGEN35/parkio.git /opt/parkio
cd /opt/parkio
git checkout v1.0.0-rc1
cd docker
cp .env.hosted-beta.example .env
chmod 600 .env
```

Replace **every** placeholder. Minimum set (see [`HOSTED-BETA-RUNBOOK.md`](../../HOSTED-BETA-RUNBOOK.md)):

| Variable | Notes |
|----------|--------|
| `PARKIO_DOMAIN` | API FQDN (no scheme) |
| `PARKIO_WEB_DOMAIN` | SPA FQDN |
| `PARKIO_MEDIA_DOMAIN` | Media FQDN |
| `PARKIO_CORS_ALLOWED_ORIGINS` | `https://<web-domain>` |
| `VITE_API_BASE_URL` | `https://<api-domain>/api/v1` |
| `PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT` | `https://<media-domain>` |
| `PARKIO_JWT_PRIVATE_KEY_PEM` | `openssl genpkey ...` |
| `PARKIO_GATEWAY_INTERNAL_SECRET` | `openssl rand -base64 48` |
| `POSTGRES_*_PASSWORD` (×9) | Unique ≥16 chars each |
| `REDIS_PASSWORD` | ≥16 chars |
| `MINIO_ROOT_PASSWORD` | ≥16 chars |
| `KAFKA_CLUSTER_ID` | 22-char production id |
| `PARKIO_RESEND_API_KEY` | `re_...` |
| `PARKIO_EXPO_ACCESS_TOKEN` | expo.dev token |
| `PARKIO_ALERT_WEBHOOK_URL` or Slack webhook | Alertmanager target |
| `PARKIO_ENVIRONMENT` | `hosted-beta` |
| `PARKIO_EMAIL_PROVIDER` | `resend` |
| `PARKIO_PUSH_DELIVERY_PROVIDER` | `expo` |
| `PARKIO_OPENAPI_ENABLED` | `false` |

**Local workstation finding:** `docker/.env` on the dev machine is **not** a hosted-beta file and must **not** be copied to the VPS.

---

## 5. Preflight (§3)

**Executed (fixture validation at tag):**

```bash
PARKIO_ENV_FILE=scripts/preflight-fixtures/valid.env ./scripts/preflight-hosted-beta.sh
# === PREFLIGHT: PASS — 48 check(s) passed, 0 warning(s) ===
```

**Production command (on VPS, after real `.env`):**

```bash
cd /opt/parkio
PARKIO_ENV_FILE=docker/.env ./scripts/preflight-hosted-beta.sh
```

Must exit **0** before deploy.

---

## 6. Deploy (§4 — NOT EXECUTED on VPS)

**Intended command:**

```bash
cd /opt/parkio
PARKIO_IMAGE_VERSION=v1.0.0-rc1 \
PARKIO_ENV_FILE=docker/.env \
./scripts/deploy-hosted-beta.sh
```

**Local dry-run attempt:** Rendered compose config; failed at manifest write because `jq` was not installed in Git Bash on Windows (`exit 127`). **VPS prerequisite:** install `jq` (`apt install jq`).

When `HEAD` is exactly `v1.0.0-rc1`, `deploy-hosted-beta.sh` auto-sets `PARKIO_IMAGE_VERSION` from `git describe` — explicit env var is optional but recommended for clarity.

---

## 7. Application smoke (§5 — NOT EXECUTED)

Requires live HTTPS endpoints. After deploy, run on VPS or via tunnel:

```bash
PARKIO_GATEWAY_URL=https://<api-domain> \
PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED=1 \
PARKIO_ENV_FILE=docker/.env \
./scripts/smoke-hosted-beta.sh
```

**Not verified in RC2:** login, upload, spot create, verify/claim, notifications, Smart Return, RBAC matrix on production URLs.

**Recommended:** Seed accounts first: `./scripts/seed-real-e2e.sh` (on VPS). Web real-stack Playwright (`frontend/apps/web/e2e-real`) against production URLs — **NOT EXECUTED**.

---

## 8. Operations verification (§6 — NOT EXECUTED)

| Check | Command / access | RC2 status |
|-------|------------------|------------|
| Prometheus | SSH tunnel `:9090` | NOT VERIFIED |
| Grafana | SSH tunnel `:3000` | NOT VERIFIED |
| Alertmanager test alert | Fire drill webhook | NOT VERIFIED |
| Backup | `PARKIO_ENV_FILE=docker/.env ./scripts/backup-databases.sh` | NOT VERIFIED |
| Restore drill | `./scripts/restore-drill.sh` | NOT VERIFIED (CI workflow exists) |
| Rollback dry-run | `./scripts/rollback-hosted-beta.sh --manifest deploy-artifacts/current.json --dry-run` | NOT VERIFIED (no deploy manifest) |

---

## 9. Issues found

| ID | Issue | Severity | Fix |
|----|-------|----------|-----|
| RC2-01 | **No VPS access** from auditor environment | Blocker | Operator runs deploy on VPS or provides SSH |
| RC2-02 | **DNS not provisioned** for fixture domains | Blocker | Create A/AAAA records to VPS IP |
| RC2-03 | **No hosted-beta `docker/.env`** with real secrets | Blocker | Operator prepares per §4 |
| RC2-04 | Local `docker/.env` is `PARKIO_ENVIRONMENT=local` | Info | Do not use for VPS |
| RC2-05 | `jq` missing on Windows Git Bash | Local only | Install on VPS: `apt install jq curl git` |

**Application code defects:** None identified. **No code changes** in RC2.

---

## 10. Remaining operator tasks (ordered)

1. Provision Ubuntu 22.04/24.04 VPS (8 vCPU / 24 GB RAM recommended per [`HOSTED-BETA-RUNBOOK.md`](../../HOSTED-BETA-RUNBOOK.md)).
2. Configure DNS for API, web, and media hostnames.
3. Configure UFW (22, 80, 443 only).
4. Clone repo, checkout `v1.0.0-rc1`, create `docker/.env` from `.env.hosted-beta.example`.
5. Run preflight → must PASS.
6. Run deploy with `PARKIO_IMAGE_VERSION=v1.0.0-rc1`.
7. Run `smoke-hosted-beta.sh` against HTTPS gateway.
8. Seed test users; run manual or Playwright smoke (login, upload, RBAC).
9. SSH-tunnel Grafana/Prometheus; test Alertmanager webhook.
10. Run backup + `restore-drill.sh`; record evidence.
11. Update this report with VPS outputs and set GO/NO GO.

---

## 11. Hosted beta GO / NO GO

| Criterion | Met? |
|-----------|------|
| Tag `v1.0.0-rc1` published | Yes |
| Preflight script validated | Yes (fixture) |
| VPS provisioned | **No** |
| DNS live | **No** (fixture domains NXDOMAIN) |
| Real `.env` on VPS | **No** |
| Deploy executed | **No** |
| HTTPS smoke | **No** |
| Backup drill on VPS | **No** |

### **Final: Hosted beta NO GO**

Deployment engineering is ready; **operator execution on a real VPS is the gating step.** Retry RC2 after §10 completes and append evidence to this report (or RC2.1 addendum).

---

## 12. References

- [`HOSTED-BETA-RUNBOOK.md`](../../HOSTED-BETA-RUNBOOK.md)
- [`docs/certification/FINAL-PRODUCTION-CERTIFICATION.md`](../certification/FINAL-PRODUCTION-CERTIFICATION.md)
- [`docs/releases/RC1-CHECKLIST.md`](RC1-CHECKLIST.md)
