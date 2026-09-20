# Synthetic payload examples (NOT SENT to real Slack)

## 1. Completed registration (UserRegistered envelope → Slack text)

Input envelope (email present in source; stripped before Slack):

```json
{
  "eventId": "11111111-1111-1111-1111-111111111111",
  "eventType": "UserRegistered",
  "aggregateType": "AuthUser",
  "aggregateId": "22222222-2222-2222-2222-222222222222",
  "occurredAt": "2026-09-20T14:00:00Z",
  "version": 1,
  "payload": {
    "userId": "22222222-2222-2222-2222-222222222222",
    "email": "redacted-in-slack@example.com"
  }
}
```

Rendered (illustrative):

```text
*Registration completed*
type=`registration.completed` severity=`info`
env=`acceptance` service=`auth-service`
at=`2026-09-20T14:00:00Z`
subject=`u_…`
outcome=`completed`
source=`auth-outbox/UserRegistered`
```

## 2. Incident then recovery

```json
{"phase":"opened","fingerprint":"parking:err-burst:abc","service":"parking-service","severity":"warning","count":42}
```

```json
{"phase":"recovered","fingerprint":"parking:err-burst:abc","service":"parking-service","severity":"info"}
```

## 3. Local success + offsite failure

```json
{
  "scope": "invite-production",
  "date": "2026-09-20",
  "local_outcome": "success",
  "offsite_outcome": "failed",
  "offsite_reason": "upload"
}
```

## 4. Local success + offsite unknown

```json
{
  "scope": "invite-production",
  "date": "2026-09-21",
  "local_outcome": "success",
  "offsite_outcome": "unknown"
}
```
