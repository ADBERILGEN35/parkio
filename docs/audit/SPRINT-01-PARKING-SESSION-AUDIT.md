# Sprint 01 Parking Session Audit

**Audit date:** 2026-07-24
**Repository baseline:** `master` (Sprint 01 commits landed; see S1-P0-13 in backlog — Azure deploy still blocked)
**Production profile:** `azure-hosted-beta`
**Mode:** repository-truth, read-only inspection followed by documentation only
**Decision:** **SPRINT 1 NOT COMPLETE — do not begin the next Strategy v2.1 product tranche**

## 1. Executive result

The repository contains a strong backend ParkingSession foundation, but it does not contain an
end-to-end Sprint 1 product.

The backend supports authenticated manual start, active lookup, complete, cancel, deterministic
terminal history, request idempotency, and a database-enforced one-ACTIVE-session invariant.
Community spot claim also creates an ACTIVE `COMMUNITY` session atomically. The current PostGIS
integration suite proves the migration, ownership, transaction, idempotency, and concurrency
behavior.

The shared API client now exposes typed ParkingSession methods including history and owner-safe
delete operations. Canonical mobile-v2 owns active-session query keys, restoration via React Query,
claim invalidation, logout cache isolation, map-chrome ACTIVE banner (timer/complete/cancel,
navigate/share; S1-P0-02/04/10), and Profile-nested terminal history with confirmed deletion
(S1-P0-11). Web still has no ParkingSession UI. Manual start and community claim both converge on
the same ACTIVE session cache.

Single-item and full-history deletion APIs exist on parking-service (S1-P0-07; R8/R9 PASS) and are
consumed by mobile-v2 + `@parkio/api-client` (S1-P0-11). Authoritative ParkingSession lifecycle
outbox events exist for start/complete/cancel (S1-P0-08) and analytics-service ingests them as
canonical `parking_session_*` facts (S1-P0-09; R17–R19 PASS end-to-end). Client nav/share
interaction events exist in mobile-v2 (`return_to_car_clicked`, `parking_location_shared`,
`parking_action_failed`); they are not Kafka lifecycle facts.
`parking_history_deleted` remains open (R22 FAIL).
Derived-observation deletion semantics are documented in PARKING-SESSION-DELETION-PRIVACY-DECISION.md
(R23). History/deletion UI architecture: `docs/architecture/PARKING-SESSION-HISTORY-DELETION-UI.md`.
The hosted-beta ParkingSession smoke suite now exists and was executed against
`https://api.parkio.dev` (run `ps-s1p012-20260724T212710Z`): lifecycle
start/active/conflict/complete/cancel/history + `no-store` PASS; deletion endpoints
return HTTP 500 on the currently deployed image (S1-P0-07 not healthy there). R27 remains
FAIL until an immutable image including S1-P0-07+ is deployed and the smoke exits 0.
Evidence: `docs/evidence/sprint-01/parking-session-hosted-beta/`. Release tooling exists
for mobile-v2, but production signing, current ParkingSession device verification, and crash
collection are not ready.

| Result | Count | Requirements |
|---|---:|---|
| PASS | 24 | R1-R15, R16-R21, R23, R25, R28 |
| PARTIAL | 2 | R24, R26 |
| FAIL | 2 | R22, R27 |
| NOT APPLICABLE | 0 | None |

The evidence for every requirement is in
`docs/audit/SPRINT-01-PARKING-SESSION-GAP-MATRIX.md`. The implementation order and
Definitions of Done are in `docs/planning/SPRINT-01-COMPLETION-BACKLOG.md`.

## 2. Audit method and verification

This audit treated the checked-out repository and current test executions as evidence. Existing
reports were not accepted as proof. No deployment, production mutation, migration creation,
configuration edit, or application-code edit was performed.

### 2.1 Current verification results

| Check | Result | Notes |
|---|---|---|
| `./gradlew :services:parking-service:test :services:gateway-service:test --no-daemon` | PASS | `BUILD SUCCESSFUL`; existing Spring `@MockBean` removal warnings only |
| `./gradlew :services:parking-service:integrationTest -Pparkio.integrationTest.requireDocker=true --rerun-tasks --no-daemon` | PASS | Real Docker/PostgreSQL/PostGIS suite; `BUILD SUCCESSFUL` |
| `pnpm --filter @parkio/validation test` | PASS | Completed before the combined frontend runner was separated |
| `pnpm --filter @parkio/api-client test` | PASS | Completed before the combined frontend runner was separated |
| `pnpm --filter @parkio/web exec vitest run --reporter=verbose` | PASS | 70 test files, 508 tests |
| `pnpm --filter @parkio/mobile-v2 test -- --runInBand` | PASS | 33 suites, 167 tests after S1-P0-04; Jest reports its existing forced-exit/open-handle warning |
| Shared types/validation/API client/Web/mobile-v2 `typecheck` | PASS | All five filtered projects completed |
| Shared types/validation/API client/Web/mobile-v2 `lint` | PASS | All five filtered projects completed |

Docker Desktop was reachable through both `docker.exe` and the Linux Docker client. The first
Windows `cmd.exe` launch failed before Gradle with WSL
`UtilBindVsockAnyPort:307`; the same mandatory-Docker integration suite was then run successfully
through Linux Gradle against the reachable Docker engine.

These checks prove the behavior that has tests. They do not prove absent Web/mobile product
flows, a signed release artifact, or an Azure runtime flow.

## 3. Backend findings

### 3.1 Domain and application

The aggregate is
`services/parking-service/src/main/java/com/parkio/parking/domain/ParkingSession.java`.
Its exact lifecycle entry points are:

- `ParkingSession.start(...)` creates an `ACTIVE` session with a generated UUID and a
  server-controlled `Instant`.
- `ParkingSession.complete(Instant)` transitions only an ACTIVE session to `COMPLETED`.
- `ParkingSession.cancel(Instant)` transitions only an ACTIVE session to `CANCELLED`.
- `ParkingSession.end(...)` rejects terminal-to-terminal transitions and an end time before
  `startedAt`.
- The aggregate validates latitude, longitude, and the exact `NUMERIC(12,2)` fee envelope.

Lifecycle enums are:

- `services/parking-service/src/main/java/com/parkio/parking/domain/ParkingSessionStatus.java`:
  `ACTIVE`, `COMPLETED`, `CANCELLED`.
- `services/parking-service/src/main/java/com/parkio/parking/domain/ParkingSource.java`:
  `MANUAL`, `FACILITY`, `CURB`, `COMMUNITY`, `AUTO`.

The application service is
`services/parking-service/src/main/java/com/parkio/parking/application/ParkingSessionService.java`.
It implements `startSession`, `completeSession`, `cancelSession`, `findActive`, and bounded
`history` overloads. `startSession` performs an early active-session check; ownership-sensitive
mutations use the repository's `findByIdAndUserId`, so a foreign or unknown UUID receives the
same not-found behavior.

The repository port,
`services/parking-service/src/main/java/com/parkio/parking/application/port/ParkingSessionRepository.java`,
contains `save`, `findActiveByUserId`, `findByIdAndUserId`, and two
`findHistoryByUserId` overloads. It has no deletion operation.

Architectural note: `ParkingSession` is also a JPA `@Entity`, despite the repository architecture
rules describing a framework-free domain layer. This is a real layering deviation, but it does
not invalidate the lifecycle invariants and is not a Sprint 1 completion blocker.

### 3.2 Persistence and migrations

`services/parking-service/src/main/resources/db/migration/V15__create_parking_sessions.sql`
creates `parking_sessions` with:

- UUID identity and owner, lifecycle/source columns, server timestamps, precise coordinates,
  PostGIS `GEOGRAPHY(Point,4326)`, optional exact fee, optional `reminder_at`, audit timestamps,
  and optimistic-lock `version`.
- `ck_parking_sessions_status`, `ck_parking_sessions_source`,
  `ck_parking_sessions_latitude`, `ck_parking_sessions_longitude`,
  `ck_parking_sessions_estimated_fee`, and `ck_parking_sessions_lifecycle`.
- `trg_parking_sessions_set_location`, which derives the PostGIS point at insert time.
- `trg_parking_sessions_reject_immutable_update`, which protects owner, source, location,
  start time, identity, and creation time.
- Partial unique index `uq_parking_sessions_active_user` on `user_id WHERE status = 'ACTIVE'`.
- Terminal-history index `idx_parking_sessions_user_history` on
  `(user_id, started_at DESC, id DESC)`.
- GiST index `idx_parking_sessions_location`.

Idempotency storage comes from
`services/parking-service/src/main/resources/db/migration/V10__create_idempotency_records.sql`.
There is no ParkingSession deletion, reminder-delivery, or lifecycle-event migration.

The persistence adapter is
`services/parking-service/src/main/java/com/parkio/parking/infrastructure/persistence/ParkingSessionRepositoryAdapter.java`.
`save` uses `saveAndFlush()` and translates only the named
`uq_parking_sessions_active_user` violation to stable domain code
`ACTIVE_PARKING_SESSION_EXISTS`.

`services/parking-service/src/main/java/com/parkio/parking/infrastructure/persistence/ParkingSessionJpaRepository.java`
uses owner-scoped reads. Its history query is terminal-only and orders by
`startedAt DESC, id DESC`; continuation predicates use both fields.
`ParkingSessionHistoryCursor` and
`services/parking-service/src/main/java/com/parkio/parking/presentation/ParkingSessionHistoryCursorCodec.java`
encode a versioned, maximum-512-character Base64URL cursor and reject malformed,
non-canonical, unknown-field, or unsupported-version values.

### 3.3 Presentation and OpenAPI

`services/parking-service/src/main/java/com/parkio/parking/presentation/ParkingSessionController.java`
owns `/api/v1/parking/sessions`:

| Method and path | Function / operation ID | Contract |
|---|---|---|
| `POST /api/v1/parking/sessions` | `startSession` / `startParkingSession` | Requires `Idempotency-Key`; returns 201 |
| `GET /api/v1/parking/sessions/active` | `findActive` / `getActiveParkingSession` | Returns 200 or 204 |
| `POST /api/v1/parking/sessions/{sessionId}/complete` | `completeSession` / `completeParkingSession` | Requires `Idempotency-Key`; returns 200 |
| `POST /api/v1/parking/sessions/{sessionId}/cancel` | `cancelSession` / `cancelParkingSession` | Requires `Idempotency-Key`; returns 200 |
| `GET /api/v1/parking/sessions/history` | `history` / `getParkingSessionHistory` | `size` 1-100, opaque cursor |

There is no `DELETE /api/v1/parking/sessions/{sessionId}` and no
`DELETE /api/v1/parking/sessions/history`.

`services/parking-service/src/main/java/com/parkio/parking/presentation/dto/StartParkingSessionRequest.java`
allows only `latitude`, `longitude`, and optional decimal-string `estimatedFee`.
`StartParkingSessionRequestDeserializer` rejects client-controlled or unknown fields.
The controller supplies `ParkingSource.MANUAL` and passes `null` for `reminderAt`.

`services/parking-service/src/main/java/com/parkio/parking/presentation/dto/ParkingSessionResponse.java`
omits owner and persistence internals, but also omits `reminderAt`. The database/domain reminder
field is therefore dormant: a client cannot request it, observe it, or receive a scheduled
notification.

The contract is generated from Springdoc annotations rather than a checked-in static
ParkingSession OpenAPI file.
`services/parking-service/src/test/java/com/parkio/parking/infrastructure/config/OpenApiEndpointTest.java`
asserts the five operation IDs, bearer scheme, 204 active absence, strict request schema,
idempotency header, examples, and omission of trusted internal headers.

`services/parking-service/src/main/java/com/parkio/parking/infrastructure/web/ParkingSessionNoStoreFilter.java`
adds `Cache-Control: no-store` for all session paths and community claim.

### 3.4 Idempotency and community claim

`services/parking-service/src/main/java/com/parkio/parking/application/IdempotencyService.java`
claims `(user_id, http_method, idempotency_key)` in PostgreSQL, validates route/body
fingerprints, replays a completed response exactly, conflicts on key reuse with different input,
and keeps the mutation and idempotency record in one transaction. Keys must be 8-128 characters.

Duplicate starts are covered twice:

1. the same key replays the one committed start;
2. a different key or racing request is stopped by
   `uq_parking_sessions_active_user`, with the stable conflict code.

`services/parking-service/src/main/java/com/parkio/parking/application/ParkingApplicationService.java`
method `claimSpot` atomically creates an ACTIVE `COMMUNITY` ParkingSession using the authenticated
claimer and server-owned spot coordinates, then commits the spot transition, status history,
existing `ParkingSpotClaimedEvent`, and idempotency record together. It does **not** emit a
ParkingSession lifecycle analytics event.

### 3.5 Authorization and direct access

The service trusts `X-User-Id` only behind the gateway and does not accept owner identity in the
request body. `GatewayAuthFilter` at
`services/parking-service/src/main/java/com/parkio/parking/infrastructure/web/GatewayAuthFilter.java`
requires the internal `X-Gateway-Auth` secret on API/internal paths.

The hosted profile removes gateway and backend host ports in
`docker/docker-compose.hosted-beta.yml`; Caddy is the public edge. Local Compose may publish
service ports, but a direct call without the shared gateway secret is rejected. The current
PostGIS HTTP tests verify that complete, cancel, active, and history remain owner-scoped.

### 3.6 Backend tests

Relevant unit/contract tests exist at:

- `services/parking-service/src/test/java/com/parkio/parking/domain/ParkingSessionTest.java`
- `services/parking-service/src/test/java/com/parkio/parking/application/ParkingSessionServiceTest.java`
- `services/parking-service/src/test/java/com/parkio/parking/presentation/ParkingSessionControllerTest.java`
- `services/parking-service/src/test/java/com/parkio/parking/presentation/ParkingSessionHistoryCursorCodecTest.java`
- `services/parking-service/src/test/java/com/parkio/parking/presentation/dto/ParkingSessionResponseTest.java`
- `services/parking-service/src/test/java/com/parkio/parking/infrastructure/persistence/ParkingSessionRepositoryAdapterTest.java`
- `services/parking-service/src/test/java/com/parkio/parking/presentation/ParkingIdempotencyTest.java`
- `services/parking-service/src/test/java/com/parkio/parking/infrastructure/web/GatewayAuthFilterTest.java`
- `services/parking-service/src/test/java/com/parkio/parking/infrastructure/config/OpenApiEndpointTest.java`

The mandatory-Docker suite
`services/parking-service/src/test/java/com/parkio/parking/infrastructure/persistence/ParkingSessionPostgisIntegrationTest.java`
contains 20 tests covering V15/Flyway validation, constraints, PostGIS, immutability, optimistic
locking, deterministic history, supported HTTP fee behavior, duplicate and simultaneous starts,
same-key replay, community-claim atomicity/rollback, manual-start/claim races, claim/expiry races,
same-user and two-user races, HTTP ownership, and history ownership/order.

`services/parking-service/src/test/java/com/parkio/parking/infrastructure/persistence/Task08ParkingLifecyclePostgisIT.java`
adds five real-PostGIS lifecycle/idempotency/concurrent-claim tests. Both classes ran in the
successful current `integrationTest` invocation.

## 4. Gateway findings

`services/gateway-service/src/main/resources/application.yml` route `parking-service` forwards
`/api/v1/parking/**` and applies the parking rate-limit tier.

Security and propagation are owned by:

- `AuthenticationGlobalFilter`: strips inbound `X-User-*`, validates bearer JWT, injects verified
  identity.
- `AuthorizationGlobalFilter` and `RouteAuthorizationRules`: parking is an authenticated-user
  route; object ownership remains in parking-service.
- `GatewayAuthHeaderGlobalFilter`: strips inbound `X-Gateway-Auth` and stamps the configured
  internal secret.
- `ParkingSessionResponsePolicyGlobalFilter`: applies `Cache-Control: no-store` to session and
  community-claim paths, including gateway-generated errors.

`services/gateway-service/src/test/java/com/parkio/gateway/infrastructure/web/ParkingSessionResponsePolicyIntegrationTest.java`
asserts no-store on active-session and claim 401/429 responses. The gateway test suite also covers
JWT validation, identity-header replacement, authorization, internal-secret stamping, and route
registration. There is no gateway-to-real-parking authenticated success test specifically for
ParkingSession, but the individual gateway filters and real parking-service HTTP surface are
covered.

## 5. Web findings

The shared contract layer is ahead of the client:

- `frontend/packages/types/src/parking.ts` defines `StartParkingSessionRequest`,
  `ParkingSessionResponse`, `ParkingSessionHistoryParams`, and
  `ParkingSessionHistoryResponse`.
- `frontend/packages/validation/src/contracts/parking.ts` defines strict request/response schemas.
- `frontend/packages/validation/src/contracts/parking-contracts.test.ts` exercises these schemas.

The executable client now exposes ParkingSession methods on `createParkingApi`
(`startParkingSession`, `getActiveParkingSession`, `completeParkingSession`,
`cancelParkingSession`, `getParkingSessionHistory`) with focused contract tests in
`frontend/packages/api-client/src/parking.session.test.ts`. Web and mobile-v2 still do not
consume them:

- `frontend/apps/web/src/app/sdk.ts` and `runtime.ts` correctly own one SDK/runtime, but their
  parking facade consumers have no session query/mutation options.
- `frontend/apps/web/src/data/keys.ts` has `nearby`, `mySpots`, `spot`, and media keys only.
- `frontend/apps/web/src/data/query-options`, `data/mutation-options`, and their hooks contain no
  active session, restoration, start, complete, cancel, history, or deletion operations.
- `frontend/apps/web/src/data/SessionQueryCacheSync.tsx` clears existing user-scoped roots but has
  no ParkingSession cache root.
- `frontend/apps/web/src/pages/SpotDetailPage.tsx` uses the spot-claim mutation. That mutation
  updates spot caches only and does not fetch or invalidate an active session.

No Web route/page/component implements the active card, elapsed counter, return-to-car
navigation, parking-location share, history, deletion, or the required loading/empty/
unauthorized/error states. The 508 passing Web tests do not include a ParkingSession product test.

## 6. Mobile findings

### 6.1 Canonical owner

The canonical production client is unambiguous in current architecture documents:

- `frontend/README.md`, “Mobile foundation (WP-07)”: `apps/mobile-v2` is canonical and
  `apps/mobile` is not the production target.
- `frontend/architecture/sprint-3/WP-07-MOBILE.md`, sections 1-2:
  `frontend/apps/mobile-v2` is the only production Mobile owner; the legacy app is not imported.

`frontend/apps/mobile/README.md` and `docs/beta/mobile-release.md` still describe the legacy app
as production/release material. That is documentation drift, not evidence that legacy Mobile is
canonical.

### 6.2 mobile-v2 product behavior

`frontend/apps/mobile-v2/src/features/spots/SpotActions.tsx` implements the displayed
`Park ettim` action as:

```text
parkingApi.claimSpot(spotId, createIdempotencyKey())
```

Success invalidates only `parkingKeys.nearbyRoot()`, `parkingKeys.spot(spotId)`, and
`parkingKeys.mySpots()`. It does not read or invalidate an active session.

`frontend/apps/mobile-v2/src/data/keys.ts` mirrors the Web spot keys and has no session root.
There is no ParkingSession query option, hook, store, route, screen, card, timer, end/cancel
mutation, external-navigation action, native location share, history, or deletion UI.
`app/_layout.tsx` restores authentication, onboarding, and the spot-share draft; it does not
restore a ParkingSession.

Generic location behavior does exist:

- `frontend/apps/mobile-v2/src/features/map/hooks.ts` owns foreground permission and one-shot
  location.
- `frontend/apps/mobile-v2/src/features/map/MapCards.tsx` exposes allow/settings/dismiss UX.
- `frontend/apps/mobile-v2/app/(main)/(tabs)/map.tsx` wires permission and settings actions.
- `frontend/apps/mobile-v2/src/features/share/steps/LocationStep.tsx` has location selection and
  permission handling for spot sharing.

Those components are reusable, but no ParkingSession start flow calls them, so they do not satisfy
the session-specific permission/retry requirement.

`frontend/apps/mobile-v2/src/features/share/state/shareDraftStore.ts` persists a **spot-sharing**
draft, and upload/create code has limited retry behavior. It is not a ParkingSession local draft.
`frontend/architecture/sprint-3/WP-07-MOBILE.md` explicitly lists offline mutation queues as a
non-goal, but it does not make a ParkingSession-specific decision about start reconciliation,
idempotency-key persistence, or draft expiry.

### 6.3 Legacy Mobile

Legacy `frontend/apps/mobile` also contains only a claim action rather than the Sprint 1 session
experience. It has pluggable, memory-buffered seams in
`src/services/analytics.ts` and `src/services/crashReporting.ts`, but its analytics union does not
contain the six required ParkingSession events and no vendor transport is installed. These legacy
seams cannot be counted for canonical mobile-v2.

### 6.4 Release readiness

mobile-v2 has useful foundations:

- `frontend/apps/mobile-v2/eas.json` defines development, preview, hosted-beta, and production
  profiles.
- `frontend/apps/mobile-v2/scripts/validate-release-env.mjs` validates required public runtime
  values.
- `frontend/apps/mobile-v2/scripts/run-android-release.mjs` creates a profile-specific embedded
  environment and invalidates stale bundles.
- `.github/workflows/mobile-ci.yml` runs mobile quality gates.

Release gaps:

- `frontend/apps/mobile-v2/android/app/build.gradle` signs the `release` build with
  `signingConfigs.debug`.
- There is no current mobile-v2 release/device verification guide for ParkingSession.
- `docs/beta/mobile-release.md` targets legacy Mobile and itself records debug signing.
- No currently verified signed mobile-v2 artifact or ParkingSession device test was found.
- mobile-v2 has no ErrorBoundary/crash-reporting installation or vendor SDK. Web has a
  console/disabled reporting seam, and Azure builds keep it disabled.

## 7. Platform findings

### 7.1 Analytics

The repository has an analytics service, Kafka, inbox/outbox infrastructure, and parking-spot
events. `ParkingOutboxRelay` publishes spot aggregates to `parkio.parking.spot` and session
lifecycle aggregates to `parkio.parking.session`. Analytics currently maps `ParkingSpotClaimed`
to a parking-claim aggregate; that is not one of the required Sprint 1 session product events.

**Producer + consumer status (S1-P0-08 / S1-P0-09):** parking-service emits authoritative outbox
facts with wire types `ParkingSessionStarted`, `ParkingSessionCompleted`, and
`ParkingSessionCancelled`. analytics-service consumes `parkio.parking.session` into canonical
`parking_session_started|completed|cancelled` observations (inbox-deduped). R17–R19 are PASS
end-to-end.

Client interaction events (S1-P0-10) are implemented in mobile-v2
`productAnalytics.ts` as `return_to_car_clicked`, `parking_location_shared`, and
`parking_action_failed` (privacy-safe params only; no coordinates/URLs/sessionIds).
They are not published to the authoritative `parkio.parking.session` Kafka topic.

A case-insensitive repository search still found **no** implementation of:

- `parking_history_deleted`

Controller OpenAPI English descriptions are not event production. R22 remains FAIL until
deletion analytics land.

### 7.2 Privacy and deletion

Owner-scoped reads, opaque foreign-object behavior, gateway authentication, and no-store response
policies are implemented.

Lifecycle privacy:

- Owner-safe single/full hard-delete APIs exist (S1-P0-07; R8/R9 PASS).
- No account-erasure event consumer exists in parking-service.
- `services/parking-service/src/main/java/com/parkio/parking/infrastructure/lifecycle/RetentionCleanupJob.java`
  deletes transport outbox/inbox rows only, not ParkingSession or derived user observations.
- `docs/startup/14-faq.md` says self-service account erasure is not evidenced and directs beta
  deletion requests to `privacy@parkio.dev`.
- A community claim also creates spot status history, a `ParkingSpotClaimed` outbox event, and an
  authoritative `ParkingSessionStarted` session-lifecycle fact (separate topic/aggregate).
  analytics observation. The repository does not state whether those derived records are deleted,
  anonymized, aggregated, or retained when parking history is deleted.

### 7.3 Flags, rollback, and Azure

Smart Return is controlled by server/Web/mobile environment flags, including
`PARKIO_SMART_RETURN_ENABLED`, `VITE_SMART_RETURN_ENABLED`, and
`EXPO_PUBLIC_SMART_RETURN_ENABLED`.
There is no ParkingSession feature flag.

R28 still passes because `services/parking-service/README.md` documents the exact coordinated
rollout and rollback rule for the invariant-changing community claim: every exposed client must
restore an active session, and claims must be disabled or drained before reverting the backend.
The repository also has immutable-image hosted deployment rollback procedures. This is a manual
operational strategy, not a client exposure toggle.

`docker/docker-compose.azure-hosted-beta.yml` enables parking-service and disables tracing for the
constrained profile. `docs/azure/AZURE-RUNTIME-SERVICE-MATRIX.md` records parking and analytics as
enabled. The current production deployment/smoke success is supplied baseline context, but
`scripts/smoke-hosted-beta.sh` checks only parking nearby and direct-service rejection. It does
not call start, active, complete, cancel, history, or deletion. R27 fails.

## 8. Exact Sprint 1 blockers

1. The shared API client has no ParkingSession operations, so Web and mobile-v2 cannot consume
   the five existing endpoints.
2. The canonical mobile-v2 app does not restore or display the ACTIVE session that community
   claim already creates.
3. `Park ettim` lacks a manual session-start flow, session-specific permission/error/retry UX,
   and explicit reconciliation after ambiguous network failure.
4. ~~Elapsed counter / complete / cancel~~ **Done (S1-P0-04).** ~~Return-to-car / share / client
   click events~~ **Done (S1-P0-10; mobile-v2).** Web parity still absent.
5. ~~Deletion backend contract~~ **Done (S1-P0-07).** ~~History/deletion UI~~ **Done (S1-P0-11; mobile-v2).**
6. ~~Lifecycle analytics R17–R19~~ **Done (S1-P0-08/09).** ~~Client R20–R21~~ **Done (S1-P0-10).**
   R22 `parking_history_deleted` still absent.
7. Derived-observation deletion/privacy semantics are documented (R23 PASS); account erasure is not
   implemented.
8. Offline/local session draft behavior is decided as online-only Option A (R16 PASS); no offline
   deletion queue.
9. The optional reminder is only a dormant database/domain field.
10. Web has no ParkingSession data or presentation flow.
11. ~~hosted-beta ParkingSession smoke suite~~ **Added + executed (S1-P0-12).** R27 still FAIL:
    deletion HTTP 500 on current azure image; dirty-tree deploy not authorized.
12. mobile-v2 release proof is incomplete: debug release signing, no current ParkingSession
    device checklist/artifact, and no crash collection.

## 9. Dependency order

1. Freeze privacy deletion semantics, analytics payload policy, and the ParkingSession offline
   decision.
2. ~~Add the missing shared API-client methods and response validation without changing the
   established backend contracts.~~ **Done (S1-P0-01).**
3. ~~Add canonical mobile-v2 active-session query ownership/restoration and make community claim
   invalidate/refetch it.~~ **Done (S1-P0-02).**
4. ~~Build the mobile-v2 manual start and ParkingSession-specific permission/error states.~~
   **Done (S1-P0-03 for start + R15).** ~~Timer/complete/cancel~~ **Done (S1-P0-04).**
   ~~Navigation/share + client click/share analytics~~ **Done (S1-P0-10).**
5. ~~Add backend deletion operations~~ **Done (S1-P0-07; R8/R9).**
   ~~Add authoritative server lifecycle events~~ **Done (S1-P0-08; R17–R19 producers).**
   ~~Ingest lifecycle events in analytics-service~~ **Done (S1-P0-09; R17–R19 E2E).**
   ~~Add mobile-v2 history/deletion UI~~ **Done (S1-P0-11).**
   Remaining: deletion analytics (R22 / S1-DEL-08).
6. Add Web parity using Web-owned keys/query options/hooks.
7. Close hosted-beta smoke, current mobile-v2 release verification/signing, and crash reporting.
8. Implement the optional reminder only after the core lifecycle is accepted.

The recommended next single task is **authorize azure-hosted-beta deploy of S1-P0-01…11 + re-run
ParkingSession smoke for R27**, or Product-selected **R22 / S1-DEL-08**.
S1-P0-01 through S1-P0-12 (suite) are implemented; hosted R27 PASS is blocked on image freshness.
Sprint 1 remains incomplete (R22 FAIL, R24/R26 PARTIAL, R27 FAIL).
