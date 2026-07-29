# Sprint 01 Parking Session Gap Matrix

**Baseline:** `master` / `a265463a6c9a1961e25b73479dce3fefd1b4b3d8`
**Audit date:** 2026-07-24
**Decision:** **NOT COMPLETE**

## Status rules

- **PASS:** current code and current verification satisfy the requirement.
- **PARTIAL:** a meaningful portion or a generic foundation exists, but Sprint 1 behavior is not
  fully implemented or verified.
- **FAIL:** required behavior or evidence is absent.
- **NOT APPLICABLE:** requirement does not apply. No requirement received this status.

## R1-R28

| ID | Requirement | Status | Exact repository evidence | Gap / risk |
|---|---|---|---|---|
| R1 | A user can have only one ACTIVE parking session. | **PASS** | `services/parking-service/src/main/resources/db/migration/V15__create_parking_sessions.sql`: partial unique index `uq_parking_sessions_active_user`; `ParkingSessionService.startSession`; `ParkingSessionRepositoryAdapter.save` uses `saveAndFlush`; `ParkingSessionPostgisIntegrationTest.databaseEnforcesPartialActiveUniquenessAndLifecycleConstraints`, `sequentialDuplicateAndConcurrentIndexViolationUseSameStableDomainConflict`, `manualStartAndCommunityClaimRaceCommitExactlyOneCoherentSession`, and `differentKeysForSameUserAreSerializedByActiveSessionConstraint`. Current mandatory-Docker integration suite passed. | None for the invariant. Clients currently hide the resulting conflict/session. |
| R2 | Duplicate start requests cannot create duplicate sessions. | **PASS** | `IdempotencyService.execute` and migration `V10__create_idempotency_records.sql`; controller start requires `Idempotency-Key`; same-key and different-key races are covered by `ParkingSessionControllerTest.enforcesIdempotencyKeyAndNormalizedFingerprint`, `ParkingSessionPostgisIntegrationTest.simultaneousSameKeyRequestsReplayOneCommittedMutation`, and the unique-index tests. | None for server duplication. A future offline client must persist/reuse its key for ambiguous retries. |
| R3 | Start endpoint exists and has a documented contract. | **PASS** | Backend start contract unchanged. mobile-v2 consumes start via `useStartParkingSession` / `ParkHereStartControl` (S1-P0-03). **Web:** map start flow (WP-07.2). | Optional `reminderAt` is not in the request contract. |
| R4 | Active-session endpoint exists. | **PASS** | `ParkingSessionController.findActive`; `GET /api/v1/parking/sessions/active`; 200 or 204. mobile-v2 and **Web** consume active session via shared `parkingApi.getActiveParkingSession` (WP-07.2). | — |
| R5 | Complete endpoint exists. | **PASS** | Backend complete contract unchanged. mobile-v2 (S1-P0-04) and **Web** (WP-07.2) consume `parkingApi.completeParkingSession`. | — |
| R6 | Cancel endpoint exists. | **PASS** | Backend cancel contract unchanged. mobile-v2 (S1-P0-04) and **Web** (WP-07.2) consume `parkingApi.cancelParkingSession` with confirm UX. | — |
| R7 | History endpoint exists and pagination is deterministic. | **PASS** | Backend history + cursor ordering. mobile-v2 Profile history (S1-P0-11) and **Web** Profile Parking History with infinite query (WP-07.2). | — |
| R8 | A single history item can be deleted. | **PASS** | Backend DELETE single. mobile-v2 (S1-P0-11) and **Web** (WP-07.2) typed delete with ConfirmModal; `ParkingHistoryDeleted` emitted and ingested (R22, `0a70b03`/`d482bcc`). | — |
| R9 | Full parking history can be deleted. | **PASS** | Backend DELETE history. mobile-v2 delete-all (S1-P0-11) and **Web** bulk delete (WP-07.2); one bulk `ParkingHistoryDeleted` event when `deletedCount >= 1`. | — |
| R10 | Users cannot access or mutate another user's sessions. | **PASS** | Gateway `AuthenticationGlobalFilter` strips forged identity and injects verified `X-User-*`; `GatewayAuthHeaderGlobalFilter` stamps the internal secret; parking `GatewayAuthFilter` rejects direct untrusted API calls; `ParkingSessionService.requireOwnedSession` uses `findByIdAndUserId`; history/active/delete queries include `userId`; foreign DELETE UUIDs return opaque `204` without ownership leak; PostGIS lifecycle/history/delete ownership tests passed; no-store filters exist at gateway and service. | Admin/support deletion remains out of Sprint 1 scope. |
| R11 | Active session is restored after application restart. | **PASS** | Shared `parkingApi.getActiveParkingSession`; mobile-v2 and **Web** active query/banner restore (WP-07.2); claim + manual start converge onto the same active cache. | — |
| R12 | Elapsed time is calculated safely and consistently. | **PASS** | mobile-v2 derives elapsed from server `startedAt` + `Date.now()`; **Web** uses the same server-authoritative elapsed pattern in the active banner (WP-07.2). | — |
| R13 | Return-to-car external navigation works. | **PASS** | mobile-v2 banner navigate control (S1-P0-10). **Web** Find/Maps hand-off from active session coords (WP-07.2). | — |
| R14 | Parking location can be shared. | **PASS** | mobile-v2 native share (S1-P0-10). **Web** share control with privacy-safe params (WP-07.2). | Vendor SDK drain for mobile release builds. |
| R15 | Location permission failure has explicit retry/error UX. | **PASS** | mobile-v2 manual start permission UX (S1-P0-03). **Web** Park Here start flow with explicit location error/retry states (WP-07.2). | — |
| R16 | Offline/local draft behavior is implemented or explicitly deferred behind a documented decision. | **PASS** | ADR `docs/architecture/PARKING-SESSION-OFFLINE-DRAFT-DECISION.md` + plan `docs/planning/S1-P0-06-OFFLINE-IMPLEMENTATION-PLAN.md` select Option A online-only mutations: no durable drafts, no persisted coords/keys, no auto-replay; ambiguous in-memory keys only; restart via active GET; S1-D-01 remains deferred. | Durable offline support is intentionally not implemented; S1-OFF-* hardening may still follow. |
| R17 | `parking_session_started` analytics event exists. | **PASS** | Producer emits `ParkingSessionStarted` to `parkio.parking.session`; analytics-service consumes it (inbox-deduped) as canonical `parking_session_started` with source-split metrics (`PARKING_SESSION_STARTED_MANUAL` / `_COMMUNITY` / `_OTHER`). Covered by mapper/validator/consumer/ingestion tests. | — |
| R18 | `parking_session_completed` analytics event exists. | **PASS** | Producer `ParkingSessionCompleted` → analytics `parking_session_completed` / `PARKING_SESSION_COMPLETED` with derived duration seconds; replay-safe. | — |
| R19 | `parking_session_cancelled` analytics event exists. | **PASS** | Producer `ParkingSessionCancelled` → analytics `parking_session_cancelled` / `PARKING_SESSION_CANCELLED` with derived duration seconds; replay-safe. | — |
| R20 | `return_to_car_clicked` analytics event exists. | **PASS** | mobile-v2 `productAnalytics.trackProductEvent('return_to_car_clicked')` fires after successful Linking hand-off; params limited to coarse `platform` (no coords/URL/sessionId). | Vendor SDK drain via `setProductAnalyticsTransport` for release builds. |
| R21 | `parking_location_shared` analytics event exists. | **PASS** | Fires on `Share.sharedAction` only (not dismiss); privacy-safe params; no lifecycle name collision. | Same transport note as R20. |
| R22 | `parking_history_deleted` analytics event exists. | **PASS** | `parking-service` emits `ParkingHistoryDeleted` via transactional outbox on single/bulk terminal delete when `deletedCount >= 1` (`0a70b03`). `analytics-service` consumes, validates, and records append-only `parking_session_history_deleted` with inbox idempotency and DLT on invalid payloads (`d482bcc`). Focused producer/consumer tests pass. | — |
| R23 | Deletion/privacy behavior for derived observations is documented. | **PASS** | ADR `docs/architecture/PARKING-SESSION-DELETION-PRIVACY-DECISION.md` + plan `docs/planning/S1-P0-05-DELETION-IMPLEMENTATION-PLAN.md` define hard-delete terminal sessions, ACTIVE conflict, COMMUNITY non-cascade, rewards retain, event immutability, coords/idempotency/backup/tombstone policy, and open Product/Legal items. S1-P0-07 implements the API slice; R22 analytics implemented (S1-DEL-08). | PRIV-001 account erasure still open. |
| R24 | Relevant unit tests exist. | **PASS** | Focused/unit tests are present in committed source for parking-service (lifecycle + `ParkingHistoryDeleted` producer/contract), analytics-service (mapper/validator/consumer/ingestion), mobile-v2 (S1-P0-02/03/04/10/11 + WP-07.1 abort), and Web ParkingSession (WP-07.2: mutations, query-options, Active card, Park Here, history). Presence is proven by repository inventory and CI job definitions; this row does not claim a fresh re-run during doc sync. | Hosted-beta smoke remains R27; release/device proof remains R26. |
| R25 | Relevant integration tests exist. | **PASS** | `ParkingSessionPostgisIntegrationTest` has 20 real PostgreSQL/PostGIS tests; `Task08ParkingLifecyclePostgisIT` adds five lifecycle tests. Current `integrationTest` ran with `-Pparkio.integrationTest.requireDocker=true` and passed. | No live Azure/client E2E; that is tracked separately by R26-R27. |
| R26 | Release/mobile verification instructions exist. | **PARTIAL** | mobile-v2 has `eas.json`, `scripts/validate-release-env.mjs`, `scripts/run-android-release.mjs`, and `.github/workflows/mobile-ci.yml`. `docs/beta/mobile-release.md` targets legacy Mobile; mobile-v2 `android/app/build.gradle` signs release with the debug key; no ParkingSession device checklist or verified signed artifact exists. | Build plumbing exists, but release proof/instructions for the canonical app and this feature are incomplete. |
| R27 | Azure hosted-beta smoke coverage exists. | **FAIL** | Suite executed (`ps-s1p012-20260724T212710Z`: DELETE HTTP 500). 2026-07-25 web hosted-beta attempt classified **`DEPLOYMENT_NOT_PERFORMED`**: no SSH/`az`/`.env.azure-hosted-beta`; live `app.parkio.dev` lacks Parking Session Web markers. WP-07 source (including deletion analytics) is committed on branch `decision`. See `docs/beta/parking-session-web-status.md`. | Deploy `sha-<gitsha>` on Azure VM, re-run API+web smoke to exit 0. |
| R28 | Feature flag or rollback strategy exists. | **PASS** | No ParkingSession flag exists, but `services/parking-service/README.md` documents coordinated rollout and rollback: every claim-capable client must restore sessions; claims must be disabled/drained before backend rollback. Hosted deployment uses immutable-image rollback procedures. | Strategy is manual and current clients do not meet its rollout prerequisite. |

## Cross-layer gap summary

| Layer | Complete or credible foundation | Missing |
|---|---|---|
| Backend | Lifecycle aggregate; V15; one-ACTIVE invariant; idempotent start/terminal transitions; active/history reads; owner-safe single/full hard delete; session lifecycle + **deletion** outbox events; OpenAPI; real PostGIS tests | Reminder command/delivery; account-erasure handling; idempotency TTL sweeper |
| Gateway | Auth propagation; forged-header stripping; internal secret; parking route; rate limit; no-store | Real proxied authenticated ParkingSession success test |
| Shared frontend | ParkingSession types, Zod contracts, and typed API-client session methods including history + delete (`createParkingApi`; `parking.session.test.ts`) | — |
| Web | WP-07.2 complete in source — data layer, ACTIVE restore, Park Here, Find/Maps/Share/complete/cancel, Profile Parking History + deletion UI, polish/a11y/privacy; focused tests pass | Hosted-beta: `DEPLOYMENT_NOT_PERFORMED` (R27) |
| mobile-v2 | Canonical app; auth restore; QueryClient; ACTIVE restore/start/timer/terminal; navigate/share; Profile history + deletion UI; claim→active invalidate; session cache cleanup; release scripts | Crash reporting, signed/device proof |
| Legacy Mobile | Non-canonical analytics/crash seams and spot claim | Cannot satisfy canonical mobile requirements |
| Analytics | Kafka/inbox/read-model infrastructure; spot claim aggregate; ParkingSession lifecycle + **history-deletion** ingestion on `parkio.parking.session` (`parking_session_started/completed/cancelled`, `parking_session_history_deleted`); client click/share events (R20–R21) | Account-erasure analytics handling |
| Privacy | Owner isolation; no-store; documented derived-data policy; terminal hard-delete APIs; mobile-v2 + **web** history deletion UX; deletion analytics (R22) | Account erasure (PRIV-001) |
| Azure/operations | parking-service enabled/private; general smoke and rollback | ParkingSession hosted-beta smoke exit 0 (R27) |

## Blocker-to-dependency mapping

| Dependency order | Blockers unlocked |
|---:|---|
| 1 | Privacy semantics (R8-R9, R22-R23), offline decision (R16), analytics payload policy (R17-R22) |
| 2 | Shared API client (R11-R15 and all history/client work) |
| 3 | mobile-v2 active restoration/claim convergence (R11) |
| 4 | start/timer/actions/navigation/share (R12-R15, R20-R21) |
| 5 | backend deletion and lifecycle events (R8-R9, R17-R19, R22-R23) |
| 6 | analytics ingestion and client events (R17-R22) |
| 7 | history UI and Web parity (R8-R15) |
| 8 | hosted smoke and canonical release closure (R26-R27) |

## Recommended next single implementation task

**S1-P0-01 through S1-P0-12 and S1-DEL-08 are complete**; S1-P0-13 committed the Sprint tree but
**R27 remains FAIL** (Azure deploy blocked without authorized SSH / self-hosted runner /
`docker/.env.azure-hosted-beta` on this workstation). Next: deploy+re-smoke for R27 (WP-07.5).

R7–R9, R11–R15, R16, R17–R23 are PASS.
Keep R27 FAIL until hosted ParkingSession smoke exits 0 on an image that includes S1-P0-07+ and WP-07 deletion analytics.
Do not claim Sprint 1 complete.
