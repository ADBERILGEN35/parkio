# Production Readiness Review (PRR) Template

Complete before production deployment or authority-affecting change. Mark each item:
**VERIFIED** | **DOCUMENTED** | **ASSUMED** | **BLOCKED** | **N/A**

## 1. Scope

- [ ] Services / components in scope listed
- [ ] Explicit out-of-scope (no accidental authority expansion)
- [ ] Git SHA and migration range recorded

## 2. Architecture & Ownership

- [ ] Service owner identified per [service-criticality.md](service-criticality.md)
- [ ] Dependencies diagram current
- [ ] WP-05 engines classified TIER_4

## 3. Critical Journeys

- [ ] Journeys from [critical-user-journeys.md](critical-user-journeys.md) covered
- [ ] Success criteria defined per journey
- [ ] Degraded behavior documented

## 4. Capacity

- [ ] Pool sizes / batch sizes documented ([capacity-and-load-plan.md](capacity-and-load-plan.md))
- [ ] Unknown traffic marked PRODUCT INPUT REQUIRED

## 5. SLOs & Observability

- [ ] SLIs with numerator/denominator ([slo-sli-catalogue.md](slo-sli-catalogue.md))
- [ ] No PROPOSED SLO labeled APPROVED
- [ ] Dashboards linked (Grafana provisioning paths)
- [ ] Alerts have owners and runbooks

## 6. Failure Modes

- [ ] Dependency matrix reviewed ([reliability-guide.md](reliability-guide.md))
- [ ] Timeouts explicit on critical paths
- [ ] Retry bounded; no unsafe mutation retry without idempotency

## 7. Migrations

- [ ] Expand/contract plan ([database-migration-policy.md](database-migration-policy.md))
- [ ] Rollback compatibility: code vs schema
- [ ] Fresh + upgrade tests green

## 8. Backup / Restore / DR

- [ ] Backup method not conflated with Docker volume
- [ ] Restore last verified date or UNVERIFIED
- [ ] RPO/RTO status honest (PROPOSED/BASELINING/APPROVED)

## 9. Security & Privacy

- [ ] [security-operational-readiness.md](security-operational-readiness.md) reviewed
- [ ] No secrets in config samples
- [ ] PII not in metric labels

## 10. Incident & Rollback

- [ ] [incident-management.md](incident-management.md) roles assigned
- [ ] [rollback-runbook.md](rollback-runbook.md) steps tested or dry-run
- [ ] Kill switches verified ([kill-switch-catalogue.md](kill-switch-catalogue.md))

## 11. Feature Flags & Authority

- [ ] Decision authority off / 0% canary
- [ ] Reward settlement not activated
- [ ] Exposure reranking not activated
- [ ] Fraud enforcement not activated
- [ ] Calibration advisory only

## 12. Test Evidence

- [ ] Unit + integration CI green
- [ ] Observability validation green
- [ ] **Explicit:** unit tests alone ≠ production ready

## 13. Unresolved Risks

| Risk | Severity | Owner | Mitigation |
|------|----------|-------|------------|
| | | | |

## 14. Go / No-Go

| Decision | Name | Date |
|----------|------|------|
| GO / NO-GO | | |

Conditions for GO: all BLOCKED items resolved or accepted with expiry; no open SEV-1+.

<!-- WP-06.2B.1 -->
WP-06.2B restored-stack verification is TECHNICALLY_VERIFIED_SIGNOFF_REQUIRED on LOCAL_REPRESENTATIVE evidence (wp062b-20260728211226). Human sign-off remains NOT_REVIEWED; WP-06.3 is NOT_ELIGIBLE; production readiness is not claimed. See docs/operations/wp-06-02b-1-evidence-finalization-signoff-preparation.md.

<!-- WP-06.2B.2 -->
WP-06.2B final-state restored-stack verification is TECHNICALLY_VERIFIED_SIGNOFF_REQUIRED on LOCAL_REPRESENTATIVE evidence (`wp062b2-20260729073440`). Human sign-off remains NOT_REVIEWED; WP-06.3 is NOT_ELIGIBLE; production readiness is not claimed. See docs/operations/wp-06-02b-2-final-state-reexecution-signoff-gate.md.
