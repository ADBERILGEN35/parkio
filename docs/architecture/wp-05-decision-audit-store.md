# WP-05.7 Decision Audit Store

**Status:** Complete (2026-07-28)  
**Scope:** Immutable, append-only snapshots of **completed shadow** Decision Engine evaluations  
**Authority:** unchanged — `ParkingApplicationService.applyAiValidationResult` remains authoritative  
**Related:** [Shadow mode](./wp-05-decision-engine-shadow-mode.md), [Calibration](./wp-05-decision-calibration-shadow-analytics.md), [Implementation plan](./wp-05-implementation-plan.md)

---

## 1. Executive summary

WP-05.7 adds an internal **Decision Audit Store** so every successful shadow evaluation can be replayed, explained, and compared across policy versions. It is a deterministic snapshot store — **not** an event store, CQRS write model, analytics warehouse, or publication authority.

Runtime path (when `parkio.parking.decision.shadow-enabled=true`):

```text
EvidenceCollectionService → DecisionEngine → DecisionCalibrationObservation
  → DecisionAuditRecordFactory → DecisionAuditPort.append (isolated)
```

Failed / disabled / partial evaluations are **not** persisted.

---

## 2. Why audit precedes authority

Controlled authority migration (WP-05.8) needs forensic replay of what the engine decided under a named policy/engine version. Persisting canonical inputs+outputs in shadow mode builds that capability without flipping publication.

---

## 3. Domain model

| Type | Package | Role |
|------|---------|------|
| `DecisionAuditRecord` | `decision.audit` | Immutable aggregate for one completed shadow evaluation |
| `DecisionAuditRecordFactory` | `decision.audit` | Builds records after success |
| `DecisionReplayInput` | `decision.audit` | `EvidenceVector` + `EvaluationContext` |
| `DecisionAuditReplayer` | `decision.audit` | Offline replay only |
| `DecisionEngineFactory` | `decision.audit` | Exact policy/engine version resolution |
| `DecisionEngineVersion` | `decision.audit` | `decision-engine-v1` |
| `ShadowModeVersion` | `decision.audit` | `shadow-v1` |
| `DecisionAuditSnapshotSchema` | `decision.audit` | `decision-audit-snapshot-v1` |
| `DecisionAuditPort` | `decision.port` | Append + internal query |

`DecisionResult.equals` / `DerivedAssessment.equals` support replay identity checks.

---

## 4. Persistence model

| Layer | Symbol |
|-------|--------|
| Flyway | `V20__create_decision_audit.sql` → table `decision_audit` |
| Entity | `DecisionAuditEntity` (all columns `updatable = false`) |
| JPA | `DecisionAuditJpaRepository` |
| Mapper | `DecisionAuditSnapshotMapper` (TEXT JSON) |
| Adapter | `DecisionAuditRepositoryAdapter` |

Append refuses overwrite (`existsById` → `IllegalStateException`). No UPDATE workflow. Soft delete not used.

Indexed query columns: spot, evaluation, policy version, evaluated_at, disposition, comparison category, decisive rule, risk band, evidence profile, hard-constraint family.

---

## 5. Replay model

Offline only:

```text
DecisionAuditRecord
  → DecisionReplayInput
  → DecisionEngineFactory.forVersions(policy, engine)
  → DecisionEngine.evaluate
  → DecisionReplayComparison (identical when versions match)
```

Unknown policy or engine version → `UnsupportedDecisionVersionException` (fail closed).  
No hot-path / Kafka / publication replay.

---

## 6. Versioning

| Field | Current value | Meaning |
|-------|---------------|---------|
| `policyVersion` | `decision-shadow-v1` | Threshold / rule table |
| `decisionEngineVersion` | `decision-engine-v1` | Pipeline/facade semantics |
| `shadowModeVersion` | `shadow-v1` | Shadow orchestration semantics |
| snapshot `schemaVersion` | `decision-audit-snapshot-v1` | JSON payload shape |

Future `decision-shadow-v2` comparison uses offline replay against stored evidence — **not** automatic migration or dual runtime evaluation.

---

## 7. Snapshot contents

Persisted (canonical decision domain only):

- audit / spot / evaluation ids
- policy / engine / shadow versions
- `EvidenceVector` (typed items, strengths, reason codes, operational sourceReference)
- `EvaluationContext`
- full `DecisionResult` (including `AssessmentBundle` / `RiskAssessment`)
- legacy outcome + comparison category
- calibration dims: risk band, hard-constraint family, evidence profile, decisive rule

**Never stored:** raw image bytes, raw AI/Kafka payloads, controller DTOs, JPA entities, GPS history, high-precision coordinates, user PII, trace ids, stack traces.

---

## 8. Security

- Internal port only — no public REST / SDK / Kafka audit topic
- Snapshot serializer is infrastructure-only (Jackson)
- Metric tags never include audit/spot/evaluation ids

---

## 9. Privacy

- No image content
- No coordinates in snapshot (location evidence is polarity/reason/strength only)
- `sourceReference` may carry operational correlation ids already present on `EvidenceItem` (event/evaluation refs) — not used as metric labels
- No user identity fields

---

## 10. Immutability

- Insert-only table and entity columns
- Adapter rejects overwrite of existing `auditId`
- Corrections = new row

---

## 11. Failure isolation

Audit append failures:

- do **not** throw into `AiValidationEventsKafkaConsumer` / apply path
- do **not** change `ParkingSpot`, rewards, trust, availability
- do **not** invalidate calibration metrics already recorded
- increment `parkio.parking.decision.audit.write.failure`

Shadow evaluation failures still do **not** write audit rows.

---

## 12. Metrics

| Metric | Meaning |
|--------|---------|
| `parkio.parking.decision.audit.write.success` | Append succeeded |
| `parkio.parking.decision.audit.write.failure` | Append failed |
| `parkio.parking.decision.audit.replay.success` | Offline replay succeeded (when recorded) |
| `parkio.parking.decision.audit.replay.failure` | Offline replay failed (when recorded) |

No ID tags. Adapter: `DecisionAuditMetrics` implements `DecisionAuditWriteObserver`.

---

## 13. Future authority integration

WP-05.8 may later persist authoritative decisions with the same aggregate shape. This WP does **not** write authoritative decisions or execute dispositions from audit rows.

---

## 14. Future replay workflow

1. Load `DecisionAuditRecord` by id / spot / evaluation / time range  
2. `DecisionAuditReplayer.replayAndCompare` under bound versions  
3. Optionally evaluate a **candidate** policy offline against stored `EvidenceVector` (explicit tooling; not runtime A/B)

---

## 15. WP-05.8 prerequisites

WP-05.8 = **Controlled Decision Authority Migration** (planned). Prerequisites include:

1. WP-05.6 parity/drift readiness checklist with product-approved thresholds  
2. This audit store available in environments that will canary authority  
3. Canary authority flag default **off** + rollback to `applyAiValidationResult`  
4. Moderator/product review of high-impact disagreement samples  
5. Explicit decision whether authoritative decisions also append audit rows  

**WP-05.8 remains incomplete.**

---

## Exact inventory

**Created:** `decision/audit/*`, `V20__create_decision_audit.sql`, `DecisionAuditEntity`, `DecisionAuditJpaRepository`, `DecisionAuditSnapshotMapper`, `DecisionAuditRepositoryAdapter`, `DecisionAuditMetrics`, `DecisionAuditWriteObserver`, docs/tests listed above.

**Modified:** `DecisionAuditPort`, `DecisionShadowOrchestrator`, `ParkingInfrastructureConfig`, `DecisionResult` / `DerivedAssessment` equals, architecture README / plan / shadow / calibration cross-links.

## Related

- [WP-05.8 Controlled Authority Migration](wp-05-controlled-authority-migration.md) — default-off canary authority; shadow remains non-authoritative and independent.
