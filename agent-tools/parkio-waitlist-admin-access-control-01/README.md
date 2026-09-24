# Waitlist admin access control — audit F-01 / waitlist-scoped F-13

Scope: gateway-service only. Baseline `origin/api` = `aa865a255564464bed207a9061244af2641edd3d`
(the SHA the 2026-09-24 audit examined). Audit evidence read from
`claude/confident-einstein-68vbka` @ `a8e38ed5`,
`agent-tools/parkio-comprehensive-audit-20260924/` (FINDINGS.md F-01, F-13;
`evidence/repro-gateway-head/`).

No production endpoint was probed. No real CSV export, subscription, email, or Slack
send was made. All runs are local JUnit with mocked repository, JWT validator, and
auth-service/user-service lookups. This file contains no secrets, PII, or host paths.

## 1. Reproduction on the baseline (independent re-run)

The audit's `AuditHeadBypassReproTest.java` ran unmodified against a `git archive` of
`aa865a25`. It uses the real `WaitlistAdminSecurityWebFilter` and `WaitlistController`
with a mocked service and validator.

```
./gradlew :services:gateway-service:test --tests '*AuditHeadBypassReproTest*' --no-parallel -i
AUDIT GET  status=401 UNAUTHORIZED
AUDIT HEAD status=200 OK content-type=text/csv;charset=UTF-8 content-disposition=attachment; filename="parkio-waitlist-confirmed.csv"
BUILD SUCCESSFUL
```

The test also asserts `verify(service).export(..)`, which shows the export query path ran,
and `verifyNoInteractions(validator)`, which shows no JWT was checked. That confirms F-01
as reported.

Root cause, verified in code:
- `isProtected()` returned `false` for every method except GET.
- WebFlux serves HEAD from `@GetMapping` handlers.
- The local controllers never pass through the routed `SessionEpochGlobalFilter` or
  `AccountStatusGlobalFilter`. That second point is the waitlist-scoped part of F-13.

## 2. Fix (summary)

- `WaitlistAdminSecurityWebFilter` decides protection **by path only**, for any method.
  The patterns are `/api/v1/waitlist/export`, `/export/**`, `/admin` and `/admin/**`.
- **Exemption:** only a genuine CORS preflight (`CorsUtils.isPreFlightRequest`) skips the
  filter. `CorsWebFilter` answers it and never forwards it to a handler.
- **Order after the role check:** session epoch first, then account status. This is the
  same order and contract as the routed chain.
- **Shared implementation:** the epoch and status decisions moved into
  `SessionEpochVerifier` and `AccountStatusVerifier`. The two GlobalFilters and the
  waitlist filter all delegate to them, so there is no second authorization
  implementation. Error codes are unchanged: `TOKEN_REVOKED` 401,
  `SESSION_EPOCH_UNAVAILABLE` 503, `ACCOUNT_NOT_ACTIVE` 403 and
  `USER_STATUS_UNAVAILABLE` 503.
- **`INVALID_TOKEN` scope:** it now covers only JWT validation, and an *empty* validation
  result also counts as invalid. Before, `onErrorResume` wrapped the whole downstream
  chain.
- **Non-blocking:** all lookups use the existing non-blocking WebClient clients plus
  in-memory TTL caches. Nothing blocks the event loop.

## 3. Regression evidence (fix head, local)

`WaitlistAdminAccessControlIntegrationTest` is a full `@SpringBootTest`. It runs the real
WebFilter chain, including `CorsWebFilter`, the real handler mapping and the real
controller and service. The mocks are `WaitlistInterestRepository`, `JwtTokenValidator`,
`SessionEpochClient` and `UserStatusClient`. Every rejected request, and every preflight,
asserts **zero** calls to `exportConfirmed`, `countByStatus` and `findAdminPage`.

| Case | Result |
|---|---|
| Anonymous GET/HEAD/POST/PUT/PATCH/DELETE/OPTIONS × admin, summary and export, with and without a trailing slash | 401, 0 repo calls, validator never called |
| Anonymous HEAD export | 401, no `Content-Disposition`, not `text/csv` |
| Expired, invalid, empty-result or non-Bearer token (GET/HEAD) | 401, 0 repo calls |
| USER or MODERATOR (GET/HEAD/POST) | 403, 0 repo calls, no epoch or status lookup |
| Spoofed `X-User-*` headers, no token | 401 |
| ADMIN or SUPER_ADMIN: summary, list and export GET, plus HEAD export | 200 (behavior preserved) |
| ADMIN with POST/PUT/PATCH/DELETE on admin paths | 405 from the controller, 0 repo calls |
| Status SUSPENDED, BANNED or DELETED; unknown account (404) | 403 `ACCOUNT_NOT_ACTIVE`, 0 repo calls |
| Stale epoch; legacy token with no epoch after a bump | 401 `TOKEN_REVOKED`, 0 repo calls, no status lookup |
| Epoch lookup or status lookup unavailable | 503, 0 repo calls |
| Preflight from the allowed origin | 200 with ACAO and credentials, 0 repo calls, validator never called |
| Preflight from a disallowed origin | 403, no ACAO, 0 repo calls |
| Credentialed cross-origin admin GET | 200 with ACAO |
| Public confirm, withdraw and resend | reach the service anonymously |
| Coverage guard: every `WaitlistController` mapping except the 4 public POSTs is path-protected | 7 mappings checked |

The integration test uses absolute request URIs for the CORS cases. With the mock
client's relative URI, Spring's same-origin check rejects every Origin as
"origin is malformed". That is a harness artifact and is present on the baseline too.

**Negative controls**, each run against the fix head with the mutation then reverted:
- Put back the GET-only method check: 7 of 14 fail, covering the HEAD, anonymous
  all-method, invalid-token, non-admin, status, epoch and fail-closed cases.
- Drop the epoch and status verification: 3 of 14 fail, covering the status, epoch and
  fail-closed cases.

**Full `:services:gateway-service:test` at the fix:** 234 tests, 0 failures, 0 errors,
0 skipped. The relevant classes:

| Class | Tests |
|---|---|
| `WaitlistAdminAccessControlIntegrationTest` | 14 |
| `WaitlistAdminSecurityWebFilterTest` | 10 |
| `WaitlistControllerTest` (real H2 public and admin flows) | 17 |
| `WaitlistAdmissionsContainmentTest` | 3 |
| `SessionEpochGlobalFilterTest` | 7 |
| `AccountStatusGlobalFilterTest` | 6 |
| `CorsConfigTest` | 7 |
| `AuthenticationGlobalFilterTest` | 13 |
| `AuthorizationGlobalFilterTest` | 18 |

Exact-head CI results are recorded in the PR.

## 4. Remaining limitations and follow-ups (not fixed here)

1. **Auth-side revocation (F-13, auth-service).** `AdminApplicationService.revokeRole`,
   `revokeAllSessions` and `revokeSession` do not bump the session epoch. The gateway now
   enforces the epoch on waitlist admin, but a revoked role or admin "revoke all sessions"
   stays in the access token until it expires (15 min default). Fixing this needs an
   auth-service change.
2. **Cache lag.** Epoch and status are cached for 30 s by default. That window is the
   revocation lag, and it is the same as on routed traffic.
3. **CORS headers on waitlist-admin error responses.** Rejections are written before
   `CorsWebFilter` runs, so a cross-origin 401/403/503 carries no ACAO and the browser
   reports it as a CORS error. This is the same as the pre-fix GET behavior.
4. **Rate limiting.** The local admin endpoints still have no gateway rate limit
   (audit F-01 note).
5. **Export size.** The unbounded in-memory export is audit F-34 and was not changed.
6. **Release.** Nothing has been built or deployed. A later gateway-only image build, pin
   and deploy is required to fix production. The repo's gateway release pin
   (`docker/docker-compose.gmp-release-pins.yml`, OCI revision `031d4834`) has a filter
   identical to baseline: `git diff 031d4834 aa865a25` on that file is empty. The live
   host was not probed.
