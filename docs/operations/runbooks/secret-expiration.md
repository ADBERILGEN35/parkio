#  secret expiration.Groups[1].Value + secret expiration.Groups[2].Value.ToUpper() ecret secret expiration.Groups[1].Value + secret expiration.Groups[2].Value.ToUpper() xpiration

| Field | Detail |
|-------|--------|
| Severity | SEV-1 |
| Owner | Platform |
| Alert | See docker/prometheus/alerts.yml or operational-readiness-alert-rules.yml |

## Symptom

401 across services

## Impact

Total API failure

## Immediate safety action

Rotate secrets per security runbook

## Diagnostics

- Grafana: parkio-operational-readiness, parkio-api-health
- Prometheus: http://localhost:9090
- Logs: Loki via Grafana
- Compose: docker compose ps (from docker/)

## Containment

Rolling restart

## Rollback

See ../rollback-runbook.md and ../kill-switch-catalogue.md.

## Recovery verification

- /actuator/health/readiness green for affected services
- Error rate normalized 15m
- Run smoke: scripts/smoke-hosted-beta.sh (hosted-beta)

## Escalation

SEV-1: incident commander within 15m per ../incident-management.md

## Data reconciliation

Document any backlog replay (outbox, Kafka DLT) per ../dlq-redrive-runbook.md.

## Follow-up

Post-incident review if SEV-2+.