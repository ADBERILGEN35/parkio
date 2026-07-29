# WP-05.6 Calibration Report Template

**Purpose:** Reproducible report for decision-shadow parity/drift review before threshold or authority changes.  
**Related:** [Calibration & shadow analytics](./wp-05-decision-calibration-shadow-analytics.md)

> **WP-05.6 provides parity and drift analytics, not correctness calibration.**  
> Do not fill accuracy / false-hold / precision / recall unless a verified ground-truth source is cited.

> Any numeric **sample** values below must be clearly marked **SYNTHETIC ONLY** (fixtures or invented demo data). Leave production fields blank until real observation windows exist.

---

## Report metadata

| Field | Value |
|-------|-------|
| Report ID | |
| Author | |
| Reviewers (eng / product / risk) | |
| Prepared at (UTC) | |
| Policy version under review | `decision-shadow-v1` *(or successor)* |
| Observation window start (UTC) | |
| Observation window end (UTC) | |
| Environment | local / staging / hosted-beta / other: |
| Shadow flag enabled? | yes / no |
| Dashboard / Prometheus links | |
| Data class | production \| **SYNTHETIC ONLY** |

---

## Volume

| Metric | Value | Notes |
|--------|-------|-------|
| Total shadow attempts | | `parkio_parking_decision_shadow_attempt_total` |
| Successes | | `..._success_total` |
| Failures | | `..._failure_total` |
| Failure rate (5m / window) | | `parkio:decision_shadow:failure_rate5m` |
| Failure stages (top) | | `stage=` enum |

---

## Evidence availability

| Profile | Count / rate | Notes |
|---------|--------------|-------|
| UNKNOWN | | |
| AI_ONLY | | |
| AI_PLUS_OPERATIONAL | | |
| AI_PLUS_LOCATION | | |
| COMPLETE_CURRENT_V1 | | |
| PARTIAL | | |

Runtime context limitations (e.g. null `ParkingSpotEvidenceContext`):

-

---

## Parity (not correctness)

Comparable set = EQUIVALENT + SHADOW_MORE_RESTRICTIVE + SHADOW_MORE_PERMISSIVE + LEGACY_REVIEW_SHADOW_HOLD  
(excludes NOT_COMPARABLE / NO_SAFE_EQUIVALENCE)

| Metric | Value | Prom / rule |
|--------|-------|-------------|
| Comparable sample count | | |
| Comparable agreement rate | | `parkio:decision_shadow:comparable_agreement_rate5m` |
| More restrictive rate | | `parkio:decision_shadow:more_restrictive_rate5m` |
| More permissive rate | | `parkio:decision_shadow:more_permissive_rate5m` |
| Not-comparable rate | | `parkio:decision_shadow:not_comparable_rate5m` |

Comparison category mix:

-

---

## Disposition & risk

| Dimension | Distribution |
|-----------|--------------|
| Shadow disposition | |
| Legacy kind | |
| Legacy status | |
| Risk band | |
| Hard-constraint family | |
| HOLD rate | `parkio:decision_shadow:hold_rate5m` |
| REJECTED rate | `parkio:decision_shadow:rejected_rate5m` |
| Hard-constraint activation rate | `parkio:decision_shadow:hard_constraint_rate5m` |

---

## Assessments (active categories)

| Category | Level mix | Completeness mix |
|----------|-----------|------------------|
| CONTENT | | |
| LEGALITY | | |
| LOCATION | | |
| INTEGRITY | | |

---

## Decisive rules

| Rule | Count / rate |
|------|--------------|
| | |

Top bounded decisive rules driving disagreement:

1.
2.
3.

---

## Not-comparable

| Reason / legacy kind | Count / rate |
|----------------------|--------------|
| | |

---

## Latency

| Metric | Value |
|--------|-------|
| Orchestration duration p50 | |
| Orchestration duration p95 | `histogram_quantile` on `parkio_parking_decision_shadow_duration_seconds` |
| Orchestration duration p99 | |

---

## Known data-quality limitations

-

---

## Moderator / product-reviewed disagreement findings

| Sample ref (no PII required) | Comparison category | Disposition vs legacy | Product judgment | Action |
|------------------------------|---------------------|-----------------------|------------------|--------|
| | | | | |

---

## Threshold-change proposal

| Item | Current | Proposed | Rationale |
|------|---------|----------|-----------|
| Policy version | | | |
| Threshold / weight constants | | | |
| Fixture impact | | | |
| Offline `OfflineDecisionComparison` summary | | | |

**Automatic threshold updates are forbidden.** Changes are explicit reviewed code/config with a new policy version.

---

## Approval / rollback decision

| Decision | Choose one |
|----------|------------|
| Approve threshold/policy change for merge | ☐ |
| Defer — need more samples / review | ☐ |
| Reject change | ☐ |
| Authority migration (WP-05.7) readiness | ☐ not ready / ☐ checklist in progress / ☐ ready *(requires product-approved thresholds)* |

Rollback plan if change ships:

-

Sign-off:

| Role | Name | Date | Signature |
|------|------|------|-----------|
| Engineering | | | |
| Product | | | |
| Risk / Trust (if required) | | | |

---

## Appendix — SYNTHETIC ONLY sample (optional)

Mark clearly if used. Example placeholder (do **not** treat as production):

```text
SYNTHETIC ONLY — golden fixture window
attempts=12 successes=12 failures=0
comparable_agreement_rate=1.0
```
