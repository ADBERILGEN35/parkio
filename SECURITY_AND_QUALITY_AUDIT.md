# Parkio — Full Security, Code-Quality & Product Audit

**Date:** 2026-07-04
**Scope:** Entire monorepo — 10 Java/Spring Boot microservices (`services/*`), shared `platform/parkio-platform`, web SPA (`frontend/apps/web`), new React-Native/Expo app (`frontend/apps/mobile`), shared TS packages (`frontend/packages/*`), infra (`docker/`, `docker/caddy`, observability), CI/CD (`.github/workflows/*`), SQL migrations, and dependency/supply-chain posture.
**Method:** 9 parallel deep-dive audits (one per domain), each reading source directly and reporting `file:line`-grounded findings, plus independent hand-verification by the lead of the crown-jewel trust boundary (gateway ↔ services), JWT issuance/validation, JWKS resolver, container port model, and actuator exposure. Findings reported by multiple agents were deduplicated; severities were re-calibrated by the lead against the actual code (noted inline where I revised an agent's rating).

> **Codebase size:** ~930 Java files, ~21k LOC web TS, ~14k LOC mobile TS, 113 SQL migrations, 4 compose stacks, 13 GitHub Actions workflows.

---

## 1. Executive summary

**Parkio is a mature, unusually security-conscious codebase.** The team has a real, documented threat model (`docs/ai-context/07-security-guidelines.md`) and the code largely lives up to it. The front door is genuinely well-built:

- **No SQL injection** anywhere (repo-wide sweep: all queries parameterized, including PostGIS geo queries and idempotency keys).
- **No unsafe deserialization** (no Jackson polymorphic/default typing, no SnakeYAML unsafe load, no `ObjectInputStream`; Kafka `spring.json.trusted.packages=com.parkio.*`, type headers off).
- **No JWT algorithm confusion / `none` downgrade** — RS256 is pinned at the gateway *and* auth-service; JWKS-by-`kid`; issuer/audience/expiry enforced; fail-closed key resolution.
- **The gateway↔service trust boundary is correct and tested** — the gateway strips inbound `X-User-*` on *every* request (even public routes) and re-injects identity only after JWT validation; a shared `X-Gateway-Auth` secret (constant-time compared, fail-closed, rotation-capable) proves gateway origin to downstream services. **The classic "forge `X-User-Id` by calling a service directly" attack does not work.**
- **No end-user IDOR found in any service** — object reads use non-enumerable 404 semantics; `/me`-scoped endpoints derive identity from the verified token; analytics/moderation re-check authorization downstream (defense-in-depth).
- Strong operational hygiene: hosted-beta compose un-publishes every backend port (gateway-only ingress, smoke-tested), non-root containers with `cap_drop: ALL`, fail-closed secrets, encrypted backups, broad Dependabot, gitleaks, and current dependency versions (Spring Cloud Gateway is patched for the 2025 SpEL RCEs).

**The real risk is not the front door — it is the internal trust model and the newer business logic.** Findings concentrate in six areas the "find security bugs" framing alone would under-weight:

1. **Two systemic internal-trust assumptions** carry the whole platform (§4): a *single reused shared secret* and an *unauthenticated Kafka bus*. Most high-impact findings are reachable **only after crossing one of these internal boundaries** — by a leaked secret, an SSRF to an internal port, a compromised pod, or broker produce access — **not** by a normal authenticated user.
2. **Business-logic / abuse of the just-shipped gamification & trust-point system** (self-award, farming, no decay/velocity caps, no permanent trust ledger) and **moderation** (self-review, weaponized reports).
3. **Reliability / eventing correctness** — a verified **non-atomic inbox dedup** that can double-apply side effects under concurrency; outbox reordering; dedup TTL replay.
4. **Privacy / data retention** — Smart Return concentrates precise **home coordinates** and leaks them into unbounded, never-purged location logs; no GDPR erasure path. This contradicts the team's own documented "location privacy" rule.
5. **Availability / DoS** — JWKS refresh amplification, image-decode memory amplification, rate-limiter fail-open on Redis outage, unbounded admin aggregates.
6. **Release-readiness gaps** — SAST (CodeQL) and image signing are wired but gated off; crash/analytics telemetry and **push delivery are no-ops today**; and one **production-breaking functional bug** (all icons render as literal text because the prod CSP blocks Google Fonts).

**There are zero confirmed remote-unauthenticated critical vulnerabilities.** The single most urgent *security* item to resolve is a **HIGH that requires a runtime test** — a path-canonicalization gap at the gateway (§5.1 / §8). The most urgent *functional* item is the icon/CSP bug (§5.10).

### Severity tally (deduplicated)

| Severity | Count | Notes |
|---|---:|---|
| **HIGH** | 4 | 1 needs runtime confirmation (path confusion); 1 verified reliability (inbox dedup); 1 Kafka-scoped abuse (gamification); 1 fail-open stub (AI-validation) |
| **MEDIUM** | ~22 | privacy, DoS, enumeration, abuse, infra, eventing, mobile/web |
| **LOW / hardening** | ~30 | mostly defense-in-depth & config |
| **Systemic** | 2 | shared secret; unauthenticated Kafka (both gate the HIGH/MEDIUM impact of many others) |
| **Prod-breaking functional bug** | 1 | icon font blocked by CSP |

---

## 2. Methodology & verification status

Each finding is tagged:

- **[VERIFIED]** — confirmed against source by the lead or the domain agent read the exact code path.
- **[VERIFY-RUNTIME]** — statically plausible but exploitability depends on runtime behavior (path normalization, Kafka ACLs, network segmentation) that cannot be confirmed from the repo. These are called out explicitly in §8 — **do not treat them as confirmed or as false alarms until tested.**
- **[CONTINGENT]** — real only if a stated internal control (broker ACLs, network policy) is absent.

Severities I personally re-calibrated from the raw agent reports:
- Gateway JWKS amplification: **HIGH → MEDIUM** (the `inFlightRefresh.cache()` coalescing bounds it to ~1 outbound fetch per round-trip; it is sustained nuisance load, not a large multiplier).
- Caddy "does not strip `X-User-*`": **downgraded to LOW/defense-in-depth** — I verified the *gateway* strips and overwrites these on every request, so it is not an auth bypass; edge stripping is belt-and-suspenders.
- Gateway "analytics `/users/**` IDOR": **resolved as CLEAN** — `AnalyticsController` enforces `X-User-Id == {userId}` and re-checks ADMIN; the edge carve-out is covered downstream.

---

## 3. Top findings, severity-ranked

| # | Sev | Finding | Area | Location | Status |
|---|---|---|---|---|---|
| 1 | **HIGH** | No path canonicalization before edge authz/routing (`..`/`//` confusion → possible role-tier bypass / `/internal` reach) | Gateway | `RouteAuthorizationRules.java:70-80`, routes `application.yml:23-132` | VERIFY-RUNTIME |
| 2 | **HIGH** | Non-atomic inbox dedup (`save()`→`merge()`) double-applies concurrent duplicate events | Eventing | `*/InboxEventRepositoryAdapter.java:26-27`, `InboxEventEntity.java:15-22` | VERIFIED |
| 3 | **HIGH** (Kafka-scoped) | Forgeable/replayable point & trust grants → self-award / griefing; no role check on the only mutation path | Gamification | `ParkingEventsKafkaConsumer.java:50-70`, `GamificationApplicationService.java:101-180` | CONTINGENT on Kafka ACLs |
| 4 | **HIGH** | AI-validation always returns `PASSED` ~99% confidence (fail-open placeholder) | AI-validation | `DeterministicAiValidator.java:24-52` | VERIFIED (advisory by design) |
| 5 | MED | Systemic: one static shared secret = entire internal identity anchor | All `/internal/**` | `*/GatewayAuthFilter.java`, `.env.hosted-beta.example:72-76` | VERIFIED (§4) |
| 6 | MED | Systemic: unauthenticated Kafka; payloads/`eventId` trusted from the wire | Eventing | `EventEnvelope.java:15-23` | CONTINGENT on ACLs (§4) |
| 7 | MED | Bulk home-GPS exposure via internal endpoint + unbounded home-coord location logs | Smart Return / user / parking | `InternalUserController.java:63-71`, `parking_spot_search_logs` (no retention) | VERIFIED |
| 8 | MED | Trust-point design: no decay/floor/velocity cap, self-dealing unguarded, no permanent trust ledger (replayable) | Gamification | `TrustScore.java:43-51`, `GamificationApplicationService.java:282-301` | VERIFIED |
| 9 | MED | Rate limiter fails **open** on Redis outage + runs after JWT/JWKS + per-email-only lockout → spraying & lockout-DoS | Gateway / auth | `RateLimitConfig.java`, `RedisLoginFailureTracker.java` | VERIFIED |
| 10 | MED | JWKS refresh amplification (unauthenticated random-`kid`, no negative cache/backoff) | Gateway | `RemoteJwksKeyResolver.java:42-80` | VERIFIED |
| 11 | MED | User enumeration: registration 409-vs-201, public-profile status/oracle, login/forgot timing | Auth / user | `AuthApplicationService.java:138-140,173-174,236-249`, `UserController.java:191-195` | VERIFIED |
| 12 | MED | Redis has **no authentication** in any environment (backs locks/idempotency/rate limits) | Infra | `docker-compose.yml:153` | VERIFIED |
| 13 | MED | Moderator can resolve/assign a case where they are the subject (no separation of duties); weaponized single-reporter reports | Moderation | `ModerationApplicationService.java:159-196,99-114` | VERIFIED |
| 14 | MED | Device-token reassignment → cross-user push leakage (`(user_id,token)` not global-unique) | Notification | `NotificationApplicationService.java:218-227`, `V2:13` | VERIFIED |
| 15 | MED | `X-Correlation-Id` CRLF log-forgery + Kafka trace-context injection (systemic, all services) | Platform | `CorrelationIdFilter.java:25-28`, `KafkaTraceContextSupport.java:48-59` | VERIFIED |
| 16 | MED | Global media checksum unique constraint → existence oracle + upload-griefing | Media | `MediaApplicationService.java:168`, `V1:21` | VERIFIED |
| 17 | MED | `claimSpot` single-action terminal `FILLED` (no proof/undo) → availability griefing | Parking | `ParkingApplicationService.java:181-198` | VERIFIED |
| 18 | MED | Outbox reordering → stale trust score (user-service absolute SET, no `occurredAt` guard) | Eventing | `GamificationOutboxRelay.java:130-143`, user-service handler | VERIFIED |
| 19 | MED | Image-decode memory amplification (40 MP, no concurrency bound) → heap DoS | Media | `application.yml:153`, `ImageIoImageNormalizer.java` | VERIFIED |
| 20 | MED | No GDPR erasure/retention for analytics + user rows; search/view logs grow forever | Analytics / user / parking | `RetentionCleanupJob.java` (outbox/inbox only) | VERIFIED |
| 21 | MED | Mobile: staff routes lack client-side role guard **and** are in the push-nav allow-list | Mobile | `(main)/_layout.tsx:16`, `pushNotifications.ts:136-137` | VERIFIED |
| 22 | MED | Mobile: map WebView loads remote JS from `unpkg.com` (no SRI, `originWhitelist:['*']`) | Mobile | `mapHtml.ts:72-73`, `MapSurface.tsx:279` | VERIFIED |
| 23 | MED | Mobile: reset/verify tokens over custom `parkio://` scheme (no App Links) → interceptable | Mobile | `app.json:8`, `(auth)/reset-password.tsx:22` | VERIFY (email channel) |
| 24 | MED | Web: prod CSP exists only at the Caddy layer (no artifact-level fallback) | Web | `Caddyfile:29`, `index.html` (no meta) | VERIFIED |
| 25 | MED | CI: `workflow_dispatch` inputs interpolated into `run:` shell (script injection) | CI/CD | `release.yml:58`, `frontend-real-e2e.yml:61-62` | VERIFIED (write-access only) |
| 26 | MED | Sync external email call inside `@Transactional` → pool exhaustion + registration rolls back if email fails | Auth | `AuthApplicationService.java:60,159-160,224,248` | VERIFIED |
| — | **BUG** | **Prod icons/fonts blocked by CSP → users see literal `search`/`home`/`menu` text everywhere** | Web | `index.html:12-19` vs `Caddyfile:29` | VERIFIED |

LOW / hardening items are grouped per-area in §5 and the code-quality themes in §6.

---

## 4. Systemic architecture risks (the spine)

Two design decisions underpin the entire internal security model. Neither is a coding bug; both are *acceptable for hosted-beta behind network isolation* but must be hardened before production and are the reason many findings below are gated to "internal boundary" rather than "front door."

### S1 — A single static shared secret is the whole internal identity anchor  `[VERIFIED]`
`PARKIO_GATEWAY_INTERNAL_SECRET` is one value, reused verbatim by the gateway and all 9 services (`.env.hosted-beta.example:72-76`). The gateway stamps it on every routed request; each service's `GatewayAuthFilter` accepts it (constant-time, rotation-capable, fail-closed). **Once that check passes, `X-User-Id`/`X-User-Roles` are trusted verbatim** — a caller can self-assert `ADMIN`. `/internal/**` endpoints (bulk home-GPS, media access-URL minting, session-epoch, smart-return) are protected by *exactly this same secret*, no per-caller identity. Consequence: a leak of this one secret, an SSRF to any internal port, or compromise of any single pod yields **platform-wide user impersonation and bulk PII access**.
**Reported by:** gateway, trust/parking, remaining-services, cross-cutting (4 independent agents).
**Fix:** distinct per-service credentials or mTLS/SPIFFE for `/internal/**`; a `NetworkPolicy` so only the intended caller can reach each internal path; consider signing the injected identity (short-lived internal JWT) rather than plain headers.

### S2 — The Kafka bus is unauthenticated; event payloads are trusted from the wire  `[CONTINGENT]`
`EventEnvelope` is an unsigned record; consumers act on `payload()`, `eventType`, `ownerUserId`, `targetId`, and `eventId` verbatim with no signature/HMAC/producer allow-list. A fresh random `eventId` is *not* a duplicate, so inbox dedup never fires on a forgery. Anyone with **broker produce access** could:
- forge `UserSuspended` → lock any account (auth revokes tokens + bumps session epoch) or `UserRestored` → un-ban;
- forge `ParkingSpotClaimed{owner=self}` → self-grant points/trust;
- forge `ModerationCaseResolved{DEDUCT_POINTS/REDUCE_TRUST, target=victim}` → grief.
This is the standard internal-bus model and is **safe iff Kafka topic ACLs / mTLS restrict produce to the owning service** — which **could not be confirmed from the repo**. There is no in-code actor-vs-producer authorization.
**Fix:** enforce per-topic broker ACLs/mTLS (confirm in infra sign-off), and/or sign event provenance; add an `ownerUserId != actorUserId` invariant in gamification.

> **These two items should get an explicit infra sign-off** (broker ACLs + network segmentation). They convert most of the "HIGH-if-internal" findings below into "not reachable."

---

## 5. Findings by area

### 5.1 Gateway — the perimeter (well-built; two DoS/authz edges)

**[HIGH — VERIFY-RUNTIME] No path canonicalization before edge authorization or routing.** Edge role rules and route predicates use prefix `PathPattern`s with first-match-wins carve-outs, and there is **no `StrictHttpFirewall`-equivalent filter and no `StripPrefix`**. If Spring Cloud Gateway (Boot 3.5.15 / SCG 4.3.5) forwards `..`/`//` unmodified and the downstream servlet normalizes them, two attacks open:
- **Role-tier bypass:** `GET /api/v1/analytics/users/../overview` matches the `AUTHENTICATED` carve-out `…/analytics/users/**` at the edge, skipping the `ADMIN_ONLY` rule; if analytics-service collapses `..`, a plain USER reaches an admin path. *(Mitigating factor: `AnalyticsController` re-checks ADMIN downstream, so this specific case is likely caught — but any privileged endpoint that does NOT re-check is exposed.)*
- **Reaching a service's own `/internal/**`:** `/api/v1/parking/../../../internal/...` matches `Path=/api/v1/parking/**`, is forwarded raw with a **valid stamped `X-Gateway-Auth`**, and if the downstream normalizes, reaches internal endpoints.
**Test now** (see §8 for commands). **Fix:** add a highest-precedence global filter rejecting any decoded path containing `..`, `//`, or backslash before authz/routing; prefer explicit allow-listing over prefix carve-outs for privileged surfaces.

**[MED — VERIFIED] JWKS refresh amplification.** `JwtTokenValidator.requiredKeyId` reads the untrusted `kid` and calls `keys.resolve(kid)` *before* any signature check; on an unknown `kid` with a valid (non-expired) cache, `RemoteJwksKeyResolver.resolve` calls `refresh(true)` (`:50`), forcing an outbound JWKS GET to auth-service. No negative cache for unknown `kid`s, no backoff. Unauthenticated `alg:RS256`+random-`kid` tokens drive continuous JWKS load. *Calibration: `inFlightRefresh.cache()` coalesces concurrent misses into one outbound fetch, bounding it to ~1 fetch/round-trip — sustained nuisance load on the shared auth dependency, not a big multiplier.* **Fix:** negative-cache unknown `kid`s briefly; throttle forced refreshes; or use Nimbus `RemoteJWKSet`/Caffeine `AsyncLoadingCache`.

**[MED — VERIFIED] Rate limiting runs *after* authentication and fails **open** on Redis outage.** Custom global filters use `HIGHEST_PRECEDENCE + n`, always sorting before the per-route `RequestRateLimiter`; the edge cannot shed load before the expensive JWT/JWKS validation and remote status/epoch calls (this is what leaves the JWKS amp unthrottled). Separately, the stock `RedisRateLimiter` **allows** requests when Redis errors — so during a Redis outage the strict auth login/register limits (5/10) are bypassed exactly when credential-stuffing protection matters most. This is inconsistent with the gateway's otherwise deliberate fail-closed posture. **Fix:** add a lightweight per-IP limiter *ahead* of authentication; decide explicitly whether the auth-tier limiter should fail closed.

**Code quality / LOW:** JWKS/transport errors returned as `401 INVALID_TOKEN` instead of `503` (a JWKS outage silently 401s every valid user); unbounded in-memory `UserStatusCache`/`SessionEpochCache` with lazy-only eviction (use Caffeine); a single duplicate `kid` in JWKS throws and bricks all edge auth (log+skip instead); hand-rolled JWKS cache is subtle; `status` claim parsed but unused.

**Good practices:** RS256 pinned + verified with RSA key (no `none`/HS confusion); header spoofing stripped and **unit-tested**; genuinely spoofing-resistant `ClientIpResolver` (XFF trusted only from configured proxies, right-to-left, IP-literal only, trust-nothing default); CORS hard-fails wildcard-origin+credentials at startup; account-status and session-epoch checks fail closed (503); actuator limited to `health,info,prometheus`; no `/internal` route defined.

### 5.2 Auth-service (excellent core; enumeration & a transaction smell)

**[MED — VERIFIED] User enumeration & timing oracles.** `register` returns `409` for taken vs `201` for free email, unthrottled — inconsistent with the *correctly* enumeration-safe `forgot-password`/`resend-verification`. In `login`, BCrypt is skipped entirely when the email is unknown (fast path → timing oracle). In `forgotPassword`, the synchronous outbound email (up to 5s) runs only for existing+verified accounts (stronger latency oracle). **Fix:** uniform register response or per-email cooldown; compute a dummy BCrypt when user is null; send email async via the existing outbox.

**[MED — VERIFIED] Sync Resend email call inside the `@Transactional` boundary.** `register`/`resend`/`forgot` perform an outbound HTTP POST while holding a Hikari connection (pool max 8) for up to 5s → pool exhaustion under provider latency, and if the send throws, the **whole registration transaction (including the `UserRegistered` outbox row) rolls back** — the email provider is a hard dependency on the write path. **Fix:** persist first, dispatch email via the outbox relay (the pattern already exists, just unused for email).

**[MED — VERIFIED] No IP/global auth rate-limit at the service; per-email lockout enables lockout-DoS.** Brute-force protection is per-normalized-email only; an attacker can deliberately lock a victim (5 fails → 30s, escalating to 1h), and password spraying across many accounts from one IP is unthrottled at the service. *(Partially mitigated by the gateway's per-IP auth-tier limiter — but see §5.1 fail-open.)* **Fix:** add an IP/subnet dimension; rely on a verified gateway limiter.

**LOW / hardening (verified):** OpenAPI/Swagger enabled by default and reachable without auth or the gateway secret on a directly-reachable pod (set `PARKIO_OPENAPI_ENABLED=false` in prod); `JwtService.parse` validates issuer but **not audience** (add `.requireAudience`); `AuthUser.email` normalization uses **default JVM locale** `toLowerCase()` — a Turkish-locale host maps `I`→`ı` and can create duplicate/mismatched accounts (use `Locale.ROOT`); BCrypt cost 10 (bump to 12) with silent 72-byte truncation vs a 100-char policy; `refresh_tokens`/`password_reset_tokens` grow monotonically (retention job prunes only outbox/inbox); non-atomic Redis `INCR`+`EXPIRE` with sliding TTL; session-epoch not re-checked in the service's own bearer filter; a one-flag-flip (`log-token=true`) would log raw reset/verify links.

**Good practices (verified, do not regress):** refresh tokens stored only as SHA-256 of 256-bit random values, rotated, in families, with reuse→family-revocation + session-epoch bump committed via `noRollbackFor`, absolute family TTL cap, and optimistic-lock resolution of concurrent double-refresh mapped to a generic 401; refresh cookie `HttpOnly`+`Secure`+`SameSite=Strict`, host-only, path-scoped, never in the JSON body, with manual Origin/Referer CSRF checks; lockout indistinguishable from bad-credentials; RS256 `kid` fail-closed; mobile flow (`X-Parkio-Client: mobile`) with a guard preventing browsers from opting into the header-only refresh path.

### 5.3 Parking, Smart Return & the trust boundary

**[MED — VERIFIED] `claimSpot` is a single-action terminal state with no proof of pickup.** Any authenticated non-owner can mark **any** visible spot `FILLED` (terminal, removed from search) with one call — no corroboration, cost, or undo — versus community-verify which needs 2 reports. Within the parking rate tier (or with a few accounts) an attacker suppresses all nearby availability (business-logic DoS / data-quality abuse), and Smart Return's value depends on accurate availability. **Fix:** make a lone claim non-terminal/expiring, gate on trust score, or allow owner/community reversal.

**[MED — VERIFIED] Smart Return concentrates & leaks precise home locations.** `/internal/users/smart-return/due-return-checks` returns **every** due user's `homeLatitude/Longitude/Label` in one paginated sweep (shared-secret-only, and it mutates state). The scheduler then pipes home coords into parking `searchNearby`, which persists them in `parking_spot_search_logs` (searcher + coordinates) — and the retention job **never purges search/view logs**, so these become an unbounded, indefinitely-retained precise location history for every user. This directly contradicts the team's own rule: *"treat users' precise location history as sensitive; do not expose one user's movement patterns"* (`07-security-guidelines.md:196-199`). **Fix:** evaluate the geofence **inside** user-service (return a boolean + opaque area token, never raw lat/lng); add a retention/purge job for search & view logs; store truncated coordinates.

**LOW (verified):** actuator/OpenAPI bypass on directly-reachable services (as elsewhere); `shouldNotFilter` matches the **raw** URI (`/actuator/..;/...` — VERIFY-RUNTIME, likely rejected by Tomcat); no DB-level CHECK constraints on `latitude`/`longitude`/`confidence_score` (enforced only in the domain).

**Good practices (verified):** strong IDOR posture — ownership enforced with **non-enumerable 404** on `getMySpot`/`getSpotForViewer`/`getSpotMediaAccessUrl`; `PublicSpotResponse` omits `ownerUserId`; `@Version` optimistic locking prevents double-claim; a unique constraint closes the verify TOCTOU; expiry batch uses `FOR UPDATE SKIP LOCKED`; geo search bounded (≤50 km radius, ≤50 results) on a GiST index; spot creation requires media to be `READY` (uploaded + ClamAV-scanned); native geo queries use bound `@Param`.

**What Smart Return is (V1, flag-gated off by default):** user sets a home area + return time (`/me/smart-return/settings`, self-scoped); a morning cron prompts "leaving by car today?"; every 60s a scheduler leases due users and searches parking near each home; if spots exist it creates a "parking available near home" notification. Parking-service holds no Smart Return code — it participates only via the existing nearby-search endpoint. The user-facing surface is safe; the two concerns are the internal bulk-exposure and log accumulation above.

### 5.4 Media-service (unusually well-hardened uploads)

**[MED — VERIFIED] Global (cross-user) checksum dedup → existence oracle + griefing.** `existsByChecksum` runs against a **global** `UNIQUE (checksum)` (not owner-scoped), and normalization (deterministic re-encode) means a known image hashes identically. An attacker learns whether a specific image was uploaded by anyone (409 `DUPLICATE_MEDIA`), and can pre-upload an image to permanently block others from uploading it. **Fix:** scope the query and constraint to `(owner_user_id, checksum)`; treat same-owner dupes as idempotent success.

**[MED — VERIFIED] Image-decode memory amplification.** `max-file-size` 10 MB but `max-image-pixels` 40,000,000 → a compressed 10 MB JPEG decodes to ~160 MB `BufferedImage`, with ~2-3 full frames held transiently (~320-480 MB/request) and **no local concurrency bound**. A handful of concurrent crafted uploads → OOM/GC-thrash. **Fix:** lower to ~24-30 MP, bound concurrent decodes with a semaphore/bulkhead.

**LOW / design:** internal access-URL endpoint mints a 5-min signed GET for **any** `READY` media UUID with no ownership check — *intentional per javadoc* (parking-service authorizes visibility), but it concentrates trust in the shared secret (S1); `DELETE` returns `403` for non-owner vs `404` for missing (existence oracle — read paths correctly return 404 both ways); actuator/OpenAPI bypass; MinIO defaults `minioadmin/minioadmin` and a committed DB dev password would be used silently if env is unset (no fail-closed for storage creds); `ImageIO` temp-file cache on by default (`setUseCache(false)`).

**Good practices (verified):** magic-byte verification (declared type must equal detected — SVG/HTML/exe rejected); re-encode-from-pixels normalization strips EXIF/GPS/ICC and defeats polyglots; decompression-bomb guard reads dimensions **before** full decode; server-generated object keys (`media/{uuid}/{uuid}.ext`, never from client filename); **scan-before-store, fail-closed** (infected→422, scanner-down→503, nothing persisted, signature never returned); IDOR-hardened 404s; short-lived GET-only signed URLs never persisted/logged; no SSRF surface (no fetch-by-URL).

**Product:** WebP is advertised in the allow-list but **always 422s** (no WebP `ImageIO` reader on the classpath — intentional & tested) — add a reader or drop it; dead `perceptualHash` scaffolding (planned near-dup detection); no thumbnail/derivative pipeline (every view mints a full-size URL).

### 5.5 Gamification & trust points (the weakest business feature)

**[HIGH — CONTINGENT on Kafka ACLs] Forgeable point/trust grants.** Handlers use payload `ownerUserId`/`actorUserId`/`targetId` verbatim with no producer auth; a fresh random `eventId` defeats inbox dedup. A broker-produce-capable actor self-awards (`Claimed{owner=actor=self}` → +30/+10 +trust) or griefs (`DEDUCT_POINTS`/`REDUCE_TRUST` on a victim). **This event path is the *only* authz gate for every privileged points/trust mutation** — no role check exists. Not reachable by an end user (all HTTP mappings are GET). **Fix:** authenticate event provenance (S2); enforce `ownerUserId != actorUserId`.

**[MED — VERIFIED] Trust has no permanent idempotency ledger (points do).** Points are permanently deduped by a never-purged `uq_point_transactions_idempotency_key` (`eventId:ruleKey`, hard INSERT). **Trust relies only on the inbox**, which is purged after 30 days — so a replay after purge/offset-reset/backup-restore re-applies trust deltas and silently undoes moderation penalties. **Fix:** give trust a permanent `eventId:ruleKey` unique ledger.

**[MED — VERIFIED] Trust-point design gaps (business-critical, just shipped).** Only a 0-100 clamp: no decay, no penalty floor, no per-window velocity cap, `owner==actor` unguarded. A `-15/-10` penalty is erased by ~8-15 ordinary earn events, so trust carries little lasting signal; colluding/self-dealing accounts farm both point sides + owner trust. **Fix:** sticky/decaying penalties, positive-gain rate caps, distinct-account/device heuristics.

**LOW / quality:** leaderboard discloses stable `authUserId` UUIDs + exact balances (chainable into public-profile); `FixedBackOff(500,2)` retry budget can dead-letter a legitimate award under contention (prefer atomic `SET total_points = total_points + :delta`); three near-identical consumers to extract.

**Good practices:** points genuinely cannot be double-granted (DB unique key + `@Version`, never-purged ledger); DLQ + bounded backoff; clamped invariants (no overflow/negative); seeded rules (not hardcoded).

### 5.6 Moderation-service (strong authz; workflow-integrity gaps)

**[MED — VERIFIED] No separation of duties.** `APPROVE` is not admin-gated and `resolve()` only blocks terminal→terminal, so a **moderator can resolve/assign a case in which they are the subject** — self-exonerate an abuse case or self-approve their own flagged spot. **Fix:** reject when `moderatorId == targetId`/`ownerUserId`.

**[MED — VERIFIED] Weaponized reports.** Any user opens a case against an arbitrary, **unvalidated** `targetId`; a "serious" reason immediately opens a HIGH case; the only throttle is a per-`(reporter,target,reason)` unique index. A later admin `SUSPEND_USER` lands on the attacker-chosen victim. **Fix:** rate-limit, require corroborating distinct reporters / a count threshold, validate the target exists.

**LOW / quality:** penalty resolutions can be recorded with a null rationale (require a note for non-`APPROVE`); unbounded case listing (`findByStatus…` has no `Pageable`); Kafka handlers open cases with attacker-chosen `ownerUserId`; DTO `@Size(max=2000)` exceeds DB `VARCHAR(1000)`; invalid `?status=` enum → 500; check-then-act `openOrReuseCase` with a non-unique index → concurrent duplicate active cases; parking-spot (content) cases are unappealable by the owner (workflow gap).

**Good practices:** exemplary function-level authz re-checked in-service (`requireModerator`/`requireAdmin`), appeals ADMIN-only and ownership-checked, correct CSV role parsing, no injection.

### 5.7 AI-validation-service (a fail-open stub)

**[HIGH — VERIFIED, advisory by design] Always returns `PASSED` at ~99 confidence for all content.** `DeterministicAiValidator` derives scores from `mediaId` bits within ranges that can only map to `PASSED`, with `riskType=null` so no risk can ever fire; live consumers emit real `AiValidationCompleted{status=PASSED}`. There is **no LLM** (so prompt-injection / API-key / SSRF / cost abuse are all N/A — grep-confirmed). Impact depends on any downstream that treats `PASSED`+high-confidence as signal. **Fix:** until a real model is wired, emit `PENDING/NEEDS_REVIEW` with confidence 0 (fail-closed to human review), or gate behind an explicit placeholder flag; ensure no service auto-acts on the result.

**MED/LOW:** consumers mint verdicts for arbitrary ids, idempotency keyed on `eventId` not `mediaId` → 12 rows/event storage amplification; N+1 writes/reads; read-write `@Transactional` on read-only queries. HTTP surface is correctly MODERATOR/ADMIN-only.

### 5.8 User / Notification / Analytics

**User [MED]:** bulk home-GPS internal endpoint (see §5.3); **public-profile leaks moderation `status`** (ACTIVE/**SUSPENDED**) and is an existence oracle (404 vs 200) — drop `status`, uniform response. LOW: internal mutating endpoints trust a caller-supplied target id; Kafka handlers persist unvalidated numeric projections; N+1 in the smart-return stream (`limit` up to 500); free-text `displayName`/`city` stored unescaped (ensure frontend escapes); **no GDPR erasure endpoint**.

**Notification [MED]:** **device-token reassignment → cross-user push leakage** — uniqueness is `(user_id, token)`, not global on `token`; on a shared/resold device, user B's `(B,T,active)` row stays live, so B's notifications ("suspended", "appeal rejected", trust/points) push to A. **Fix:** treat the token as globally owned; deactivate every other user's row for `T` on registration. LOW: tokens registered with no proof-of-possession, no rate limit; internal smart-return endpoints shared-secret-only. **Reality check:** push is a **no-op today** (`NoopPushNotificationSender`; `ParkingSpotCreated` fan-out unimplemented) — Smart Return/moderation notifications don't actually deliver.

**Analytics [MED]:** **no retention/erasure** for `analytics_events`/`user_analytics_snapshots` (GDPR + unbounded growth; mitigated: pseudonymous UUIDs only). LOW: producer-trusted metrics (spoofable via S2); unpaginated admin full-table aggregates recomputed per call. **Good:** no HTTP ingestion (Kafka-only → no client spoof/DoS surface); IDOR-safe (`X-User-Id == {userId}` + ADMIN re-check); no dynamic SQL; minimal PII.

### 5.9 Eventing & data (cross-cutting)

**[HIGH — VERIFIED] Non-atomic inbox dedup.** `markProcessed` does `jpa.save(new InboxEventEntity(...))`; the entity has an assigned `@Id`, **no `@Version`**, doesn't implement `Persistable`, all columns `updatable=false` — so Spring Data resolves `save()` to `merge()` (SELECT-then-upsert), **not** a hard `persist()` INSERT. With ≥2 replicas and a redelivery, two transactions both see `existsById=false`, both run side-effects, and the second commit's `merge` takes a no-op UPDATE branch → **no PK violation → duplicate side-effect commits.** Blast radius = services without a secondary DB dedup key: **notification** (duplicate push), **moderation** (duplicate case), **ai-validation** (duplicate result, and a re-invoked paid call once a real validator lands). Parking-service already does it right (`INSERT … ON CONFLICT DO NOTHING`). **Fix:** authoritative atomic inbox claim *before* side-effects (`ON CONFLICT (id) DO NOTHING`, branch on rows-affected), or `InboxEventEntity implements Persistable<UUID>`.

**[MED — VERIFIED] Outbox reordering.** The relay marks rows published individually; a timed-out earlier same-key row can publish after a later one, and `FOR UPDATE SKIP LOCKED` multi-instance relaying with no leader election can also invert order. **user-service applies `TrustScoreUpdated` as an absolute SET with no `occurredAt` guard** → a reorder persists the stale score (auth's status handler is protected by its `occurredAt` guard — copy that pattern).

**[MED — VERIFIED] Missing per-table dedup + inbox 30-day TTL.** `moderation_cases` has a second, inbox-independent race (two distinct events → two active cases via `findActiveByTarget…isEmpty()`); add partial unique indexes. A duplicate delivered >30 days later (DLQ redrive, offset reset, backup restore) finds no inbox row → re-applies non-ledger effects.

**[MED — VERIFIED] `X-Correlation-Id` log-forgery + trace injection.** Client value only `.trim()`ed, never validated, rendered into a plain-text log pattern (no `%replace`) and fed to the hand-rolled `\n`/`=` Kafka trace codec → forged log lines in every service (poison Loki/SIEM), plus injected `traceparent`/`baggage` re-emitted as Kafka headers into consumer MDC. **Fix:** validate `^[A-Za-z0-9._-]{1,128}$` at the edge, strip CR/LF before any `MDC.put`, escape codec separators.

**LOW:** outbox DLQ *recovery* schema (auth V19) is schema-only (manual SQL, no writer); consumers manually parse JSON so a malformed envelope retries 3× before DLQ (mark `JsonProcessingException` non-retryable); parking's `ModerationActionsKafkaConsumer` acks before commit (inconsistent with other services).

**Good practices:** at-least-once outbox (publish marked only after broker ack, keyed by aggregate, capped attempts → dead-letter excluded via partial index, poison row never blocks batch), `acks=all`+idempotent producer, `DefaultErrorHandler`+`DeadLetterPublishingRecoverer`+manual post-commit ack, `tools/dlt-redrive` caps attempts and refuses DLT→DLT.

### 5.10 Web frontend (exemplary auth; one prod-breaking bug)

**[BUG — VERIFIED, prod-breaking] Icons/fonts blocked by the production CSP.** Inter and **Material Symbols Outlined** load only via external `<link>` to `fonts.googleapis.com`/`gstatic.com`, but the prod CSP is `style-src 'self' 'unsafe-inline'; font-src 'self' data:` — both origins are blocked. In hosted-beta/prod the icon font never loads, so every `Icon` renders raw ligature text: users literally see `search`, `home`, `menu`, `close`, `verified`… across the whole app, and Inter degrades to a system font. **Fix:** self-host Inter + Material Symbols (`@fontsource/*` + `@font-face`) — preferred, keeps CSP tight — or add the Google origins to `style-src`/`font-src`.

**[MED — VERIFIED] Prod CSP only at the Caddy layer.** `index.html` ships no CSP `<meta>`; if the SPA is served directly, behind a different proxy, or via `vite preview`, there is zero CSP. **Fix:** ship a baseline CSP with the artifact.

**LOW:** uploaded photos not EXIF-scrubbed client-side (**mitigated** — media-service re-encodes/strips server-side; web just lacks the mobile app's defense-in-depth); MapTiler key in the bundle (domain-restrict it); auto-geolocation prompt fires on `/map` mount (gate on a user gesture); broad `img-src https:` + external Unsplash hero on the login screen (self-host it).

**Good practices (verified):** access token in memory only + refresh token in HttpOnly cookie; single-flight refresh coordinator; `sessionEpoch` guard stops a logout-during-refresh from resurrecting a session; cross-tab logout via BroadcastChannel carries no tokens; `withCredentials` only on the 7 auth endpoints (data mutations use Bearer → CSRF-immune); **zero `dangerouslySetInnerHTML`/`eval`, zero `any`/`@ts-ignore`**; error-report sanitization redacts tokens/params; strong CSP + security headers otherwise (HSTS, `frame-ancestors 'none'`, no `script-src 'unsafe-inline'`); good a11y; SW never caches auth responses; deps resolve **above** known-CVE lines (axios 1.17, vite 6.4.3, esbuild 0.25.12).

**Product:** post-login deep-link redirect is dropped (`state.from` stored but ignored → always `/map`); moderation queue may not surface the reporter's free-text note; trust/verification timeline is a placeholder ("Community signal"); observability is a `console`-only stub; dark mode scaffolded but unused; no i18n library.

### 5.11 Mobile app (disciplined; defense-in-depth gaps)

**[MED — VERIFIED] Staff routes lack a client-side role guard and are in the push allow-list.** `(main)/_layout.tsx` guards only `isAuthenticated`; `moderation.tsx`/`analytics.tsx` have no role check; `ALLOWED_ROUTES` includes `/(main)/moderation` and `/(main)/analytics`, so a push payload's `data.route` navigates **any** authenticated user into staff UI. Exposure is bounded by backend RBAC (403), but it leaks the shape/existence of staff tooling and is a straight data leak if any staff endpoint misses server authz. **Fix:** add a `(staff)` group guard reading `roles`; remove staff routes from the allow-list.

**[MED — VERIFIED] Map WebView runs remote JS from a public CDN.** The map document loads `maplibre-gl` from `unpkg.com` at runtime with **no SRI** and `originWhitelist:['*']`, and the WebView holds a `postMessage` bridge to native. A unpkg compromise/registry-cache poisoning runs attacker JS with bridge access. (Inbound spot data is safely `JSON.stringify`'d — the risk is the remote script itself.) **Fix:** bundle `maplibre-gl` locally, or add SRI `integrity`+`crossorigin` and tighten `originWhitelist` + `onShouldStartLoadWithRequest`.

**[MED — VERIFY email channel] Auth tokens over the custom `parkio://` scheme.** No Universal/App Links configured; reset-password/verify-email read a `token` from deep-link params, so any app that also registers `parkio://` can intercept the link. Account-takeover-grade **if** the backend emails actually deep-link via `parkio://` (verify — if emails use https web links, these screens are unreachable and it's a dead-end instead, see product note). **Fix:** verified Universal/App Links for these flows.

**LOW:** no cert pinning; access token mirrored to `localStorage` on the web target (dev-only preview); crash/analytics are in-memory stubs (no Sentry/Crashlytics → prod is blind); EAS `projectId` placeholder + empty `submit.production`; a dev `map-bench` screen is routable in the release bundle; no e2e tests.

**Good practices (verified):** Keystore-backed `expo-secure-store` with **fail-closed** refusal to persist tokens if the native module is missing; single-flight refresh with rotation + reuse detection + `sessionEpoch` guard; logout wipes keystore/memory/store + revokes server-side + deregisters push; **EXIF/GPS stripped by re-encoding before upload**; foreground-only location, `blockedPermissions` (audio, SYSTEM_ALERT_WINDOW), `allowBackup=false`; zod env that throws if the prod API URL is missing; cleartext HTTP confined to the `development` EAS profile; no secrets in the bundle; all `console.*` gated on `__DEV__`; push-nav validated against an allow-list; strict TS.

**Product:** reset/verify may be dead-ends on mobile (see above); no offline persistence (react-query memory-only — painful in low-signal parking garages); Smart Return is flag-disabled in prod; background geofence is the obvious native-only upgrade web can't match.

### 5.12 Infra / Docker / CI-CD (strong hosted-beta posture)

**[MED — VERIFIED] Redis has no authentication in any environment.** `redis-server --appendonly yes`, no `--requirepass`, no `REDIS_PASSWORD` anywhere. Redis backs rate-limiting, distributed locks, and idempotency keys — flushing those enables replay/double-spend. Network-private in hosted-beta (mitigated), but any container on `parkio-backend` (or the LAN in dev) can read/flush it. **Fix:** `requirepass` from a secret + `SPRING_DATA_REDIS_PASSWORD` on consumers.

**[MED — VERIFIED] Local base compose publishes the entire data plane on `0.0.0.0`.** Every Postgres (5432-5440), Redis, Kafka, MinIO, ClamAV, Prometheus, Grafana bind to all host interfaces; newer additions correctly use `127.0.0.1:`. On untrusted Wi-Fi this exposes all DBs + object storage (with the committed `*_local_dev_pw` credentials). Hosted-beta neutralizes this with `ports: !reset []`. **Fix:** prefix data-plane publishes with `127.0.0.1:` in the base file.

**[MED — VERIFIED] CI script injection.** `version="${{ inputs.version }}"` (`release.yml:58`) and `${{ inputs.mode/base_url }}` (`frontend-real-e2e.yml:61-62`) interpolate free-text `workflow_dispatch` inputs into `run:` shell; a `$(...)` value executes. Requires repo write access (not fork-exploitable), but is a lateral-movement vector. **Fix:** pass via `env:` and reference `"$VERSION"` (the same file already does this for secrets).

**[MED — VERIFIED] SAST & image signing configured but inactive.** CodeQL is gated behind `CODEQL_ENABLED` (off on the current private repo → no SAST enforcement); cosign keyless signing + `attest-build-provenance` are gated behind `PUBLISH_IMAGES`/`ATTESTATIONS_ENABLED` (images currently unsigned/unattested, SBOMs generated). **Fix:** enable on the org/GHAS move.

**LOW / hardening:** Caddy does not strip inbound `X-User-*`/`X-Gateway-Auth` before proxying (**defense-in-depth only — the gateway already strips them, verified**); base images tag-pinned not digest-pinned; backing infra images (postgres/redis/kafka/minio/grafana/prometheus…) are 2024-era, pinned but unscanned by CI (matrix covers only the 10 app images); Trivy blocks CRITICAL only, not HIGH; actions pinned to mutable major tags not SHAs; Caddy API/media vhosts omit `frame-ancestors`/CSP and edge rate limiting; Loki `auth_enabled: false` (loopback); committed `*_local_dev_pw`/`minioadmin` + `local-dev-only…change-me` gateway secret (allowlisted, dev-bounded, but no fail-closed default for DB/storage creds).

**Good practices (verified):** hosted-beta makes Caddy the sole ingress, every backend/DB/broker port `!reset []`, observability `127.0.0.1`-bound, and a **post-deploy smoke test asserts direct `:8083` access fails**; non-root uid 10001, `no-new-privileges`, `cap_drop: ALL`, `pids_limit`, mem/cpu limits, multi-stage builds; required secrets use `${VAR:?}` fail-fast; **no `pull_request_target`**, least-privilege `contents: read`, OIDC keyless (no long-lived cloud creds), Gradle wrapper validation; gitleaks allowlist properly path+pattern-scoped; AES-256 encrypted backups + a weekly restore-drill workflow.

**Ops:** `PARKIO_WEB_UPSTREAM=web:80` is referenced by Caddy but **no `web` service is defined** in any compose file and there's no SPA Dockerfile — operators must stand it up separately (confirm the runbook covers it); `infra/` and `deploy-artifacts/` are stubs; 100% trace sampling + promtail shipping full stdout to Loki means the `*_LOG_TOKEN=false` defaults are the guardrail keeping raw auth tokens out of Loki — keep them false.

### 5.13 Dependencies & supply chain

Pinned to a recent BOM (Spring Boot **3.5.15** / Spring Cloud **2025.0.3**, gateway 4.3.5). Verified against advisories — **no known-vulnerable versions in use**:

| Library | Version | Note |
|---|---|---|
| Spring Boot | 3.5.15 | Patched for CVE-2026-22731/22733. **OSS line EOL 2026-06-30** (4 days before this audit) — plan the 4.x migration. |
| Spring Cloud Gateway | 4.3.5 | **Patched** for CVE-2025-41243/41253 (SpEL RCE). Invariant: never expose the `gateway` actuator endpoint. |
| jjwt | 0.12.6 | No CVE. Dependabot → 0.13.0 open. |
| kafka-clients | 3.9.2 | No confirmed CVE. Open PR → 4.3.1 (major — review, don't auto-merge). |
| postgresql JDBC | BOM 42.7.x | VERIFY BOM includes CVE-2026-54291 fix (low practical risk, trusted internal DB). |

Supply chain is healthy: catalog-pinned plugins (no confusables), reproducible archives + CycloneDX SBOM, broad Dependabot (Gradle/pnpm/Actions/Docker). Frontend lockfiles resolve above 2025 CVE lines.

---

## 6. Code-quality & best-practice themes

1. **External calls inside `@Transactional`** — synchronous Resend email (auth) and serial push sends (notification) hold DB connections/locks across network I/O → pool exhaustion + rollback coupling. Move to the existing outbox/async path.
2. **Deliberate filter duplication** — `GatewayAuthFilter`/`CorrelationIdFilter` are byte-identical across 9 services. This is a *documented* choice (their security guidelines forbid moving auth filters into the shared platform without a security review), so it's a conscious drift-risk trade-off, not an accident — but a security fix must be applied 9×. Consider a shared, security-reviewed component with per-service opt-in.
3. **Pervasive stale/misleading javadoc** — "Business logic is intentionally not implemented yet", "no consumer is implemented yet", "invoked directly for now" appear across services where the code is clearly built and protected. This can mislead operators into thinking endpoints are unguarded. Also **doc drift**: the project overview still says "Native mobile is not the current implemented client surface" though a full mobile app now exists, and the security doc states the password-reset TTL as both "1 hour" (line 98) and "30 minutes" (line 175).
4. **Dead code** — `perceptualHash` (media), `ContributionSnapshot`/`TimeGranularity` (gamification/analytics), `expireReturnCheckClaim`, several unused DTO params (`areaLabel`, `requestedByUserId`).
5. **DB hygiene** — missing FK-child indexes (`notification_delivery_attempts.device_token_id`, `refresh_tokens.parent_token_id`, moderation `case_id` children) → seq-scan + lock on parent delete; `EAGER` roles `@ManyToMany` (auth) → N+1; redundant/stale DDL; unbounded `refresh_tokens` growth; DTO `@Size` exceeding column length.
6. **Frontend polish** — duplicated coord/distance/relative-time formatters (consolidate into `@parkio/geo`); `console.warn` in the global query error handler on every occurrence; imperative API call in `useEffect` instead of `useMutation`; `noUncheckedIndexedAccess` off; no i18n.
7. **Retry/robustness** — thin optimistic-lock retry budgets can silently dead-letter legitimate events under contention; prefer atomic `UPDATE … SET x = x + :delta` over read-modify-write for counters.

---

## 7. Analysis dimensions — what you named, and what I added

You named three (security vulnerabilities, code quality, best practices). To make this a *full* analysis I also assessed the dimensions below — **flagged here as additions per your request**:

- **Privacy / GDPR & data lifecycle** — location-history accumulation, no erasure path, retention gaps (§5.3/5.8). Notable because it contradicts the team's own documented rules.
- **Availability / DoS** — JWKS amplification, image-decode memory amplification, rate-limiter fail-open, unbounded admin aggregates (§5.1/5.4/5.8).
- **Business-logic & economic abuse** — gamification farming/self-award, trust-point manipulation, claim-griefing, weaponized reports (§5.5/5.3/5.6).
- **Separation of duties / insider risk** — moderator self-review; single shared secret = platform-wide impersonation blast radius (§5.6/§4).
- **Reliability / distributed-systems correctness** — inbox dedup atomicity, outbox ordering, dedup-TTL replay, exactly-once-vs-at-least-once (§5.9).
- **Supply chain & dependency lifecycle** — EOL Spring Boot line, gated-off SAST/signing, image scanning coverage, action pinning (§5.12/5.13).
- **Observability as an attack/PII surface** — 100% trace sampling, log-token flags, correlation-id injection into logs & traces (§5.9/5.12).
- **Content safety** — ClamAV covers malware, **not** illegal/abusive content (CSAM) — a launch blocker for a UGC photo app (§10).
- **Feature-flag & release hygiene** — Smart Return gating is clean; but push/telemetry are no-ops and the icon bug ships to prod.
- **Documentation drift** — stale javadocs, mobile-not-implemented claim, TTL inconsistency (§6).

---

## 8. What must be verified at runtime (cannot be confirmed statically)

These change severity dramatically depending on the answer — **please test before treating as safe or as confirmed**:

1. **Gateway path confusion (§5.1, HIGH).** Through the gateway, run:
   - `GET /api/v1/analytics/users/../overview` — expect the request to be rejected (400) or to still hit analytics-service's ADMIN check; if a plain USER gets `200` with admin data, the edge authz is bypassable.
   - `GET /api/v1/parking/../../../internal/…` — expect 404/400; if it reaches a downstream `/internal/*` handler, path canonicalization is missing. Then add a `..`/`//`/backslash-rejecting global filter regardless.
2. **Kafka produce ACLs / mTLS (§4 S2).** Confirm only the owning service can produce to each `parkio.*` topic. If not, the gamification/moderation/account-lock forgeries are live.
3. **Network segmentation (§4 S1).** Confirm backend service ports and the Kafka/Redis/DB planes are unreachable except via the gateway in every non-dev environment (hosted-beta compose does this; verify k8s/prod).
4. **Mobile email deep-link channel (§5.11).** Do reset/verify emails link via `parkio://` (interceptable) or https (safe but makes the mobile screens dead-ends)?
5. **`shouldNotFilter` raw-URI matching (§5.3).** Test `/actuator/..;/internal/…` against the running Tomcat (likely rejected).
6. **PostgreSQL JDBC BOM version (§5.13)** vs CVE-2026-54291.

---

## 9. Notable good practices (so you know what not to break)

The gateway trust boundary, JWT/JWKS handling, refresh-token family rotation + reuse detection, session-epoch revocation, media scan-before-store/EXIF-strip pipeline, non-enumerable 404 IDOR posture, points idempotency ledger, outbox/DLQ hygiene, fail-closed secret handling, hosted-beta gateway-only ingress (smoke-tested), container hardening, encrypted-backup restore-drill, and the mobile secure-store/single-flight-refresh design are all **genuinely well-executed** and above the bar for a beta. Preserve these patterns; several findings above are precisely about extending them consistently (e.g., copy auth's `occurredAt` guard to user-service trust; copy parking's `ON CONFLICT` inbox claim everywhere).

---

## 10. Product & feature opportunities

### 10.1 Low-hanging fruit (near-term, high ROI)

1. **Fix the icon/CSP bug (§5.10).** Right now production ships visibly broken UI (menu items reading `home`, `search`, `menu`). Self-host the fonts — a few hours, outsized perceived-quality gain before any beta invite goes out.
2. **Wire push delivery and crash/analytics telemetry.** Push is a `Noop` sender and mobile crash/analytics are in-memory stubs — so Smart Return's payoff notification never arrives and you're blind to prod crashes. These are the two reasons a beta looks "dead" to users and to you. Wire FCM + Sentry/Crashlytics/PostHog before inviting testers.
3. **Real-time availability confidence.** The "Community signal" placeholder → a live freshness/confidence score (decay on spot age, corroboration count). This is the core trust signal that makes the map worth opening.
4. **Fix the `claimSpot` UX/integrity (§5.3)** — an expiring, reversible, corroborated claim instead of instant-terminal. Directly improves data quality, which is the product.
5. **Media thumbnails + WebP.** Add a derivative pipeline (currently every view mints a full-size URL) and either support WebP or stop advertising it (it always 422s today).
6. **Mobile offline persistence.** `persistQueryClient` so cached spots survive a cold start — parking garages have terrible signal; this is a maps-app essential.
7. **Post-login deep-link redirect (§5.10)** — honor `state.from` so a shared spot link lands where intended after login. Small fix, real growth impact on shared links.
8. **Ship dark mode** — already scaffolded, "no dark screens exist yet"; finishing it is cheap and expected on mobile.
9. **i18n / Turkish localization** — the smoke tests and home coords are İzmir; if TR is the launch market, localize now before strings proliferate.

### 10.2 Directional bets (differentiation, growth, retention)

1. **Make Smart Return the flagship.** It's the one thing a website can't do well: **opt-in background geofence** ("we'll ping you when a spot opens near home right before you leave") + **predictive availability** (ML on historical turnover per block/time) + "notify me when I'm 5 min out." Gate it behind explicit permission and market the privacy-by-design (compute the geofence server-side, never store movement) — turning §5.3's liability into a headline trust feature.
2. **Turn "trust points" into a real reputation currency.** Redesign per §5.5 (decay, penalty floors, velocity caps, anti-collusion), then **gate perks by trust tier** (early access to high-demand spots, higher point multipliers, verified-contributor badge). This drives quality contributions *and* retention, and it's the growth loop the gamification service was built for.
3. **Anti-abuse as a moat.** Crowdsourced parking lives or dies on data quality. Distinct-account/device heuristics + signed events (§4/§5.5) + corroboration thresholds (§5.6) protect the map from farming and griefing — invisible when it works, fatal when it doesn't.
4. **Community & referral loops** — neighborhood leaderboards (with opaque handles, not raw UUIDs), verified-contributor status, referral rewards in points. Cheap virality that compounds contribution density.
5. **Monetization paths** — premium Smart Return (multiple home/work areas, longer horizon, priority alerts); B2B/fleet & delivery-driver parking; sponsored/partner lots; anonymized turnover insights for municipalities/BIDs (a genuinely valuable dataset you're already accumulating).
6. **Content-safety before public launch** — add a managed content-safety/CSAM classifier (ClamAV is malware-only). This is a hard requirement for any public photo-UGC product, not optional.
7. **Verification timeline & spot history** — the backend has verification/claim events; surfacing "last verified 4 min ago by 3 people" is the credibility layer that makes users act on a listing.

---

## 11. Prioritized remediation roadmap

**P0 — before any external beta invite**
- Fix the icon/CSP bug (§5.10). *(functional)*
- Run the §8 path-confusion tests; add an edge path-canonicalization/reject filter (§5.1).
- Confirm Kafka produce ACLs + network segmentation (§4) — an infra sign-off that neutralizes the largest class of internal findings.
- Make the inbox dedup atomic (`ON CONFLICT DO NOTHING` before side-effects) in notification/moderation/ai-validation (§5.9).
- Add Redis authentication (§5.12).
- Wire push delivery + crash telemetry (product-critical, §10.1).

**P1 — before production**
- Per-service internal auth (mTLS/SPIFFE) or distinct `/internal` credential; sign injected identity (§4 S1).
- Trust-point redesign: permanent ledger + decay/floor/velocity caps + `owner!=actor` (§5.5).
- Smart Return privacy-by-design: server-side geofence + retention/purge for search/view logs + coordinate truncation (§5.3); GDPR erasure path (§5.8).
- Move email/push off the transaction onto the outbox (§5.2).
- Rate-limit: pre-auth per-IP filter + fail-closed auth tier + JWKS negative cache (§5.1).
- Close enumeration (register response, public-profile status, login/forgot timing) (§5.2/5.8).
- Moderation separation-of-duties + report corroboration (§5.6).
- Device-token global ownership (§5.8); AI-validation fail-closed to `NEEDS_REVIEW` (§5.7).
- Mobile: bundle map JS / add SRI; staff-route role guard; Universal/App Links for auth deep links (§5.11).
- `PARKIO_OPENAPI_ENABLED=false` in prod; actuator on a separate management port (all services).
- Content-safety provider (§10.2).

**P2 — hardening & hygiene**
- `X-Correlation-Id` validation everywhere (§5.9); outbox ordering guard for user-service trust (§5.9).
- Enable CodeQL + cosign signing/provenance on the org move (§5.12); digest-pin base images; scan infra images; Trivy block on HIGH.
- CI script-injection cleanup via `env:` (§5.12).
- DB indexes on FK children; BCrypt cost 12; `Locale.ROOT` email normalization; retention for token/search/view/analytics tables; media checksum scoped per-owner + concurrency-bounded decode (§5.2/5.4/5.8).
- Documentation cleanup (stale javadocs, mobile-client claim, TTL inconsistency) (§6).

---

*Prepared via 9 parallel domain-scoped source audits with independent lead verification of the auth/gateway trust boundary, JWT/JWKS handling, container port model, and actuator exposure. Items marked VERIFY-RUNTIME/CONTINGENT require the runtime checks in §8 to finalize severity.*
