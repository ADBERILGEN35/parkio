# ParkingSession deletion and derived-observation privacy decision

**Decision ID:** PARKIO-ADR-PARKING-SESSION-DELETION-001  
**Sprint task:** S1-P0-05  
**Status:** Accepted; **S1-P0-07 API slice implemented** (Product/Legal/Security approvals still required where marked)  
**Date:** 2026-07-24  
**Scope:** Decision and architecture for deletion/privacy. Production DELETE endpoints shipped under S1-P0-07; this ADR itself does not add UI, account-erasure workers, or soft-delete columns.  
**Baseline evidence:** repository truth as of Sprint 01 audit + post S1-P0-04 mobile-v2 lifecycle UI + post S1-P0-07 deletion APIs.

---

## 1. Status

This decision defines what “delete my ParkingSession data” means across Parkio services.

It is **implementation-ready**; the S1-P0-07 parking-service DELETE slice is shipped against the closed-beta defaults in 뿯½25.

It does **not** claim:

- GDPR/KVKK compliance (legal basis and DPIA remain Product/Legal owned — PRIV-001/PRIV-003)
- that mobile/Web deletion UI exists (client tasks remain open)
- that account erasure exists platform-wide (PRIV-001 remains Open)
- that `parking_history_deleted` analytics exists (R22 remains FAIL until implemented)

**R23** (“Deletion/privacy behavior for derived observations is documented”) is satisfied by this document.
**R8/R9** are PASS after S1-P0-07.

---

## 2. Context

Canonical mobile-v2 can start, restore, time, complete, and cancel an ACTIVE `ParkingSession` (S1-P0-01…04). Users still cannot delete terminal history. Community claim creates a `COMMUNITY` session **and** mutates shared spot state / emits `ParkingSpotClaimedEvent`, which drives gamification and analytics.

Without explicit semantics, implementing DELETE risks:

- erasing another user’s independently owned spot observation
- reversing or corrupting reward ledgers
- leaving precise coordinates in idempotency bodies, caches, or backups
- promising instantaneous physical purge from immutable backups that the platform does not support

Related gaps: PRIV-001 (`docs/audit/PARKIO-FINDINGS.md`), Sprint R8/R9/R22/R23 (`docs/audit/SPRINT-01-PARKING-SESSION-GAP-MATRIX.md`), backlog S1-P0-05 / S1-P0-07 / S1-P1-04.

---

## 3. Repository evidence

### 3.1 ParkingSession aggregate (current truth)

| Evidence | Path |
|---|---|
| Domain entity | `services/parking-service/src/main/java/com/parkio/parking/domain/ParkingSession.java` |
| Statuses | `ParkingSessionStatus`: `ACTIVE`, `COMPLETED`, `CANCELLED` |
| Sources | `ParkingSource`: `MANUAL`, `FACILITY`, `CURB`, `COMMUNITY`, `AUTO` |
| Application service | `ParkingSessionService` — start/complete/cancel/findActive/findHistory only |
| HTTP API | `ParkingSessionController` — `/api/v1/parking/sessions` (no DELETE) |
| Schema | `services/parking-service/src/main/resources/db/migration/V15__create_parking_sessions.sql` |

**Persisted fields:** `id`, `user_id`, `status`, `parking_source`, `started_at`, `ended_at`, `latitude`, `longitude`, `location` (PostGIS geography via trigger), `estimated_fee`, `reminder_at`, `created_at`, `updated_at`, `version`.

**Invariants (DB):**

- Partial unique index `uq_parking_sessions_active_user` — at most one ACTIVE per `user_id`
- Immutable update trigger rejects changes to `id`, `user_id`, `parking_source`, `started_at`, `latitude`, `longitude`, `location`, `created_at`
- **No FK** to users or spots (intentional; comment in V15)
- **No table references** `parking_sessions` (nothing cascades)

### 3.2 Community claim linkage (current truth)

| Evidence | Path |
|---|---|
| Claim orchestration | `ParkingApplicationService.claimSpot` |
| Order | expire check 뿯↽ claim spot 뿯↽ `parkingSessions.startSession(..., COMMUNITY, spot.lat, spot.lng, ...)` 뿯↽ save spot 뿯↽ history reason `CLAIMED` 뿯↽ outbox `ParkingSpotClaimedEvent` |
| Spot status history | `parking_spot_status_history` (V4) — FK to spot, **no session_id** |
| Claim event | `ParkingSpotClaimedEvent` — `ownerUserId`, `actorUserId`, `parkingSpotId`, status; **no coordinates**, **no sessionId** |
| Topic | `parkio.parking.spot` (`KafkaTopicsConfig.PARKING_SPOT`) |

**Critical fact:** session 뿯↽ spot linkage is **temporal/coord-copy only**, not a foreign key.

### 3.3 Events (current truth)

Sealed parking domain events are **ParkingSpot** aggregate events only (`docs/architecture/event-contracts.md`).

**ABSENT:** `ParkingSessionStarted`, `ParkingSessionCompleted`, `ParkingSessionCancelled`, `ParkingSessionDeleted` (gap matrix R17–R19).

Consumers of `ParkingSpotClaimed`:

- gamification-service 뿯↽ points/trust
- analytics-service 뿯↽ `PARKING_CLAIMED`
- notification-service 뿯↽ ignores claim

### 3.4 Rewards / analytics (current truth)

| Store | Linkage | Session id? | Coords? |
|---|---|---|---|
| `point_transactions` (gamification V2) | `user_id`, `related_spot_id`, `related_event_id` | No | No |
| Analytics `PARKING_CLAIMED` | claimer `userId` + spot as related entity | No | No |
| Product mobile analytics union | no `parking_session_*` events | — | — |

### 3.5 Idempotency / transport retention (current truth)

| Store | Behavior |
|---|---|
| `idempotency_records` (V10) | Session start/complete/cancel store serialized `ParkingSessionResponse` (includes lat/lng) for TTL default 24h; opportunistic delete on expired-key reuse only |
| `outbox_events` / `inbox_events` | Cleaned by `RetentionCleanupJob` (defaults P7D / P30D) — transport only |
| `parking_sessions` | **No retention sweeper** |

### 3.6 Deletion / erasure elsewhere (current truth)

| Exists | Scope |
|---|---|
| media-service DELETE media | soft-delete media object |
| notification-service DELETE device-token | push token |
| auth-service admin DELETE refresh session | auth refresh token — **not** ParkingSession |

**ABSENT:** user account deletion API; `UserDeletionRequested` event; parking erasure consumer; session DELETE OpenAPI ops; manual erasure runbook file (recommended by PRIV-001, not present as implemented procedure).

### 3.7 Backups (current truth)

`docs/operations/backup-runbook.md`: per-service `pg_dump`, MinIO mirror, default `BACKUP_RETENTION_DAYS=14`. No session-specific purge-from-backup.

### 3.8 Client surface (current truth)

Shared `@parkio/api-client` exposes start/active/complete/cancel/history — **no delete**. mobile-v2 Map chrome owns ACTIVE lifecycle UI; history UI and deletion UI are out of scope until later tasks.

---

## 4. Terminology

| Term | Meaning in this decision |
|---|---|
| **Directly user-owned** | Row whose primary purpose is that user’s private parking memory (`parking_sessions.user_id`) |
| **Shared / jointly produced** | Spot observation, status history, and claim events that affect other users’ map reality |
| **Derived** | Rewards, analytics, metrics, caches computed from claim/session activity |
| **Hard delete** | Physical `DELETE` of the row |
| **Anonymize / detach** | Remove or replace `user_id` (and other direct identifiers) while retaining non-identifying facts |
| **Tombstone** | Durable record that an erasure occurred, used after restore/replay |
| **Logical deletion complete** | Primary DB + online caches no longer return user-linked session PII via product APIs |
| **Physical backup expiry** | Dump copies age out per `BACKUP_RETENTION_DAYS` |
| **Terminal session** | `COMPLETED` or `CANCELLED` |
| **Erasure receipt** | Optional status resource for async account-level erasure (not required for sync session DELETE) |

---

## 5. Data inventory

| Data/entity | Exists now? | Evidence |
|---|---|---|
| `parking_sessions` | Yes | V15 |
| Session API responses (precise coords) | Yes | `ParkingSessionResponse` |
| `idempotency_records.response_body` (session JSON) | Yes | V10 + `IdempotencyService` |
| `parking_spots` | Yes | V2 — claim mutates status |
| `parking_spot_status_history` (`CLAIMED`) | Yes | V4 + `claimSpot` |
| `ParkingSpotClaimedEvent` / outbox / Kafka | Yes | domain event + relay |
| Gamification `point_transactions` / trust | Yes | claim handlers |
| Analytics `PARKING_CLAIMED` | Yes | analytics consumer |
| `parking_spot_view_logs` / `parking_spot_search_logs` | Yes | V5/V6 — adjacent location PII (PRIV-002); **not** created by session start |
| ParkingSession lifecycle Kafka events | **No** | R17–R19 |
| Session뿯↽spot FK | **No** | V15 / claim code |
| Session DELETE API | **No** | controller/OpenAPI |
| Account erasure saga | **No** | PRIV-001 |
| Session retention job | **No** | `RetentionCleanupJob` transport-only |
| History UI (mobile-v2/Web) | **No** | Sprint audit |
| Crash reporter session payloads | **Not evidenced** for ParkingSession | treat as future control |

---

## 6. Ownership classification

| Class | Entities | Deletion principle |
|---|---|---|
| A. Directly user-owned | `parking_sessions` rows for that `user_id`; client active-session cache for that user | Removable by owner (within lifecycle rules) |
| B. Shared / jointly produced | `parking_spots`, spot status history, public map availability after claim | **Not** deleted because one claimant clears private history |
| C. Derived from claim (not session row) | `point_transactions`, trust adjustments, analytics `PARKING_CLAIMED` | Do not reverse on session delete; account erasure may anonymize/detach |
| D. Transport / ephemeral copies | idempotency bodies, outbox/inbox rows, HTTP caches | Expire/minimize; purge when feasible |
| E. Operational backups | `pg_dump` artifacts | Retain until backup retention expires; restore must not re-expose erased PII without tombstone replay |

---

## 7. Deletion surfaces

### 7.1 Delete one ParkingSession (product)

| Dimension | Decision |
|---|---|
| Actor | Authenticated session owner |
| Authorization | Gateway-injected user id must equal `parking_sessions.user_id`; foreign UUID 뿯↽ same opaque outcome as not-found |
| Allowed statuses | **Terminal only** (`COMPLETED` / `CANCELLED`). `ACTIVE` 뿯↽ conflict (must complete/cancel first) |
| Sync/async | **Synchronous** in parking-service (local hard delete; no cross-service FK) |
| API result | `204 No Content` on success and on already-deleted (idempotent). `409` if ACTIVE. `401/403` per platform auth. Never reveal ownership of foreign ids |
| Idempotency | Natural idempotency (repeat DELETE 뿯↽ 204). Optional `Idempotency-Key` allowed if aligned with existing mutation pattern; not required for correctness |
| User-visible completion | Session disappears from history; active query unchanged unless it was already null |
| Erasure receipt | Not required for this surface |
| Retry | Safe to retry DELETE |

### 7.2 Delete all ParkingSession history (product)

| Dimension | Decision |
|---|---|
| Actor | Authenticated owner |
| Scope | All **terminal** sessions for `user_id` |
| ACTIVE handling | ACTIVE is **not** deleted; remains until complete/cancel |
| Sync/async | Synchronous hard delete of terminal rows for that user |
| API result | `204` (default; see D-API-01) |
| Idempotency | Repeat 뿯↽ 204 with zero rows affected |
| Concurrent start | New ACTIVE created during delete-all remains; only terminals matched at statement time are removed (transactional snapshot) |

### 7.3 Delete entire user account (platform)

| Dimension | Decision |
|---|---|
| Actor | User (future self-serve) or Support/Admin under audited procedure |
| Authorization | Platform account-erasure contract (does not exist yet — PRIV-001) |
| Sync/async | **Asynchronous saga** across services; parking-service is one consumer (S1-P1-04) |
| Parking slice | Hard-delete **all** `parking_sessions` for user (including ACTIVE after forced cancel/complete policy — see 뿯½8); purge user-scoped idempotency rows; **do not** delete spots owned by others; for spots **owned by** the erased user, follow platform spot-erasure rules (separate ADR; Product approval) |
| Receipt | Erasure status/receipt **required** at platform level |
| ParkingSession-only DELETE APIs | Insufficient for account erasure |

### 7.4 Administrative / security erasure

| Dimension | Decision |
|---|---|
| Actor | Admin/Support with elevated role + audit log |
| Capability | May invoke same parking erase use-cases with reason codes; may place legal hold (**Product/Legal**) |
| Abuse | Rate-limited; every admin erase audited |

### 7.5 Retention expiry

| Dimension | Decision |
|---|---|
| `parking_sessions` | **No automatic expiry** in Sprint 1 unless Product sets a retention limit (D-RET-01) |
| Idempotency bodies with coords | Enforce scheduled purge of expired `idempotency_records` (not only opportunistic) |
| Outbox/inbox | Keep existing P7D/P30D transport retention |
| Backups | `BACKUP_RETENTION_DAYS` (default 14) |

---

## 8. Canonical semantics

### 8.1 Lifecycle matrix

| Status | Single delete allowed? | Mechanism | Notes |
|---|---|---|---|
| `ACTIVE` | **No** | Return conflict; client must complete/cancel | Avoids race with complete/cancel |
| `COMPLETED` | Yes | Hard delete row | Removes precise coords from primary DB |
| `CANCELLED` | Yes | Hard delete row | Same |
| Already deleted | Yes (idempotent) | No-op 204 | Do not leak existence beyond owner auth boundary |

### 8.2 Field disposition on single/history delete

| Field | Disposition |
|---|---|
| `id`, `user_id`, `status`, `parking_source`, `started_at`, `ended_at`, `estimated_fee`, `reminder_at`, timestamps, version | Removed with hard delete |
| `latitude`, `longitude`, `location` | Physically removed with hard delete |
| Soft-delete / nulling coords in place | **Rejected** for product delete — V15 immutability trigger forbids updating lat/lng/location; hard delete is the coherent path |

### 8.3 ACTIVE during account erasure

**Recommended default (requires Product approval D-ACT-01):** platform erasure cancels ACTIVE sessions server-side (system cancel) then hard-deletes all sessions for the user.

Rejected alternative: leave ACTIVE stranded after auth user is gone.

---

## 9. Per-entity retention/deletion matrix

| Data/entity | Owner | Delete | Anonymize/detach | Retain | Retention reason | Future implementation |
|---|---|---:|---:|---:|---|---|
| `parking_sessions` (terminal, owner) | User | Yes | — | — | Private parking memory | S1-P0-07 |
| `parking_sessions` (ACTIVE, product delete) | User | No (conflict) | — | Until terminal | Lifecycle integrity | Client + API |
| `parking_sessions` (account erasure) | User | Yes (after forced terminal) | — | — | Erasure | S1-P1-04 |
| Session API cache (mobile RQ) | User device | Clear on delete success / logout | — | — | Prevent stale coords | Client task with history UI |
| `idempotency_records` with session body | User | Delete expired + purge on erase when practical | — | ≤ TTL (24h default) | Replay safety | Sweeper + erase hook |
| `parking_spots` (claimed) | Spot owner (reporter) | **No** on claimant session delete | — | Product map truth | Shared observation | None for session delete |
| `parking_spot_status_history` CLAIMED | Spot lifecycle | **No** | Optional detach of actor id on **account** erasure only (D-HIST-01) | Operational history | Shared | S1-P1-04 if approved |
| `ParkingSpotClaimedEvent` (published) | Platform event | **No rewrite** of Kafka/outbox history | — | Transport retention | Immutable stream | None |
| Gamification `point_transactions` | User ledger | **No** on session delete | Maybe on account erasure (D-REW-01) | Accounting / anti-abuse | Immutable-style ledger | S1-P1-04 decision |
| Trust adjustments | User | **No** on session delete | Maybe on account erasure | Anti-abuse | Trust model | S1-P1-04 |
| Analytics `PARKING_CLAIMED` | Platform | **No** on session delete | Anonymize user id on account erasure (D-ANA-01) | Product metrics | Aggregate analytics | Analytics erasure task |
| Future `parking_session_*` analytics | Platform | N/A until created | Must never include precise coords | — | R17–R22 policy | Analytics tasks |
| `parking_spot_search_logs` / view logs | Platform | Out of session-delete scope | Separate PRIV-002 program | Currently indefinite (gap) | Ops | PRIV-002 remediation |
| Backups containing sessions | Ops | Not surgically purged | — | Until backup retention | Disaster recovery | Restore + tombstone replay |
| Application logs with `sessionId` | Ops | Log retention policy | Redact coords (must not log lat/lng) | Short ops window | Security/debug | Logging standards task |

---

## 10. Precise-coordinate policy

Precise `latitude`/`longitude`/`location` are **high-sensitivity**.

| Rule | Requirement |
|---|---|
| Product single/history delete | Physical removal via hard delete of `parking_sessions` |
| Reduced-precision retain | **Not** retained on the session row after delete |
| Aggregates | If Product later retains geo aggregates, use ≥ city-level or grid cells with k-anonymity (**D-GEO-01**) — not part of session row |
| Events | Existing `ParkingSpotClaimedEvent` has no coords (good). `ParkingSpotCreatedEvent` has coords (spot creation — separate policy) |
| Idempotency bodies | Treated as coordinate copies; expire and purge |
| Logs | **Must not** log precise session coordinates (claim path already logs sessionId without coords — keep that bar) |
| Caches | Active/history query caches must not serve deleted session coords; session routes already use `no-store` — still invalidate client RQ keys |
| Other users / future login | Hard delete + owner-scoped queries + cache clear prevent retrieval |

---

## 11. Shared / community-data policy

When a `COMMUNITY` session was created by claim:

| Artifact | On claimant session delete | On claimant account erasure |
|---|---|---|
| Claimant `parking_sessions` row | Hard delete (if terminal) / forced terminal then delete (account) | Hard delete all |
| Spot `FILLED` / availability | **Unchanged** | Unchanged unless spot **owned by** erased user (platform spot policy) |
| Original reporter’s spot | **Unchanged** | Unchanged |
| Status history `CLAIMED` | **Retain** | Retain; optional anonymize actor (D-HIST-01) |
| `ParkingSpotClaimedEvent` | **Retain** (no stream rewrite) | Retain |
| Reporter’s rewards | **Unchanged** | Unchanged |

**Invariant:** deleting private parking memory must not delete another user’s independently owned report.

**Invariant:** after account erasure, retained shared rows must not remain reasonably linkable to the erased user if they store `actorUserId` — hence D-HIST-01 / D-ANA-01.

---

## 12. Derived-observation policy

| Derived datum | Classification | Why |
|---|---|---|
| Spot occupancy after claim | Retain unchanged | Shared map truth; not owned by session row |
| `parking_spot_status_history` | Retain (optional detach on account erasure) | Audit of spot lifecycle |
| Confidence / ranking signals (if any use claims) | Recalculate or leave; do not require session row | No session FK today |
| Reputation / trust from claim | Retain on session delete; review on account erasure | Anti-abuse + trust model |
| Reward points from claim | Retain ledger rows on session delete | Immutable accounting; linked to spot/event not session |
| Fraud/abuse evidence referencing claim | Retain with security retention (**D-SEC-01**) | Security |
| Analytics `PARKING_CLAIMED` | Retain on session delete; anonymize on account erasure | Metrics without session id today |
| Operational Micrometer counters | Retain aggregates | Non-PII counters |
| Future session lifecycle analytics | Must be privacy-minimized (no coords/address) | R17–R22 |

---

## 13. Rewards / reputation policy

**Repository architecture:** claim awards use spot/event ids, not session ids. Manual session complete/cancel awards **do not exist**.

| Action | Session product delete | Account erasure |
|---|---|---|
| Reverse points | **No** | **No** by default (D-REW-01 may choose compensating entries instead of DELETE) |
| Delete ledger rows | **No** | **No** |
| Anonymize `user_id` on ledger | N/A | **Allowed** if Product/Security accept loss of per-user audit (D-REW-01) |
| Recalculate reputation | Not required for session delete | Optional recompute after detach |

**Do not** silently delete `point_transactions` because a parking session disappeared.

---

## 14. Event / outbox / inbox policy

| Topic | Policy |
|---|---|
| Existing ParkingSpot stream | Immutable; do not rewrite payloads |
| Session delete | **No mandatory Kafka event** for parking-local hard delete (no current consumers). Optional `ParkingSessionDeleted` later if needed — out of Sprint 1 critical path |
| Account erasure | Platform `UserDeletionRequested` (proposed; not in repo) 뿯↽ parking consumer erases slice |
| Dead letters | Must not reintroduce deleted session PII into online APIs |
| Replay | Spot claim replay must not recreate a deleted private session row. Session rows are not event-sourced |

---

## 15. Logging and analytics policy

| Channel | userId? | sessionId? | Precise coords? | Obligation |
|---|---|---|---|---|
| App logs (parking) | Careful | Yes (claim already does) | **No** | Keep coords out of log lines |
| Gateway logs | Auth subject | Path ids | **No** bodies with coords | no-store already on session paths |
| Tracing attributes | Prefer low-cardinality | Avoid raw if possible | **No** | Observability standards |
| Metrics labels | No high-cardinality userId | No | No | Keep outcome/spot bounded |
| Product analytics | Pseudonymous ok | Prefer not | **Never** | Future `parking_history_deleted` must be privacy-safe (R22) |
| Crash reporters | Minimize | Minimize | **Never** | Configure before enable |
| Security audit logs of erase | Actor, target user, reason, counts | Optional | **No** | Retain per D-SEC-01 |

Distinguish: **security audit** (who erased what) vs **product analytics** (funnel counts).

---

## 16. Cache / index / replica policy

| Store | Action on session delete |
|---|---|
| mobile-v2 React Query active/history keys | Invalidate/remove deleted ids |
| Gateway / CDN | Session routes already `no-store` |
| Redis (if session cache appears later) | Key by user; drop on delete |
| Read replicas | Same hard delete via primary replication |
| Search indexes | **None** for sessions today |
| Object storage | Sessions store no objects today |

---

## 17. Backup and restore policy

| Stage | Semantics |
|---|---|
| Immediate | Logical deletion complete when primary parking DB + online caches comply |
| Backups | May retain deleted rows until `BACKUP_RETENTION_DAYS` prune |
| Promise | **Do not** claim instantaneous physical removal from backups |
| Restore | Restoring an old dump may reintroduce deleted sessions; **required control:** durable **erasure tombstone ledger** + replay after restore. Until ledger exists, operator runbook must warn and run manual SQL erase after restore |
| MinIO | N/A for session blobs |

---

## 18. Authorization and abuse controls

| Rule | Requirement |
|---|---|
| Owner-only | Deletes scoped by authenticated `user_id` |
| Foreign UUID | Indistinguishable not-found / no-content (no existence oracle) |
| Admin | Separate audited path |
| Rate limit | Apply gateway/user rate limits to delete-all |
| Idempotency | Repeat safe |
| Audit | Record erase actions without coords |
| Cross-user | Impossible to delete another user’s session via product API |

---

## 19. API contract recommendation

### Option A — Resource DELETE (recommended)

Align with existing prefix `/api/v1/parking/sessions` (not `parking-sessions`).

| Operation | Method/path | Semantics |
|---|---|---|
| Delete one | `DELETE /api/v1/parking/sessions/{sessionId}` | Owner; terminal only; 204 idempotent; 409 if ACTIVE |
| Delete all terminal history | `DELETE /api/v1/parking/sessions/history` | Owner; deletes terminals; preserves ACTIVE |

**Why A:** Matches current controller style, OpenAPI operationId pattern, and local transactional hard delete. No async job needed for parking-only PII.

### Option B — Command + erasure job/status

`POST /api/v1/parking/sessions/erasures` + `GET .../erasures/{id}`.

**Reject for Sprint 1 session/history delete:** unnecessary complexity while deletion is single-DB. **Reserve** for platform account erasure saga.

### Responses (recommended)

| Code | Meaning |
|---|---|
| 204 | Deleted or already absent (owner-scoped) |
| 401 | Unauthenticated |
| 409 | ACTIVE session cannot be deleted |
| 429 | Rate limited |

OpenAPI impact: additive operations only — implemented in S1-P0-07, not this task.

---

## 20. Database strategy

| Table | Strategy | Rationale |
|---|---|---|
| `parking_sessions` | **Hard delete** | No inbound FKs; immutability trigger blocks in-place coord redaction |
| Soft delete column | Rejected for Sprint 1 product delete | Would retain precise coords unless also redacted (forbidden by trigger without migration) |
| Tombstone ledger (new) | **Add** for restore replay / account erasure audit | Small table: `session_id`, `user_id`, `erased_at`, `reason`, `surface` |
| `idempotency_records` | Delete expired; delete rows for user on account erasure; best-effort on session erase | Contains coord copies |
| Spot / history / points | Retain / optional anonymize | Shared or ledger |

**Future migration:** erasure tombstone table; optional scheduled idempotency sweeper; **no** FK changes to spots.

---

## 21. Privacy invariants (testable)

1. After owner deletes a terminal session, `GET .../history` and `GET .../active` never return that session id or its coordinates.
2. Precise coordinates of a deleted session are absent from primary `parking_sessions` and from online client caches after successful delete handling.
3. User A cannot delete User B’s session (foreign id 뿯↽ opaque 204/404 behavior).
4. Deleting a `COMMUNITY` session does not delete the spot, status history, or another user’s ownership.
5. Retained aggregates/events after account erasure are not keyed by the erased user’s stable id without an approved anonymization step.
6. Backup restore followed by tombstone replay (once implemented) does not leave erased sessions queryable.
7. Repeat DELETE is idempotent (204).
8. Session delete does not delete or reverse `point_transactions`.
9. DELETE of ACTIVE returns deterministic conflict; complete/cancel remain the terminal path.
10. User switch / logout clears prior user’s session query cache (existing `SessionQueryCacheSync`) and must not show deleted foreign history.

---

## 22. Failure and retry semantics

| Scenario | Safe outcome |
|---|---|
| Delete ACTIVE | 409; no row change |
| Delete already deleted | 204 |
| Delete foreign UUID | Opaque 204/404; no leak |
| Concurrent complete + delete | Serialize on row; complete then delete, or 409 while ACTIVE |
| Concurrent cancel + delete | Same |
| Delete-all while new start | Deletes current terminals; new ACTIVE preserved |
| Reward service down | Session delete still succeeds (no reward call) |
| Kafka/outbox down | Session delete still succeeds (no required publish) |
| Cache invalidation failure (client) | Next refetch must omit; server is source of truth |
| DB transaction failure | No partial row delete; client retries |
| Duplicate DELETE | 204 |
| Account deletion during ACTIVE | Force terminal then delete (D-ACT-01) |
| Restore old backup | Tombstone replay or manual erase before serving traffic |
| Delayed claim consumer | Must not recreate deleted private session |
| Analytics with coords | Forbidden |
| User switch while delete pending | Ignore stale responses for prior user |

---

## 23. Migration / rollout considerations

1. Ship OpenAPI + backend delete before mobile history UI.
2. Feature exposure may follow parking-service rollback guidance: clients that can create sessions must handle missing history rows.
3. Tombstone ledger should land with or before relying on backup restore drills for erasure proof.
4. Do not enable public self-serve account erasure until platform saga exists (PRIV-001).
5. Update FAQ / `privacy@` support runbook to match this decision.

---

## 24. Rejected alternatives

| Alternative | Why rejected |
|---|---|
| Soft-delete session keeping lat/lng | Retains high-sensitivity coords |
| Soft-delete + null coords without migration | Blocked by V15 immutability trigger |
| Cascade-delete spot on COMMUNITY session delete | Destroys shared/other-user data |
| Reverse gamification points on session delete | Ledger is spot/event based; would corrupt accounting |
| Rewrite Kafka claim events | Published events are immutable |
| Async job for single session delete | Unnecessary; single DB |
| Path `/api/v1/parking-sessions` | Diverges from existing `/api/v1/parking/sessions` |
| Promise purge from backups on DELETE | Unsupported by backup-runbook |

---

## 25. Product / legal / security decisions still required

| ID | Decision | Options | Engineering impact | Privacy impact | Recommended default | Blocking? | Owner |
|---|---|---|---|---|---|---|---|
| D-ACT-01 | Account erasure vs ACTIVE | Force cancel then delete vs reject erasure until user terminals | Saga steps | Stranded ACTIVE risk | Force cancel then delete | **Blocking for S1-P1-04** | Product + Backend |
| D-REW-01 | Ledger on account erasure | Retain with userId / anonymize userId / compensating clawback | Gamification migrations | Linkability vs audit | Retain rows; anonymize userId | Blocking for public erasure | Product + Security |
| D-HIST-01 | Status history `actorUserId` | Retain / null actor / hash | History schema | Linkability | Null/hash actor on account erasure only | Blocking for public erasure | Product + Legal |
| D-ANA-01 | Analytics user id on erasure | Delete events / anonymize / retain | Analytics jobs | Linkability | Anonymize | Blocking for public erasure | Data + Legal |
| D-GEO-01 | Any geo aggregate after erase | None / coarse grid with k | Possible rollups | Re-ID risk | None in Sprint 1 | Non-blocking | Data + Legal |
| D-RET-01 | Max age of terminal sessions | Forever until user deletes / N days | Retention job | Storage + risk | Forever until user deletes (beta) | Non-blocking | Product + Legal |
| D-SEC-01 | Security audit retention | 30/90/365 days | Audit store | Compliance | 365 days beta default | Non-blocking | Security + Legal |
| D-SUP-01 | Beta support manual erase | Email-only runbook vs wait for API | Ops | Honoring requests | Manual SQL runbook aligned to this ADR until S1-P0-07 | **Blocking for external beta testers** | Ops + Legal |
| D-API-01 | Delete-all response shape | 204 vs 200+count | OpenAPI | Low | 204 | Non-blocking | Backend Architecture |
| D-PROD-01 | Confirm Product accepts “ACTIVE must terminal first” | Accept / allow force delete ACTIVE | API | Races | Accept conflict | Blocking for S1-P0-07 | Product |

**Closed-beta acceptance for S1-P0-07:** accept D-PROD-01 and D-API-01 defaults; keep account-erasure decisions open without blocking single/history DELETE.

---

## 26. Consequences

### Positive

- Clear owner-safe hard delete path for terminal sessions
- Shared community data preserved
- Reward ledgers protected
- Precise coordinates removed from primary DB on delete
- R23 documentation gap closed

### Negative / follow-on cost

- Tombstone + restore replay required for strong backup guarantees
- Idempotency sweeper needed to shrink coord residue window
- Account erasure remains a larger multi-service program
- History UI must teach “ACTIVE cannot be deleted” UX

---

## 27. Definition of Done for future implementation

S1-P0-07 API DoD (shipped):

- OpenAPI annotations + parking-service `DELETE` single + `DELETE` history match this ADR
- Integration tests prove ownership, ACTIVE conflict, opaque foreign/missing, COMMUNITY non-cascade
- Precise coordinates removed with hard-deleted rows; responses/logs do not serialize entities
- Idempotency residue for prior mutation response bodies remains TTL-bound follow-up (no unsafe JSON purge in S1-P0-07)
- Account erasure remains explicitly out of scope until S1-P1-04 + platform contract

Still open after S1-P0-07:

- Client history/deletion UI and cache invalidation
- `parking_history_deleted` analytics (R22)
- Scheduled idempotency sweeper / erasure tombstone (plan S1-DEL-02 / account saga)
- Support/FAQ wording updates

---

## 28. S1-P0-07 implementation status

| Item | Status |
|---|---|
| `DELETE /api/v1/parking/sessions/{sessionId}` | Shipped (`deleteParkingSession`) |
| `DELETE /api/v1/parking/sessions/history` | Shipped (`deleteParkingSessionHistory`) |
| Owner predicate on all deletes | `userId` from authenticated `X-User-Id` |
| ACTIVE conflict | `409` + `PARKING_SESSION_NOT_TERMINAL` |
| Missing / foreign / already deleted | Opaque `204` |
| Hard delete (no soft delete / tombstone column) | JPQL `@Modifying` DELETE |
| Non-cascade COMMUNITY evidence | PostGIS IT asserts spot/history/outbox retained |
| Idempotency residue purge | **Not** implemented; expire via existing TTL / future sweeper |
| Mobile/Web deletion UI | Out of scope |
| Account erasure saga | Out of scope |

---

## Document control

| Field | Value |
|---|---|
| Authors | Engineering (S1-P0-05) |
| Reviewers required | Product, Security, Legal (for 뿯½25), Backend Architecture |
| Related | `docs/planning/S1-P0-05-DELETION-IMPLEMENTATION-PLAN.md`, Sprint backlog S1-P0-07 / S1-P1-04, PRIV-001 |
