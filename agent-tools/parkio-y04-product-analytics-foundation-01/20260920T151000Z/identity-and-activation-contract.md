# Y04 — Identity and activation contract

## Consent

| State | Capture | Vendor outbound | Queue |
|-------|---------|-----------------|-------|
| `unset` (default) | No | No | Empty / discarded |
| `granted` | Yes (approved inventory) | Only if vendor flag + key + host | Bounded flush |
| `denied` | No | No | Discarded on transition |

UI: Preferences checkbox/toggle (web PreferencesCard, mobile preferences). No new privacy microservice.

**Revocation:** discards unsent queue; does not flush pre-revoke history.  
**Re-enable:** starts a **new** analytics session; does **not** upload pre-consent history.

## Identity

| Concept | Policy |
|---------|--------|
| `analyticsSessionId` | Random local id; rotated on consent grant and logout |
| `distinctId` | Optional **backend-provided pseudonym only**; reject `@` / empty / >128 chars |
| Email / raw userId | **Forbidden** |
| Alias anonymous → authenticated | **Not implemented** — no silent join |
| Logout / account switch | `resetIdentity()`: end session, clear distinct, discard queue, rotate session |
| Shared device | New session after logout; User A queue never reassigned to User B |

Authenticated user timelines that require server HMAC pseudonym remain **blocked** until backend erasure/pseudonym API is available. Anonymous session analytics works with consent alone.

## Persistence

- Web: `localStorage` keys `parkio.analytics.consent.v1` / `session.v1` / `distinct.v1`
- Mobile-v2: `jsonStore` bag `product-analytics-v1` (non-secret)

Analytics IDs are **not** placed on public APIs or Prometheus labels.
