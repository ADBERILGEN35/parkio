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
| known client of the account (one marker per client) | 30 days after that client's last login or completed reset | exempt from the account wait (its own pair tiers still apply) |

A client becomes **known** for an account when it logs in successfully or completes the account's
password reset; the shared `unknown` client never does. This is the allowlist of previously
authenticated addresses that NIST SP 800-63B 5.2.2 lists among the measures against lockout.

**Admission.** Before the password is checked, the attempt claims the waits that apply to it in one
atomic step (a Redis script): the pair wait and, for a client that is not known, the account wait. When
either is still running the attempt is refused; otherwise both are set for the current tier's delay and
the attempt is evaluated. However many attempts arrive at once, from however many addresses, one of them
is evaluated per running wait. Below the first tier no wait applies, so attempts that arrive together
there are all evaluated: at a tier crossing the overshoot is at most the number of attempts in flight
(#311 review B1). The failure that follows sets the next wait from its own time.

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
`account:*:clients` as a plain set); `known:{email}:{client}` (a plain marker) is new and ignored by v1, so
an upgrade or a rollback reads the other's state without type errors (Redis IT, both directions). IPv6
pairs restart once at the upgrade (their key changes from /128 to /64). After a rollback to v1 the
`known:*` markers are neither read nor erased by v1; they expire within 30 days, or are removed with
`redis-cli --scan --pattern 'auth:login:v2:known:*' | xargs -r redis-cli del` as a rollback step.

## Measured behaviour (LoginThrottleSimulationTest)

`LoginThrottleSimulationTest` runs each scenario for 24 h on a virtual clock against v2 and against a
replica of the v1 rules merged in #306, with the login control flow of `AuthApplicationService`. The
attacker is optimal: it times every attempt so that it is evaluated (an upper bound; a real attacker
cannot see the waits). In rows A6 to A8 it also sends 8 or 32 attempts at every opening, from different
addresses, all admitted or refused before any failure is recorded: v1 checked without claiming, so all of
them were evaluated; v2 claims the waits atomically. The v1 column comes from a replica with millisecond
waits; v1 itself truncated waits to whole seconds, so its real numbers were slightly higher (about 10 %
for the 10 s delay). The test asserts the v2 bounds and writes this table to
`build/login-throttle-simulation.md`:

| Id | Scenario (24 h, optimal attacker) | v1 (#306) | v2 (proposed) |
|---|---|---|---|
| A1 | Distributed attack, 1,000 IPv4 addresses: guesses in hour 1 | 409 | 151 |
| A2 | same: guesses in hour 2 | 360 | 51 |
| A3 | same: guesses per hour, hours 3-24 (max) | 360 | 12 |
| A4 | same: guesses in 24 h | 8689 | 466 |
| A5 | same: owner on a network used before, 144 logins with the right password: refused | 144/144 | 0/144 |
| A6 | Distributed attack, 1,000 addresses, 8 attempts in flight at every opening: guesses in 24 h | 37000 | 466 |
| A7 | same with 32 attempts in flight: guesses in 24 h | 42000 | 466 |
| A8 | same with 32 in flight: guesses per hour, hours 3-24 (max) | 1000 | 12 |
| B1 | Same attack, owner on a new device: logins refused (144 tries, password reset from the device at 12 h) | 144/144 | 72/144 |
| B2 | same: the owner's first login after the reset (next scheduled try, 31.7 s later) | refused | succeeded |
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
| I1 | No attack: owner mistypes 25 times on a laptop, resets from the phone: the laptop's next login, 30 s after the reset | succeeded | succeeded |

Reading the table:

- A distributed attack on one account gets about 150 guesses in its first hour, about 50 in the
  second and 12 per hour after that, regardless of the number of addresses (A, C, F); v1 allowed
  about 360 per hour. The owner on a network used for the account before is never refused (A5, E2);
  under v1 every attempt was refused while the attack lasted.
- Sending many attempts at once does not raise v2's bound (A6 to A8): admission lets one attempt through
  each wait; under v1 every attempt in flight was evaluated.
- An owner on a new device is refused like any unknown client during the attack, and gets in by
  completing a password reset from that device: the first login after the reset succeeds (B1, B2); under
  v1 the attacker re-saturated the account within seconds of the reset.
- An attacker holding one IPv6 /64 is one client (E1: 43 guesses a day instead of v1's 8,689).
- Shared NAT: other accounts behind the same address are unaffected (G1). An attacker behind the
  owner's own IPv4 address shares the owner's pair and keeps the owner out from that address, in v1 and
  v2 alike (H1); the owner gets in from any other network (H2). Each owner reset clears the attacker's
  pair too (H3: 62 guesses instead of 43). For IPv6 the shared pair is now the whole /64 (see below).

## Residual risks and decisions (v2)

- **Unknown clients during an attack.** A distributed attacker (12 addresses suffice) keeps the account's
  unknown clients waiting while the attack lasts; known clients and the reset path are the recovery.
  A challenge (CAPTCHA) or device-bound trust (a signed device cookie or token, which also survives the
  address changes of mobile carriers) would let a new device in without a reset; neither is part of v2.
- **Known clients are addresses.** An attacker on a network the owner used before (the same NAT or
  carrier-grade NAT address) is exempt from the account wait and limited by its pair tiers (about 43
  guesses a day). Mobile users whose public address changes between sessions are rarely known.
- **Retention and privacy.** A known-client marker keeps the digest of one client address (an IPv4
  address or an IPv6 /64) per account for exactly 30 days after that client's last login or completed
  reset, and is removed at account erasure. The e-mail and address digests are unsalted SHA-256: IPv4
  addresses can be enumerated and e-mails guessed from a dictionary, so anyone holding a copy of Redis
  (AOF-persisted to the `redis-data` volume; not backed up) can recover a 30-day account-to-address
  association offline. v1 kept only failure data, for at most 24 h. A keyed HMAC (as `WaitlistHasher`
  does) would prevent offline reversal at the cost of a new secret. Owner decision: accept 30 days and
  plain digests, shorten the window, or require the HMAC.
- **IPv6 /64 sharing.** One pair, and one known-client exemption, covers a whole /64: a home or office
  LAN, or a /64 that a hosting provider shares between customers. An attacker on the owner's LAN (or a
  shared /64) shares the owner's pair and, if the /64 is known, skips the account wait (its pair tiers
  still apply). Special forms: IPv4-compatible `::a.b.c.d`, `::1` and `::` all map to the zero /64;
  Teredo clients share their relay's /64; scoped literals (`%`) become the unknown client. None is a
  normal internet source.
- **Redis loss** empties every known-client allowlist; owners are unknown clients until their next login
  or reset. Redis down means login fails closed (500), as in v1 (the SEC-002 audit entries cite the
  replaced `RedisLoginFailureTracker`; the posture is unchanged).
- **Spraying across accounts** (a few guesses against many accounts) is outside per-account throttling;
  the gateway's per-IP limit (auth routes: 5 requests/s, burst 10) is the control, and it still keys
  IPv6 clients by /128. Keying the gateway's anonymous limits by IPv6 /64 is a separate decision (it
  changes every anonymous limit, not only login).
- **Co-located attacker** (H1) is unchanged from v1 for IPv4; for IPv6 it now covers the owner's whole /64.
- No challenge, device binding or notification to the account owner yet.

## Tests

- `ThrottledLoginFailureTrackerTest` (in-memory store, virtual clock): pair tiers for known and unknown
  clients, account tiers for unknown clients only, admission claiming the waits (one evaluation per wait;
  32 concurrent admissions admit exactly one), the unknown bucket never known, the 30-day known window,
  success and reset clearing, erasure, no raw identifiers in the key space.
- `RedisLoginThrottleStoreTest`: the Redis commands behind each primitive, including the admission script
  and the incremental scan used by erasure.
- `LoginThrottleClientKeyHttpTest`: over HTTP, the gateway-resolved client (IPv6 by /64) reaches login
  admission, the success and the password reset; without the header the reset uses the unknown client.
- `LoginThrottleSimulationTest`: the measured table above, with the v2 bounds asserted.
- `LoginClientKeysTest`, `ClientIdentityResolverTest`: IPv4, IPv6 /64, IPv4-mapped, refusals of anything
  else, random literal-like input; the header is trusted only on gateway-authenticated requests.
- `AuthApplicationServiceTest`: the service with the real tracker on an in-memory store; attacker
  failures from another client do not throttle the user; brute force from one client; shared NAT per
  account only; account tier delays unknown clients but not a known one; reset from a new device during
  an attack lets the owner in at once; reset clearing only at commit and best effort.
- `AccountErasureApplicationServiceTest`, `AccountErasureHttpIntegrationTest`: the erasure request removes
  the account's throttle state (also through the Spring-wired hook over HTTP); an unavailable store does
  not fail it.
- `AuthApplicationServiceTest` also sends sixteen concurrent wrong-password logins from different
  addresses when the account wait opens: exactly one reaches the password check.
- `ThrottledLoginFailureTrackerRedisIT` (Testcontainers `redis:7-alpine`, `integrationTest`): the same
  rules with real TTLs and types, 32 concurrent admissions against real Redis admitting exactly one,
  v1-written state read and cleared without type errors, the keys v1 uses keeping v1's types after v2
  operations (rollback), erasure, and no raw identifiers in the key space.
- `ClientIpHeaderGlobalFilterTest` (gateway): spoofed header stripped, trusted-proxy chain, and the
  framework-strategy check above. `AdminGatewayIngressTest` (auth): the header is trusted only on
  gateway-authenticated requests.
