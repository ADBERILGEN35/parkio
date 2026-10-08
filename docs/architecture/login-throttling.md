# Login throttling keyed by account and client (CL-F15)

Status: v1 implemented 2026-10-07 (owner decision P3, #306). **Policy v2 proposed 2026-10-08**
(owner decision 2026-10-08 item 6: distributed account lockout, legitimate-user recovery, IPv6
rotation; not activated until the owner approves it). v1 replaced the e-mail-only hard lock of the
earlier `RedisLoginFailureTracker`.

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

## Throttling (policy v2)

Counters live in Redis under SHA-256 digests of the e-mail and the client (no raw e-mail or address in
the key space). The rules are in `LoginThrottlePolicy` and `ThrottledLoginFailureTracker`; the Redis
primitives in `RedisLoginThrottleStore`.

**Client key.** The gateway-resolved client IP (`ClientIdentityResolver`), normalised by
`LoginClientKeys`: an IPv4 address as it is, an IPv6 address as its /64 network (so rotating addresses
inside one /64 is one client), an IPv4-mapped IPv6 address as the IPv4 client, anything else the shared
`unknown` client. No host name is ever resolved.

| Counter | Window | Effect |
|---|---|---|
| (account, client) failures | 24 h after the last failure | the pair waits 30 s from the 5th failure, 5 min from the 10th, 1 h from the 20th |
| account failures (all clients) | 1 h after the last failure | from the 50th failure each further failure makes the account's **unknown** clients wait 10 s; from the 100th, 60 s; from the 200th, 5 min |
| known clients of the account | 30 days after the last mark | exempt from the account wait (their own pair tiers still apply) |

A client becomes **known** for an account when it logs in successfully or completes the account's
password reset; the shared `unknown` client never does. This is the allowlist of previously
authenticated addresses that NIST SP 800-63B 5.2.2 lists among the measures against lockout.

A refused attempt (pair or account still waiting) is not counted as a failure, so retrying during a
wait does not escalate the tier. A throttled attempt is answered exactly like a wrong password
(`INVALID_CREDENTIALS`), also when the password was right; logs and the `login_lockouts` counter
("attempts refused by the throttle") see the difference.

Clearing:

- a successful login clears that client's pair and marks it known; the account counter and other
  clients' pairs stay (one successful login does not end an attack, and gives the attacker no fresh
  budget);
- a completed password reset clears every client's pair for the account (the tracker keeps the set of
  client digests that failed) and marks the resetting client known, once the reset has committed; the
  account counter stays. Best effort: if the throttle store is unavailable the reset still succeeds and
  the counters expire on their own;
- an account erasure request removes everything the throttle keeps for the account, known clients
  included, after the request commits (best effort; registered after the durable-recording step and
  never throws).

The key names and types of v1 are kept (`pair:*`, `account:*:failures`, `account:*:wait`,
`account:*:clients` as a plain set); `account:*:known` (sorted set scored by member expiry) is new and
ignored by v1, so an upgrade or a rollback reads the other's state without type errors. IPv6 pairs
restart once at the upgrade (their key changes from /128 to /64).

## Measured behaviour (LoginThrottleSimulationTest)

`LoginThrottleSimulationTest` runs each scenario for 24 h on a virtual clock against v2 and against a
replica of the v1 rules merged in #306, with the login control flow of `AuthApplicationService`. The
attacker is optimal: it times every attempt so that it is evaluated (an upper bound; a real attacker
cannot see the waits). The test asserts the v2 bounds and writes this table to
`build/login-throttle-simulation.md`:

| Id | Scenario (24 h, optimal attacker) | v1 (#306) | v2 (proposed) |
|---|---|---|---|
| A1 | Distributed attack, 1,000 IPv4 addresses: guesses in hour 1 | 409 | 151 |
| A2 | same: guesses in hour 2 | 360 | 51 |
| A3 | same: guesses per hour, hours 3-24 (max) | 360 | 12 |
| A4 | same: guesses in 24 h | 8689 | 466 |
| A5 | same: owner on a network used before, 144 logins with the right password: refused | 144/144 | 0/144 |
| B1 | Same attack, owner on a new device: logins refused (144 tries, password reset from the device at 12 h) | 144/144 | 72/144 |
| B2 | same: time from the reset to the owner's first successful login | never | 31 s |
| B3 | same: attacker guesses in 24 h | 8738 | 466 |
| C1 | Distributed attack, 12 IPv4 addresses: guesses in 24 h | 505 | 466 |
| C2 | same: guesses per hour, hours 3-24 (max) | 12 | 12 |
| D1 | One IPv4 address: guesses in hour 1 | 20 | 20 |
| D2 | same: guesses in 24 h | 43 | 43 |
| E1 | IPv6 attacker with one /64, new address per attempt: guesses in 24 h | 8689 | 43 |
| E2 | same: owner on a network used before, 144 logins: refused | 144/144 | 0/144 |
| F1 | IPv6 attacker with one /48, new /64 per attempt: guesses in 24 h | 8689 | 466 |
| G1 | Shared NAT: attacker brute-forces one account; 200 other accounts behind the same address log in (2 typos + right password): refused | 0/600 | 0/600 |
| H1 | Attacker behind the owner's own NAT address: owner logins from that address refused (25 tries, reset at 12 h) | 25/25 | 25/25 |
| H2 | same attacker: owner logins from another network (mobile) refused | 0/48 | 0/48 |
| H3 | same attacker: guesses in 24 h | 62 | 62 |
| I1 | No attack: owner mistypes 25 times on a laptop, resets from the phone: laptop login after the reset | 30 s | 30 s |

Reading the table:

- A distributed attack on one account gets about 150 guesses in its first hour, about 50 in the
  second and 12 per hour after that, regardless of the number of addresses (A, C, F); v1 allowed
  about 360 per hour. The owner on a network used for the account before is never refused (A5, E2);
  under v1 every attempt was refused while the attack lasted.
- An owner on a new device is refused like any unknown client during the attack, and gets in by
  completing a password reset from that device (B1, B2); under v1 the attacker re-saturated the account
  within seconds of the reset.
- An attacker holding one IPv6 /64 is one client (E1: 43 guesses a day instead of v1's 8,689).
- Shared NAT: other accounts behind the same address are unaffected (G1). An attacker behind the
  owner's own address shares the owner's pair and keeps the owner out from that address, in v1 and v2
  alike (H1); the owner gets in from any other network (H2). Each owner reset clears the attacker's
  pair too (H3: 62 guesses instead of 43).

## Residual risks and decisions (v2)

- **Unknown clients during an attack.** A distributed attacker (12 addresses suffice) keeps the account's
  unknown clients waiting while the attack lasts; known clients and the reset path are the recovery.
  A challenge (CAPTCHA) or device-bound trust (a signed device cookie or token, which also survives the
  address changes of mobile carriers) would let a new device in without a reset; neither is part of v2.
- **Known clients are addresses.** An attacker on a network the owner used before (the same NAT or
  carrier-grade NAT address) is exempt from the account wait and limited by its pair tiers (about 43
  guesses a day). Mobile users whose public address changes between sessions are rarely known.
- **Retention.** The known-client set keeps digests of client addresses per account for 30 days after
  the last login (IPv4 digests can be reversed by enumeration). It is removed at account erasure.
  Owner decision: accept 30 days, or shorten it.
- **Spraying across accounts** (a few guesses against many accounts) is outside per-account throttling;
  the gateway's per-IP limit (auth routes: 5 requests/s, burst 10) is the control, and it still keys
  IPv6 clients by /128. Keying the gateway's anonymous limits by IPv6 /64 is a separate decision (it
  changes every anonymous limit, not only login).
- **Co-located attacker** (H1) is unchanged from v1.
- No challenge, device binding or notification to the account owner yet.

## Tests

- `ThrottledLoginFailureTrackerTest` (in-memory store, virtual clock): pair tiers for known and unknown
  clients, account tiers for unknown clients only, the unknown bucket never known, the 30-day known
  window, success and reset clearing, erasure, no raw identifiers in the key space.
- `LoginThrottleSimulationTest`: the measured table above, with the v2 bounds asserted.
- `LoginClientKeysTest`, `ClientIdentityResolverTest`: IPv4, IPv6 /64, IPv4-mapped, refusals of anything
  else, random literal-like input; the header is trusted only on gateway-authenticated requests.
- `AuthApplicationServiceTest`: the service with the real tracker on an in-memory store; attacker
  failures from another client do not throttle the user; brute force from one client; shared NAT per
  account only; account tier delays unknown clients but not a known one; reset from a new device during
  an attack lets the owner in at once; reset clearing only at commit and best effort.
- `AccountErasureApplicationServiceTest`: the erasure request removes the account's throttle state; an
  unavailable store does not fail it.
- `ThrottledLoginFailureTrackerRedisIT` (Testcontainers `redis:7-alpine`, `integrationTest`): the same
  rules with real TTLs and types, v1-written state read and cleared without type errors, erasure, and
  no raw identifiers in the key space.
- `ClientIpHeaderGlobalFilterTest` (gateway): spoofed header stripped, trusted-proxy chain, and the
  framework-strategy check above. `AdminGatewayIngressTest` (auth): the header is trusted only on
  gateway-authenticated requests.
