# ParkingSession offline and local-draft decision

**Decision ID:** PARKIO-ADR-PARKING-SESSION-OFFLINE-001  
**Sprint task:** S1-P0-06  
**Status:** Accepted for Sprint 1 (Product may reopen durable drafts later via S1-D-01)  
**Date:** 2026-07-24  
**Scope:** Decision and architecture only — no AsyncStorage drafts, offline queues, background sync, UI, or production code are introduced by this document.  
**Baseline evidence:** Sprint 01 audit + mobile-v2 S1-P0-01…04 behavior + S1-P0-05 deletion/privacy ADR.

---

## 1. Status

This decision defines how ParkingSession **mutations and reads** behave when the device is offline, when transport results are ambiguous, and across app restart / logout / user switch.

It **closes R16** by explicitly selecting an **online-only mutation policy** for Sprint 1, with bounded **in-memory** attempt metadata for ambiguous retries while the authenticated JS lifecycle remains alive.

It does **not** claim:

- durable offline ParkingSession support
- persisted drafts or idempotency keys
- background automatic replay
- that `shareDraftStore` or media upload offline queue is a ParkingSession pattern
- GDPR/KVKK compliance

**Related:** S1-D-01 remains the deferred durable-start-queue alternative; S1-P0-05 covers deletion privacy of coordinates after commit.

---

## 2. Context

Canonical mobile-v2 can start, restore, time, complete, and cancel ACTIVE sessions. Manual start already blocks when NetInfo reports offline and keeps one in-memory idempotency key for ambiguous transport. Complete/cancel keep separate in-memory keys and reconcile via active-session fetch. ACTIVE state is always restored from the backend.

Without an explicit decision, implementers might copy the **share-flow** offline draft + auto-upload path (`shareDraftStore` + `useDraftUpload`), which persists location and auto-retries — inappropriate for high-sensitivity ParkingSession start coordinates and lifecycle mutations.

WP-07 already lists offline mutation queues as a **non-goal** (`frontend/architecture/sprint-3/WP-07-MOBILE.md`). This ADR scopes that deferral specifically to ParkingSession.

---

## 3. Repository evidence

### 3.1 Current ParkingSession client behavior

| Evidence | Path / fact |
|---|---|
| Manual start | `useStartParkingSession.ts` — `useOnlineStatus` gate; caller-owned `keyRef`; ambiguous reuse; ACTIVE conflict reconcile |
| Complete/cancel | `useTerminalParkingSession.ts` — separate keys; mutual exclusion; sessionId + identity binding; **no** online preflight today |
| Active restore | `activeParkingSessionQueryOptions` + `useActiveParkingSession` — GET active; HTTP 204 → `null` |
| Banner / timer | `ActiveParkingSessionBanner.tsx` + `useNowTicker` — elapsed from server `startedAt`; no offline mutation |
| Claim → COMMUNITY session | `SpotActions.tsx` `claimMutation` — React Query mutation; **new** `createIdempotencyKey()` per press; invalidates `activeSession` |
| Shared API client | `@parkio/api-client` start/active/complete/cancel/history — no offline queue |
| Query defaults | `query-client.ts` — `networkMode: 'online'`; mutations `retry: false` |
| Online wiring | `QueryProvider.tsx` NetInfo → `onlineManager`; AppState → `focusManager` |
| Connectivity hook | `useOnlineStatus.ts` — optimistic `true` until NetInfo; `isConnected !== false` |
| Session cache isolation | `sessionQueryCache.ts` + `SessionQueryCacheSync.tsx` — clears `parkingKeys.sessionsRoot()` on userId change |
| Location | `features/map/hooks.ts` — `requestForegroundPermissionsAsync` + `getCurrentPositionAsync` |

### 3.2 Local persistence inventory (mobile-v2)

| Mechanism | Used for today | Suitable for session coords? | Suitable for session idempotency keys? |
|---|---|---|---|
| `jsonStore` (document-dir JSON) | locale, onboarding, **share drafts** (incl. lat/lng), recent searches | **No** for ParkingSession start | **No** (non-secret store) |
| `secureStore` (Expo SecureStore) | access/refresh tokens + userId only | Not selected for Sprint 1 | Not selected for Sprint 1 |
| React Query in-memory cache | active session, nearby, etc. | Not a mutation queue | No |
| Persisted React Query | **Absent** | N/A | N/A |
| Zustand persist middleware | **Absent** for parking | N/A | N/A |
| AsyncStorage | **Not used** in mobile-v2 | No | No |
| SQLite | **Absent** | N/A | N/A |
| Background TaskManager | **Not evidenced** for ParkingSession | N/A | N/A |
| In-memory hook refs | start/complete/cancel attempt keys | Coords only in HTTP body during submit | **Yes** (Sprint 1 path) |

### 3.3 Backend idempotency and lifecycle

| Evidence | Fact |
|---|---|
| `IdempotencyService` | Key required; scoped by `user_id` + HTTP method + key; fingerprint/path conflict → `IDEMPOTENCY_KEY_CONFLICT` |
| TTL | `parkio.idempotency.ttl` default **24h** (`application.yml` / `PARKIO_IDEMPOTENCY_TTL`) |
| Replay | COMPLETED records replay stored `ParkingSessionResponse` (includes lat/lng) until expiry |
| Expired keys | Opportunistic delete on reuse after `expires_at` |
| `completeSession` / `cancel` | Server sets `endedAt` via `Clock.instant()` — **no client-controlled endedAt** |
| ACTIVE uniqueness | Partial unique index one ACTIVE per `user_id` |

### 3.4 Contrasting offline patterns (must not be confused)

| Pattern | Behavior | Applies to ParkingSession? |
|---|---|---|
| Share draft + photo | Persist draft + location via `jsonStore`; upload auto-retries on reconnect | **No** |
| Spot photo fetch | Explicitly never persist | N/A |
| OfflineBanner / `common.offlineBody` | Generic “some actions will be queued” copy | **Misleading** for ParkingSession — policy is **not** queued |

---

## 4. Terminology

| Term | Meaning |
|---|---|
| **Clearly offline** | NetInfo / `useOnlineStatus` reports not connected (`isConnected === false`) |
| **Ambiguous transport** | Request dispatched; `NetworkError`, `TimeoutError`, or indeterminate result |
| **In-memory attempt** | Hook `useRef` / React state for idempotency key + phase; dies on process death |
| **Durable draft** | Persisted to disk/SecureStore across restart |
| **Auto replay** | Client submits without a new explicit user tap after reconnect/restart |
| **User-confirmed retry** | Explicit CTA after reconnect |
| **Server wins** | `GET /active` overrides local optimism |
| **Logical attempt** | One user intent bound to one idempotency key until conclusive success/rejection/abandon |

---

## 5. Current behavior

| Operation | Clearly offline | Persist draft | Persist coords | Persist key | Auto replay | Notes |
|---|---|---|---|---|---|---|
| Manual start | Blocked (`phase: offline`) | No | No | In-memory only after submit starts | No — explicit `retry` | Location foreground-only |
| Complete | No online gate today | No | N/A | In-memory | No | Mutual exclusion with cancel |
| Cancel | No online gate today | No | N/A | In-memory | No | ConfirmModal UX |
| Active GET | Query `networkMode: online` | No | Cache may hold while ACTIVE | N/A | Refetch on reconnect/focus | Authoritative restore |
| Community claim | RQ mutation; fails if offline | No | Spot coords already on server | New key each press | RQ `retry: false` | Creates COMMUNITY session |

---

## 6. Offline operation inventory

Same as §5, plus Sprint 1 **hardening gaps** (still not durable offline):

- Complete/cancel lack explicit offline preflight.
- Generic `common.offlineBody` implies queuing.
- Claim has no ParkingSession-specific offline CTA (toast on error only).
- App kill drops in-memory keys → reconcile via active lookup after restart.

---

## 7. Candidate strategies

### Option A — Online-only mutation policy (leading)

Block mutations while clearly offline; keep bounded in-memory keys for ambiguous requests; reconcile via active lookup; require explicit user retry; no disk drafts.

| Dimension | Assessment |
|---|---|
| Privacy | Best — no local precise coords |
| Accidental submission | Low |
| Duplicate mutation | Controlled by in-memory key reuse while alive |
| Stale location | High protection — no auto-submit of old fix |
| Complexity | Lowest; largely matches current code |
| Background reliability | N/A — no background replay |
| Account isolation | Matches existing identity checks |
| Testability | High |

### Option B — Encrypted local draft

Persist bounded draft; user confirms before submit; short expiry; purge on logout. Requires Product/Security approval for coordinates. Medium–high complexity; stale location risk remains.

### Option C — Durable offline queue + auto replay

Persist mutation + key; auto-replay on reconnect. Worst privacy for start coords; high intent-drift risk; unreliable background execution; highest complexity. Rejected for Sprint 1.

### Option D — Hybrid

Start online-only; optional durable terminal intents without coords; no auto-replay. Medium complexity; little Sprint 1 value versus A for short sessions. Deferred with S1-D-01 family if Product insists later.

**Recommendation:** **Option A** for Sprint 1. Defer B/C/D to **S1-D-01** only if Product later requires durable start.

---

## 8. Canonical decision

**Accepted policy (Sprint 1):** Online-only ParkingSession mutations with server-authoritative ACTIVE restoration.

| Operation | Clearly offline | Persist draft | Persist coordinates | Persist key | Auto replay | Reconcile | User confirmation |
|---|---:|---:|---:|---:|---:|---:|---:|
| Manual start | **No** (block) | **No** | **No** | **No** (in-memory only) | **No** | Yes (active GET) | Yes for retry |
| Complete | **No** (block) | **No** | N/A | **No** (in-memory only) | **No** | Yes | Yes for retry |
| Cancel | **No** (block) | **No** | N/A | **No** (in-memory only) | **No** | Yes | Yes for retry |
| Active restore | Read when online | **No** | Cache only while ACTIVE | N/A | Refetch on reconnect | Server wins | N/A |
| Community claim | **No** durable queue | **No** | Spot already server-side | Ephemeral per attempt | **No** | Invalidate active | User re-taps claim |

**Hard rules:**

1. Never persist precise ParkingSession start coordinates on device.
2. Never auto-replay start/complete/cancel after reconnect, restart, or background.
3. Never treat React Query cache or share drafts as a ParkingSession mutation queue.
4. After process death, active-session lookup is authoritative; user must explicitly retry if still needed.
5. `endedAt` remains **server-controlled** (`ParkingSessionService` + `Clock`).

---

## 9. Manual start policy

| Situation | Required behavior |
|---|---|
| Clearly offline before location | Block; offline UX; no key; no coords stored |
| Offline after location acquired, before HTTP | Block if offline before dispatch; discard ephemeral fix; do not persist |
| Disconnect during request | Keep in-memory key; reconcile active; if null → ambiguous + explicit retry with **same** key and **same** request body fingerprint |
| Persist coords | **Forbidden** |
| Local park-here draft | **Forbidden** in Sprint 1 |
| Auto-replay | **Forbidden** |
| ACTIVE exists on retry | Reconcile; surface existing ACTIVE; clear start key |
| User moved before retry | Same ambiguous attempt: keep coupled body+key. New logical attempt: reacquire location + new key |
| App kill mid-start | Key lost; relaunch fetches active — if present restore; else fresh start |

---

## 10. Complete policy (“Ayrıldım”)

| Rule | Decision |
|---|---|
| Clearly offline | Block with offline UX (harden if missing) |
| Persist offline complete intent | **No** |
| Optimistic UI complete before server | **No** |
| Elapsed timer while pending | Continues from `startedAt` until ACTIVE cache cleared |
| Auto-replay | **No** |
| Server already terminal | Reconcile → active null → clear attempt |
| Different ACTIVE after reconnect | Do **not** complete the new session; abandon prior attempt |
| `endedAt` | Server clock only |
| Pending lifetime | In-memory for current auth app lifecycle only |

---

## 11. Cancel policy

Same dimensions as complete, with a separate idempotency key and ConfirmModal.

| Extra rule | Decision |
|---|---|
| Must not cancel newer/different session | `sessionId` binding; abandon if active id ≠ target |
| Offline cancel draft | **No** |
| Auto-replay | **No** |

---

## 12. Active-session restoration policy

| Rule | Decision |
|---|---|
| Source of truth | Backend GET active (204 → null) |
| Offline | Do not invent ACTIVE from drafts; no offline success claims |
| Reconnect / focus | Existing RQ `refetchOnReconnect` / `refetchOnWindowFocus` |
| After restart | Fresh fetch; no replay of prior in-memory attempts |

---

## 13. Ambiguous-result policy

| Aspect | Start | Complete / Cancel |
|---|---|---|
| Metadata in memory | key + phase | key + op + sessionId |
| Persist metadata | No | No |
| Key survives restart | **No** | **No** |
| Before user retry | Active refetch | Active refetch |
| Same key on retry | Yes while ambiguous + same identity | Yes while ambiguous + same sessionId + same op |
| New key | Conclusive reject, reset, identity change, new intent | Same + sessionId change |
| Abandon | Success, resolved ACTIVE conflict, logout | Active null or different id, logout |
| After backend TTL (24h) | Client does not retain keys across TTL without persistence | Same |
| UX | Never claim success/failure when commit unknown | Same |

---

## 14. Idempotency-key policy

| Store | Start | Complete | Cancel |
|---|---|---|---|
| Medium | In-memory `useRef` only | In-memory only | In-memory only |
| Bound to | userId + sessionEpoch | userId + sessionEpoch + sessionId + op | Same |
| Logout / user switch | Cleared | Cleared | Cleared |
| Backend retention | 24h default | 24h | 24h |

**Claim:** continues to mint a **new** key per user press (`SpotActions`); unifying claim ambiguous-reuse is out of Sprint 1 unless Product prioritizes a claim-hardening task.

---

## 15. Precise-coordinate policy

| Rule | Decision |
|---|---|
| AsyncStorage / jsonStore / SecureStore / SQLite | **Must not** store ParkingSession start coords in Sprint 1 |
| Reduced-precision local draft | **Not** accepted as Sprint 1 substitute |
| Retention | Ephemeral in request body / ACTIVE query cache only |
| Logout / success / abandon | Clear attempt state; RQ session roots cleared on user switch |
| Crash reports / logs | Must not include lat/lng (align with S1-P0-05) |
| Device backup | No Parkio session draft files under this policy |

---

## 16. Local-storage policy

| Datum | Allowed store (Sprint 1) |
|---|---|
| Precise start coordinates | Memory during submit; RQ cache while ACTIVE |
| Idempotency keys | Memory only |
| sessionId / op / retry metadata | Memory (+ RQ ACTIVE payload for sessionId) |
| Share draft location | Existing share product only — **not** a ParkingSession draft |

---

## 17. App restart policy

| Mid-operation kill | Lost | Restored | Auto-replayed |
|---|---|---|---|
| Location acquisition | Local fix | None | No |
| Start submit | In-memory key | Active GET | No |
| Complete / cancel | In-memory keys | Active GET | No |

Stale drafts do not exist. Newer ACTIVE from another device is protected because terminal ops require current bound `sessionId`.

---

## 18. Background / foreground policy

| Topic | Decision |
|---|---|
| Background mutations | **Not permitted** as a product feature |
| AppState | Focus refetch for reads; no mutation worker |
| Foreground location | Remains required for start |
| Reconnect while backgrounded | May refetch queries when focused; **must not** auto-submit parking mutations |
| Notifications | No ParkingSession offline-completion notifications in Sprint 1 |

---

## 19. User / auth isolation

Pending attempts bind to `userId` + `sessionEpoch` (+ `sessionId` for terminal).

| Event | Behavior |
|---|---|
| Logout / user switch | Clear in-memory attempts; `clearUserSessionQueries` |
| Token expiry | Unauthorized clears terminal attempt; no cross-user replay |
| Shared device | No durable draft → no cross-account disk residue |

---

## 20. Conflict and stale-state matrix

| Scenario | Safe outcome |
|---|---|
| Offline before start | No HTTP; offline UX |
| Other device creates ACTIVE while this device was offline | Next online start hits ACTIVE conflict → reconcile existing |
| Offline complete/cancel intent | Not persisted; user must act online |
| Pending complete then other device cancels | Reconcile → active null → treat done |
| Pending cancel then other device completes | Same |
| Pending + logout / sessionId change | Drop attempt |
| Idempotency TTL before retry | No client persistence across 24h; new attempt = new key |
| `NOT_ACTIVE` / `NOT_FOUND` | Reconcile; clear if target no longer ACTIVE |
| `ACTIVE_PARKING_SESSION_EXISTS` | Reconcile existing ACTIVE |
| Active null / same / different | Clear / allow same-key retry / abandon prior terminal |

---

## 21. UX semantics

| State | Principle |
|---|---|
| Offline before action | Internet required — **no** “will queue” |
| Location unavailable | Existing permission/settings phases |
| Request may have succeeded | Could not confirm — check status or try again |
| Retry available | Explicit CTA; same logical attempt when ambiguous |
| Draft saved / expired | **Not used** in Sprint 1 |
| Session changed elsewhere | Show restored ACTIVE; abandon stale terminal intent |
| Already completed | Banner gone after reconcile |
| Sign-in / account changed | Attempts cleared |

Avoid technical idempotency jargon; never claim success before confirmation; never claim failure when commit is unknown.

---

## 22. Security / abuse analysis

| Risk | Mitigation |
|---|---|
| Replaying old start / stale location | No durable key/coords; reacquire on new attempt |
| Duplicate rewards / second ACTIVE | Server unique ACTIVE + idempotency |
| Cancel/complete wrong session | sessionId binding + abandon on mismatch |
| Cross-account draft leakage / key theft | No drafts; keys not on disk |
| Background replay / queue poisoning | Forbidden / no queue |
| Clock manipulation | Server `endedAt` and idempotency expiry |
| Coords after logout | Cache clear + no disk coords |
| Unbounded auto-retry | User-driven only; RQ mutation `retry: false` |

---

## 23. Observability policy

Allowed future telemetry: operation type, online/offline boolean, ambiguous vs offline phase, retry count bucket, reconcile outcome category, conflict category.

**Must not include:** precise coordinates, idempotency keys, raw payloads, tokens, raw error bodies.

---

## 24. Testable invariants

1. Clearly offline start never submits and never schedules later auto-submit.
2. Precise start coordinates are never written to jsonStore/SecureStore/AsyncStorage/SQLite.
3. Ambiguous retry of the same logical attempt reuses the same idempotency key (and fingerprint body for start).
4. Complete/cancel never execute against a different `sessionId` than the bound target.
5. Logout/user switch removes pending attempt state and session query cache.
6. No automatic replay creates a second ACTIVE session.
7. Stale pending terminal cannot clear a newer unrelated ACTIVE.
8. App restart converges through backend active-session lookup.
9. UI never claims success before confirmed response or successful reconcile.
10. No persisted expired attempt can be replayed (none persist).
11. No infinite automatic retry loop.
12. No background ParkingSession mutation submission.
13. Share-flow offline upload is not invoked by ParkingSession hooks.
14. Server `endedAt` is never forged by the client.

---

## 25. Product decisions still required

| ID | Decision | Options | Recommended default | Blocking? | Owner |
|---|---|---|---|---|---|
| D-OFF-01 | Allow durable start draft later? | Keep A / S1-D-01 | Keep A | Non-blocking Sprint 1 | Product + Privacy |
| D-OFF-02 | Persist terminal intents without coords? | No / hybrid | No | Non-blocking | Product |
| D-OFF-03 | Auto-replay vs always confirm | Auto / confirm | **Confirm** | Accepted | Product |
| D-OFF-04 | Ambiguous attempt max age while app alive | Lifecycle / N min | App lifecycle | Non-blocking | Mobile |
| D-OFF-05 | Align generic offline banner copy | Keep / fix “queued” | Fix | Non-blocking | Product + Mobile |
| D-OFF-06 | Claim offline UX parity | Toast / explicit offline | Explicit message | Non-blocking | Product |
| D-OFF-07 | Privacy-safe ambiguous telemetry | Off / on | On for beta ops | Non-blocking | Data + Mobile |
| D-OFF-08 | Retry after >24h ambiguity | New attempt | New attempt | Accepted | Backend Architecture |

---

## 26. Consequences

### Positive

- Matches S1-P0-03/04 and WP-07 non-goals.
- Avoids local precise-coordinate retention (aligns with S1-P0-05).
- Clear support story: parking changes need a connection.

### Negative / accepted costs

- User cannot start/complete/cancel while offline.
- App kill during ambiguous request loses idempotency key — mitigated by ACTIVE unique index + reconcile / `NOT_ACTIVE` paths.
- Generic “queued” copy is inaccurate until updated.

---

## 27. Rejected alternatives

| Alternative | Why rejected (Sprint 1) |
|---|---|
| Copy `shareDraftStore` for parking start | Persists location; auto-upload semantics |
| SecureStore encrypted start draft | Product need unproven; stale location risk |
| Durable complete/cancel queue | Wrong-session risk; little benefit |
| Auto-replay on NetInfo reconnect | Intent drift; background surprises |
| Persist idempotency keys across kill | Complexity + secure binding + TTL alignment |
| Client-controlled `endedAt` | Backend uses server `Clock` |
| RQ cache as offline queue | Reads ≠ mutation durability |
| Background Expo tasks for parking | No infra; unreliable |

---

## 28. Definition of Done for future implementation

Future work may claim this policy is **enforced** only when:

1. Start/complete/cancel clearly offline paths are consistent and tested.
2. No ParkingSession draft files exist in jsonStore/SecureStore.
3. Ambiguous key reuse + identity/session binding tests remain green.
4. Restart tests prove active GET convergence without auto-replay.
5. Offline banner/copy does not promise ParkingSession queuing.
6. Hosted-beta network-interruption smoke covers start + terminal ambiguous cases.
7. S1-D-01 remains deferred unless Product reopens it.

**This ADR alone does not implement offline support.**

---

## Document control

| Field | Value |
|---|---|
| Authors | Sprint 01 S1-P0-06 decision task |
| Approvers needed to reopen drafts | Product, Privacy/Security |
| Supersedes | Generic WP-07 offline deferral for ParkingSession scope |
| Next backlog feature task | S1-P0-07 (deletion APIs); offline hardening tasks listed in the companion plan |