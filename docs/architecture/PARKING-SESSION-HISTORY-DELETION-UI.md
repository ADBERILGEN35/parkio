# ParkingSession history and deletion UI (mobile-v2)

**Sprint task:** S1-P0-11  
**Date:** 2026-07-24  
**Status:** COMPLETE (client UI + shared delete API methods)  
**Related:** `PARKING-SESSION-DELETION-PRIVACY-DECISION.md`, S1-P0-05/07

## Placement

- Route: `app/(main)/profile/parking-history.tsx`
- Entry: Profile tab `ListRow` → `/(main)/profile/parking-history`
- No new tab; ACTIVE remains Map banner only

## History query

- API: `GET /parking/sessions/history` via `parkingApi.getParkingSessionHistory`
- React Query: `parkingSessionHistoryInfiniteQueryOptions` + `useInfiniteQuery`
- Key: `parkingKeys.sessionHistory(size)` under user-scoped `sessionsRoot`
- Page size: 20; cursor from `nextCursor`; backend order `startedAt DESC, id DESC`
- Defensive filter: only `COMPLETED` / `CANCELLED` rows are deletable

## Deletion

- Shared client: `deleteParkingSession(sessionId)`, `deleteParkingSessionHistory()`
- Paths: `DELETE /parking/sessions/{id}`, `DELETE /parking/sessions/history`
- No body, no client userId, no Idempotency-Key
- Online-only; blocked when known offline; no queue / no persistence
- Confirm via existing `ConfirmModal`; no undo; no optimistic removal
- Single delete: after 204, remove row from infinite pages for current user
- Delete-all: after 204, set history to empty pages; never clear ACTIVE query
- Opaque 204 treated as success; 409 → invalidate ACTIVE + history

## Privacy

- In-memory React Query only
- No coordinates in row UI; no session UUID shown
- No client deletion analytics (`parking_history_deleted` remains R22 FAIL)

## Verification

```bash
cd frontend/packages/api-client && npx vitest run src/parking.session.test.ts
cd frontend/apps/mobile-v2 && npm test -- --testPathPattern="s1p011|parkingHistory" --runInBand
cd frontend/apps/mobile-v2 && npm test -- --runInBand && npm run typecheck
```

## Remaining limitations

- R22 `parking_history_deleted` not implemented
- Web / legacy-mobile parity not in scope
- Account erasure / PRIV-001 not in scope