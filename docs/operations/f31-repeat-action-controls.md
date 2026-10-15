# F-31 repeat-action controls — investigation record

Issue: #384 · date: 2026-10-15 · **no product fix authorized**

## Controls exercised (synthetic fixtures only)

| Area | Control | Evidence |
|---|---|---|
| Gamification | Inbox claim by `eventId` before rewards | `duplicateEventIsSkipped`, claim redelivery in existing suite; `f31DistinctEventIdsStillAwardSeparately` |
| Gamification | Points transaction idempotency key | Existing suite |
| Moderation | Duplicate report (reporter+target+reason) | `duplicateReportIsRejected` |
| Moderation | Duplicate appeal (case+user) | `f31DuplicateAppealIsRejected` |
| Moderation | Admin-only sanctions | `f31NonAdminCannotSuspend` |
| Moderation | Erased-user fence | Existing N-07 tests |

## Disposition

**Not reproducible** as “absence of all controls.” Event-id dedupe, duplicate
report/appeal checks, and admin role enforcement hold under synthetic tests.

Semantically repeated actions that carry **new** event IDs still award points —
that is the current idempotency contract (per eventId), not a verified abuse
bypass of the inbox. Any rate-limit / semantic-dedupe product policy is a
separate owner decision; this investigation does not invent one.

## Test counts

Recorded in the PR / issue close comment from:

```
./gradlew :services:gamification-service:test --tests '*GamificationApplicationServiceTest'
./gradlew :services:moderation-service:test --tests '*ModerationApplicationServiceTest'
```

plus integrationTest tasks (existing Postgres ITs only; no new IT required for
this investigation-only item).
