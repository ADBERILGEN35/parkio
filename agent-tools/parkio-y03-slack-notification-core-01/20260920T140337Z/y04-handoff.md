# Y04 handoff — behavior analytics

## Ready inputs from Y01–Y03

- Y01 `behavior-analytics-contract-v1.md` remains the analytics contract.
- Y03 Slack must **not** become a user-behavior stream. Detailed behavior belongs in Y04 analytics.
- Registration Slack carries only pseudonym + env + completion — no funnel analytics.

## Suggested Y04 first slice

1. Authoritative **server** events for first meaningful product action (prefer Kafka session/spot events over client-only).
2. Privacy-preserving aggregates; no Slack dumping of per-user trails.
3. Do not activate real user tracking in Y03 leftovers.

## Explicit non-goals for Y04 borrowed from Y03

- Do not close G01/G02 via analytics alone without their own criteria.
- Do not require Slack for analytics acceptance.
- MinIO risk acceptance remains independent (`NOT_GRANTED`).

## Y03 leftovers for later

- Kafka consumer sidecar for `parkio.auth.user` → enqueue
- Runtime incident producer (AM fingerprint fan-in or error-budget adapter)
- Bot-based thread update (optional)
