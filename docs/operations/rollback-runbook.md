# Rollback Runbook

Application rollback is not equivalent to database rollback. See kill-switch-catalogue.md for immediate authority disable.

## Application rollback
Deploy previous image; verify readiness; run smoke scripts.

## Config rollback
Restore env overlay; restart services.

## Kill switches
Set PARKIO_PARKING_DECISION_AUTHORITY_ENABLED=false and CANARY=0; restart parking-service.

## Migration incident
Do not edit applied Flyway files. Forward-fix or restore from backup. See runbooks/flyway-migration-failure.md.