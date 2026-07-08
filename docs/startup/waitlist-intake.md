# Parkio Waitlist Intake Contract

Status: proposed contract only. Do not treat this as an implemented backend.

## Goal

Collect the minimum useful hosted-beta interest signal without inventing demand,
tracking users beyond consented beta communication, or creating a general CRM.

## Minimal Data Model

`waitlist_interest`

| Field | Required | Notes |
|---|---:|---|
| `id` | yes | Server-generated UUID. |
| `email` | yes | Lowercase normalized email. Store as PII; restrict access. |
| `emailHash` | yes | HMAC-SHA-256 or equivalent keyed hash for duplicate/rate checks without exposing raw email in logs. |
| `consentTimestamp` | yes | Client-submitted timestamp may be accepted for UX, but server must stamp canonical receipt time. |
| `city` | no | Free-text city/general area, trimmed and length-limited. No precise coordinates. |
| `role` | no | One of `driver`, `tester`, `partner`. |
| `source` | yes | Example: `parkio.dev-landing`. |
| `createdAt` | yes | Server timestamp. |
| `ipHash` | yes | Short-retention keyed hash for rate limiting/abuse review. |
| `userAgentHash` | no | Optional abuse signal; avoid storing full UA unless needed. |

## Smallest Public API

`POST /api/v1/waitlist`

Request:

```json
{
  "email": "driver@example.com",
  "consentTimestamp": "2026-07-08T00:00:00.000Z",
  "city": "Izmir",
  "role": "tester",
  "source": "parkio.dev-landing"
}
```

Response:

```json
{
  "status": "accepted"
}
```

Return the same `202 Accepted`-style body for new and duplicate emails to avoid
enumeration. Do not return whether an email was already present.

## Validation

- `email`: required, normalized, max 254 chars.
- `consentTimestamp`: required ISO-8601 timestamp; server records canonical receipt time.
- `city`: optional, max 120 chars, no coordinates.
- `role`: optional enum: `driver`, `tester`, `partner`.
- `source`: required allow-list value, initially `parkio.dev-landing`.

## Abuse Controls

- Rate limit by IP hash and email hash at the gateway/API layer.
- Add a honeypot field only if spam appears; keep it absent from the initial public contract.
- Do not log raw email, city, IP, or full user agent.
- Store raw email encrypted at rest if available; otherwise restrict DB role access and exports.

## Admin Export

Smallest safe path: authenticated admin-only CSV export from the service that owns
the table, with audit logging and explicit date filters. Export columns should be:
`email`, `city`, `role`, `source`, `createdAt`, `consentTimestamp`.

Do not expose export to moderators. Do not include IP/user-agent hashes in normal export.

## Service Recommendation

No existing service is clearly dedicated to marketing/waitlist ownership. The
smallest safe implementation is a new narrow waitlist module in the gateway-facing
public API layer only if it can be isolated with its own table, rate limit, and
admin-only export. Otherwise keep the frontend placeholder until a dedicated
waitlist owner/service is selected.
