# Parkio Operations Documentation

Operational contracts, runbooks, and governance evidence for the Parkio platform.
This directory is the source of truth for **how production readiness is proven** —
not asserted.

## WP-06 Operational Platform

| Document | Purpose |
|----------|---------|
| [WP-06.1 Operational Readiness & Production Governance](wp-06-01-operational-readiness-production-governance.md) | Master hub: audit matrix, lifecycle, blockers |
| [WP-06.2 Staging Verification & Runtime Baseline](wp-06-02-staging-verification-backup-restore-runtime-baseline.md) | Staging evidence pipeline, backup/restore automation |
| [Staging Environment Model](staging-environment-model.md) | CI_EPHEMERAL / STAGING_LOCAL safety categories |
| [Staging Verification Runbook](staging-verification-runbook.md) | Isolated verification procedure |
| [Gateway Timeout Governance](gateway-timeout-governance.md) | Downstream timeout audit |
| [Service Criticality](service-criticality.md) | Tier model and operational consequences |
| [Critical User Journeys](critical-user-journeys.md) | End-to-end journey maps with dependencies |
| [SLO/SLI Catalogue](slo-sli-catalogue.md) | SLI definitions; targets marked PROPOSED/BASELINING/APPROVED |
| [Error Budget Policy](error-budget-policy.md) | Advisory budget semantics tied to approved SLOs |
| [Production Readiness Review Template](production-readiness-review-template.md) | Formal PRR checklist |
| [Release Readiness Checklist](release-readiness-checklist.md) | RC evidence model |
| [Progressive Delivery](progressive-delivery.md) | Staged rollout model (no auto-canary) |
| [Kill Switch Catalogue](kill-switch-catalogue.md) | Exact configuration keys and defaults |
| [Municipal registry review runbook](municipal-registry-review-runbook.md) | DATA-WP-04 conservative link review (dark by default) |
| [Hosted-beta disk cleanup](hosted-beta-disk-cleanup.md) | Safe reclaim + 12 GiB deploy disk gate |
| [Rollback Runbook](rollback-runbook.md) | Code, config, policy, database rollback |
| [Incident Management](incident-management.md) | Severity, roles, escalation |
| [Backup & Restore](backup-restore.md) | Data durability status (not volume = backup) |
| [Disaster Recovery](disaster-recovery.md) | DR scenarios and sequencing |
| [Database Migration Policy](database-migration-policy.md) | Flyway safety and expand/contract |
| [Capacity & Load Plan](capacity-and-load-plan.md) | Baselines and load profiles |
| [Security Operational Readiness](security-operational-readiness.md) | Ops security controls audit |

## Incident Runbooks

Runbooks live under [`runbooks/`](runbooks/). Each includes symptom, impact, safety
action, diagnostics, recovery, and escalation.

## Pre-WP-06 Operations (retained)

| Document | Domain |
|----------|--------|
| [Reliability Guide](reliability-guide.md) | Dependency matrix, failure modes, retries |
| [Backup Runbook](backup-runbook.md) | Hosted-beta backup scripts and schedule |
| [Restore Runbook](restore-runbook.md) | Restore procedures |
| [Disaster Recovery Runbook](disaster-recovery-runbook.md) | DR drill procedures |
| [Alert Response Runbook](alert-response-runbook.md) | Alertmanager routing |
| [Security Boundaries](security-boundaries.md) | Trust boundaries |
| [Runtime Validation](runtime-validation.md) | Compose stack verification |
| [Performance Capacity](performance-capacity.md) | Sizing guidance |
| [Outcome Validation Runbook](outcome-validation-runbook.md) | WP-05.10 outcome job |
| [DLQ Redrive Runbook](dlq-redrive-runbook.md) | Kafka DLT recovery |
| [Municipal Parking Source Runbook](municipal-parking-source-runbook.md) | DATA-WP-01 İZUM sync / stale / rollback |

## Related Architecture

- [Architecture README](../architecture/README.md)
- [Observability Metrics](../architecture/observability-metrics.md)
- [WP-05 Implementation Plan](../architecture/wp-05-implementation-plan.md)

## Important

WP-06.2 adds **repeatable staging verification evidence** (restore drills, semantic
checks, evidence bundles). WP-06.1 governance remains valid. Neither package expands
Decision authority, enables WP-05 schedulers by default, or claims production launch.

- [WP-06.2B shared staging / restored-stack verification](wp-06-02b-shared-staging-signoff-restored-database-verification.md)
- [WP-06.2B.1 evidence finalization & sign-off preparation](wp-06-02b-1-evidence-finalization-signoff-preparation.md)

- [WP-06.2B.2 final-state re-execution & sign-off gate](wp-06-02b-2-final-state-reexecution-signoff-gate.md)

- Municipal/OSM sources: [municipal-parking-source-runbook.md](municipal-parking-source-runbook.md)
- İZELMAN inventory/tariff safety: [DATA-WP-03 architecture](../architecture/wp-data-03-izelman-inventory-tariffs.md)
