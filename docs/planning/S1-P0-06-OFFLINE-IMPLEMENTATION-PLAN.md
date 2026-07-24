# S1-P0-06 — ParkingSession offline / local-draft implementation plan

**Parent decision:** `docs/architecture/PARKING-SESSION-OFFLINE-DRAFT-DECISION.md`  
**Canonical policy:** Option A — online-only mutations; no durable ParkingSession drafts in Sprint 1.  
**Status:** Planning only — no production code in S1-P0-06.

---

## 1. Goal

Translate the accepted offline decision into atomic future tasks that:

1. **Enforce** the online-only policy where current code is incomplete (complete/cancel offline preflight, copy honesty).
2. **Prove** invariants with tests and smoke coverage.
3. **Keep** S1-D-01 deferred (durable start queue) unless Product reopens D-OFF-01.

This plan does **not** add AsyncStorage drafts, SecureStore session keys, background sync, or auto-replay workers.

---

## 2. Recommended task graph

```
S1-P0-06 (decision) ──COMPLETE──┐
                                ├─► S1-OFF-01  Complete/cancel online preflight
                                ├─► S1-OFF-02  Offline copy honesty (banner / parking strings)
                                ├─► S1-OFF-03  Restart / kill convergence tests
                                ├─► S1-OFF-04  Policy guard tests (no jsonStore/SecureStore parking drafts)
                                ├─► S1-OFF-05  Privacy-safe ambiguous telemetry (optional)
                                ├─► S1-OFF-06  Hosted-beta network-interruption smoke
                                └─► S1-OFF-07  Ops/support note (online-only parking mutations)

Backlog parallel (unrelated to offline): S1-P0-07 deletion APIs
Deferred: S1-D-01 durable start queue (only if D-OFF-01 flips)
```

**Suggested backlog order after S1-P0-06:** proceed to **S1-P0-07**. Schedule S1-OFF-01…04 as small mobile-v2 hardening alongside or immediately after P0-07 if capacity allows; they are not blockers for deletion APIs.

---

## 3. Task catalog

### S1-OFF-01 — Complete/cancel clearly-offline preflight

| Field | Value |
|---|---|
| Priority | P1 (policy enforcement) |
| Owner | mobile-v2 |
| Dependencies | S1-P0-06 ADR |
| Scope | Gate `useTerminalParkingSession` (or banner CTAs) with `useOnlineStatus`; dedicated offline phase/UX; no HTTP when clearly offline |
| Non-goals | Durable intents; auto-replay; changing complete/cancel API |
| Acceptance | Offline complete/cancel never call API; tests cover offline block + online path unchanged |
| Tests | Hook unit tests mirroring start offline test |
| Rollback | Revert gate; ADR still valid |
| Docs | Reference ADR §§10–11 |

### S1-OFF-02 — Offline copy honesty

| Field | Value |
|---|---|
| Priority | P2 |
| Owner | mobile-v2 / Product |
| Dependencies | D-OFF-05 default |
| Scope | Adjust `common.offlineBody` and/or parking-specific strings so ParkingSession is not described as queued; keep share upload queued wording local to share |
| Non-goals | Redesigning OfflineBanner layout |
| Acceptance | No user-visible claim that parking start/leave/cancel will auto-submit later |
| Tests | Snapshot/string tests if present; manual copy review |
| Rollback | Revert strings |
| Docs | ADR §21 |

### S1-OFF-03 — App restart / process-death convergence tests

| Field | Value |
|---|---|
| Priority | P1 |
| Owner | mobile-v2 |
| Dependencies | Existing start/terminal hooks |
| Scope | Tests proving: after simulated remount with empty in-memory keys, active GET decides UI; no auto mutation; ambiguous keys do not survive remount |
| Non-goals | Real process kill in CI |
| Acceptance | Invariants 8–9 covered by tests |
| Tests | RTL/hook tests with remount + mocked active API |
| Rollback | Remove tests only |
| Docs | ADR §§17, 24 |

### S1-OFF-04 — No-persistence guardrails

| Field | Value |
|---|---|
| Priority | P1 |
| Owner | mobile-v2 |
| Dependencies | S1-P0-06 |
| Scope | Grep/architecture test or unit assertion that parking hooks never import `jsonStore` / `secureStore` write paths for session drafts; document forbidden keys |
| Non-goals | Banning share draft location persistence |
| Acceptance | CI fails if ParkingSession draft persistence is introduced without ADR change |
| Tests | Lightweight lint/test in mobile-v2 |
| Rollback | Remove guard if Product activates S1-D-01 |
| Docs | ADR §§15–16 |

### S1-OFF-05 — Privacy-safe ambiguous telemetry

| Field | Value |
|---|---|
| Priority | P3 |
| Owner | mobile-v2 + Data |
| Dependencies | D-OFF-07 |
| Scope | Optional counters: offline_block, ambiguous_transport, reconcile_hit/miss, conflict_category — **no** coords/keys/payloads |
| Non-goals | Full product analytics funnel; R17–R22 session event work |
| Acceptance | Schema review proves no sensitive fields |
| Tests | Payload shape unit test |
| Rollback | Disable events |
| Docs | ADR §23 |

### S1-OFF-06 — Hosted-beta network-interruption smoke

| Field | Value |
|---|---|
| Priority | P2 |
| Owner | QA / Mobile |
| Dependencies | S1-OFF-01 recommended |
| Scope | Manual or scripted smoke: airplane mode before start; kill during submit; reconnect + active restore; complete ambiguous path |
| Non-goals | Automating full device farm |
| Acceptance | Checklist attached to beta runbook with pass/fail |
| Tests | Smoke checklist |
| Rollback | N/A |
| Docs | Ops note |

### S1-OFF-07 — Support / ops note

| Field | Value |
|---|---|
| Priority | P2 |
| Owner | Ops + Mobile |
| Dependencies | ADR |
| Scope | Short note: parking mutations require connectivity; ambiguous “may have worked” → ask user to reopen app / check banner; no manual draft purge needed |
| Non-goals | Account erasure runbook (S1-P0-05) |
| Acceptance | FAQ/support doc aligned with ADR |
| Tests | N/A |
| Rollback | Doc revert |
| Docs | `docs/startup` or ops as chosen by Ops |

### S1-D-01 — Durable offline start queue (DEFERRED)

Only if Product flips D-OFF-01. Requirements remain those in `SPRINT-01-COMPLETION-BACKLOG.md` §5. **Do not start** under S1-P0-06.

---

## 4. Threat / failure outcomes (must remain true)

| Scenario | Required safe outcome |
|---|---|
| Offline before start | Blocked; no draft |
| Disconnect after start dispatch | In-memory ambiguous; reconcile; explicit retry same key |
| App killed during ambiguous start | Key lost; active GET; no auto-replay |
| Reconnect same ACTIVE | Restore banner |
| Reconnect different ACTIVE | Show new ACTIVE; abandon old terminal intent |
| Complete committed, response lost | Reconcile → null ACTIVE → success-equivalent |
| Cancel committed, response lost | Same |
| App killed during complete/cancel | Key lost; restore via active GET |
| Logout with pending attempt | Cleared |
| User switch | Cleared; no cross-user replay |
| Idempotency TTL expiry | New logical attempt if user still acts |
| Device clock changes | Server clock for `endedAt` |
| Location becomes stale | No durable start; new attempt reacquires |
| Auto-replay after intent change | Forbidden |
| Infinite queue retries | No queue |
| SecureStore unavailable | Irrelevant (not used for drafts) |
| Active lookup temporarily unavailable | Ambiguous/reconcileFailed UX; no false success |
| Other device changes session | Server wins on reconcile |
| Long JS suspension | Treat failed HTTP as ambiguous if resumed; kill → restart policy |

---

## 5. Mapping to Sprint backlog

| Plan item | Backlog relationship |
|---|---|
| S1-P0-06 decision | Closes R16 as documented deferral / online-only |
| S1-OFF-01…07 | Hardening under R16 enforcement; do not reopen R16 to PARTIAL |
| S1-D-01 | Remains deferred |
| S1-P0-07 | Next P0 feature task (deletion APIs) — independent |

---

## 6. Explicit non-goals (rejected strategies)

- AsyncStorage / jsonStore ParkingSession drafts
- SecureStore encrypted start coordinates
- Background Expo tasks / TaskManager parking workers
- NetInfo-triggered auto-submit
- Client-controlled `endedAt`
- Persisting idempotency keys across process death
- Reusing share upload offline queue for parking

---

## 7. Definition of Done for this planning doc

- Tasks are atomic and testable.
- Deferred durable queue is clearly separated.
- No task claims offline implementation is already shipped.
- Recommended next **backlog** task after S1-P0-06 remains **S1-P0-07**.