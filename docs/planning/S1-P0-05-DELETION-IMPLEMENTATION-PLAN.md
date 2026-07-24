# S1-P0-05 — ParkingSession deletion implementation plan

**Parent decision:** `docs/architecture/PARKING-SESSION-DELETION-PRIVACY-DECISION.md`  
**Sprint task:** S1-P0-05 (decision complete; this plan schedules implementation)  
**Date:** 2026-07-24  
**Rule:** No production code ships under S1-P0-05 itself.
**S1-P0-07 status (2026-07-24):** S1-DEL-01, S1-DEL-03, and S1-DEL-04 are COMPLETE in parking-service.
S1-DEL-02 (erasure tombstone / idempotency sweeper) remains deferred; coord residue is TTL-documented.
**S1-P0-11 status (2026-07-24):** S1-DEL-05 (api-client delete methods) and S1-DEL-06 (mobile-v2
history/deletion UI) are COMPLETE. S1-DEL-08 (`parking_history_deleted` / R22) remains open.
Account erasure tasks remain blocked on PRIV-001.

---

## 1. Goal

Break the accepted privacy decision into atomic, testable implementation tasks with clear owners, dependencies, non-goals, and rollback.

---

## 2. Recommended implementation order

```text
S1-P0-05 (this decision) ✅
    │
    ├─► S1-DEL-01  OpenAPI + error contract for DELETE single/history
    ├─► S1-DEL-02  Erasure tombstone migration (+ optional idempotency sweeper)
    ├─► S1-DEL-03  parking-service single + history hard-delete use cases
    ├─► S1-DEL-04  Integration tests (ownership, ACTIVE conflict, COMMUNITY isolation, idempotency)
    ├─► S1-DEL-05  Shared api-client + validation delete operations
    ├─► S1-DEL-06  mobile-v2 history UI delete actions (depends on history UI task)
    ├─► S1-DEL-07  Support/FAQ/manual beta erase runbook alignment
    ├─► S1-DEL-08  privacy-safe parking_history_deleted analytics (R22)
    ├─► S1-DEL-09  Hosted-beta smoke for delete routes
    │
    └─► Platform account erasure (blocked on PRIV-001 contract)
            ├─► S1-DEL-10  Platform UserDeletionRequested contract
            ├─► S1-DEL-11  parking-service erasure consumer (S1-P1-04)
            ├─► S1-DEL-12  gamification anonymize/detach (D-REW-01)
            ├─► S1-DEL-13  analytics anonymize (D-ANA-01)
            └─► S1-DEL-14  backup restore tombstone replay drill
```

**Do not start** Web deletion UI until Product accepts Web ParkingSession parity (currently deferred in Sprint audit).

**Map to existing backlog:**

| Plan ID | Existing backlog ID (if any) |
|---|---|
| S1-DEL-01…04 | S1-P0-07 |
| S1-DEL-05…06 | **COMPLETE via S1-P0-11** (api-client delete + mobile-v2 history UI) |
| S1-DEL-08 | contributes to R22 (still FAIL) |
| S1-DEL-11 | S1-P1-04 |

---

## 3. Atomic tasks

### S1-DEL-01 — OpenAPI / API contract for session deletion

| Field | Value |
|---|---|
| Priority | P0 |
| Owner/service | parking-service / API contract owners |
| Dependencies | S1-P0-05 ADR; Product accept D-PROD-01, D-API-01 |
| Scope | Document `DELETE /api/v1/parking/sessions/{sessionId}` and `DELETE /api/v1/parking/sessions/history`; operationIds; 204/401/409/429; ACTIVE conflict code; ownership opacity |
| Non-goals | Implementation; account erasure API; Kafka events |
| Acceptance | OpenAPI + examples match ADR §19; contract tests/fixtures updated |
| Tests | OpenAPI endpoint presence assertions (existing OpenApiEndpointTest style) |
| Rollback | Revert contract PR before clients depend on it |
| Docs | Update event/API docs indexes if required |

### S1-DEL-02 — Tombstone migration + idempotency sweeper

| Field | Value |
|---|---|
| Priority | P0 |
| Owner/service | parking-service |
| Dependencies | S1-DEL-01 (can parallelize after ADR) |
| Scope | Flyway migration for erasure tombstone table (`session_id`, `user_id`, `erased_at`, `reason`, `surface`); scheduled purge of expired `idempotency_records` |
| Non-goals | Soft-delete columns on `parking_sessions`; spot schema changes |
| Acceptance | Migration applies cleanly on PostGIS; sweeper removes only expired rows; no coord logging |
| Tests | Migration IT; sweeper unit/IT |
| Rollback | Reverse migration only if unused; otherwise stop sweeper |
| Docs | Note restore replay dependency |

### S1-DEL-03 — Single + history hard-delete use cases

| Field | Value |
|---|---|
| Priority | P0 |
| Owner/service | parking-service |
| Dependencies | S1-DEL-01, S1-DEL-02 |
| Scope | Repository delete-by-id-and-user; delete-all-terminal-by-user; controller wiring; write tombstones; best-effort idempotency residue cleanup; **no** spot/history/points mutation |
| Non-goals | Account saga; reward clawback; session Kafka publish |
| Acceptance | Matches ADR §7–§11, §18–§20; ACTIVE → 409; foreign id opaque; COMMUNITY session delete leaves spot/points |
| Tests | Controller + service unit; PostGIS IT |
| Rollback | Disable routes via config/README rollback; data already deleted remains deleted |
| Docs | parking-service README deletion section |

### S1-DEL-04 — Deletion integration / invariant tests

| Field | Value |
|---|---|
| Priority | P0 |
| Owner/service | parking-service |
| Dependencies | S1-DEL-03 |
| Scope | Prove ADR §21 invariants 1–9 at HTTP+DB level; concurrent complete/delete; delete-all with ACTIVE preserved |
| Non-goals | Mobile UI tests |
| Acceptance | Mandatory Docker IT green with `-Pparkio.integrationTest.requireDocker=true` |
| Tests | Extend `ParkingSessionPostgisIntegrationTest` / controller tests |
| Rollback | N/A (tests) |
| Docs | Gap matrix R8/R9 evidence when green |

### S1-DEL-05 — Shared client delete operations

| Field | Value |
|---|---|
| Priority | P0 |
| Owner/service | `@parkio/api-client`, `@parkio/validation`, `@parkio/types` |
| Dependencies | S1-DEL-01 |
| Scope | Typed `deleteParkingSession` / `deleteParkingSessionHistory`; Zod if needed; contract tests |
| Non-goals | UI |
| Acceptance | Client tests mirror OpenAPI; no coord logging |
| Tests | `parking.session.test.ts` extensions |
| Rollback | Remove exports |
| Docs | api-client changelog if used |

### S1-DEL-06 — mobile-v2 history deletion UX

| Field | Value |
|---|---|
| Priority | P1 (after history list exists) |
| Owner/service | mobile-v2 |
| Dependencies | S1-DEL-05; history UI backlog task |
| Scope | Single-item delete + delete-all with existing `ConfirmModal`; cache invalidation; ACTIVE conflict UX; localization |
| Non-goals | Web UI; new design system; offline queue |
| Acceptance | ADR confirmation copy; no coords/IDs rendered; identity isolation |
| Tests | Focused RN tests |
| Rollback | Hide CTAs |
| Docs | Sprint evidence |

### S1-DEL-07 — Support / FAQ / beta manual erase runbook

| Field | Value |
|---|---|
| Priority | P0 for external beta (D-SUP-01) |
| Owner/service | Ops + Legal |
| Dependencies | ADR |
| Scope | Align `docs/startup/14-faq.md` and a new ops runbook with ADR; document manual SQL erase steps until S1-DEL-03 ships |
| Non-goals | Claiming self-serve erasure |
| Acceptance | FAQ matches capability; runbook lists tables touched and tables **not** touched |
| Tests | Doc review checklist |
| Rollback | N/A |
| Docs | This task |

### S1-DEL-08 — `parking_history_deleted` analytics (privacy-safe)

| Field | Value |
|---|---|
| Priority | P1 |
| Owner/service | analytics + clients |
| Dependencies | S1-DEL-03; analytics payload policy |
| Scope | Emit privacy-safe event (counts/surface only; **no** coords, addresses, or raw session payloads) |
| Non-goals | Full analytics platform redesign |
| Acceptance | R22 can move toward PASS; schema review rejects coords |
| Tests | Consumer/unit assertions on payload keys |
| Rollback | Disable emission flag |
| Docs | event-contracts / analytics docs |

### S1-DEL-09 — Hosted-beta smoke for delete routes

| Field | Value |
|---|---|
| Priority | P1 |
| Owner/service | DevOps / scripts |
| Dependencies | S1-DEL-03 deployed to beta |
| Scope | Extend `scripts/smoke-hosted-beta.sh` (or sibling) to exercise session delete auth/no-store/ownership without printing coords |
| Non-goals | Full E2E mobile |
| Acceptance | R27 progress for session routes |
| Tests | Smoke script dry-run in CI where appropriate |
| Rollback | Remove smoke steps |
| Docs | smoke README |

### S1-DEL-10 — Platform account-erasure contract

| Field | Value |
|---|---|
| Priority | P0 for public beta |
| Owner/service | auth-service / platform |
| Dependencies | PRIV-001 design; Legal |
| Scope | Define `UserDeletionRequested` (name TBD), auth, receipt, retry, fan-out |
| Non-goals | Parking implementation |
| Acceptance | Contract doc + OpenAPI/event schema approved |
| Tests | Contract tests |
| Rollback | N/A until producers ship |
| Docs | architecture erasure ADR |

### S1-DEL-11 — parking-service account-erasure consumer (S1-P1-04)

| Field | Value |
|---|---|
| Priority | P0 for public beta |
| Owner/service | parking-service |
| Dependencies | S1-DEL-03, S1-DEL-10, D-ACT-01 |
| Scope | Force-terminal ACTIVE; hard-delete all sessions; tombstones; purge user idempotency rows; **do not** delete others’ spots |
| Non-goals | Media/MinIO; gamification |
| Acceptance | Duplicate delivery safe; invariants hold |
| Tests | Consumer IT with inbox dedupe |
| Rollback | Stop consumer; manual reconcile |
| Docs | S1-P1-04 evidence |

### S1-DEL-12 — Gamification anonymize/detach on account erasure

| Field | Value |
|---|---|
| Priority | P0 for public erasure |
| Owner/service | gamification-service |
| Dependencies | S1-DEL-10, D-REW-01 |
| Scope | Implement approved ledger policy (default: retain rows, anonymize `user_id`) |
| Non-goals | Reversing historical points on session delete |
| Acceptance | No user-linkable ledger after erase per policy |
| Tests | Ledger IT |
| Rollback | Feature flag |
| Docs | gamification privacy note |

### S1-DEL-13 — Analytics anonymize on account erasure

| Field | Value |
|---|---|
| Priority | P0 for public erasure |
| Owner/service | analytics-service |
| Dependencies | S1-DEL-10, D-ANA-01 |
| Scope | Anonymize/delete user-keyed `PARKING_CLAIMED` (and future session events) per policy |
| Non-goals | Rewriting Kafka history |
| Acceptance | Queries by erased userId return empty |
| Tests | Analytics IT |
| Rollback | Feature flag |
| Docs | analytics retention |

### S1-DEL-14 — Backup restore + tombstone replay drill

| Field | Value |
|---|---|
| Priority | P1 |
| Owner/service | Ops + parking-service |
| Dependencies | S1-DEL-02, S1-DEL-03 |
| Scope | Extend restore runbook: after `pg_restore`, replay tombstones before traffic; prove deleted sessions absent |
| Non-goals | Surgical edit of encrypted historical dumps |
| Acceptance | Restore drill checklist item signed off |
| Tests | Scripted drill in staging |
| Rollback | N/A |
| Docs | `docs/operations/restore-runbook.md` |

---

## 4. Explicitly out of scope for early deletion delivery

- Web ParkingSession deletion UI (until Web parity accepted)
- Offline durable delete queue
- Rewriting `ParkingSpotClaimedEvent` payloads
- Automatic retention expiry of terminal sessions (unless D-RET-01 changes)
- Claiming GDPR/KVKK compliance without Legal sign-off
- Implementing S1-P0-06 (offline decision) inside deletion work

---

## 5. Threat scenarios → owning task

| Scenario | Owning task |
|---|---|
| Delete ACTIVE | S1-DEL-03/04 |
| Already deleted / foreign UUID | S1-DEL-03/04 |
| Concurrent complete/cancel + delete | S1-DEL-04 |
| Delete-all vs new start | S1-DEL-04 |
| Reward/Kafka unavailable | S1-DEL-03 (no remote calls) |
| Cache invalidation failure | S1-DEL-06 |
| Account erase during ACTIVE | S1-DEL-11 + D-ACT-01 |
| Backup reintroduces data | S1-DEL-14 |
| Analytics coords | S1-DEL-08 schema gate |

---

## 6. Definition of Done for this plan document

- Tasks are atomic and ordered
- Each has acceptance criteria and tests
- Cross-service work is separated
- Blocking Product/Legal items are called out
- No production code claimed complete

---

## 7. Next engineering action after S1-P0-05

**S1-P0-07 shipped** the parking-service API slice (S1-DEL-01/03/04). S1-DEL-02 tombstone/sweeper remains deferred.

**Recommended next single task from backlog:** **S1-P0-08** — authoritative ParkingSession lifecycle events (R17–R19; prerequisite for R22). Do not claim mobile deletion UI or account erasure from S1-P0-07.