# Release Readiness Checklist

Evidence model for a release candidate. Store completed checklists in version control
or release artifacts (not a new database table).

## Release Record

| Field | Example / source |
|-------|------------------|
| Git commit SHA | `git rev-parse HEAD` |
| Branch / tag | |
| Build identity | CI run URL / Gradle build scan |
| Artifact identity | Docker image digest (if built) |
| Java runtime | 21 (from Gradle toolchain) |
| Migration range | e.g. V1–V26 (`services/parking-service/src/main/resources/db/migration/`) |
| Configuration version | Env overlay / hosted-beta manifest |
| Feature-flag state | [kill-switch-catalogue.md](kill-switch-catalogue.md) |
| Policy versions | e.g. `decision-shadow-v1`, `calibration-policy-v1` |
| Test evidence | CI green: backend-ci, integration, observability-validation |
| Integration evidence | Testcontainers IT list |
| Security evidence | security-ci, supply-chain workflows |
| Migration evidence | Fresh + upgrade IT green |
| Observability evidence | promtool + dashboard validation |
| Rollback compatibility | Document backward-compatible schema range |
| Known risks | |
| Approvers | |
| Status | See statuses below |

## Statuses

`DRAFT` → `EVIDENCE_INCOMPLETE` → `READY_FOR_STAGING` → `STAGING_VERIFIED` →
`READY_FOR_PRODUCTION_REVIEW` → `APPROVED` / `REJECTED` → `DEPLOYED` / `ROLLED_BACK`

## Mandatory Checks (WP-06.1)

- [ ] No authority expansion: `canary-percentage=0`, `authority.enabled=false`
- [ ] Shadow schedulers per policy (reward/fraud/calibration default off)
- [ ] `./gradlew :services:parking-service:test` green
- [ ] Observability validation workflow green
- [ ] Docker compose config validates
- [ ] Migration monotonicity verified
- [ ] PRR completed ([template](production-readiness-review-template.md))
- [ ] Rollback steps documented ([rollback-runbook.md](rollback-runbook.md))
- [ ] Error budget status recorded (or BASELINING)

## Blockers (automatic REJECTED)

- Applied Flyway migration edited in place
- Secrets committed
- Unit tests only claimed as production readiness
- Missing rollback owner for authority-affecting change

## CI Evidence

| Workflow | Path |
|----------|------|
| Unit tests | `.github/workflows/backend-ci.yml` |
| Integration | `.github/workflows/backend-integration.yml` |
| Observability | `.github/workflows/observability-validation.yml` |
| Runtime | `.github/workflows/runtime-validation.yml` |
| Backup drill | `.github/workflows/backup-restore-drill.yml` |

<!-- WP-06.2B.1 -->
WP-06.2B restored-stack verification is TECHNICALLY_VERIFIED_SIGNOFF_REQUIRED on LOCAL_REPRESENTATIVE evidence (wp062b-20260728211226). Human sign-off remains NOT_REVIEWED; WP-06.3 is NOT_ELIGIBLE; production readiness is not claimed. See docs/operations/wp-06-02b-1-evidence-finalization-signoff-preparation.md.

<!-- WP-06.2B.2 -->
WP-06.2B final-state LOCAL_REPRESENTATIVE evidence: `wp062b2-20260729073440` (`APPLICATION_VERIFICATION_SUCCEEDED`, `SIGNOFF_REQUIRED`, `signOffDecision=NOT_REVIEWED`). Shared staging INFRA_INPUT_REQUIRED. WP-06.3 NOT_ELIGIBLE. Production readiness not claimed. See docs/operations/wp-06-02b-2-final-state-reexecution-signoff-gate.md.
