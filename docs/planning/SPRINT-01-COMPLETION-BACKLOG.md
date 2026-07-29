# Sprint 01 Parking Session Completion Backlog

**Derived from:** `docs/audit/SPRINT-01-PARKING-SESSION-AUDIT.md` and
`docs/audit/SPRINT-01-PARKING-SESSION-GAP-MATRIX.md`
**Baseline:** `master` / `a265463a6c9a1961e25b73479dce3fefd1b4b3d8`
**Backlog rule:** only gaps proven in the current repository are included

## 1. Do not rebuild

The following already exist and must be extended, not recreated:

- `ParkingSession` aggregate and `ParkingSessionService`.
- Migration `V15__create_parking_sessions.sql`.
- Database one-ACTIVE invariant `uq_parking_sessions_active_user`.
- Generic `IdempotencyService` and migration `V10__create_idempotency_records.sql`.
- Start, active, complete, cancel, and deterministic-history endpoints.
- Owner-safe repository queries, gateway authentication propagation, direct-service guard, and
  no-store response policy.
- Shared ParkingSession TypeScript types and Zod schemas.
- Canonical Web/mobile-v2 SDK, QueryClient, key-factory, auth, and cache-isolation architecture.
- Real PostgreSQL/PostGIS integration suites.
- Documented coordinated rollback strategy.

## 2. Recommended dependency sequence

```text
Decisions: privacy + offline + event data policy
  -> shared API-client ParkingSession methods
  -> mobile-v2 active restoration + claim convergence
  -> mobile-v2 start/timer/end/cancel/navigation/share
  -> backend deletion + authoritative lifecycle events
  -> analytics ingestion + history UI
  -> Web parity
  -> hosted smoke + canonical release/crash closure
  -> optional reminder
```

Privacy and offline decisions may be written in parallel with the shared-client task. Backend
deletion must not be implemented until the derived-observation decision is accepted.

## 3. P0 — Sprint 1 blockers

### S1-P0-01 — Add typed ParkingSession operations to the shared API client

**Status:** COMPLETE (implemented on top of baseline `a265463`)
**Layers:** Web foundation, mobile-v2 foundation
**Closes/unlocks:** dependency for R11-R16 and client-side R8-R9
**Prior evidence of gap:** `frontend/packages/api-client/src/parking.ts` had no session methods even
though `frontend/packages/types/src/parking.ts` and
`frontend/packages/validation/src/contracts/parking.ts` already contain the contracts.

**Implementation delivered:**

- Extended `createParkingApi` in `frontend/packages/api-client/src/parking.ts` with:
  `startParkingSession`, `getActiveParkingSession`, `completeParkingSession`,
  `cancelParkingSession`, and `getParkingSessionHistory`.
- Preserved the five established backend paths and caller-provided `Idempotency-Key` headers.
- Forwarded `AbortSignal` on active/history reads.
- Converted active HTTP 204 to `null` (no fabricated session).
- Validated responses with existing `@parkio/validation` schemas via `ContractValidationError`.
- Focused contract tests in `frontend/packages/api-client/src/parking.session.test.ts`.

**Definition of Done (verified):**

- All five methods have exact shared TypeScript return types.
- Start/complete/cancel send the caller-provided idempotency key.
- Active 200, active 204, history cursor/size, contract validation, and cancellation have focused
  tests.
- Existing spot methods and their tests remain unchanged and green.
- `pnpm --filter @parkio/api-client test` / `typecheck` / `lint` pass.
- Related `@parkio/types` typecheck and `@parkio/validation` test/typecheck pass.
- Public-export inventory check passes (no new named exports required).
- No backend controller, migration, deployment, or route shape changes.

**Dependencies:** none.

### S1-P0-02 — Restore and expose the active session in canonical mobile-v2

**Status:** COMPLETE (implemented on top of baseline `a265463` + S1-P0-01)
**Layers:** mobile-v2
**Closes/unlocks:** R11; prerequisite for R12-R15
**Prior evidence of gap:** `frontend/apps/mobile-v2/src/data/keys.ts`, query options, and
`app/_layout.tsx` had no ParkingSession ownership; `SpotActions.claimMutation` invalidated only
spot caches.

**Implementation landed:**

- Query keys: `parkingKeys.sessionsRoot()` / `parkingKeys.activeSession()` in `src/data/keys.ts`.
- Query option: `activeParkingSessionQueryOptions()` → `parkingApi.getActiveParkingSession(signal)`.
- Hook: `useActiveParkingSession`.
- UI: `ActiveParkingSessionBanner` mounted in existing map top overlay
  (`app/(main)/(tabs)/map.tsx`), subordinate to search chrome / Smart Return banner.
- Community claim: `SpotActions` also invalidates `parkingKeys.activeSession()`.
- Cache isolation: `parkingKeys.sessionsRoot()` added to `USER_SESSION_QUERY_ROOTS`.
- Restoration/reconnect/foreground: existing QueryClient policy
  (`refetchOnMount` / `refetchOnReconnect` / `refetchOnWindowFocus`) + `QueryProvider`
  AppState→`focusManager`; no new polling loop.

**Design preservation evidence:**

- Placement: map floating chrome strip beside existing `SmartReturnBanner` (priority #1/#2).
- Reused: `Glass`, `AppText`, `Button`, `Chip`, `Skeleton`, MaterialCommunityIcons,
  `formatClock`, theme `colors` (`primary`, `primaryFixed`, `onSurfaceVariant`, `tertiary`,
  `surfaceContainer2`), Smart Return row density (gap 10 / padding 12×9 / 34 icon bubble /
  `radius={16}`), `useT` localization (TR+EN `parkingSession.*` keys), a11y roles/labels.
- New visual component: only `ActiveParkingSessionBanner` (same Glass-strip pattern as
  Smart Return; no new card language).
- No new tab, root route, modal, bottom sheet, or navigation redesign.

**Definition of Done:** (all verified for S1-P0-02)

- A backend ACTIVE session appears after cold app restart without a new mutation (query on map
  mount + shared client).
- A 204/null is an empty success (banner returns `null`; not an error).
- A successful community claim reveals the created `COMMUNITY` session via invalidation/refetch.
- Unauthorized teardown clears the session cache and precise coordinates via existing
  `SessionQueryCacheSync`.
- Network/error state has an explicit retry action.
- Unit/component tests cover 200, 204, remount/query ownership, claim invalidation, retry, and
  logout isolation.
- mobile-v2 typecheck, lint, and tests pass.

**Dependencies:** S1-P0-01.

### S1-P0-03 — Implement mobile-v2 manual “Park ettim” start and location failure UX

**Status:** COMPLETE (implemented on top of S1-P0-01 + S1-P0-02)
**Layers:** mobile-v2
**Closes/unlocks:** R3 client consumption, R15; part of Sprint 1 “Park ettim”
**Prior evidence of gap:** `SpotActions.tsx` only claimed a community spot; generic location UX
existed but no ParkingSession start flow used it.

**Implementation landed:**

- Entry point: `ParkHereStartControl` in Map top overlay under `ActiveParkingSessionBanner`.
- Hook: `useStartParkingSession(location)` calls `parkingApi.startParkingSession({ latitude,
  longitude }, idempotencyKey)` only (no client `ParkingSource`).
- Location: reuses `useLocation` with `canAskAgain` / `getCanAskAgain` + `Linking.openSettings`.
- Idempotency: one in-memory key per submitted attempt; reused on ambiguous network/timeout/
  cancellation; cleared on conclusive rejection, success, successful conflict reconcile, and
  auth identity change.
- Conflict: `ACTIVE_PARKING_SESSION_EXISTS` → active-session refetch; banner shows restored
  session; raw code never rendered.
- Success: `setQueryData(parkingKeys.activeSession(), session)`; start strip hides.
- Design: Glass/AppText/Button/Skeleton/MCI + Smart Return strip density; TR/EN
  `parkingSession.start.*` keys; no new tab/route/modal/sheet.

**Definition of Done:** (verified)

- Granted location starts one session and surfaces it through the existing active banner.
- Denied-can-ask-again, denied-open-settings, provider error, offline, ambiguous, and retry
  states are explicit and tested.
- Double tap and network retry reuse the same key and cannot create duplicate sessions.
- Stable active conflict restores the existing session.
- No precise coordinates are logged or rendered.
- mobile-v2 typecheck, lint, and tests pass.

**Dependencies:** S1-P0-01, S1-P0-02.

### S1-P0-04 — Add safe elapsed timer, “Ayrıldım”, and cancel

**Status:** COMPLETE (implemented on top of S1-P0-01 + S1-P0-02 + S1-P0-03)
**Layers:** mobile-v2
**Closes/unlocks:** R12 and client completion of R5-R6
**Prior evidence of gap:** no ParkingSession timer or terminal action existed in mobile-v2.

**Implementation landed:**

- Timer: `elapsedDuration.ts` + `useNowTicker` derive display from server `startedAt` + `Date.now()`;
  clamp invalid/future to `00:00`; ~1s interval + AppState→active recalculation; no AsyncStorage;
  no network polling; uses `formatCountdown` from `src/lib/time.ts`.
- Complete: primary “Ayrıldım” → `parkingApi.completeParkingSession(sessionId, key)` (no confirm).
- Cancel: secondary “İptal” → existing `ConfirmModal` → `parkingApi.cancelParkingSession(sessionId, key)`.
- Mutual exclusion: shared `inFlightRef` + operation-scoped disable; separate complete/cancel keys.
- Ambiguous transport: retain op-specific key, refetch active; null → success; same id → retry;
  different id → leave alone and drop attempt.
- Domain: `PARKING_SESSION_NOT_ACTIVE` / `PARKING_SESSION_NOT_FOUND` → active reconcile.
- Success convergence: `setQueryData(parkingKeys.activeSession(), null)` when terminal response is
  non-ACTIVE (set-only; avoids stale refetch race). History invalidation not added (no history UI).
- Identity: attempt state cleared on user/sessionId change; late results gated by auth + sessionId.
- Design: extends existing `ActiveParkingSessionBanner` Glass strip; TR/EN `parkingSession.*` keys;
  no new tab/route/modal framework/bottom sheet.

**Definition of Done:** (verified)

- Elapsed duration renders from `startedAt` and recovers after fake time jumps / foreground.
- Invalid/future `startedAt` never yields NaN/negative/Infinity/raw ISO.
- Complete and cancel call the correct endpoints with distinct caller-owned idempotency keys.
- Ambiguous retries reuse the same op-specific key; complete/cancel cannot run concurrently.
- Success clears the active banner; Park ettim control can reappear.
- Focused tests + mobile-v2 typecheck/lint/tests pass.

**Dependencies:** S1-P0-02.

### S1-P0-05 — Define ParkingSession deletion and derived-observation privacy semantics

**Status:** COMPLETE (decision + implementation plan; no production deletion code)
**Layers:** privacy, backend, analytics
**Closes/unlocks:** R23 (documentation); prerequisite for R8-R9 and R22 implementation
**Prior evidence of gap:** no documented rule for the session row, spot status history,
`ParkingSpotClaimedEvent`, analytics observations, precise location, and backups after a
single/full-history deletion.

**Decision landed:**

- ADR: `docs/architecture/PARKING-SESSION-DELETION-PRIVACY-DECISION.md`
- Plan: `docs/planning/S1-P0-05-DELETION-IMPLEMENTATION-PLAN.md`
- Terminal-only hard delete for product single/history DELETE; ACTIVE → conflict
- COMMUNITY session delete must not erase spots, status history, claim events, or points
- Precise coordinates removed via hard delete; idempotency residue + backup tombstone policy defined
- Account erasure remains async platform saga (PRIV-001 / S1-P1-04); open Product/Legal items listed
- Recommended API: `DELETE /api/v1/parking/sessions/{sessionId}` and `DELETE .../history`

**Definition of Done:** (verified for decision task)

- Source row and known derived records inventoried from repository evidence.
- Single history, full history, and account erasure semantics are explicit.
- Ownership, idempotency, failure/retry, and privacy invariants are specified.
- Unresolved Product/Legal/Security approvals are listed (not silently invented).
- Implementation plan is atomic and actionable; **no** deletion endpoints claimed implemented.
- R8/R9/R22 remain FAIL until code ships.

**Dependencies:** none.

### S1-P0-06 — Record a ParkingSession-specific offline/local-draft decision

**Status:** COMPLETE (decision + implementation plan; no durable offline code)
**Layers:** mobile-v2, privacy
**Closes/unlocks:** R16 (documented online-only policy)
**Prior evidence of gap:** WP-07 generically deferred offline mutation queues; no ParkingSession-specific
decision covered start, key persistence, stale drafts, logout, or reconciliation.

**Decision landed:**

- ADR: `docs/architecture/PARKING-SESSION-OFFLINE-DRAFT-DECISION.md`
- Plan: `docs/planning/S1-P0-06-OFFLINE-IMPLEMENTATION-PLAN.md`
- Canonical policy: **Option A** — online-only mutations; no durable ParkingSession drafts
- Precise start coordinates must not be persisted; no auto-replay; no background mutation workers
- Ambiguous attempts: in-memory keys only while authenticated JS lifecycle is alive
- After restart: active-session GET is authoritative; user explicitly retries if needed
- `endedAt` remains server-controlled; S1-D-01 remains deferred
- Hardening tasks S1-OFF-01…07 enforce/copy-test the policy without claiming offline support

**Definition of Done:** (verified for decision task)

- ParkingSession-named decision covers start, complete, cancel, active restore, and claim convergence.
- Offline, ambiguous transport, reconnect, app restart, logout, and user-switch are explicit.
- Precise-location persistence is forbidden for Sprint 1 with purge/isolation rules.
- Future acceptance tests and hardening tasks are listed; share drafts are not treated as session drafts.
- No durable offline implementation is falsely claimed.

**Dependencies:** none.

### S1-P0-07 — Add owner-safe single and full history deletion APIs

**Status:** COMPLETE (parking-service backend; no mobile/Web deletion UI; no account erasure)
**Layers:** backend, privacy
**Closes/unlocks:** R8-R9; prerequisite for R22
**Prior evidence of gap:** no delete method existed in `ParkingSessionRepository`,
`ParkingSessionService`, `ParkingSessionController`, or OpenAPI.

**Implemented:**

- `DELETE /api/v1/parking/sessions/{sessionId}` — owner-scoped hard delete of one terminal row;
  ACTIVE → `409` / `PARKING_SESSION_NOT_TERMINAL`; missing/foreign/already-deleted → opaque `204`.
- `DELETE /api/v1/parking/sessions/history` — bulk hard delete of all owned terminal rows;
  preserves ACTIVE; repeated/empty → `204`.
- JPQL `@Modifying` deletes with `userId` + terminal status predicates (no soft delete / tombstone).
- No cascade into spots, status history, claim outbox, rewards, or events (PostGIS IT evidence).
- Idempotency-Key not required (natural idempotency). Coord-bearing `idempotency_records` residue
  for prior start/complete/cancel responses remains a documented TTL follow-up (no unsafe JSON purge).

**Definition of Done:** (verified)

- Controller, service, repository, OpenAPI annotations, and error contract agree.
- Unit + controller + OpenAPI + PostGIS integration tests cover ownership, ACTIVE conflict,
  history absence after delete, and non-cascade community artifacts.
- Existing start/active/complete/cancel/history/idempotency suites remain green.
- No mobile/Web UI, api-client delete methods, or account-erasure saga shipped in this task.

**Dependencies:** S1-P0-05.

### S1-P0-08 — Produce authoritative ParkingSession lifecycle events

**Status:** COMPLETE (producer/outbox only; no analytics consumer; no deletion events)
**Layers:** backend, analytics (producer side)
**Closes/unlocks:** R17-R19; prerequisite for R22 / S1-P0-09
**Prior evidence of gap:** parking-service emitted parking-spot events only.

**Implemented:**

- Wire types: `ParkingSessionStarted`, `ParkingSessionCompleted`, `ParkingSessionCancelled`
  (PascalCase repository convention; map to product `parking_session_*` names in S1-P0-09).
- Topic: `parkio.parking.session`, key = `sessionId`, envelope version 1.
- Appended from `ParkingSessionService` start/complete/cancel via existing outbox (same TX).
- COMMUNITY claim creates `ParkingSessionStarted` through `startSession` while still emitting
  `ParkingSpotClaimed` on the spot topic (no double-count confusion at producer).
- Privacy-minimized payloads (no coords / idempotency keys / spot FK).
- Docs: `event-contracts.md`, `kafka-transport.md`, `PARKING-SESSION-LIFECYCLE-EVENTS.md`.

**Explicitly deferred in this task:** `parking_history_deleted` / deletion lifecycle events
(remain for a later deletion-analytics task; R22 stays FAIL).
*Status at task completion time; subsequently closed by S1-DEL-08 / WP-07.3.*

**Definition of Done:** (verified)

- One outbox event per successful transition; HTTP idempotency replay writes none.
- Failed/conflict/rollback paths write no session lifecycle event.
- MANUAL vs COMMUNITY distinguishable via `source`.
- Unit, contract, controller, relay, and PostGIS evidence green.

**Dependencies:** S1-P0-05.

### S1-P0-09 — Ingest ParkingSession events in analytics-service

**Status:** COMPLETE (lifecycle ingestion only; no deletion analytics)
**Layers:** analytics
**Closes/unlocks:** R17-R19 end-to-end producer→consumer; prerequisite for measuring session funnel
**Prior evidence of gap:** analytics-service handled spot claim aggregates but no ParkingSession event.

**Implemented:**

- Consumer: `ParkingSessionEventsKafkaConsumer` on `parkio.parking.session`, group `parkio.analytics`.
- Wire → canonical: `ParkingSessionStarted` → `parking_session_started`,
  `ParkingSessionCompleted` → `parking_session_completed`,
  `ParkingSessionCancelled` → `parking_session_cancelled`.
- Metrics: source-split started (`_MANUAL` / `_COMMUNITY` / `_OTHER`) plus terminal COMPLETED/CANCELLED.
- Inbox `tryClaim(eventId)` + `ingest()` TX; duration seconds on terminal events.
- Contract validation (envelope v1, aggregate, status, timestamps) → DLT on failure.
- COMMUNITY start is distinct from `ParkingSpotClaimed` (no double-count).
- Docs: `PARKING-SESSION-ANALYTICS-INGESTION.md`.

**Explicitly deferred:** `parking_history_deleted` / deletion analytics (R22 remains FAIL).
Backlog “four authoritative server events” exceeded producer scope (three lifecycle events only).
*Status at task completion time; subsequently closed by S1-DEL-08 / WP-07.3.*

**Definition of Done:** (verified)

- Each event consumed once under redelivery; counters distinguish source and terminal outcome.
- Malformed/unsupported version follow existing DLT path.
- Unit tests for mapper, validator, consumer, ingestion pass; full analytics-service suite green.

**Dependencies:** S1-P0-08.

### S1-P0-10 — Add return-to-car navigation, location sharing, and client events

**Status:** COMPLETE (mobile-v2 only; no Web; no deletion UI)
**Layers:** mobile-v2, analytics, privacy
**Closes/unlocks:** R13-R14, R20-R21
**Prior evidence of gap:** no ParkingSession maps/share actions or client event names.

**Implemented:**

- `ActiveParkingSessionBanner` compact navigate + share `IconButton`s.
- Validated session `latitude`/`longitude` → iOS Apple Maps / Android geo / OSM HTTPS fallback via Expo Linking.
- Native `Share.share` with localized lead + HTTPS maps link (no drafts).
- Product analytics seam `productAnalytics.ts`: `return_to_car_clicked`,
  `parking_location_shared`, `parking_action_failed` (privacy-safe params only).
- Docs: `docs/architecture/PARKING-SESSION-RETURN-NAVIGATION-SHARING.md`.

**Definition of Done:** (verified)

- Navigate/share use current ACTIVE coords; invalid fail closed; no persistence; no permission ask.
- Events fire after accepted hand-off / sharedAction; dismiss does not count as share.
- Unit/component tests mock Linking/Share and assert privacy-safe parameters.
- Complete/cancel/timer regressions green; typecheck green.

**Dependencies:** S1-P0-02; S1-P0-05 privacy policy.

### S1-P0-11 — Add mobile-v2 history and deletion UI ✅ COMPLETE (2026-07-24)

**Layers:** mobile-v2, privacy, shared api-client
**Closes/unlocks:** client history consumption of R7; mobile deletion UX for R8–R9
**Closed later by S1-DEL-08 / WP-07.3:** R22 (`parking_history_deleted`) — **PASS** on branch `decision`
**Evidence:** Profile-nested history screen, user-scoped infinite query, typed delete methods,
ConfirmModal single/delete-all, ACTIVE preserved, online-only mutations, TR/EN i18n + a11y,
architecture note `docs/architecture/PARKING-SESSION-HISTORY-DELETION-UI.md`.

**Implementation delivered:**

- Cursor-based history via `parkingKeys.sessionHistory(size)` + `useInfiniteQuery`.
- Loading, empty, pagination, error/retry, pull-to-refresh, COMPLETED/CANCELLED rows.
- Confirmed single-item and full-history deletion (server-confirmed; no optimistic remove).
- Cache updates scoped to current user/session epoch; ACTIVE query never cleared on success.

**Definition of Done:** (met for client UI)

- Pagination appends without duplicates and respects server cursor order.
- Single delete removes only the accepted item; full delete reaches a tested empty state.
- Foreign-object / missing opaque `204` is not distinguishable in UI.
- Confirmation copy matches the derived-data privacy decision (ACTIVE preserved).
- mobile-v2 + api-client tests cover query, mutation, screen, privacy, and regression.

**Dependencies:** S1-P0-01, S1-P0-05, S1-P0-07.

### S1-DEL-08 — Emit and ingest `parking_history_deleted` (R22 / WP-07.3) ✅ COMPLETE (2026-07-29)

**Status:** COMPLETE (producer + analytics consumer on branch `decision`)
**Layers:** backend (parking-service), analytics
**Closes/unlocks:** R22
**Evidence commits:** `0a70b03` — `feat(parking): publish parking history deleted lifecycle events`;
`d482bcc` — `feat(analytics): consume parking history deleted lifecycle event`

**Implemented:**

- **Producer:** `parking-service` appends `ParkingHistoryDeleted` to the transactional outbox on
  successful single or bulk terminal history delete when `deletedCount >= 1`. Wire `eventType`:
  `ParkingHistoryDeleted`; `aggregateType`: `ParkingSession`. Scope values: `SINGLE_TERMINAL_SESSION`
  (single delete; `aggregateId` = `sessionId`; `deletedCount` = 1) and `ALL_TERMINAL_HISTORY` (bulk
  delete; `aggregateId` = `userId`; `deletedCount` = rows removed). No event on zero-delete or
  failed ACTIVE deletion.
- **Consumer:** `analytics-service` validates producer-compatible fields, records append-only
  deletion analytics (`PARKING_SESSION_HISTORY_DELETED` → `parking_session_history_deleted`), and
  excludes the metric from the parking funnel. Duplicate Kafka delivery is idempotent via inbox
  `tryClaim(eventId)`. Malformed / invalid-version / validation-rejected **supported**
  payloads follow the established DLT path (`parkio.dlt.analytics`). Unsupported event types
  are ignored and acknowledged (no DLT).

**Definition of Done:** (verified)

- Single and bulk deletes emit one privacy-minimized event each when at least one row is removed.
- Analytics ingestion is append-only; deletion metrics do not reverse prior lifecycle observations.
- Idempotent replay under duplicate delivery; invalid **supported** payloads route to DLT;
  unsupported event types are ignored and acked.
- Focused parking-service and analytics-service unit/contract tests pass.

**Dependencies:** S1-P0-07, S1-P0-08, S1-P0-09.

### S1-P0-13 — Release Sprint 01 to azure-hosted-beta for R27 ⚠️ COMMITS READY / DEPLOY BLOCKED (2026-07-24)

**Layers:** DevOps, release
**Closes/unlocks:** R27 only after immutable deploy + hosted smoke exit 0
**Evidence:** git commits on `master`; deploy requires Azure VM SSH + `docker/.env.azure-hosted-beta`

**Status:**

- Sprint 01 source committed in reviewable commits on `master` (parking, analytics, mobile, smoke).
- Local unit/integration/typecheck gates for affected packages passed before commit.
- Azure VM `api.parkio.dev` (20.199.17.76) accepts SSH on port 22, but this operator workstation
  has no authorized public key / Azure CLI / self-hosted `parkio-beta` runner /
  `docker/.env.azure-hosted-beta`, so immutable image deploy and post-deploy smoke were not executed.
- Previous hosted DELETE HTTP 500 root cause: deployed HEAD lacked `@DeleteMapping`;
  `GlobalExceptionHandler` maps `HttpRequestMethodNotSupportedException` to opaque 500.
  Current source PostGIS IT proves terminal/history delete 204 / ACTIVE 409 semantics.
- R27 remains FAIL until deploy + smoke exit 0. R22 is **PASS** (S1-DEL-08). Do not mark Sprint 1 complete.

**Definition of Done:** not met (no post-deploy hosted PASS evidence).

**Dependencies:** S1-P0-01…12; operator Azure SSH or self-hosted deploy runner.

### S1-P0-12 — Add hosted-beta ParkingSession smoke coverage ⚠️ EXECUTED / R27 NOT PASS (2026-07-24)

**Layers:** DevOps, backend runtime
**Closes/unlocks:** R27 only when hosted suite exits 0 on an immutable image that includes S1-P0-07+
**Evidence:** `scripts/smoke-parking-session-hosted-beta.sh` + `scripts/lib/parking-session-smoke-*.cjs`;
run evidence under `docs/evidence/sprint-01/parking-session-hosted-beta/`.

**Implementation delivered:**

- Fail-closed hosted ParkingSession smoke (safety gates, disposable-account confirmation, redaction).
- Lifecycle coverage: start, idempotent replay, active read, second-start conflict, complete,
  history, cancel, deletion probes, ACTIVE-preservation setup, cleanup.
- Optional User B owner isolation; cursor pagination when deletion is healthy.
- Outbox/analytics marked NOT_OBSERVABLE without private ops endpoints.
- Unit tests for config gates, redaction, and mocked runner behavior.

**Hosted execution (azure-hosted-beta / api.parkio.dev):**

- Run `ps-s1p012-20260724T212710Z`: **17 PASS / 7 FAIL / 2 NOT_EXECUTED / 2 NOT_OBSERVABLE**, exit **1**.
- Lifecycle start/active/conflict/complete/cancel/history **PASS** with `Cache-Control: no-store`.
- Deletion endpoints return **HTTP 500** (deployed revision lacks healthy S1-P0-07 opaque 204).
- Owner isolation and cursor pagination **NOT_EXECUTED** (no User B / deletion unhealthy).
- Deploy of dirty Sprint working tree **not authorized** (would require commit/push or `--allow-dirty`).

**Definition of Done:** partially met (suite exists + executed); **not met** for “current
azure-hosted-beta profile passes on an actual deployed immutable image” while deletion fails.

**Dependencies:** S1-P0-07; S1-P0-08/S1-P0-09 if event delivery is included in the smoke gate.

## 4. P1 — Required completion/hardening after the mobile core

### S1-P1-01 — Add Web ParkingSession parity ✅ COMPLETE IN SOURCE (WP-07.2)

**Status:** COMPLETE in committed source (Web ParkingSession lifecycle UI + data layer).
Hosted deployment proof is **not** part of this item — see R27 / S1-P0-13 / WP-07.5.
**Layers:** Web
**Closes/unlocks:** Web portions of R8-R15 (source); does not close R27
**Prior evidence of gap (historical):** Web had no session keys, query options, hooks,
route/page, or cache cleanup at Sprint backlog authorship time.

**Implemented (WP-07.2):**

- Web-owned ParkingSession keys/query/mutation options via shared `@parkio/api-client`.
- Active restoration, timer, start/complete/cancel/confirm, maps/share, history pagination,
  single delete, and bulk history delete on Map/Profile routes.
- User-session cache cleanup via `SessionQueryCacheSync` / `parkingKeys.sessionsRoot()`.
- Focused component/query/mutation/cache tests in `frontend/apps/web`.

**Definition of Done:** (met for source)

- Web supports the accepted Sprint 1 lifecycle without direct HTTP calls or ad hoc keys.
- Active state restores after refresh and clears on logout/user switch.
- Elapsed, navigation/share, history, and deletion match the shared behavior/privacy policy.
- Route ownership remains in the canonical route manifest.
- Focused tests exist in committed source (CI definitions cover them; hosted smoke is R27).

**Dependencies:** all P0 API/privacy/backend tasks needed by the corresponding flow.

### S1-P1-02 — Implement the optional reminder end to end

**Layers:** backend, mobile-v2, Web, notification
**Closes/unlocks:** optional reminder scope
**Evidence of gap:** V15/domain have `reminder_at`; request/response omit it, controller passes null,
and no scheduler/notification flow exists.

**Implementation task:**

- Define reminder semantics, timezone/instant behavior, update/cancel behavior, and notification
  copy.
- Add the accepted request/response contract without client-controlled lifecycle timestamps.
- Schedule/deliver idempotently through notification-service and cancel on terminal/deleted
  sessions.
- Add client controls only after backend semantics are stable.

**Definition of Done:**

- A user can opt in to one reminder and see its scheduled state.
- Delivery is at-most-once from the user's perspective under scheduler retries.
- Complete, cancel, deletion, and account erasure cancel or suppress pending delivery.
- Timezone/DST, past time, permission denied, and notification-provider failure are tested.
- Reminder data follows the accepted privacy/retention policy.

**Dependencies:** accepted core lifecycle and S1-P0-05.

### S1-P1-03 — Close canonical mobile-v2 release, signing, and crash verification

**Layers:** mobile-v2, DevOps
**Closes/unlocks:** R26 release completion
**Evidence of gap:** release build uses debug signing; release guide targets legacy app; no
ParkingSession device proof; mobile-v2 has no crash collection.

**Implementation task:**

- Replace debug release signing with the approved secret-backed signing path.
- Add a mobile-v2 release/device checklist covering cold restore, timer background/foreground,
  start retry, complete/cancel, maps/share, history/delete, offline decision, and auth isolation.
- Install and privacy-configure the approved crash reporter with release-symbol mapping.
- Retire or clearly relabel legacy release instructions.

**Definition of Done:**

- APK/AAB signature is inspected and is not `CN=Android Debug`.
- Embedded API/profile values are verified from the built artifact.
- A physical device or production-shaped emulator run records every ParkingSession checklist
  result.
- A controlled non-fatal and crash reach the configured provider without PII/coordinates.
- Store/release credentials never enter Git or logs.
- The handoff records artifact hash, version, commit, signer, and verification date.

**Dependencies:** P0 mobile flow and history tasks.

### S1-P1-04 — Add parking-service participation in account erasure

**Layers:** backend, privacy
**Closes/unlocks:** broader privacy/account-deletion support
**Evidence of gap:** no parking-service account-erasure consumer/use case exists; beta uses a
manual support statement.

**Implementation task:**

- Implement the accepted cross-service erasure contract for ParkingSession and its derived
  records.
- Reuse the existing inbox/idempotency pattern.
- Produce an auditable completion/failure signal without retaining erased PII.

**Definition of Done:**

- Duplicate erasure delivery is safe.
- Active and terminal sessions follow the accepted policy.
- Derived parking records, analytics, logs, and backups have explicit handled/exception status.
- A multi-service integration test proves parking-service completion and retry behavior.
- The beta support/runbook and public privacy wording match the implemented capability.

**Dependencies:** S1-P0-05 and the platform account-erasure contract.

## 5. Deferred — only after an explicit decision

### S1-D-01 — Implement a durable offline ParkingSession start queue

**Layers:** mobile-v2, privacy
**Reason deferred:** R16 can be closed by an explicit online-only decision; a durable mutation
queue adds sensitive location storage and difficult reconciliation.

**Implementation task if activated:**

- Persist an encrypted/bounded start draft and idempotency key.
- Reconcile server ACTIVE state before replay.
- Expire drafts and clear them on logout/user switch.

**Definition of Done:**

- Reconnect cannot create a duplicate session.
- A server session always wins over a stale draft.
- Draft coordinates and keys have tested expiry/cleanup.
- Crash/restart/reconnect/race tests pass.
- Privacy approval covers local location storage.

**Dependencies:** S1-P0-06 must explicitly select this path.

### S1-D-02 — Add a dedicated ParkingSession exposure flag

**Layers:** backend/client configuration, DevOps
**Reason deferred:** R28 already has an explicit rollback strategy; a flag is operational
hardening, not required to avoid recreating working lifecycle behavior.

**Implementation task if activated:**

- Define a client-exposure flag that prevents new session/claim entry without making existing
  active sessions inaccessible.
- Keep active read, return-to-car, and safe completion available during rollback.

**Definition of Done:**

- Turning the flag off blocks new starts/claims but preserves restoration and terminal cleanup.
- Web and mobile-v2 use the same documented exposure semantics.
- Rollback drill proves no hidden ACTIVE sessions are stranded.
- Flag state is observable without exposing secrets.

**Dependencies:** accepted client restoration behavior.

## 6. Recommended next single implementation task

**S1-P0-13 commits are on `master`; R27 remains FAIL** until an authorized operator deploys the
immutable `sha-<gitsha>` images to azure-hosted-beta and the ParkingSession smoke exits 0.

Next authorized work (pick one, do not invent undeclared scope):

1. **Complete S1-P0-13 deploy** from the Azure VM (`/opt/parkio`): pull/checkout the release SHA,
   run `PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta PARKIO_ENV_FILE=docker/.env.azure-hosted-beta
   ./scripts/deploy-hosted-beta.sh`, then re-run `./scripts/smoke-parking-session-hosted-beta.sh`.
2. **S1-DEL-08 / R22** (`parking_history_deleted`) — **COMPLETE** on branch `decision`
   (`0a70b03`, `d482bcc`).

Sprint 1 remains incomplete (R26 PARTIAL, R27 FAIL). R22 and R24 are **PASS**.
