# Known Issues — v1.0.0-rc1

Verified findings only. No speculative defects.

---

## Hosted Beta

| ID | Issue | Impact | Workaround / action |
|----|-------|--------|---------------------|
| HB-01 | **Mobile device runtime smoke not executed** on FFINAL (emulator present; dev client session not recorded) | Mobile beta widened without fresh device proof | Run dev-client login + upload smoke on physical device before broad mobile beta ([`docs/beta/mobile-release.md`](../beta/mobile-release.md)) |
| HB-02 | **Single-node data plane** in default Compose overlay (Postgres, Kafka, MinIO, Redis) | No HA; node loss = outage | Accept for closed beta; monitor disk; follow backup runbook |
| HB-03 | **`infra/` IaC is placeholder** | No Terraform/K8s automation in repo | Use `docker/` + operator scripts for beta |
| HB-04 | **Gradle/Docker dev version labels** (`0.0.1-SNAPSHOT` default) | Cosmetic in dev; release uses tag override | Set `PARKIO_IMAGE_VERSION=v1.0.0-rc1` and `-PparkioVersion` at release build |
| HB-05 | **Web profile / Smart Return / legal pages** not re-run on FFINAL real-stack smoke | Gaps in runtime proof, not proven bugs | Spot-check manually during beta; file issues if found |
| HB-06 | **Expo Go limitations** for push notifications | Push may not work in Expo Go | Use dev client or release APK for push testing |

---

## Public Production

| ID | Issue | Impact | Status |
|----|-------|--------|--------|
| PP-01 | **No managed HA Postgres with PITR** | Data loss risk | **NO GO** — see production-readiness plan |
| PP-02 | **No managed Kafka RF≥3** | Broker loss = event loss risk | **NO GO** |
| PP-03 | **No secrets manager + rotation** | Operational secret risk | **NO GO** |
| PP-04 | **No CD with rollback + approval** | Unsafe deploy velocity | **NO GO** |
| PP-05 | **No on-call / alerting production runbook** | Incidents undetected | Partial — Alertmanager wired; on-call process operator-defined |
| PP-06 | **No load / security pen test at production scale** | Unknown capacity / vuln surface | **NO GO** |

Source: [`docs/architecture/production-readiness.md`](../architecture/production-readiness.md), [`FINAL-PRODUCTION-CERTIFICATION.md`](../certification/FINAL-PRODUCTION-CERTIFICATION.md).

---

## Operator

| ID | Issue | Impact | Mitigation |
|----|-------|--------|------------|
| OP-01 | **No LICENSE file in repository** | Blocks public open-source distribution; legal ambiguity for third parties | Select and add LICENSE before public GitHub release |
| OP-02 | **Preflight must pass** before every deploy | Misconfiguration causes startup failure or insecure deploy | `scripts/preflight-hosted-beta.sh` |
| OP-03 | **JWT key + gateway secret required** | Services refuse start without them | Generate per environment; never reuse dev keys |
| OP-04 | **CORS empty = deny** | SPA login fails if origins not set | Set `PARKIO_CORS_ALLOWED_ORIGINS` |
| OP-05 | **Media public endpoint must match browser URL** | 403 on image display | Align `PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT` |
| OP-06 | **Backup restore not automated in CI** | Untested restore = data risk | Execute [`backup-runbook.md`](../operations/backup-runbook.md) drill |

---

## Open source publication

| ID | Issue | Impact | Mitigation |
|----|-------|--------|------------|
| OSS-01 | **No `LICENSE`** | Blocks OSS distribution | Maintainer must select and add license |
| OSS-02 | **No `NOTICE`** | Attribution unclear for third parties | Add if required by chosen license |
| OSS-03 | **No `CODEOWNERS`** | Review routing undefined | Add for public collaboration |
| OSS-04 | **No `CODE_OF_CONDUCT.md`** | Community norms undefined | Add before public contribution intake |

**Open source publication remains NO GO** until OSS-01 is resolved.

---

## Out of Scope (RC1)

- Kubernetes / Helm charts (deferred)
- Spring Boot 4 / MinIO 9 / springdoc 3 migration
- Role-management self-service API
- Multi-region deployment
- F3.4 upload race — closed **non-actionable** (no proven device defect)

---

## Accepted Risks

| ID | Risk | Rationale |
|----|------|-----------|
| AR-01 | **Notification fan-out TODO** in `NotificationApplicationService` | Documented backlog; does not block beta inbox/push paths |
| AR-02 | **Partial frontend runtime matrix** | Strong unit + web smoke coverage; gaps documented in FFINAL |
| AR-03 | **Dependabot major ignores** (MinIO 9, springdoc 3) | Requires deliberate migration; patch/minor still flow |
| AR-04 | **Draft GitHub Release only** | Human approval before image publish (`PUBLISH_IMAGES` gate) |