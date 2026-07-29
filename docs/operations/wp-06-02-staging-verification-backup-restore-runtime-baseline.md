# WP-06.2 — Staging Verification, Backup/Restore Automation & Runtime Baseline

**Status:** Current (WP-06.2). Does not claim production-ready or approved SLO/RPO/RTO.

## Executive summary

WP-06.2 adds repeatable, fail-closed staging verification on WP-06.1 governance: safety
guards (`scripts/staging/lib/safety-guards.sh`), evidence bundles
(`build/operational-evidence/`), CI workflows, and gateway global downstream timeouts.

## Capability matrix (abbreviated)

| Capability | Location | Status |
|------------|----------|--------|
| Restore drill | `scripts/restore-drill.sh` | CI_VERIFIED |
| Staging pipeline | `scripts/staging/run-verification-pipeline.sh` | WP-06.2 |
| Safety guards | `scripts/staging/lib/safety-guards.sh` | WP-06.2 |
| Evidence schema | `docs/operations/evidence/operational-evidence-schema.json` | WP-06.2 |
| Gateway timeouts | `spring.cloud.gateway.httpclient.*` | WP-06.2 |

See linked runbooks for procedures. Full section index maintained in repository commits.

## Production blockers (unchanged)

Offsite backup approval, production RPO/RTO, full-stack CI journeys, MinIO isolated restore,
secrets manager, per-route gateway baselining.

## Next package

WP-06.3 Deployment Automation (when supported platform exists).