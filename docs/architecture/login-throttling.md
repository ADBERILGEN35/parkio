# Login throttling keyed by account and client (CL-F15)

Status: implemented 2026-10-07 (owner decision P3). Replaces the e-mail-only hard lock of
`RedisLoginFailureTracker`.

## Problem

The previous tracker counted failed logins per normalized e-mail and locked the e-mail for 30 s,
5 min or 1 h after 5, 10 or 20 failures; the lock was checked before the password. Anyone who knew an
e-mail could keep that account locked from anywhere, and a password reset did not clear the counter.

## Trust model: who is the client

- The gateway resolves the client IP with `ClientIpResolver` (peer address, or the right-most
  untrusted `X-Forwarded-For` hop behind a configured trusted proxy) and stamps it on every routed
  request as `X-Parkio-Client-Ip` (`ClientIpHeaderGlobalFilter`). Any client-supplied copy of that
  header is stripped first, like `X-User-*` and `X-Gateway-Auth`.
- Hosted-beta runs the gateway with `server.forward-headers-strategy=framework` (#161). Spring's
  `ForwardedHeaderTransformer` then runs before any gateway filter, replaces the peer address with
  `InetSocketAddress.createUnresolved(<first X-Forwarded-For entry>)` and removes the forwarding
  headers (Spring Web 6.2; proven by `ClientIpHeaderGlobalFilterTest` and `ClientIpResolverTest`).
  An unresolved address has no `InetAddress`, and until this change `ClientIpResolver` returned null
  for it: in hosted-beta every anonymous client shared the `unknown` bucket of the gateway's per-IP
  rate limits and of the waitlist IP limit. The resolver now reads the host string as an IP literal
  (never DNS; host names are refused), which fixes those limits as well.
- The stock transformer prefers the RFC 7239 `Forwarded` header over `X-Forwarded-For`, and Caddy
  passes `Forwarded` through unchanged. The gateway therefore replaces Boot's transformer with
  `EdgeOwnedForwardedHeaderTransformer`, which drops any `Forwarded` header first, and Caddy also
  removes it (`header_up -Forwarded`); only the edge-owned `X-Forwarded-For` can set the address. Caddy, the only internet-facing ingress, replaces
  `X-Forwarded-For` with the connecting client's address for untrusted clients, so the value the
  gateway resolves is the edge-observed client. In that mode the gateway's trusted-proxy list plays no
  part; the edge owns the header. A caller that reached the gateway without passing Caddy could pick
  its own `X-Forwarded-For`; hosted-beta publishes no gateway port, so such a caller is already inside
  the backend network.
- auth-service reads `X-Parkio-Client-Ip` only on a request that `GatewayAuthFilter` authenticated
  with the shared gateway secret (request attribute `parkio.gatewayAuthenticated`), and only when it
  is an IP literal (`ClientIdentityResolver`). Anything else falls back to the shared `unknown`
  client, which is the pre-CL-F15 behaviour for that account.

## Throttling

Counters live in Redis under SHA-256 digests of the e-mail and the client (no raw e-mail or address in
the key space); the tiers are in `LoginThrottlePolicy`.

| Counter | Window | Effect |
|---|---|---|
| (account, client) failures | 24 h after the last failure | the pair waits 30 s from the 5th failure, 5 min from the 10th, 1 h from the 20th |
| account failures (all clients) | 1 h after the last failure | from the 50th failure, every client of the account waits 10 s per further failure (soft cap; never longer) |

A refused attempt (pair or account still waiting) is not counted as a failure, so retrying during a
wait does not escalate the tier. A throttled attempt is answered exactly like a wrong password
(`INVALID_CREDENTIALS`), also when the password was right; logs and the `login_lockouts` counter
(now "attempts refused by the throttle") see the difference. A single client cannot reach the account
cap on its own: its pair delays allow at most 20 failures in the first hour, so the cap needs several
clients (`LoginThrottlePolicyTest` proves the arithmetic).

Clearing:

- a successful login clears that client's pair and the account-wide counter and delay; other clients'
  pairs (an attacker's) keep their delays;
- a completed password reset clears every client's pair for the account (the tracker keeps a set of
  client digests per account for this) and the account-wide counter, once the reset has committed.
  This is best effort: if the throttle store is unavailable the reset still succeeds and the counters
  expire on their own (24 h / 1 h).

Pre-CL-F15 keys (`auth:login:failures:*`, `auth:login:lock:*`) are no longer read and expire on their
own (24 h and at most 1 h).

## Residual risks (accepted with P3)

- Clients behind one shared NAT address share a pair per account: a wrong-password streak by one of
  them delays the others for the same account, bounded by the tiers above. Other accounts from the
  same address are unaffected.
- A distributed attacker with many addresses can keep an account at the soft cap. Each attacker
  failure restarts the account's 10 s wait, so an attacker failing about every 10 s gets about 360
  guesses per hour per account and, while that lasts, the owner's attempts can all land inside a
  wait and be refused (the review measured 0 of 360 owner attempts admitted in such an hour). The
  e-mail-only lock it replaces allowed about 20 guesses in the first hour and then locked the owner
  out for an hour, from any single client. The gateway's per-IP rate limit and the 1 h pair tier
  raise the attacker's cost; a challenge or a trusted-device exemption (P3 item 4) is the
  product-level answer and is not part of this change. Owner decision: keep these parameters
  (`LoginThrottlePolicy`: cap 50 per hour, 10 s soft delay) or change them.
- IPv6 clients are keyed by the full /128 address, so an attacker holding one /64 can present many
  client keys and bypass the per-client tiers (the account soft cap still applies). Keying IPv6 by
  /64 would close that at the cost of shared buckets on shared /64 networks; owner decision.
- No challenge, device binding or notification to the account owner yet.

## Tests

- `AuthApplicationServiceTest`: attacker failures from another client do not throttle the user;
  brute force from one client is throttled progressively; shared NAT shares a bucket per account only;
  the account soft cap delays briefly and never locks; a password reset clears every client; a missing
  client key uses the shared bucket; a refused attempt does not escalate.
- `RedisLoginFailureTrackerRedisIT` (Testcontainers `redis:7-alpine`, `integrationTest`): the same
  scenarios with real TTLs and a key-space check for raw identifiers.
- `ClientIpHeaderGlobalFilterTest` (gateway): spoofed header stripped, trusted-proxy chain, and the
  framework-strategy check above. `ClientIdentityResolverTest`, `AdminGatewayIngressTest` (auth): the
  header is trusted only on gateway-authenticated requests.
