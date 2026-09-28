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
| `CookieCsrfChromiumAcceptanceIT` | RANDOM_PORT auth + static app/evil origins; opt-in via `PARKIO_CSRF_BROWSER=1` |
| `CookieCsrfGuardHttpIntegrationTest` (#125, merged) | MockMvc Origin/Referer/mobile/no-mutation matrix — **kept** |

**Enable locally:**

```text
cd frontend/apps/web && pnpm install && pnpm exec playwright install chromium
cd ../../..
# from repo root / auth-service module:
$env:PARKIO_CSRF_BROWSER=1
./gradlew :services:auth-service:test --tests com.parkio.auth.presentation.CookieCsrfChromiumAcceptanceIT
```

**When the flag is unset:** the IT **aborts** (Assumption) with an explicit limitation — it does **not** mark browser boundary proof as passed.

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
5. Default CI does not install Playwright Chromium unless operators set `PARKIO_CSRF_BROWSER=1` and deps.

## 4. Marketing alerts #2–#4 triage (no validator rewrite)

File: `scripts/validate-marketing-site.mjs` (build-time static check, not a request handler).

| Alert line | Expression | Data source | Comparison | Sink |
|---|---|---|---|---|
| 155 | `founderSameAs.includes('https://www.linkedin.com/in/oguzhan-tasyaran/')` | Local `index.html` → regex extract JSON-LD → `JSON.parse` → `@graph` Organization.founder.sameAs | **Exact string membership** in a locally parsed array | CI `check()` failure message only |
| 159 | `!founderSameAs.includes('https://www.linkedin.com/company/parkio-app')` | same | exact membership (negative) | CI check only |
| 164 | `orgSameAs.includes('https://www.linkedin.com/company/parkio-app')` | same Organization.sameAs | exact membership | CI check only |

CodeQL models `String.prototype.includes` as substring sanitization of **URLs**. Here the receiver is a **JSON-LD string array element list**, not a request URL being validated before an open redirect/SSRF sink. There is no network fetch of the compared URL and no security decision based on substring containment of untrusted request input.

**Disposition:** leave alerts **open** for GHAS bookkeeping; do **not** rewrite the validator merely to change the CodeQL result. Not treated as an exploitable URL-validation vulnerability in this codebase path.

## 5. Act on evidence

- **No browser-reachable CSRF bypass reproduced** in MockMvc (#125) or the Chromium lab design.
- Therefore: **no production CSRF fix PR**; **no** alert #7 false-positive close.
- This draft PR carries: evidence doc + opt-in Chromium harness + keep #125 tests.
- Alert #7 stays open for an **explicit** security disposition (e.g. accept compensating controls with documented residual risk, or a future Spring CSRF token design if product requirements change).

## 6. Checks / verification notes (this worktree)

| Item | Result |
|---|---|
| Base / start SHA | `9159c794ffe393450eacc49b40597b14e7b1aa4e` (`origin/api`) |
| Before reproduction | GHAS check-run `109092933811` failure; 4 annotations (#2–#4, #7); no Chromium CSRF lab; #125 MockMvc already merged on tip |
| After (this branch) | Evidence doc + opt-in Chromium harness; server-received Cookie assertions replace a Playwright-header false green; **no** production CSRF change or alert dismiss |
| `CookieCsrfGuardHttpIntegrationTest` | PASS (default CI path) |
| `CookieCsrfChromiumAcceptanceIT` without `PARKIO_CSRF_BROWSER` | Aborted (Assumption) — does not claim browser pass |
| `CookieCsrfChromiumAcceptanceIT` with `PARKIO_CSRF_BROWSER=1` + Playwright Chromium | PASS locally (Secure/HttpOnly/Strict jar; allowed refresh/logout; cross-site cookie omit; forged mobile stays cookie path; no reuse/epoch bump) |
| Gateway TLS sibling stack | **Skipped** — not exercised |
| GHAS alerts #2–#4, #7 | Still **open** (expected; no disposition close) |
| #104 / #118 / #119 | Untouched |
