# Parkio — Complete Technical, Security and Production Audit (Sprint A1)

**Date:** 2026-07-12
**Commit audited:** `f5efa0ddae1aadbbe3a8d9209f3587c62a392d7a` (branch `master`, after tag `v1.0.0-rc1`), plus in-flight uncommitted landing/waitlist changes present in the working tree (audited as-is, attributed to the maintainer, preserved untouched).
**Method:** independent, repository-evidence-based review by code/config/migration/test inspection plus fresh command execution. No application behavior, schema, Docker, or CI file was modified. Documentation claims were treated as hypotheses and checked against code. Docker-dependent verification was **BLOCKED** in this environment (daemon unreachable) and is recorded as such — never converted to PASS.

Companion documents: [Findings](PARKIO-FINDINGS.md) · [Test evidence](PARKIO-TEST-EVIDENCE.md) · [Architecture risk map](PARKIO-ARCHITECTURE-RISK-MAP.md) · [GO/NO-GO](PARKIO-PRODUCTION-GO-NO-GO.md) · [Roadmap](PARKIO-REMEDIATION-ROADMAP.md) · [findings.json](findings.json) / [findings.csv](findings.csv)

---

## 0. Executive summary

Parkio is a Java 21 / Spring Boot 3.5 microservices monorepo (10 services + reactive gateway, db-per-service on Postgres/PostGIS, Kafka with transactional outbox/inbox and DLTs, Redis, MinIO+ClamAV media pipeline) with a React 19/Vite web SPA, an Expo SDK 56 mobile app, six shared TypeScript packages, a full Compose-based hosted-beta topology with Caddy TLS and a provisioned Prometheus/Grafana/Loki/Tempo/Alertmanager stack, 15 GitHub Actions workflows, and an unusually complete documentation set.

**The headline conclusion: the repository is what it says it is.** This audit set out to find gaps between claims and code and found remarkably few — the one stale claim discovered (DOC-001) understates the project. The application layer is genuinely hosted-beta-shaped: security architecture is implemented, not aspirational (verified point by point, finding SEC-004); 712 backend unit tests re-executed green from a cold cache during this audit; the performance baseline in the repo is a real measurement that candidly flags the untested write path.

**The genuinely open risks cluster in four places:**
1. **Privacy operations** — no account deletion/erasure anywhere (PRIV-001), indefinite per-user location logs (PRIV-002), placeholder legal pages over real PII collection (PRIV-003). This is the largest gap between the "privacy-conscious" positioning and the implementation.
2. **Operations-on-the-day** — alerts route to a null receiver until an env var is set, no uptime monitoring, on-call undefined (OPS-001); live-host deploy/restore evidence does not exist yet (by the repo's own admission).
3. **Working-tree hygiene** — the uncommitted landing/waitlist rework failed its own test deterministically at the audit snapshot (fixed by later same-day maintainer changes, still uncommitted), and the Hostinger deploy zip predates the fix (WEB-001, partially mitigated). The tree kept evolving during audit day; master is not release-clean.
4. **Stage-mismatch costs** — a 10-service topology with ~7.7 GiB idle footprint and 9 databases for a zero-user product (ARCH-001), with transport plumbing copy-pasted nine times (ARCH-002). Both are conscious, documented trade-offs; they are still costs.

**Zero CRITICAL findings.** 3 HIGH (PRIV-001, INFRA-001, OSS-001 — the latter two documented in-repo as accepted/known), 13 MEDIUM, 11 LOW, 2 INFORMATIONAL (verified strengths). No committed secrets (the one real private key found lives in a gitignored, untracked local `.env`; the repo boundary is guarded by gitleaks config, push-protection-safe fixtures, and a preflight that rejects `CHANGE_ME`).

---

## Phase 1 — Repository inventory & source-of-truth

Full inventory in the [architecture risk map §1](PARKIO-ARCHITECTURE-RISK-MAP.md). Summary: 12 Gradle modules (10 services, platform lib, dlt-redrive tool), 2 frontend apps + 6 packages in a pnpm workspace, 22 operator shell scripts (all `bash -n` clean), 15 CI workflows, 124 Flyway migrations across 10 databases (incl. gateway waitlist), 7 Grafana dashboards, 42 Prometheus alert rules, k6 harness with a recorded benchmark report, and ~40 documentation files spanning architecture/ops/beta/release/certification/startup/brand/design.

Contradiction scan (code vs README vs certifications vs release docs vs startup claims): **one** contradiction found, in the self-critical direction (DOC-001: KNOWN-ISSUES says restore isn't CI-automated; a weekly CI restore drill exists). Startup docs explicitly deny having users/revenue/funding. Certification scores (hosted beta 84/100 GO, production 42/100 NO GO) are consistent with this audit's independent scoring below.

Repository cleanliness note: ~20 stray build/audit log files (`gradle-build*.log`, `audit-*.log`, `hs_err_pid*.log`) sit at the repo root untracked/committed—cosmetic, grouped under code-quality, not itemized.

## Phase 2 — Architecture

See [architecture risk map](PARKIO-ARCHITECTURE-RISK-MAP.md). Key judgments: real boundaries (not a distributed monolith); textbook event reliability design (outbox/inbox/DLT/redrive); deliberate fail-closed posture at the gateway; the service count exceeds what the stage needs (ARCH-001) and plumbing duplication is the price of the "no shared domain" rule taken slightly too far (ARCH-002). Single-host runtime is resource-governed (mem/cpu/pids limits, heap pinning, readiness-ordered startup, graceful shutdown) — verified in the hosted-beta overlay, matching runtime-sizing.md.

## Phase 3 — Backend services

Sampled deeply: gateway, auth, parking, media, moderation, analytics; structurally reviewed: user, gamification, notification, ai-validation. Consistent hexagonal layering (application ports / domain / infrastructure adapters / presentation DTOs), no entity leakage in sampled controllers, Flyway-owned schemas with `ddl-auto: validate`, `open-in-view: false`, optimistic locking (`version` columns), pagination on user-facing lists (exception: BE-002 analytics snapshots), exactly **one** TODO in all main code (BE-001, documented as accepted risk AR-01 — matches docs). No silent exception swallowing found in sampled paths; error contracts unified via shared `ApiError`.

Per-service scores (0–100: correctness / security / maintainability / testability / ops-readiness):

| Service | Corr. | Sec. | Maint. | Test. | Ops | Hosted-beta ready | Prod ready |
|---------|------|------|--------|-------|-----|-------------------|------------|
| gateway | 88 | 90 | 85 | 85 | 85 | Yes | With SEC-001 posture decision |
| auth | 90 | 92 | 85 | 85 | 85 | Yes | Yes (app layer) |
| parking | 88 | 85 | 85 | 85 | 82 | Yes | Yes (app layer) |
| media | 87 | 90 | 82 | 80 | 80 | Yes | Load-test first (PERF-001) |
| moderation | 85 | 88 | 82 | 78 | 80 | Yes | Yes (app layer) |
| user | 85 | 85 | 82 | 78 | 80 | Yes | Yes |
| notification | 82 | 85 | 80 | 80 | 78 | Yes (fan-out gap) | Feature-gap BE-001 |
| gamification | 82 | 85 | 80 | 72 | 78 | Yes | Yes |
| ai-validation | 80 | 85 | 80 | 72 | 78 | Yes | Yes |
| analytics | 80 | 85 | 78 | 65 | 75 | Yes | BE-002 first |

(Scores are code-inspection-based; runtime integration behavior not independently re-verified this audit — TEST-001.)

## Phase 4 — Authentication & authorization

Verified in code: registration → BCrypt hash, email verification tokens (SecureRandom), resend limiter; login → Redis-backed per-account lockout with escalating durations; access tokens RS256 (JWKS published, kid-based rotation support with verification-only old keys), issuer+audience+expiry enforced with 30s skew; refresh tokens opaque high-entropy, stored as SHA-256, rotated per use, family-scoped revocation with **reuse detection that also bumps the session epoch**; logout vs logout-all correctly scoped; gateway re-checks live account status AND session epoch per request (30s cache, fail-closed 503 on dependency loss); web transport = memory-only access token + HttpOnly refresh cookie; mobile = SecureStore.

Authorization matrix (edge, verified in `RouteAuthorizationRules` + `PublicEndpoints`, first-match-wins):

| Surface | Anonymous | USER | MODERATOR | ADMIN |
|---------|-----------|------|-----------|-------|
| /auth bootstrap (register/login/refresh/logout/verify/reset/JWKS), POST /waitlist, /actuator/health,info | ✅ | ✅ | ✅ | ✅ |
| users, parking, geocoding, media, gamification, notifications | ❌ 401 | ✅ (+ ownership in-service) | ✅ | ✅ |
| POST moderation/reports, GET reports/me, POST appeals | ❌ | ✅ | ✅ | ✅ |
| moderation/** (queue, cases, assignment) | ❌ | ❌ 403 | ✅ | ✅ |
| moderation account actions (suspend/restore/trust/score, appeal resolution) | ❌ | ❌ | ❌ (in-service ADMIN check, verified) | ✅ |
| GET analytics/users/** | ❌ | ✅ own only (in-service check verified) | ✅ own | ✅ |
| analytics/** (platform), GET waitlist/export | ❌ | ❌ | ❌ | ✅ |
| ai-validations/** | ❌ | ❌ | ✅ | ✅ |

Defense-in-depth confirmed: the edge strips client-supplied identity headers before stamping verified ones; downstream services independently require the internal secret (constant-time, rotation-window list). Weaknesses: no MFA (acceptable for beta), account enumeration on register/resend mitigated by rate limits (not fully eliminated — standard trade-off), SEC-001/SEC-002 Redis-outage postures need runtime verification.

## Phase 5 — Application security (OWASP-oriented)

- **Injection:** JPA/parameterized JDBC throughout sampled code; no string-concatenated SQL found; no template engines server-side; no command execution.
- **XSS:** React escaping; no `dangerouslySetInnerHTML` in sampled pages; strict CSP at Caddy (`script-src 'self'`; no unsafe-inline for scripts).
- **CSRF:** state-changing APIs are bearer-token (not cookie) authenticated; the only auth cookie is the HttpOnly refresh token scoped to refresh/logout — SameSite/secure flags set in `RefreshCookieProperties` (web transport); CORS deny-by-default allow-list with wildcard+credentials fail-fast.
- **SSRF/path traversal/upload:** media pipeline sniffs real content type, caps size/pixels, re-encodes (EXIF orientation read, metadata not preserved through re-encode), ClamAV-scans, stores under generated keys in a private bucket, serves via time-limited presigned URLs on a dedicated domain. Zip/image-bomb mitigations = pixel caps + normalization.
- **Secrets:** none committed (verified: only gitignored local `.env` and clearly-fake fixtures); fail-closed startup on missing JWT key/gateway secret; preflight rejects placeholders. `.gitleaks.toml` + security-ci gitleaks job + push-protection-safe fixtures.
- **Actuator/debug:** exposure limited to `health,info,prometheus` (all services, verified in gateway + sampled services); springdoc present for local API docs — bypasses gateway auth filter (`shouldNotFilter` allows docs paths) but the services are not publicly routable in hosted-beta (`ports: !reset []`), leaving edge exposure nil; flag for awareness, not a finding.
- **Headers:** full CSP/HSTS/XCTO/Referrer-Policy/Permissions-Policy at Caddy; equivalent nginx headers tested in `securityHeaders.test.ts`.
- **Rate limiting/abuse:** per-tier Redis token buckets on every route keyed by verified user id (else spoof-resistant client IP with trusted-proxy allow-list, empty default); waitlist has independent dual limits. Gap: SEC-001 fail-open question.
Residual items: SUP-001/002 (pinning), SEC-003 (local key hygiene), PRIV-004 (secret reuse default).

## Phase 6 — Database & data integrity

124 migrations, disciplined naming, per-service ownership. Sampled schemas show: FKs within service scope, unique indexes where dedupe matters (waitlist email_hash, inbox event ids, outbox recovery), CHECK constraints on enum-ish columns (waitlist role/source), TIMESTAMPTZ everywhere sampled, GIST index on PostGIS geography maintained by trigger (keeps JPA entity H2-portable — a smart, documented trade-off), status history tables, soft-delete on media with audit retention. Gaps: DATA-001 (no lat/lng range CHECKs), PRIV-002 (log tables lack retention), cross-service references are intentionally unconstrained UUIDs (documented; orphan risk handled at read time). Rolling-migration compatibility: additive patterns observed in V7–V19 sequences (column adds, nullable-first). Backup scripts per-database plus MinIO; restore scripts + weekly CI drill; PITR absent (INFRA-001).

## Phase 7 — Web frontend

Verified: central env validation (production-like builds refuse localhost fallbacks), route guards + role guards with tests, global error boundary with one-shot chunk-reload recovery, provider-agnostic error reporting with token/password redaction, PWA manifest + app-shell SW that **never** caches API/auth/token-bearing requests (tested), offline banner, real 404, SEO/OG/robots/sitemap with tests, self-hosted fonts, jest-axe accessibility tests, code-split landing (`main.tsx` bootstraps landing without app/auth code — protected-asset isolation verified in the bundle split design and by the standalone legal-page bootstrap in the uncommitted work). Memory-only access token; refresh via HttpOnly cookie; MapLibre with keyed tiles via env. Failing point: WEB-001 (uncommitted work, deterministic red test). Scores: quality 84, security 86, accessibility 78 (axe tests exist; no full audit run), performance 78 (code-splitting yes; no Lighthouse evidence), maintainability 84, production-readiness 80 (landing-path caveat WEB-001).

## Phase 8 — Mobile

Expo SDK 56 / RN with typed feature modules. Verified: SecureStore-backed token storage with in-memory bridge (never AsyncStorage for secrets — grep-confirmed), `allowBackup=false` via config plugin, refresh flow + account-switch cache invalidation tests, RBAC parity tests, deep-link guards, camera/location/notification permission flows via plugins (no raw permission over-grants in app.json), EAS profiles with env-pinned API URLs (no hardcoded prod URLs in source), release verification script (`verify:artifact`), SYSTEM_ALERT_WINDOW blocked. Gaps: MOB-001 (no device smoke for RC1; store artwork/owner blockers), TEST-002 (one flaky test), Expo Go push limitations documented. iOS is configured but untested on hardware per docs. Scores: quality 80, security 85, UX 72 (device-runtime unproven).

## Phase 9 — Infrastructure & containers

Verified: multi-stage Dockerfiles, non-root uid 10001, healthchecks, OCI provenance labels, reproducible jars; hosted-beta overlay closes all published ports except Caddy 80/443 (`!reset []` pattern), per-container mem/cpu/pids limits, JVM heap pinned 65% + ExitOnOOM, log rotation, graceful shutdown, readiness-ordered startup; Caddy ACME TLS with strict headers; observability containers provisioned. Gaps: SUP-002 (no digest pinning), no read-only rootfs/cap_drop (LOW, grouped), INFRA-002 (IaC stub), OPS-001 (null alert receiver). Resource requirements (adopting the repo's **measured** numbers, p221): minimum hosted beta 16 GB / 8 vCPU with observability trimmed; recommended 24 GB / 8 vCPU; small public beta undetermined until write-path load test (PERF-001); disk driven by Postgres+MinIO+Loki (168h retention) — monitor, ~50–100 GB start. Growth risks: media uploads (MinIO), Loki/Tempo at 100% trace sampling (tune down for beta), PRIV-002 log tables.

## Phase 10 — AWS readiness

Architecture maps cleanly; no code changes required for a lift (env-var config, S3-compatible media, standard Postgres/Kafka/Redis). Realistic topologies:
- **Cheapest credible (hosted beta):** single EC2 (m7g/r7g 8 vCPU/32 GB ≈ $200–260/mo on-demand, less reserved) running the existing compose stack + EBS snapshots + S3 for backups + Route 53 + SES for email. ARM64 works for JVM/nginx/Caddy; verify ClamAV/MinIO images for arm64 or pick x86.
- **Not yet:** ECS Fargate for 11 always-on JVMs (~$300+ compute alone), MSK (≥ ~$150/mo minimum), RDS ×9 (use 1–2 instances with 9 logical DBs as production-readiness.md already recommends), NAT Gateway (avoid: public subnets + SGs, or one NAT instance), CloudFront optional later.
- **Required before AWS deploy:** minimal IaC or documented build (INFRA-002), Secrets Manager adoption path (PP-03), budget alarms + Cost Anomaly Detection on day one (credit-burn guard), SES sandbox exit for auth emails.
Decision recorded as **NOT VERIFIED** for deployment, **GO** for Activate technical proof (see GO/NO-GO doc). AWS readiness score: 65.

## Phase 11 — CI/CD & supply chain

15 workflows: backend/frontend/mobile CI, backend-integration (Testcontainers with fail-fast Docker probe), security-ci (gitleaks pinned v8.28.0, CodeQL, Trivy with SARIF upload), supply-chain (SBOMs), release (draft-gated `PUBLISH_IMAGES`, attestations/id-token perms scoped per job), hosted-beta-deploy, runtime/chaos/observability validation, weekly backup-restore drill, performance smoke. Least-privilege `permissions:` blocks throughout (mostly `contents: read`). Gradle wrapper validation present; pnpm frozen-lockfile in builds; Dependabot configured with documented major-version ignores. Weaknesses: SUP-001 (tag-pinned actions — the main gap), SUP-002, no gradle dependency-verification metadata (LOW, grouped), fork-PR posture default (no `pull_request_target` misuse found). Score: CI/CD 82, supply chain 74.

## Phase 12 — Dependencies & licensing

Java: Spring Boot 3.5.x line, Spring Cloud BOM-managed, no dynamic versions observed in sampled build files. JS: workspace-pinned pnpm 9.15, React 19.2.3, Vite 7, Expo 56; `pnpm audit --prod` = 1 moderate (OSS-002, build-time transitive). License: OSS-001 — deliberate "license pending"; public release **not** legally ready; NOTICE decision deferred until selection (correctly documented in-repo). No license-incompatibility scan possible until a license is chosen.

## Phase 13 — Tests & QA

See [test evidence](PARKIO-TEST-EVIDENCE.md) for the coverage matrix, executed commands and claim-checks. Net: unit layers are strong and *fresh-green* (712 backend re-executed; 465 frontend counted across three packages, 2 non-green items both characterized); integration/E2E layers exist and are CI-gated but were BLOCKED from local re-verification; the highest-risk missing tests are behavioral (Redis chaos, write-path load, erasure, device smoke) rather than unit gaps. No percentage coverage is claimed anywhere (no coverage tooling configured — recorded as a gap, LOW, grouped). Testing score: 78.

## Phase 14 — Performance

Confirmed defects: none. Measured evidence: read path 1,182 req/s @ p95 6 ms, 0 errors, not saturated, rate-limiter-bound (p221 — a genuinely good result honestly framed). Likely risks: ClamAV single-container upload ceiling, Kafka single broker, 100% trace sampling cost, PRIV-002/BE-002 growth. Requires load test: entire write path (PERF-001) — proposed plan: k6 scenario of authenticated upload(2 MB image)+spot-create+verify at 5/10/20 concurrent, disposable stack, measure ClamAV queue depth + outbox relay lag + MinIO latency; run before any public cohort. Score: 66 (evidence-limited, not defect-driven).

## Phase 15 — Observability & operations

Structured logging with correlation IDs (filter per service) + OTel trace/span IDs in patterns; PII in logs sampled clean (emails/tokens not logged in sampled paths; userIds do appear — acceptable, note for log-retention policy); metrics tagged per service with a documented catalogue; 7 dashboards; 42 alert rules incl. DLQ/outbox lag; blackbox exporter configured. Operator diagnosability table (login spike → auth metrics+lockout counters: yes; DB/Kafka/MinIO outage → health+alerts: yes, **if** OPS-001 wired; cert renewal failure → no synthetic check: **gap**; backup failure → drill exists in CI, live-host: gap; unauthorized admin activity → audit trails in moderation actions: partial). RPO today = backup cadence (daily scripted); RTO = manual restore (unmeasured on live host). Scores: observability 80, operations 62, backup/restore 72.

## Phase 16 — Privacy & data handling

Personal data inventory: email + BCrypt hash + roles + status (auth); profile (user); precise spot lat/lng + address text + photos (parking/media, user-generated); **searcher lat/lng per search + per-spot view logs (PRIV-002)**; refresh-token hashes; device push tokens; moderation reports/sanctions; waitlist email + HMAC(ip/ua) + consent timestamp; logs with userIds (Loki 168h). Third-party transfers: Resend (email), Expo push, MapTiler tiles (client-side). Strengths: raw IP never persisted (HMAC only), token hashing, private media bucket, minimization mindset in waitlist design. Gaps: **no erasure (PRIV-001), no behavioral-log retention (PRIV-002), placeholder legal pages (PRIV-003)**, no data-export endpoint (GDPR portability — LOW at this stage, noted), analytics consent model not yet needed (no third-party analytics found in web/mobile — verified absence). Technical privacy readiness: **55** — the architecture minimizes well, the lifecycle (retention/erasure) is missing. This is a technical assessment, not legal advice.

## Phase 17 — Product & UX correctness

Cross-platform parity matrix (verified via routes/screens/tests, not device runtime):

| Flow | Backend | Web | Mobile | Tests | Docs | Beta-ready |
|------|---------|-----|--------|-------|------|------------|
| Report spot (photo, context, legal status) | ✅ | ✅ | ✅ | ✅ unit both | ✅ | ✅ |
| Discover nearby (PostGIS + map) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Verify / claim / expiry rules (owner-cannot-verify/claim enforced) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Reports & moderation queue / appeals | ✅ | ✅ | partial (user-facing only) | ✅ | ✅ | ✅ |
| Gamification / leaderboard / trust | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Smart Return | ✅ events | ✅ map mode | ✅ feature module | ✅ (one flaky, TEST-002) | ✅ beta-flagged | ✅ behind flag |
| Notifications inbox + push | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ (Expo Go caveat) |
| Nearby-spot push fan-out | ❌ TODO | — | — | — | documented AR-01 | feature gap |
| Account deletion | ❌ | ❌ | ❌ | ❌ | ❌ | **gap (PRIV-001)** |
| Waitlist | ✅ | ⚠️ WEB-001 | n/a | red test | ✅ | conditional |

No undocumented features found; no state-machine disagreements found in sampled status flows (spot status transitions have history tables + tests). Abuse incentives: verification farming is bounded by one-verification-per-user-per-spot constraint (unique index) and trust scoring; owner self-verification blocked.

## Phase 18 — Code quality

Strengths: uniform layering, small focused classes, exceptional comment discipline (comments explain *why* and cite ai-context rules), one TODO in main code, consistent error contracts, no dead-code clusters found in sampling. Debt clusters (grouped, not itemized): (1) ARCH-002 plumbing duplication ×9; (2) root-level stray log files and `tmp/`, `deploy-artifacts/`, `backup-artifacts/` working directories cluttering the repo; (3) frontend test-runtime fragility on slow filesystems (vitest environment overhead, TEST-002/WEB-001 timeout coupling); (4) minor: 5 lint warnings, analytics unbounded reads. Maintainability score: 81.

## Phase 19 — Developer experience

Verified on a fresh-ish WSL environment: README quick-start commands are accurate (build/test/typecheck/lint all worked exactly as documented, including the WSL warning about workspace test orchestration — which this audit reproduced: package-level runs behaved better); prerequisites accurate; gradle convention plugin makes per-service work uniform; docker/README documents ports/secrets/health. Frictions: /mnt/c I/O makes everything 5–20× slower (documented in repo memory but not README); Docker Desktop required for anything integration-shaped; DX-001 (machine npmrc) predates this audit; no devcontainer. DX score: 72.

## Phase 20 — Documentation truth

Every major maturity claim mapped to evidence: "hosted-beta certified" → substantiated with the explicit caveat (also verified) that **no live deployment evidence exists**; "public production NO GO" → confirmed accurate; "privacy-conscious" → **partially supported** (design yes, lifecycle no — PRIV-001/002 temper the claim); "fully tested" → not claimed anywhere (good); benchmark claims → match raw report; FFINAL "529 tests" → order-of-magnitude confirmed (465 recounted in 3 packages). One stale row (DOC-001). Documentation accuracy score: 88 — the best area of the repository relative to industry norms.

## Phase 21 — Verification execution

Recorded in full in [PARKIO-TEST-EVIDENCE.md](PARKIO-TEST-EVIDENCE.md): 15 command groups executed (PASS: backend tests 712/712, typecheck, lint, api-client 37/37, web build, 22 script syntax checks, pnpm audit; FAIL: web suite 1 deterministic in uncommitted work, mobile 1 flake), 9 command groups BLOCKED on the missing Docker daemon with exact rerun instructions.

## Phase 22 — Scores

| Area | Score | Basis / deductions (finding IDs) |
|------|-------|----------------------------------|
| Architecture | 78 | Clean boundaries, honest sizing; −ARCH-001 stage mismatch, ARCH-002 duplication |
| Backend correctness | 85 | 712 fresh-green tests, disciplined domain rules; −runtime integration unverified today (TEST-001) |
| Backend maintainability | 80 | Uniform layering; −ARCH-002 |
| API design | 82 | Consistent contracts/DTOs/pagination; −BE-002 |
| Authentication | 88 | Full verified chain incl. reuse detection + epoch; −MFA absent, enumeration residual |
| Authorization | 85 | Edge matrix + in-service ownership verified; −no automated authz matrix test at edge |
| Application security | 82 | SEC-004 strengths; −SEC-001/002 unverified postures, PRIV-004 |
| Data integrity | 84 | Outbox/inbox/constraints/locking; −DATA-001, cross-service refs unconstrained (by design) |
| Database design | 83 | PostGIS trigger pattern, migrations quality; −PRIV-002 growth |
| Kafka/event reliability | 80 | Design textbook; runtime under broker loss unverified locally (TEST-001) |
| Cache correctness | 76 | TTL'd fail-closed caches; −SEC-001 fail-open question |
| Web quality | 84 | Guards/boundaries/PWA/SEO tested; −WEB-001 |
| Web security | 86 | Token model + CSP + SW policy verified |
| Web accessibility | 78 | jest-axe + focus/reduced-motion work present; no full audit evidence |
| Web performance | 78 | Code-splitting, self-hosted fonts; no field metrics |
| Mobile quality | 80 | Feature completeness + tests; −TEST-002, device proof gap |
| Mobile security | 85 | SecureStore/allowBackup/no-secrets-in-bundle verified |
| Mobile UX | 72 | −MOB-001 device runtime unproven |
| Infrastructure | 78 | Hardened compose; −INFRA-002 IaC stub, no read-only rootfs |
| Container security | 80 | Non-root, limits, closed ports; −SUP-002 |
| CI/CD | 82 | 15 gates incl. integration w/ fail-fast; −SUP-001 |
| Supply-chain security | 74 | SBOM/Trivy/gitleaks/attestations; −SUP-001/002, DX-001 workstation |
| Testing | 78 | Strong units, CI-gated integration; −blocked re-verification, 2 red items, no coverage tooling |
| Performance/scalability | 66 | Real read benchmark; −write path unknown (PERF-001) |
| Observability | 80 | Full stack + 42 alerts + dashboards; −no synthetic checks |
| Operations | 62 | Runbooks excellent; −OPS-001 null receiver, no live deploy/restore evidence, no on-call |
| Backup/restore | 72 | Scripts + weekly CI drill; −no PITR, no live-host drill (INFRA-001) |
| Privacy | 55 | Minimization design good; −PRIV-001/002/003 lifecycle absent |
| Documentation accuracy | 88 | One stale row (DOC-001), self-critical direction |
| Developer experience | 72 | Accurate docs; −WSL/Docker friction, DX-001 |
| Hosted-beta readiness | 76 | App layer ready; ops-day items open (OPS-001, deploy evidence) |
| Public-production readiness | 40 | PP-01..06 + PRIV cluster all open — matches repo's own 42 |
| AWS readiness | 65 | Clean mapping, no IaC, cost plan needed |
| **Overall technical maturity** | **76** | Application layer ~85; operations/privacy lifecycle pull the composite down |

Scores >90 were withheld deliberately: every area retains at least one unverified runtime dimension in this audit environment.

## Phase 23–24

See [GO/NO-GO](PARKIO-PRODUCTION-GO-NO-GO.md) and [Roadmap](PARKIO-REMEDIATION-ROADMAP.md).
