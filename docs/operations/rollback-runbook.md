# Rollback Runbook

Application rollback is not equivalent to database rollback. See kill-switch-catalogue.md for immediate authority disable.

## Application rollback
Since U13 the production list (`docker/compose.production.files`) pins every app service, and committed migrations are forward-fix only. Recover from a bad release with a forward fix (a deploy of corrected pins); verify readiness; run smoke scripts. A deploy of a previous image is not an accepted rollback unless schema compatibility with the live databases is proven first, and `scripts/rollback-hosted-beta.sh` refuses while every app service is pinned (see docs/beta/rollback-runbook.md). The invite-production profile builds its app services; its gated rollback is separate (docs/operations/invite-production-rollback-runbook.md).

## Config rollback
Restore env overlay; restart services.

## Kill switches
Set PARKIO_PARKING_DECISION_AUTHORITY_ENABLED=false and CANARY=0; restart parking-service.

## Migration incident
Do not edit applied Flyway files. Forward-fix. A restore from backup is BLOCKED in production (see disaster-recovery-runbook.md); it is an option only outside production. See runbooks/flyway-migration-failure.md.