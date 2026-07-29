# WP-07 Engineering Specification -- Mobile Application Foundation & Sprint 01 Closure

> **Status:** WP-07.1–WP-07.3 **complete** on branch `decision`; WP-07.4–WP-07.5 **deferred / operator-gated**
> **Derived from:** repository state as of 2026-07-29
> **Prerequisite:** WP-05 COMPLETE, WP-06 (through 06.2B.2) COMPLETE
> **Does not:** reopen WP-05, reopen WP-06, claim production readiness, implement deployment automation

## WP-07 package status (2026-07-29)

| Package | Status | Evidence |
|---------|--------|----------|
| **WP-07.1** Mobile foundation closure | **Complete** | `4d76c62` — `test(mobile-v2): close WP-07.1 upload abort coverage`; formal closure in `frontend/architecture/sprint-3/WP-07-MOBILE.md` §9 |
| **WP-07.2** Web ParkingSession parity | **Complete** | Web session mutations, UI, cache isolation, and focused tests in `frontend/apps/web` (start, complete, confirm, cancel, history delete, pagination) |
| **WP-07.3** `ParkingHistoryDeleted` producer + analytics consumer | **Complete** | `0a70b03` — `feat(parking): publish parking history deleted lifecycle events`; `d482bcc` — `feat(analytics): consume parking history deleted lifecycle event` |
| **WP-07.4** Mobile release signing & crash verification | **Deferred / operator-gated** | Requires operator-provisioned signing keystore and device evidence (S1-P1-03 / R26) |
| **WP-07.5** Hosted beta deploy gate (R27) | **Deferred / operator-gated** | Requires Azure VM SSH or self-hosted runner; smoke exit 0 not yet recorded |

WP-07 engineering packages **07.1–07.3** are closed in source. **07.4** and **07.5** remain open by design.

---

## 1. Executive Summary

WP-07 is the correct next engineering milestone because the repository evidence shows:

1. **WP-05** (Decision Intelligence Shadow Stack) -- all 15 sub-packages complete, all shadow engines disabled by default, governance tests enforce kill-switch defaults.
2. **WP-06** (Operational Platform) -- 06.1 governance current, 06.2B.2 final-state evidence technically complete with `SIGNOFF_REQUIRED`. WP-06.3-06.5 (deployment automation, secrets hardening, production PRR) are human/infrastructure-gated -- not engineering work.
3. **WP-07 Mobile Foundation (WP-07.1)** -- formally closed in `frontend/architecture/sprint-3/WP-07-MOBILE.md` §9 (2026-07-29, commit `4d76c62`).
4. **Sprint 01 Parking Session** -- P0 tasks and WP-07.2 Web parity plus WP-07.3 deletion analytics (`R22` **PASS**) are complete in source on branch `decision`. Release signing (R26), hosted deploy smoke (R27), optional reminder, and account erasure remain open.

WP-07 therefore consolidates: **(A)** formal closure of the mobile-v2 foundation, **(B)** Sprint 01 P1 completion to close the parking session feature end-to-end, and **(C)** hosted beta deploy gate for Sprint 01. These are the repository's own declared remaining work items -- not invented scope.

---

## 2. Current Repository State

### Completed Work

| Package | Status |
|---------|--------|
| WP-05.1-05.15 | Complete -- shadow decision, trust, reward, fraud, exposure, calibration engines |
| WP-06.1 | Current -- operational readiness governance |
| WP-06.2 through 06.2B.2 | Technically complete -- staging verification, backup/restore, evidence |
| WP-07.1 Mobile foundation | **Complete** -- `4d76c62`; guardrails + upload abort test |
| WP-07.2 Web ParkingSession parity | **Complete** -- lifecycle UI, mutations, cache, tests in `frontend/apps/web` |
| WP-07.3 Deletion analytics | **Complete** -- `0a70b03` producer, `d482bcc` consumer; R22 **PASS** |
| Sprint 01 P0 (S1-P0-01-13) | Complete in source -- API client, active session, start/timer/end/cancel, events, analytics, history, deletion, navigation, smoke |

### Remaining Roadmap (repository-defined)

| Item | Status | Type |
|------|--------|------|
| WP-06.3 Deployment Automation | NOT_ELIGIBLE -- blocked on human sign-off | Human/infra gate |
| WP-06.4 Secrets Hardening | Not started | Infra gate |
| WP-06.5 Production Launch PRR | Not started | Human gate |
| WP-07.4 Release signing & crash | Deferred / operator-gated | Operator gate |
| WP-07.5 Hosted beta deploy (R27) | Deferred / operator-gated | Operator gate |
| Sprint 01 R26, R27 | PARTIAL / FAIL | Operator gate |
| Sprint 01 optional P1 (reminder, account erasure) | Not started / deferred | Product / platform |

### Repository-Backed Technical Debt

| ID | Debt | Source |
|----|------|--------|
| WP-07-8.3 | Legacy `apps/mobile` retirement | `WP-07-MOBILE.md` section 8 (out of WP-07 scope) |
| S1-R26 | Mobile release/signing/crash | `SPRINT-01-COMPLETION-BACKLOG.md` (WP-07.4) |
| S1-R27 | Hosted deploy + smoke PASS | `SPRINT-01-COMPLETION-BACKLOG.md` (WP-07.5) |
| AR-01 | Notification fan-out TODO | `KNOWN-ISSUES.md` |
| ST-02 | Gateway per-route timeouts BASELINING_REQUIRED | `KNOWN-ISSUES.md` |

### Future Milestones (not WP-07)

- Shadow engine policy activation / authority graduation (post-calibration, requires separate operational runbook)
- Public production launch (requires WP-06.3-06.5 + PP-01-06 closure)
- Kubernetes / multi-region (deferred per production-readiness.md)

---

## 3. Scope

### Included

1. **WP-07.1** -- Formal Mobile Foundation Closure (remaining production debt from `WP-07-MOBILE.md` section 8)
2. **WP-07.2** -- Web ParkingSession Parity (S1-P1-01)
3. **WP-07.3** -- ParkingSession Deletion Analytics Event (S1-R22 / S1-DEL-08)
4. **WP-07.4** -- Mobile Release Signing & Crash Verification (S1-P1-03)
5. **WP-07.5** -- Hosted Beta Deploy Gate for Sprint 01 (S1-P0-13 completion + R27)

### Excluded

- WP-06.3-06.5 (deployment automation, secrets hardening, production PRR) -- human/infra gates
- Backend schema changes to ParkingSession domain (V15 is stable)
- Decision Engine authority activation or canary percentage changes
- Shadow engine default enablement changes
- New microservices
- Store publication (App Store / Play Store)
- Background location or offline mutation queues (deferred per S1-D-01)
- Account erasure saga (S1-P1-04 -- requires platform contract not yet defined)

### Deferred

- S1-P1-02 (optional parking session reminder) -- requires Product decision on reminder semantics
- S1-P1-04 (account erasure) -- requires platform erasure contract
- S1-D-01 (offline parking start queue) -- explicitly deferred per ADR
- S1-D-02 (ParkingSession exposure flag) -- operational hardening, not required
- Legacy `apps/mobile` deletion -- blocked until `mobile-v2` is formally canonical

---

## 4. Objectives

### Business Objectives

- Close the Sprint 01 parking session feature across both Web and mobile-v2 platforms
- Achieve a deployable state for hosted beta with ParkingSession lifecycle fully functional
- Produce a release-signed mobile artifact suitable for closed beta distribution

### Technical Objectives

- Formally close WP-07 mobile foundation with all section 8 debt addressed
- Implement Web ParkingSession UI using the shared API client (no direct HTTP calls)
- Emit `parking_history_deleted` lifecycle event to close the analytics event contract
- Produce release-signed APK/AAB with crash reporting configured

### Operational Objectives

- Deploy Sprint 01 commits to azure-hosted-beta and achieve smoke exit 0
- Validate deletion endpoints return 204 (not the previously observed 500) on hosted
- Produce post-deploy evidence artifacts for R27

---

## 5. Architecture

### Services Affected

| Service | Change Type |
|---------|-------------|
| `parking-service` | **Complete (WP-07.3)** — `ParkingHistoryDeleted` outbox on single/bulk delete (`0a70b03`) |
| `analytics-service` | **Complete (WP-07.3)** — consume `ParkingHistoryDeleted`, append-only deletion metric (`d482bcc`) |
| `frontend/apps/web` | **Complete (WP-07.2)** — ParkingSession keys, query-options, hooks, pages, tests |
| `frontend/apps/mobile-v2` | **Complete (WP-07.1)** — upload abort test closure (`4d76c62`); signal support for remaining SDK methods per `WP-07-MOBILE.md` |
| `frontend/packages/api-client` | None -- ParkingSession methods already implemented (S1-P0-01) |

### Data Flow (ParkingSession Deletion Event -- WP-07.3)

```
User deletes session/history
  -> ParkingSessionService.deleteSession() / deleteHistory()
    -> Append ParkingHistoryDeleted to outbox (same TX)
      -> ParkingOutboxRelay polls -> Kafka parkio.parking.session
        -> analytics-service ParkingSessionEventsKafkaConsumer
          -> inbox tryClaim -> ingest deletion metric
```

### Events

| Event | Topic | Key | New? |
|-------|-------|-----|------|
| `ParkingHistoryDeleted` | `parkio.parking.session` | `aggregateId` (`sessionId` single, `userId` bulk) | Yes (WP-07.3) |

Existing events unchanged: `ParkingSessionStarted`, `ParkingSessionCompleted`, `ParkingSessionCancelled`.

### REST APIs

No new endpoints. Existing endpoints unchanged:

- `DELETE /api/v1/parking/sessions/{sessionId}` -- already implemented (S1-P0-07)
- `DELETE /api/v1/parking/sessions/history` -- already implemented (S1-P0-07)

### Kafka Topics

No new topics. `parkio.parking.session` gains one new event type (`ParkingHistoryDeleted`).

### Background Jobs

No new background jobs. No scheduler default changes.

### Database Ownership

No new tables. No new migrations. The deletion event uses the existing `outbox_events` table.

### State Transitions

No changes to `ParkingSpotStatus` or `ParkingSessionStatus` state machines.

### Authority Boundaries

No changes. Decision authority remains `enabled:false`, canary 0%.

---

## 6. ADR Impact

| ADR | Impact |
|-----|--------|
| `docs/architecture/adr/ADR-WP05-decision-engine-placement.md` | None -- no decision engine changes |
| `docs/architecture/PARKING-SESSION-DELETION-PRIVACY-DECISION.md` | Referenced -- deletion event follows the accepted privacy semantics |
| `docs/architecture/PARKING-SESSION-OFFLINE-DRAFT-DECISION.md` | Referenced -- Web parity follows online-only policy |

No new ADRs required. WP-07 implements decisions already recorded.

---

## 7. Risks

### Technical

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Web ParkingSession UI introduces ad-hoc query keys | Low | Must use canonical `keys.ts` pattern from mobile-v2; guardrail enforces |
| Deletion event breaks analytics consumer | Low | Consumer already handles unknown event types via DLT; contract test required |

### Operational

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Azure hosted-beta deploy requires SSH access unavailable to engineer | High | Operator gate -- documented as dependency, not engineering blocker |
| Release signing requires keystore not in repository | Medium | Documented in S1-P1-03; operator must provision |

### Migration

None -- no Flyway migrations in WP-07.

### Security

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Deletion event leaks PII | Low | Payload must be privacy-minimized (no coords, no idempotency keys) per S1-P0-08 pattern |
| Release APK signed with debug key | Medium | S1-P1-03 definition of done requires CN != Android Debug verification |

### Performance

No performance risks -- no new background jobs, no query pattern changes.

### Scalability

No scalability risks -- event volume proportional to existing deletion rate.

### Rollback

| Component | Rollback Strategy |
|-----------|-------------------|
| Deletion event (parking-service) | Analytics consumer ignores unknown events via DLT; removing producer is backward-compatible |
| Web ParkingSession UI | Feature is additive; removal leaves Web at current state |
| Mobile signal support | Non-breaking improvement; revert is safe |

---

## 8. Dependencies

### Internal

| Dependency | Required By |
|-----------|-------------|
| S1-P0-01 (shared API client session methods) | WP-07.2 (Web parity) |
| S1-P0-05 (deletion privacy decision) | WP-07.3 (deletion event) |
| S1-P0-07 (deletion backend) | WP-07.3 (event producer) |
| S1-P0-08 (lifecycle event pattern) | WP-07.3 (event wire format) |
| S1-P0-09 (analytics consumer pattern) | WP-07.3 (consumer) |

### External

| Dependency | Required By |
|-----------|-------------|
| Azure VM SSH access / self-hosted runner | WP-07.5 (deploy gate) |
| Release signing keystore | WP-07.4 (release build) |

### Infrastructure

None beyond existing hosted-beta VPS.

### Business

| Decision | Required By |
|----------|-------------|
| Product acceptance of deletion analytics scope | WP-07.3 (per S1-DEL-08 backlog note) |

---

## 9. Implementation Order

### WP-07.1 -- Mobile Foundation Closure

**Goal:** Address remaining production debt from `WP-07-MOBILE.md` section 8 and formally close the mobile foundation work package.

**Inputs:** `WP-07-MOBILE.md` section 8 debt list, existing `mobile-v2` codebase.

**Outputs:** Signal support for remaining SDK methods, upload abort unit test, updated `WP-07-MOBILE.md` with closure statement.

**Dependencies:** None -- independent of other WP-07 packages.

**Acceptance Criteria:**
- All SDK read methods in `@parkio/api-client` that mobile-v2 uses accept `{ signal?: AbortSignal }`
- Upload abort has a focused unit test beyond the existing draft-upload cleanup test
- `WP-07-MOBILE.md` updated with formal closure statement and date
- `pnpm guardrails:wp07` and `pnpm --filter @parkio/mobile-v2 test:wp07` pass
- mobile-v2 typecheck, lint, and full test suite pass

**Rollback Strategy:** Revert signal additions -- callers continue to work without signal.

#### Scope

- **Files likely affected:**
  - `frontend/packages/api-client/src/*.ts` (add signal to remaining methods)
  - `frontend/apps/mobile-v2/src/features/share/__tests__/` (upload abort test)
  - `frontend/architecture/sprint-3/WP-07-MOBILE.md` (closure statement)
- **Services affected:** None (frontend only)
- **Kafka topics:** None
- **Database tables:** None
- **Tests required:** Unit -- signal forwarding, upload abort
- **Evidence required:** `pnpm guardrails:wp07` exit 0, `pnpm --filter @parkio/mobile-v2 test` exit 0
- **Governance required:** `pnpm guardrails` (full) pass
- **Completion criteria:** WP-07-MOBILE.md declares formal closure

---

### WP-07.2 -- Web ParkingSession Parity

**Goal:** Implement Sprint 01 ParkingSession lifecycle in the Web client using the shared API facade.

**Inputs:** Shared `@parkio/api-client` session methods (S1-P0-01), canonical query-options patterns, deletion privacy decision (S1-P0-05).

**Outputs:** Web ParkingSession keys, query-options, hooks, route/page, cache cleanup, history, deletion UI.

**Dependencies:** S1-P0-01 (complete).

**Acceptance Criteria:**
- Web supports active session restoration on page load via shared API client
- Active session clears on logout / user switch via existing `SessionQueryCacheSync`
- Timer, start, complete, cancel, navigation/share, history, deletion match mobile-v2 behavior
- No direct HTTP calls -- all through `@parkio/api-client` session methods
- No ad-hoc query keys -- canonical `keys.ts` pattern
- Route ownership in the canonical route manifest
- Component, query, mutation, and cache isolation tests pass
- Web typecheck, lint, and test suite pass

**Rollback Strategy:** Remove ParkingSession route and query-options -- Web returns to pre-Sprint-01 state without session support.

#### Scope

- **Files likely affected:**
  - `frontend/apps/web/src/data/keys.ts` (add session keys)
  - `frontend/apps/web/src/data/query-options/parking.ts` (add session query-options)
  - `frontend/apps/web/src/pages/` (new session route/page or integration into existing map)
  - `frontend/apps/web/src/data/sessionQueryCache.ts` (add session roots to cleanup)
  - `frontend/apps/web/src/components/` (session UI components)
- **Services affected:** None (frontend only)
- **Kafka topics:** None
- **Database tables:** None
- **Tests required:** Unit -- query-options, cache isolation, component; Integration -- route rendering
- **Evidence required:** `pnpm --filter @parkio/web test` exit 0, `pnpm --filter @parkio/web typecheck` exit 0
- **Governance required:** `pnpm guardrails` pass, no cross-ownership violations
- **Completion criteria:** Web renders active session, supports full lifecycle, and cleans up on auth change

---

### WP-07.3 -- ParkingSession Deletion Analytics Event

**Goal:** Emit `ParkingHistoryDeleted` lifecycle event on session deletion and consume it in analytics-service.

**Inputs:** S1-P0-08 event pattern, S1-P0-09 consumer pattern, S1-P0-05 privacy decision.

**Outputs:** `ParkingHistoryDeleted` event type, analytics consumer handler, deletion counter metrics.

**Dependencies:** S1-P0-07 (deletion backend, complete), S1-P0-08 (event pattern, complete), S1-P0-09 (analytics consumer, complete). Requires Product acceptance of deletion analytics scope.

**Status:** **Complete** (`0a70b03`, `d482bcc`).

**Acceptance Criteria (verified):**
- Single deletion emits one `ParkingHistoryDeleted` event with `aggregateId` = `sessionId`, scope `SINGLE_TERMINAL_SESSION`, and `deletedCount` = 1
- Bulk deletion emits one event with `aggregateId` = `userId`, scope `ALL_TERMINAL_HISTORY`, and `deletedCount` = rows removed (`>= 1`)
- No event on zero-delete or failed ACTIVE deletion
- Event payload is privacy-minimized: no coordinates, no idempotency keys
- Analytics consumer ingests deletion events and increments `parking_session_history_deleted` counter
- Malformed/unsupported events follow existing DLT path
- HTTP idempotency replay of a delete that already succeeded writes no new event
- Unit and contract tests for producer and consumer
- Existing parking-service and analytics-service test suites pass

**Rollback Strategy:** Remove event emission from deletion path. Analytics consumer ignores unknown event types. No data loss.

#### Scope

- **Files likely affected:**
  - `services/parking-service/src/main/java/com/parkio/parking/application/ParkingSessionService.java`
  - `services/parking-service/src/main/java/com/parkio/parking/infrastructure/messaging/` (event type)
  - `services/analytics-service/src/main/java/com/parkio/analytics/infrastructure/messaging/ParkingSessionEventsKafkaConsumer.java`
  - `services/analytics-service/src/main/java/com/parkio/analytics/application/` (ingestion handler)
- **Services affected:** parking-service, analytics-service
- **Kafka topics:** `parkio.parking.session` (existing -- new event type)
- **Database tables:** `outbox_events` (parking-service), `inbox_events` (analytics-service) -- existing
- **Tests required:** Unit -- event creation, privacy fields; Contract -- wire format; Integration -- consumer ingestion
- **Evidence required:** `./gradlew :parking-service:test` exit 0, `./gradlew :analytics-service:test` exit 0
- **Governance required:** Event contract documented in `docs/architecture/event-contracts.md`
- **Completion criteria:** R22 status changes from FAIL to PASS

---

### WP-07.4 -- Mobile Release Signing & Crash Verification

**Goal:** Produce a release-signed mobile artifact with crash reporting configured.

**Inputs:** mobile-v2 codebase, signing keystore (operator-provisioned).

**Outputs:** Release-signed APK/AAB, crash reporter integration, device verification evidence.

**Dependencies:** WP-07.1 (mobile foundation closure). Requires signing keystore not in repository.

**Acceptance Criteria:**
- APK/AAB signature is inspected and CN != "Android Debug"
- Embedded API endpoint and profile values verified from built artifact
- Crash reporter configured with release-symbol mapping
- Non-fatal and crash events reach configured provider without PII/coordinates
- Store/release credentials never enter Git or logs
- Physical device or production-shaped emulator run records ParkingSession checklist results
- Handoff records artifact hash, version, commit, signer, and verification date

**Rollback Strategy:** Continue distributing debug-signed builds for beta. No production impact.

#### Scope

- **Files likely affected:**
  - `frontend/apps/mobile-v2/android/` (signing config)
  - `frontend/apps/mobile-v2/app.json` or `app.config.js` (crash reporter plugin)
  - `frontend/apps/mobile-v2/scripts/` (release build scripts)
- **Services affected:** None (mobile only)
- **Kafka topics:** None
- **Database tables:** None
- **Tests required:** Build verification -- signature inspection, embedded config validation
- **Evidence required:** Signed artifact hash, device test report, crash reporter delivery proof
- **Governance required:** Signing credentials audit (not in Git)
- **Completion criteria:** R26 status changes from PARTIAL to PASS

---

### WP-07.5 -- Hosted Beta Deploy Gate

**Goal:** Deploy Sprint 01 commits to azure-hosted-beta and achieve ParkingSession smoke exit 0.

**Inputs:** Committed source on `master`, `scripts/smoke-parking-session-hosted-beta.sh`, operator Azure access.

**Outputs:** Immutable image deployed, smoke evidence, R27 PASS.

**Dependencies:** WP-07.3 (deletion event -- so deletion endpoints return 204 not 500 on hosted). Requires Azure VM SSH access or self-hosted runner.

**Acceptance Criteria:**
- Immutable `sha-<gitsha>` images deployed via `scripts/deploy-hosted-beta.sh`
- `scripts/smoke-parking-session-hosted-beta.sh` exits 0
- Deletion endpoints return 204 (not 500)
- Lifecycle start/active/conflict/complete/cancel/history all PASS
- Post-deploy evidence recorded under `docs/evidence/sprint-01/`

**Rollback Strategy:** Redeploy previous known-good image SHA. ParkingSession feature is additive -- previous image simply lacks it.

#### Scope

- **Files likely affected:** None (deploy uses existing scripts and committed source)
- **Services affected:** All (full stack redeploy)
- **Kafka topics:** None changed
- **Database tables:** None changed (Flyway auto-migrates on startup)
- **Tests required:** Hosted smoke suite
- **Evidence required:** Smoke output log, HTTP response samples, deploy manifest
- **Governance required:** Preflight script (`scripts/preflight-hosted-beta.sh`) exit 0
- **Completion criteria:** R27 status changes from FAIL to PASS

---

## 10. Testing Strategy

| Category | WP-07.1 | WP-07.2 | WP-07.3 | WP-07.4 | WP-07.5 |
|----------|---------|---------|---------|---------|---------|
| Unit | Signal forwarding, upload abort | Query-options, cache, components | Event creation, privacy, consumer | -- | -- |
| Integration | -- | Route rendering | Consumer ingestion (Testcontainers) | -- | -- |
| Testcontainers | -- | -- | Kafka + Postgres round-trip | -- | -- |
| Contract | -- | -- | Wire format validation | -- | -- |
| Replay | -- | -- | Idempotent delete produces no duplicate event | -- | -- |
| Restore | -- | -- | -- | -- | Flyway auto-migrate on deploy |
| Performance | -- | -- | -- | -- | -- |
| Security | -- | -- | Privacy field assertions | Signing CN check | -- |
| End-to-end | -- | -- | -- | Device checklist | Hosted smoke |
| Governance | `pnpm guardrails:wp07` | `pnpm guardrails` | Event contract doc | Credential audit | Preflight script |
| Operational | -- | -- | -- | Crash delivery proof | Deploy + smoke evidence |

---

## 11. CI/CD Impact

### New Workflows

None required. Existing workflows cover all WP-07 packages:
- `backend-ci.yml` -- parking-service and analytics-service tests
- `frontend-ci.yml` -- Web tests and typecheck
- `mobile-ci.yml` -- mobile-v2 tests
- `hosted-beta-deploy.yml` -- manual deploy (WP-07.5)

### New Gates

None required.

### New Evidence

- WP-07.5 produces post-deploy smoke evidence under `docs/evidence/sprint-01/`
- WP-07.4 produces signed artifact verification record

### New Release Checks

None beyond existing `release.yml` draft + `PUBLISH_IMAGES` gate.

---

## 12. Operational Readiness

### Monitoring

No new monitoring required. Deletion metrics use existing Prometheus/Grafana patterns.

### Metrics

| Metric | Service | New? |
|--------|---------|------|
| `parkio_analytics_events_ingested_total{event_type="parking_session_history_deleted"}` | analytics-service | Yes (WP-07.3) |

### Tracing

No new tracing. Deletion events inherit existing `trace_id` propagation via outbox `EventEnvelope`.

### Alerting

No new alerts required.

### Runbooks

No new runbooks. Existing `docs/operations/runbooks/` covers Kafka consumer lag, outbox relay, and DLT.

### Recovery

Standard recovery: redeploy previous image, Kafka consumer replays from last committed offset.

---

## 13. Security Review

### Authentication

No changes. All ParkingSession endpoints already require valid JWT via gateway.

### Authorization

No changes. Owner-scoped queries and mutations already enforce `userId` predicates.

### Secrets

WP-07.4 introduces a signing keystore. It must:
- Not enter Git or CI logs
- Be provisioned by operator
- Be referenced via environment variable or Gradle property

### Rate Limits

No changes. Existing gateway rate limits cover all affected endpoints.

### Replay Attacks

No new replay surface. Deletion is naturally idempotent (delete of non-existent row returns 204).

### Data Ownership

`ParkingHistoryDeleted` event payload must not contain coordinates, idempotency keys, or session content -- only `userId`, `sessionId`, `deletedAt`, and `deletionType` (single/bulk).

### Least Privilege

No new permissions. Analytics consumer already has read access to `parkio.parking.session` topic.

---

## 14. Migration Review

### Will Flyway change?

**No.** WP-07 introduces no new migrations. All required tables exist (V15 sessions, V7 outbox, V8 inbox).

### Backward compatibility?

Yes. The `ParkingHistoryDeleted` event is additive. Analytics consumer's existing DLT path handles unknown event types.

### Zero-downtime?

Yes. No schema changes, no breaking API changes, no consumer group rebalance triggers.

### Rollback?

Remove event emission from deletion path. Consumer ignores unknown types. No data migration needed.

---

## 15. Acceptance Criteria

| ID | Criterion | Verification |
|----|-----------|--------------|
| AC-1 | `WP-07-MOBILE.md` declares formal closure with date | File inspection |
| AC-2 | All `@parkio/api-client` read methods used by mobile-v2 accept `{ signal }` | `pnpm guardrails:wp07` exit 0 |
| AC-3 | Upload abort has a dedicated unit test | `pnpm --filter @parkio/mobile-v2 test` includes abort test |
| AC-4 | Web renders active ParkingSession and supports full lifecycle | Manual verification + component tests |
| AC-5 | Web ParkingSession uses canonical keys, not ad-hoc strings | Guardrail check |
| AC-6 | Web session cache clears on logout/user switch | Unit test |
| AC-7 | `ParkingHistoryDeleted` event emitted on single and bulk delete | PostGIS IT + contract test |
| AC-8 | Deletion event payload contains no coordinates or idempotency keys | Unit test assertion |
| AC-9 | Analytics consumer ingests deletion events and increments counter | Unit + Testcontainers test |
| AC-10 | Idempotent delete replay produces no duplicate event | Unit test |
| AC-11 | Release APK/AAB CN != "Android Debug" | Artifact signature inspection |
| AC-12 | Crash reporter delivers non-fatal without PII | Device test evidence |
| AC-13 | `scripts/smoke-parking-session-hosted-beta.sh` exits 0 on hosted | Post-deploy smoke log |
| AC-14 | Deletion endpoints return 204 on hosted (not 500) | Smoke evidence |
| AC-15 | R22, R26, R27 statuses updated from FAIL/PARTIAL to PASS | `SPRINT-01-COMPLETION-BACKLOG.md` update |

---

## 16. Out of Scope

WP-07 will NOT:

- Reopen WP-05 or change any shadow engine defaults
- Reopen WP-06 or add verification work
- Activate Decision Engine authority or change canary percentage
- Add new Flyway migrations
- Add new microservices
- Implement ParkingSession reminder (S1-P1-02) -- requires Product decision
- Implement account erasure (S1-P1-04) -- requires platform contract
- Implement offline mutation queue (S1-D-01) -- explicitly deferred
- Implement ParkingSession exposure flag (S1-D-02) -- deferred
- Delete legacy `apps/mobile` -- blocked until formal `mobile-v2` canonical declaration
- Publish to App Store / Play Store
- Implement deployment automation (WP-06.3)
- Claim production readiness
- Add Kubernetes / Helm charts
- Change gateway timeout baselines

---

## 17. Final Recommendation

WP-07 is the correct next engineering milestone because:

1. **It is repository-derived.** The repository defines WP-07 as Mobile Application Foundation (`frontend/architecture/sprint-3/WP-07-MOBILE.md`) and Sprint 01 completion backlog (`docs/planning/SPRINT-01-COMPLETION-BACKLOG.md`) -- both with explicit unclosed items.

2. **It does not conflict with WP-06.3-06.5.** Those are human/infrastructure gates (deployment automation, secrets, PRR), not engineering work. WP-07 engineering can proceed in parallel with human sign-off and operator provisioning.

3. **It closes the product feature.** Sprint 01 Parking Session lifecycle, Web parity, and deletion analytics (`R22`) are implemented on branch `decision`. Release signing (WP-07.4) and hosted deploy gate (WP-07.5 / R27) remain operator-gated.

4. **It has no infrastructure prerequisites.** WP-07.1-07.3 require only the existing codebase. WP-07.4 requires a signing keystore (operator-provisioned). WP-07.5 requires Azure SSH (operator-provisioned). Neither requires managed cloud services, IaC, or new CI infrastructure.

5. **It does not expand scope.** Every item in WP-07 is already documented in the repository as remaining work. No new features, no new architecture, no new services.

The recommended execution order is: **WP-07.1 and WP-07.3** (parallel, independent) **-> WP-07.2** (can also start independently) **-> WP-07.4** (requires 07.1) **-> WP-07.5** (requires 07.3 for deletion endpoint validation on hosted).
