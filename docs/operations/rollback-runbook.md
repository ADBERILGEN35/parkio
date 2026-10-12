# Rollback Runbook

Application rollback is not equivalent to database rollback. See kill-switch-catalogue.md for immediate authority disable.

## Application rollback
Since U13 every app service is digest-pinned, and committed migrations are forward-fix only. Recover from a bad release with a forward fix (a deploy of corrected pins); verify readiness; run smoke scripts. A deploy of a previous image is not an accepted rollback unless schema compatibility with the live databases is proven first, and `scripts/rollback-hosted-beta.sh` refuses while every app service is pinned (see docs/beta/rollback-runbook.md).

## Config rollback
Restore env overlay; restart services.

## Kill switches
Set PARKIO_PARKING_DECISION_AUTHORITY_ENABLED=false and CANARY=0; restart parking-service.

## Migration incident
Do not edit applied Flyway files. Forward-fix or restore from backup. See runbooks/flyway-migration-failure.md.