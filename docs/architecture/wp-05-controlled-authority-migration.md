# WP-05.8 Controlled Decision Authority Migration

## 1. Executive summary

WP-05.8 introduces a **default-off**, deterministic, canary-scoped authority path for the Decision Engine inside `parking-service`. Non-selected traffic continues to use the legacy AI-status mapping in `ParkingApplicationService.applyAiValidationResult`. The initial canary may apply only `PENDING_VALIDATION × FULL_PUBLISH` through existing aggregate publication methods, with a required AUTHORITATIVE `decision_audit` row in the same database transaction.

## 2. Scope and non-goals

**In scope:** authority config, deterministic canary, eligibility, disposition compatibility, orchestrator, transactional authoritative audit, bounded metrics, PromQL/Grafana, activation/rollback runbooks.

**Non-goals:** full cutover, auto percentage increases, trust/availability/fraud engines, Kafka/SDK/API changes, moderator override authority, REJECTED/HOLD/SHADOW/LIMITED_PUBLISH/EXPIRED authority, public audit APIs.

## 3. Current legacy authority

`AiValidationEventsKafkaConsumer` → (inbox) → `DecisionAuthorityApplicationService` → when not selected → `ParkingApplicationService.applyAiValidationResult` → `ParkingSpot.applyAiValidationPassed|Uncertain|Rejected` → status history + outbox (`ParkingSpotActivated` when ACTIVE).

## 4. Controlled authority architecture

```text
AI event
  → inbox dedupe
  → DecisionAuthoritySelector (config + status + canary)
  → if not selected: legacy apply (+ optional shadow)
  → if selected:
       EvidenceCollection (+ ParkingSpotEvidenceContext)
       DecisionEngine.evaluate
       disposition compatibility gate
       appendAuthoritativeRequired(audit)
       applyAuthoritativeFullPublish (aggregate methods)
       skip shadow for this event
```

Authority selection is separate from decision calculation. `DecisionEngine` remains pure and mode-agnostic.

## 5. Configuration model

| Property | Env | Default |
|----------|-----|---------|
| `parkio.parking.decision.authority.enabled` | `PARKIO_PARKING_DECISION_AUTHORITY_ENABLED` | `false` |
| `parkio.parking.decision.authority.canary-percentage` | `PARKIO_PARKING_DECISION_AUTHORITY_CANARY_PERCENTAGE` | `0` |
| `parkio.parking.decision.authority.policy-version` | `PARKIO_PARKING_DECISION_AUTHORITY_POLICY_VERSION` | `decision-shadow-v1` |

Validated by `DecisionAuthoritySettings` at bean creation. Unsupported policy versions fail startup. Shadow config remains independent.

## 6. Deterministic cohort algorithm

**Version:** `authority-canary-v1` (`AuthorityAlgorithmVersion.V1`)

1. Material: `authority-canary-v1|{parkingSpotId}|{evaluationId}` (UTF-8)
2. SHA-256 digest
3. First 4 bytes → unsigned int → `bucket = floorMod(value, 10000)` ∈ `[0,9999]`
4. Selected iff `bucket < canaryPercentage * 100` (basis points)

No `Random`, `System.currentTimeMillis`, `Object.hashCode`, or `String.hashCode`.

## 7. Eligibility model

`DecisionAuthoritySelection` + `AuthorityEligibilityReason`:
`AUTHORITY_DISABLED`, `ZERO_PERCENT_CANARY`, `NOT_SELECTED`, `UNSUPPORTED_CURRENT_STATUS`, `UNSUPPORTED_DISPOSITION`, `INSUFFICIENT_EVIDENCE_PROFILE`, `UNSUPPORTED_POLICY_VERSION`, `STALE_EVENT`, `ALREADY_FINALIZED`, `MODERATOR_CONTROLLED`, `CONFIGURATION_INVALID`, `ELIGIBLE_SELECTED`, `HARD_CONSTRAINT_ACTIVE`, `IDEMPOTENT_ALREADY_APPLIED`.

## 8. Initial canary safety profile

Requires: authority enabled, percentage > 0, non-stale, `PENDING_VALIDATION`, complete current-v1 evidence profile (`AI + operational + location`), no hard constraint, disposition `FULL_PUBLISH`, matrix `APPLY_SUPPORTED`, deterministic selection.

## 9. Supported dispositions

| Disposition | Canary authority |
|-------------|------------------|
| FULL_PUBLISH | **Enabled** (PENDING_VALIDATION only) |
| HOLD | Implemented mapping reserved; **disabled** (`LEGACY_ONLY`) |
| REJECTED | **Disabled** (`LEGACY_ONLY`) |
| SHADOW / LIMITED_PUBLISH / EXPIRED | **Unsupported** (`FUTURE_UNSUPPORTED`) |

## 10. Status × disposition transition matrix

See `AuthorityDispositionCompatibility`. Only `PENDING_VALIDATION × FULL_PUBLISH` = `APPLY_SUPPORTED`. Exhaustive over all enum pairs; no silent future-enum approval.

## 11. Legacy fallback semantics

| Case | Behavior |
|------|----------|
| Not eligible / not selected | Legacy apply |
| Incomplete evidence / hard constraint / unsupported disposition | Legacy apply (pre-mutation) |
| Engine/evidence technical failure | Legacy apply (pre-mutation) |
| Authoritative audit failure | **Rethrow** → TX rollback (no legacy in same failed TX) |
| Apply outcome mismatch after audit | **Rethrow** → TX rollback |

## 12. Transaction boundary

Consumer `@Transactional` wraps inbox + authority orchestration. Preferred order in one TX: validate → decide → insert AUTHORITATIVE audit → mutate ParkingSpot → history/outbox → commit. Audit failure before mutation prevents apply; apply failure after audit rolls back audit.

## 13. Authoritative audit semantics

- Shadow: best-effort (`safeAppendAudit`)
- Authority: `DecisionAuditPort.appendAuthoritativeRequired` (must propagate)
- Successful apply → `execution_mode=AUTHORITATIVE`, `authority_applied=true`
- Failed/unsupported attempts → metrics only (no misleading AUTHORITATIVE row)

## 14. Audit schema evolution

Flyway `V21__decision_audit_authority_mode.sql`:
- columns: `execution_mode`, `authority_algorithm_version`, `canary_bucket`, `authority_applied`, `applied_status`
- V20 rows default `SHADOW` / `authority_applied=false`
- Partial unique index on `(evaluation_id, policy_version)` WHERE AUTHORITATIVE AND applied
- Snapshot JSON may include the same fields; relational columns are query dimensions. Schema remains `decision-audit-snapshot-v1` readable.

## 15. Idempotency and retry

- Inbox `ON CONFLICT DO NOTHING` on event id
- Canary stable for same spot+evaluation
- Authoritative uniqueness prevents duplicate applied rows
- Retry after success: inbox no-op; if bypassed, `findAuthoritativeApplied` → `IDEMPOTENT_ALREADY_APPLIED`
- Retry after rollback: re-run selection + apply safely

## 16. Moderator priority

Moderator approval/rejection aggregate methods unchanged. `PENDING_REVIEW` / `SUSPICIOUS` → `MODERATOR_CONTROLLED` (legacy only). Terminal states → `ALREADY_FINALIZED`.

## 17. Downstream invariants

`applyAuthoritativeFullPublish` reuses `applyAiValidationPassed`, same history reason `AI_PASSED`, same `ParkingSpotActivated` emission, same REVIEW_FAILED-on-age path, same search visibility rules.

## 18. Observability catalogue

Metrics (bounded tags only: `policy_version`, `authority_algorithm_version`, reason/disposition/status enums):
- `parkio.parking.decision.authority.considered`
- `...selected`, `...applied`, `...applied_status`, `...fallback`
- `...audit_failure`, `...engine_failure`, `...duration`

Never tag spot/evaluation/audit IDs, scores, reason-code collections, or canary buckets.

## 19. Prometheus ratio definitions

File: `docker/prometheus/decision-authority-recording-rules.yml`

- selection rate = selected / considered
- apply success = applied / (applied + fallback)
- fallback rate = fallback / (applied + fallback)
- engine/audit/conflict rates use selected as denominator

Globally disabled traffic is counted as `considered{reason=AUTHORITY_DISABLED}` and is **not** an authority failure.

## 20. Grafana panels

Dashboard: `docker/grafana/provisioning/dashboards/parkio-decision-authority.json` (selection, apply/fallback, failures, disposition, latency). WP-05.6 shadow dashboard retained.

## 21. Activation runbook

1. Shadow healthy  
2. Audit store healthy  
3. Calibration/report reviewed  
4. Product/risk approval recorded outside code  
5. Set `enabled=true` with `canary-percentage=0`  
6. Confirm no selected traffic  
7. Set first approved non-zero percentage  
8. Monitor authority metrics  
9. Sample authoritative audits internally  
10. Increase only via human config change  
11. Rollback via percentage `0` or `enabled=false`

## 22. Rollback runbook

1. `PARKIO_PARKING_DECISION_AUTHORITY_CANARY_PERCENTAGE=0` **or** `..._AUTHORITY_ENABLED=false`  
2. Restart only if env not hot-reloaded (Spring Boot requires restart for these properties)  
3. Committed ParkingSpot states and audits remain  
4. No DB rollback / audit deletion  

## 23. Failure taxonomy

Eligibility: see §7. Fallback: `NOT_SELECTED`, `UNSUPPORTED_DISPOSITION`, `ENGINE_FAILURE`, `AUDIT_FAILURE`, `TRANSITION_CONFLICT`, `CONFIGURATION_FAILURE`, `LEGACY_REQUIRED`, `EVIDENCE_INCOMPLETE`, `HARD_CONSTRAINT`, `UNKNOWN`.

## 24. Security/privacy

No public endpoint or request parameter controls authority. No IDs/coordinates in metric tags. No raw AI payload in logs at info. Config via env/secrets conventions.

## 25. Backward-compatibility proof

Defaults disabled + 0% canary → exact legacy path. No API/Kafka/SDK change. Shadow still optional. V20 audits remain SHADOW-readable. Moderator/reward/visibility unchanged when authority off.

## 26. Exact files and symbols

- `decision.authority.*` — selector, canary, matrix, reasons, execution mode  
- `application.DecisionAuthorityApplicationService`  
- `application.DecisionAuthoritySettings`  
- `application.result.ControlledAuthorityApplyResult`  
- `ParkingApplicationService.applyAuthoritativeFullPublish`  
- `AiValidationEventsKafkaConsumer`  
- `DecisionAuditPort.appendAuthoritativeRequired` / `findAuthoritativeApplied`  
- `V21__decision_audit_authority_mode.sql`  
- `DecisionAuthorityMetrics`

## 27. Deferred authority scope

HOLD, REJECTED, SHADOW, LIMITED_PUBLISH, EXPIRED; multi-policy experimentation; automatic rollout; cross-status authority.

## 28. WP-05.9 prerequisites

Stable canary metrics, calibration confidence, product approval for broader dispositions, and Availability Engine design — **not started**.