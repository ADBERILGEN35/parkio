# Parking Session stale lifecycle — security audit

Focused review of authorization, privacy, and spoofing controls for the ACTIVE
parking-session confirm / remind / auto-complete path.

| | |
|---|---|
| **Scope** | parking-service session HTTP API, stale scheduler, session Kafka events, notification reminder payloads, retention/deletion |
| **Out of scope** | Spot claim economics, media upload malware scanning, admin tooling |
| **Companion** | [`PARKING-SESSION-LIFECYCLE.md`](PARKING-SESSION-LIFECYCLE.md), [`PARKING-SESSION-LIFECYCLE-EVENTS.md`](PARKING-SESSION-LIFECYCLE-EVENTS.md), [`PARKING-SESSION-DELETION-PRIVACY-DECISION.md`](PARKING-SESSION-DELETION-PRIVACY-DECISION.md) |
| **Date** | 2026-07-25 |

## Findings (controls in place)

### Authorization / IDOR — opaque 404

Session mutations (`confirm-active`, `complete`, `cancel`) load with
`findByIdAndUserId`. Foreign or missing ids raise `PARKING_SESSION_NOT_FOUND`
(HTTP **404**), not 403. Delete-one terminal history returns opaque **204** for
missing/foreign ids so ownership is not enumerable.

**Verdict:** IDOR enumeration via status codes is mitigated for these paths.

### No `completionReason` leak on HTTP

`ParkingSessionResponse` exposes `completionType` (`MANUAL` | `AUTO`) but **not**
`completionReason`. Internal reasons (`AUTO_TIMEOUT`, `ADMIN`, …) stay on the
entity/events for ops/analytics, not the public session DTO.

**Verdict:** Client API does not leak fine-grained completion provenance.

### No coordinates in reminder notifications

`ParkingSessionReminderRequestedEvent` carries `sessionId`, `userId`, `stage`,
`startedAt`, `lastConfirmedAt`, `occurredAt` — explicitly **no** latitude,
longitude, address, or geohash (locked by lifecycle event contract tests).

**Verdict:** Reminder fan-out does not re-broadcast parked coordinates.

### Retention / deletion privacy

- Scheduler retention is **off by default**
  (`PARKIO_PARKING_SESSION_RETENTION_ENABLED=false`).
- User hard-delete APIs are owner-scoped; ACTIVE rows are preserved on bulk
  history delete.
- Analytics does not ingest coordinates (see analytics ingestion privacy
  exclusions). Account-erasure of historical analytics remains a known open
  item (PRIV-001) outside this feature’s claim.

### CSRF — N/A for bearer API

Session APIs are Bearer-token JSON APIs behind the gateway. Browser cookie
session CSRF patterns do not apply to the primary mobile/web API client model.
Gateway still enforces authn before injecting `X-User-Id`.

### Rate limits

Backend parking-service does not emit 429 itself. Edge limiting is on
**gateway-service** Redis `RequestRateLimiter`
(`parkio.gateway.rate.limit.rejected.count`). Session start/complete should
inherit gateway route limits; treat sustained abuse as a gateway/tuning concern.

### Event spoofing via gateway secret

`GatewayAuthFilter` requires `X-Gateway-Auth` matching
`PARKIO_GATEWAY_INTERNAL_SECRET` (plus optional rotation list) on all
non-actuator HTTP paths. Direct calls to parking-service without the secret
receive 401. Kafka producers for session lifecycle are parking-service outbox
only; consumers trust broker ACLs + inbox idempotency by `eventId`.

Compromise of `PARKIO_GATEWAY_INTERNAL_SECRET` allows forging `X-User-Id` and
acting as any user against parking-service — this is an intentional trust
boundary (gateway-only ingress).

## Residual risks

| ID | Risk | Severity | Notes |
|---|---|---|---|
| R1 | Gateway internal secret compromise ⇒ full user impersonation on parking APIs | **MEDIUM** | Mitigate with secret rotation (`internal-accepted-secrets`), restricted network publish of service ports on hosted-beta, and host access control. |
| R2 | Operator/DBA with SQL access can mutate sessions without outbox events | **MEDIUM** | Runbook forbids hand SQL transitions; use audited scripts only. |
| R3 | Metrics/logs include `sessionId` / `userId` in application logs (`CONFIRMED_ACTIVE`, `REMINDER_SENT`) | **LOW** | Needed for ops; Loki retention + access controls apply. Metrics never tag by user/session. |
| R4 | `completionType=AUTO` visible to clients reveals timeout completion | **LOW** | Intentional UX signal; fine-grained `completionReason` still withheld. |
| R5 | At-least-once Kafka delivery could re-notify if notification inbox fails open | **LOW** | Inbox claim by `eventId` is the control; monitor notification backlog alerts. |
| R6 | Enabling retention hard-deletes history without a separate legal hold workflow | **LOW** while default-off; becomes **MEDIUM** if enabled in production without policy review. |

## Test anchors

- Controller/OpenAPI: foreign session → 404 examples
- `ParkingSessionLifecycleEventContractTest`: reminder payload privacy lock
- Gateway filter fail-closed without `PARKIO_GATEWAY_INTERNAL_SECRET`

## Recommendations

1. Keep service ports off public NIC on hosted-beta (gateway-only ingress).
2. Rotate gateway internal secret with overlapping accepted secrets during
   deploys.
3. Do not enable retention until product/legal sign-off and a restore drill.
4. Keep analytics ignore-path for `ParkingSessionReminderRequested` on every
   consumer deploy.