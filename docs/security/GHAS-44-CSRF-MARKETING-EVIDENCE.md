# GHAS / umbrella #44 — CSRF & marketing CodeQL evidence

**Base (origin/api at investigation start):** `9159c794ffe393450eacc49b40597b14e7b1aa4e`  
**Last previously reviewed SHA (same tip):** `9159c794ffe393450eacc49b40597b14e7b1aa4e`  
**PR under study:** umbrella [#44](https://github.com/ADBERILGEN35/parkio/pull/44) (`api` → decision trunk)  
**Do not modify:** #104, #118, #119. No alert dismissals, query suppressions, CI weakening, merges, image publishes, or production access.

## 1. Exact GHAS alerts (still four)

GitHub Advanced Security check-run `109092933811` on head `9159c794…` concluded **failure** with title **“4 new alerts including 4 high severity…”** and **4 annotations**:

| Annotation | Alert identity (prior) | Path | Line | CodeQL query |
|---|---|---|---|---|
| Incomplete URL substring sanitization | #2 | `scripts/validate-marketing-site.mjs` | 155 | `js/incomplete-url-substring-sanitization` |
| Incomplete URL substring sanitization | #3 | `scripts/validate-marketing-site.mjs` | 159 | same |
| Incomplete URL substring sanitization | #4 | `scripts/validate-marketing-site.mjs` | 164 | same |
| Disabled Spring CSRF protection | #7 | `services/auth-service/.../SecurityConfig.java` | 27 (`csrf.disable()`) | `java/spring-disabled-csrf-protection` |

**Separate from GHAS:** Security CI workflow jobs that run CodeQL analysis for Java/JS can succeed on the same SHA while the GHAS **comparison** check on #44 still fails. Those successful analysis jobs do **not** clear the four annotated alerts above.

## 2. Security boundary inventory (auth cookie CSRF)

### Cookie-backed routes (ambient `parkio_refresh`)

| Route | Reads cookie | Sets/clears cookie | Origin guard |
|---|---|---|---|
| `POST /api/v1/auth/login` | no | sets (browser path) | `validateOriginIfPresent` |
| `POST /api/v1/auth/refresh-token` | yes (non-mobile) | rotates | **`validateOrigin` required** |
| `POST /api/v1/auth/logout` | yes (non-mobile) | clears | **`validateOrigin` required** |
| `POST /api/v1/auth/logout-all` | ignores cookie; needs Bearer | clears cookies if browser | **`validateOrigin` required** when non-mobile |
| `POST /api/v1/auth/reset-password` | no | clears | `validateOriginIfPresent` |
| `POST /api/v1/auth/change-password` | Bearer | clears | `validateOriginIfPresent` |

Cookie attributes (production defaults): **HttpOnly**, **Secure**, **SameSite=Strict**, host-only, path-scoped to `/api/v1/auth/refresh-token` and `/api/v1/auth/logout`.

### Bearer-only / non-cookie ambient

- `GET /api/v1/auth/me`, `change-password`, account erasure, admin APIs: Bearer JWT (gateway-validated at edge).
- `logout-all`: Bearer required; refresh cookie alone → 401 (`CookieCsrfGuardHttpIntegrationTest`).

### Mobile body-token path

`isMobileClient` requires `X-Parkio-Client: mobile` **and** absent `Origin` **and** absent `Referer`. Browser pages that send the mobile header still carry Origin on cross-origin `fetch`, so they stay on the cookie path (no body refresh token). Native clients omit Origin/Referer and send the token in JSON.

### Gateway / CORS / direct exposure

- **Hosted-beta / azure-hosted-beta:** `auth-service` host ports `!reset []` — auth is **not** published on the host; browsers reach APIs only via gateway `:8080` (+ Caddy TLS in real deploys).
- **Local `docker-compose.apps.yml`:** auth published on `8081`; every request still needs `X-Gateway-Auth`.
- **Gateway CORS** (`CorsConfig` + `parkio.gateway.cors.*`): allow-list origins, optional credentials; empty origins deny cross-origin browsers. Credentials + `*` origins fail closed.
- **Auth-service** enables Spring Security `cors(withDefaults())` but has **no** production `CorsConfigurationSource`; CORS for browsers is an **edge** concern.

### Can a browser-reachable state-changing request use ambient credentials without the intended guard?

| Attack sketch | Result of review |
|---|---|
| Cross-site POST with cookies (classic CSRF) | `SameSite=Strict` omits cookie on cross-site; if cookie were present, `validateOrigin` rejects foreign Origin before mutation (`#125` MockMvc + Chromium lab). |
| Sibling subdomain (`app.` ↔ `api.`) same-site | SameSite **sends** cookie; **Origin allow-list** is the CSRF guard. Not re-proven here under TLS sibling hosts (see limitations). |
| Forge `Origin` from page JS | Forbidden request header — browsers ignore it. |
| Forge `X-Parkio-Client: mobile` from page | Still has Origin → cookie path; no body-token opt-in. |
| Call auth directly with stolen gateway secret | Lab/local risk only; hosted-beta does not publish auth ports. |
| `csrf.disable()` alone | CodeQL #7 remains **open** — compensating controls are Origin/Referer + cookie attributes + gateway edge, **not** a false-positive disposition. |

**Do not infer safety solely from SameSite or MockMvc.** MockMvc always attaches cookies when the test sets them; #125 proves server-side policy only. Chromium lab (below) proves cookie jar / SameSite omission on `localhost` vs `127.0.0.1`.

## 3. Bounded Chromium acceptance

| Artifact | Role |
|---|---|
| `scripts/auth-csrf-chromium-acceptance.mjs` | Drives headless Chromium against lab origins |
| `CookieCsrfChromiumAcceptanceIT` | RANDOM_PORT auth + static app/evil origins; enabled in the dedicated PR workflow with `PARKIO_CSRF_BROWSER=1` |
| `CookieCsrfGuardHttpIntegrationTest` (#125, merged) | MockMvc Origin/Referer/mobile/no-mutation matrix — **kept** |

**Enable locally:**

```text
cd frontend/apps/web && pnpm install && pnpm exec playwright install chromium
cd ../../..
# from repo root / auth-service module:
$env:PARKIO_CSRF_BROWSER=1
./gradlew :services:auth-service:test --tests com.parkio.auth.presentation.CookieCsrfChromiumAcceptanceIT
```

**When the flag is unset:** the IT **aborts** (Assumption) with an explicit limitation — it does **not** mark browser boundary proof as passed. With the flag set, missing Playwright is a test failure, and the dedicated workflow also checks that exactly one test ran with zero skips.

### What the Chromium lab proves (when enabled)

- Secure / HttpOnly / SameSite=Strict in the real Chromium cookie jar
- Allowed-origin refresh + logout
- Cross-site page on `http://127.0.0.1` does **not** attach the `localhost` Strict cookie (asserted from the lab server's received `Cookie` header for both POSTs). Playwright `request.headers()` omits cookie-related headers and is not used as proof.
- Forged `X-Parkio-Client: mobile` with browser Origin stays on cookie path (no JSON refresh token)
- After cross-site rejection, the live refresh row is later revoked by legitimate logout (`LOGOUT`), not reuse/epoch bump

### Exact limitations (not a full edge proof)

1. **Not via gateway** — pages inject lab `X-Gateway-Auth`; production clients never hold that secret.
2. **Test-only CORS bean** on auth — production browsers rely on **gateway** CORS.
3. **HTTP localhost secure-context**, not TLS `app.parkio.dev` ↔ `api.parkio.dev` sibling-subdomain SameSite+Origin.
4. **Missing Origin / Referer** cannot be omitted by Chromium cross-origin `fetch` — remains #125 MockMvc evidence.
5. The dedicated PR workflow installs Playwright Chromium and runs this test with `PARKIO_CSRF_BROWSER=1`; general Backend CI still does not run the browser lab.

## 3b. Real request path (browser → gateway → auth)

Production / hosted-beta shape (`docker/.env.*.example`, `CorsConfig`, `GatewayAuthHeaderGlobalFilter`, `AuthController`):

1. Browser on `https://app.parkio.dev` issues credentialed `fetch` to `https://api.parkio.dev/api/v1/auth/*`.
2. **Gateway CORS** (`parkio.gateway.cors.allowed-origins` ← `PARKIO_CORS_ALLOWED_ORIGINS`, credentials default **true**) must reflect the app origin or the browser blocks the response (and typically the preflight).
3. **GatewayAuthHeaderGlobalFilter** strips any inbound `X-Gateway-Auth` and stamps the configured secret — browsers never hold it.
4. Request is proxied to **auth-service** (host ports reset in hosted-beta; not browser-reachable).
5. Auth cookie path: `parkio_refresh` HttpOnly+Secure+SameSite=Strict, host-only on the API host. Refresh/logout require `validateOrigin` against the **same** env allow-list (`parkio.security.refresh-cookie.allowed-origins` ← `PARKIO_CORS_ALLOWED_ORIGINS`).
6. Same-site sibling (`evil.parkio.dev`): SameSite=Strict **may send** the cookie; CSRF resistance requires gateway CORS **and/or** auth Origin rejection. Cross-site: cookie omitted.

### Gateway HTTPS sibling-host lab (this PR)

| Artifact | Role |
|---|---|
| `scripts/csrf-lab/run-auth-csrf-gateway-https-lab.mjs` | Boots Postgres+Redis, real auth+gateway `bootRun`, TLS fronts, disposable **auth-direct** front |
| `scripts/csrf-lab/playwright-gateway-driver.mjs` | Allowed positives + sibling/cross JSON + **simple form** + auth Origin isolate |
| `scripts/csrf-lab/session-probe.mjs` | Postgres before/after: refresh row + `session_epoch` + active count |
| `src/csrfLab/.../CsrfLabRequestCaptureFilter` | Lab-only source set (`-Pparkio.csrfLab=true` on bootRun). **Not** in production `bootJar` (`assertCsrfLabAbsentFromBootJar`) |
| Edge JSONL (`gateway-tls` / `auth-direct`) | Records whether OPTIONS/POST reached the TLS edge |
| `CorsConfigTest.siblingSubdomain…` | Unit: evil sibling gets no ACAO |
| Workflow | Fail-closed; requires layer keys + auth Origin isolate `reachedAuth` + 403; fails on `security_defect` |

**Attribution rules (browser status 0 is never enough):**

| Case | What must be proven |
|---|---|
| Sibling/cross **JSON** | Session+epoch unchanged; layer from edge log (`gateway-preflight-blocked-post` vs auth) |
| Sibling/cross **simple** (`application/x-www-form-urlencoded`) | No preflight; POST may reach gateway/auth; session+epoch unchanged |
| **Auth Origin isolate** (lab auth-direct on `api.parkio.test` other port: stamps secret, reflects CORS; production allowlists unchanged) | Auth receives evil Origin → **403**; session+epoch unchanged |
| Allowed login/refresh/logout | Positive controls; logout revokes with `LOGOUT`, no epoch bump |

Browser pages **do not** inject `X-Gateway-Auth`. Lab requires `PARKIO_CSRF_GATEWAY_BROWSER=1` (exit 2 if unset). If any malicious case mutates refresh/epoch → lab exits `security_defect` (CI fails; do not treat as accepted evidence).

**Local agent limitation:** Docker daemon was not running on the Windows agent host, so the full gateway HTTPS lab was not executed locally; CI on ubuntu-latest is the execution venue.

## 4. Marketing alerts #2–#4 triage (no validator rewrite)

File: `scripts/validate-marketing-site.mjs` (build-time static check, not a request handler).

| Alert line | Expression | Data source | Comparison | Sink |
|---|---|---|---|---|
| 155 | `founderSameAs.includes('https://www.linkedin.com/in/oguzhan-tasyaran/')` | Local `index.html` → regex extract JSON-LD → `JSON.parse` → `@graph` Organization.founder.sameAs | **Exact string membership** in a locally parsed array | CI `check()` failure message only |
| 159 | `!founderSameAs.includes('https://www.linkedin.com/company/parkio-app')` | same | exact membership (negative) | CI check only |
| 164 | `orgSameAs.includes('https://www.linkedin.com/company/parkio-app')` | same Organization.sameAs | exact membership | CI check only |

CodeQL models `String.prototype.includes` as substring sanitization of **URLs**. Here the receiver is a **JSON-LD string array element list**, not a request URL being validated before an open redirect/SSRF sink. There is no network fetch of the compared URL and no security decision based on substring containment of untrusted request input.

**Disposition:** leave alerts **open** for GHAS bookkeeping; do **not** rewrite the validator merely to change the CodeQL result. Not treated as an exploitable URL-validation vulnerability in this codebase path.

## 5. Act on evidence / proposed dispositions (alerts remain open)

- **No browser-reachable CSRF bypass reproduced** in MockMvc (#125), direct-auth Chromium (#126), gateway CORS unit sibling case, or the gateway HTTPS lab design.
- **`csrf.disable()` is a real configuration** (CodeQL #7 is correct that Spring CSRF is off). That is **not** the same statement as “an exploitable CSRF path exists.” Compensating controls: cookie SameSite=Strict + Secure + HttpOnly, auth Origin/Referer on cookie mutations, gateway CORS allow-list with credentials, gateway-stamped secret, hosted-beta auth not published.
- **Proposed #7 disposition (pending explicit security decision):** accept residual risk with compensating controls documented; do **not** auto-close as false positive. Optional follow-up: Spring CSRF tokens for cookie routes if product wants defense-in-depth beyond Origin.
- **Proposed #2–#4 disposition (pending explicit decision):** informational / not exploitable URL sanitization — exact array membership on build-time JSON-LD; leave open or reclassify without rewriting the validator solely for CodeQL.
- Therefore: **no production CSRF fix PR** in this arc; **no** alert dismissals.

## 6. Checks / verification notes (this worktree)

| Item | Result |
|---|---|
| Continuation from tip | `031d08ff` → head `f2809e1156b29d371c154aa97d8ff0ec0f8b5089` on base `822828a5…` |
| Lab filter packaging | Moved to `src/csrfLab`; CI verified absent from bootJar |
| Gateway HTTPS CI | **PASS** — run `36539541839` (session probes + layer attribution + auth Origin isolate 403) |
| Direct-auth Chromium CI | **PASS** — run `36539541524` |
| Exact-head checks | 22 success, 4 skipped, 0 failed |
| Session mutations on malicious cases | **None** (no `security_defect`) |
| GHAS #2–#4 / #7 | Still open; not dismissed |
| #104 / #118 / #119 | Untouched |
| Merge / dismiss / publish / deploy / production | **None** |

**Proposed #7:** `csrf.disable()` is real configuration, not a proven exploitable browser CSRF path under the tested gateway HTTPS sibling/cross/simple/auth-Origin matrix. Keep #7 open until an explicit accept-risk or defense-in-depth decision. Absence of a reproduced bypass is not proof that every CSRF path is safe.
