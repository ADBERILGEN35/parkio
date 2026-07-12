# Parkio Audit Findings — Sprint A1

**Date:** 2026-07-12 · **Commit:** `f5efa0ddae1a` · **Findings:** 29 (CRITICAL 0, HIGH 3, MEDIUM 13, LOW 11, INFO 2)

Machine-readable versions: [`findings.json`](findings.json), [`findings.csv`](findings.csv). IDs and severities are identical across all three files.

> Note: the working tree continued to evolve on audit day after evidence collection (additional maintainer changes to scripts, compose, EAS and waitlist files). Findings were evaluated at the snapshot; WEB-001 was re-checked against the later tree and downgraded to Partially mitigated.

## Index

| ID | Severity | Confidence | Status | Title |
|----|----------|------------|--------|-------|
| INFRA-001 | HIGH | Confirmed | Accepted risk | Single-node data plane: no HA Postgres/PITR, single Kafka broker, single MinIO |
| OSS-001 | HIGH | Confirmed | Accepted risk | No open-source license selected; repository is source-visible only |
| PRIV-001 | HIGH | Confirmed | Open | No account deletion or data-erasure capability anywhere in the stack |
| ARCH-001 | MEDIUM | Confirmed | Accepted risk | Ten-service microservice topology is heavy for a pre-user product |
| ARCH-002 | MEDIUM | Confirmed | Accepted risk | Infrastructure plumbing is copy-pasted across all services by design; drift risk |
| DX-001 | MEDIUM | Confirmed | Open | Developer machine npm configuration disables TLS to the package registry |
| MOB-001 | MEDIUM | Confirmed | Open | Mobile device runtime smoke never executed for the release line; store-readiness blockers open |
| OPS-001 | MEDIUM | Confirmed | Open | Alert delivery, uptime monitoring and on-call are operator-TODO; alerts route to null by default |
| PERF-001 | MEDIUM | Confirmed | Open | Write-path performance is unmeasured; read path has real evidence |
| PRIV-002 | MEDIUM | Confirmed | Open | Per-user location behavior logs grow forever (search + view logs, no retention) |
| PRIV-003 | MEDIUM | Confirmed | Open | Legal privacy/terms pages are explicit placeholders; waitlist has no deletion/retention procedure |
| SEC-001 | MEDIUM | Low confidence / requires runtime verification | Open | Edge rate limiting likely fails open when Redis is down (framework default) |
| SEC-002 | MEDIUM | High confidence | Open | Redis is an availability single point of failure for login and waitlist |
| SUP-001 | MEDIUM | Confirmed | Open | GitHub Actions are tag-pinned, not commit-SHA-pinned |
| TEST-001 | MEDIUM | Confirmed | Blocked from verification | All Docker-dependent verification was BLOCKED in this audit environment |
| WEB-001 | MEDIUM | Confirmed | Partially mitigated | Uncommitted landing/waitlist rework failed its own unit test at audit snapshot (fixed later the same day, still uncommitted) |
| BE-001 | LOW | Confirmed | Accepted risk | Notification NEARBY_PARKING fan-out is a TODO (documented accepted risk AR-01) |
| BE-002 | LOW | Confirmed | Open | Analytics snapshot reads are unbounded findAll() (slow-growing tables) |
| DATA-001 | LOW | High confidence | Open | No database CHECK constraints on latitude/longitude ranges |
| DOC-001 | LOW | Confirmed | Open | KNOWN-ISSUES OP-06 ('backup restore not automated in CI') is stale — a weekly CI restore drill exists |
| INFRA-002 | LOW | Confirmed | Accepted risk | Alertmanager placeholder receiver ships by default (see OPS-001) and infra/ IaC is a stub |
| OSS-002 | LOW | Confirmed | Open | One moderate npm advisory: transitive uuid <11.1.1 via Expo xcode tooling |
| PRIV-004 | LOW | Confirmed | Open | Waitlist HMAC hash secret defaults to reusing the gateway internal secret |
| SEC-003 | LOW | Confirmed | Open | Local dev docker/.env holds a real RSA private key in plaintext (gitignored, untracked) |
| SUP-002 | LOW | Confirmed | Open | Container base images are tag-pinned, not digest-pinned |
| TEST-002 | LOW | Confirmed | Open | One flaky mobile test under slow I/O (SmartReturnScreen search flow) |
| WEB-002 | LOW | Confirmed | Open | Five ESLint warnings in the web app (react-refresh export patterns) |
| DOC-002 | INFORMATIONAL | Confirmed | False positive rejected | Documentation maturity claims are calibrated and evidence-backed (verified strength) |
| SEC-004 | INFORMATIONAL | Confirmed | False positive rejected | Verified security strengths (recorded for due diligence) |

---

## INFRA-001 — Single-node data plane: no HA Postgres/PITR, single Kafka broker, single MinIO

- **Domain:** Infrastructure
- **Severity:** HIGH · **Confidence:** Confirmed · **Status:** Accepted risk
- **Estimated effort:** XL · **Suggested owner:** DevOps/SRE
- **Dependencies:** Budget for managed services
- **Files:** `docker/docker-compose.yml`, `docker/docker-compose.hosted-beta.yml`, `docs/releases/KNOWN-ISSUES.md`, `docs/architecture/production-readiness.md`

**Description.** The entire data plane lives on one VPS with volume-level backups (scripted daily dumps) but no PITR, no replication, no failover.

**Evidence.** docker-compose.yml / hosted-beta overlay run 9 Postgres containers, 1 Kafka (KRaft, RF=1 default via parkio.kafka.replication-factor), 1 MinIO, 1 Redis on one host; docs/architecture/production-readiness.md and KNOWN-ISSUES HB-02/PP-01..03 state this openly.

**Why it matters.** Host loss means full outage; disk loss means losing everything since the last backup window.

**Failure / abuse scenario.** VPS dies at 23:00; nightly backup was 01:00; the beta loses a day of spots, verifications, waitlist entries; recovery is manual restore (hours).

**Hosted-beta impact.** Acceptable, documented risk for a closed invite beta with backup runbook discipline.

**Public-production impact.** Blocks public production (matches the repo's own NO-GO). Requires managed Postgres with PITR, Kafka RF>=3 or managed alternative, S3/versioned media storage.

**Recommended remediation.** For beta: enforce backup schedule + weekly restore drill on the real host, monitor disk. For production: execute the managed-services migration already planned in production-readiness.md.

**Validation criteria.** Documented RPO/RTO met in a live restore drill; production migration validated in staging.

---

## OSS-001 — No open-source license selected; repository is source-visible only

- **Domain:** Open source / legal
- **Severity:** HIGH · **Confidence:** Confirmed · **Status:** Accepted risk
- **Estimated effort:** XS · **Suggested owner:** Maintainer
- **Dependencies:** none
- **Files:** `LICENSE`, `README.md`, `docs/releases/KNOWN-ISSUES.md`

**Description.** A deliberate 'license pending' placeholder blocks any public open-source distribution and makes external contribution legally ambiguous.

**Evidence.** LICENSE file explicitly reserves all rights pending maintainer license selection; README and KNOWN-ISSUES OSS-01 repeat it.

**Why it matters.** Public repo promotion, contributions, or reuse before selection creates legal ambiguity for everyone involved.

**Failure / abuse scenario.** A contributor forks and builds on the code assuming OSS; rights are unclear; dispute risk.

**Hosted-beta impact.** No impact on hosted beta (operator-run).

**Public-production impact.** Blocks open-source publication (repo already says NO GO).

**Recommended remediation.** Maintainer/legal decision: pick a license, replace LICENSE, add NOTICE if required, update README/SECURITY/release docs. Not chosen by this audit per instructions.

**Validation criteria.** LICENSE contains final text; README/SECURITY updated; NOTICE added if the license requires it.

---

## PRIV-001 — No account deletion or data-erasure capability anywhere in the stack

- **Domain:** Privacy
- **Severity:** HIGH · **Confidence:** Confirmed · **Status:** Open
- **Estimated effort:** L · **Suggested owner:** Backend lead
- **Dependencies:** Event contract change; moderation/suspension interplay
- **Files:** `services/media-service/src/main/java/com/parkio/media/presentation/MediaController.java:137`, `services/notification-service/src/main/java/com/parkio/notification/presentation/NotificationController.java:89`

**Description.** There is no endpoint, admin tool, script, or documented operator procedure to delete a user account or erase a user's personal data (auth record, profile, refresh tokens, spots, media, logs, gamification, notifications).

**Evidence.** grep for account deletion/erasure across services and frontends returns no user-facing or admin flow; only DELETE endpoints are media soft-delete and notification device-token removal.

**Why it matters.** GDPR/KVKK grant data subjects an erasure right. Once external testers register real accounts, the platform cannot honor deletion requests without ad-hoc SQL surgery across 9 databases plus MinIO.

**Failure / abuse scenario.** An external beta tester emails a deletion request; the operator has no supported path and either ignores it (compliance exposure) or hand-edits 9 databases (integrity risk).

**Hosted-beta impact.** Acceptable only for closed beta with informed internal testers; a written manual erasure runbook is the minimum stopgap.

**Public-production impact.** Blocks public beta/production. Erasure must exist as a designed, event-driven cross-service flow before real users are onboarded.

**Recommended remediation.** Design an account-deletion saga (auth-service originates a UserDeletionRequested event; each service erases/anonymizes its slice; media purges MinIO objects), plus a documented manual runbook as interim.

**Validation criteria.** A deleted user's PII is verifiably absent from all 9 service databases, MinIO, and Redis after the flow completes; integration test proves it.

---

## ARCH-001 — Ten-service microservice topology is heavy for a pre-user product

- **Domain:** Architecture
- **Severity:** MEDIUM · **Confidence:** Confirmed · **Status:** Accepted risk
- **Estimated effort:** XL · **Suggested owner:** Architect
- **Dependencies:** none
- **Files:** `settings.gradle.kts`, `docs/operations/runtime-sizing.md`, `benchmarks/reports/p221/REPORT.md`

**Description.** Service boundaries are clean (db-per-service, events, no shared domain), but the fixed operational cost — memory, deploy surface, 9 schemas to migrate/backup, 15 CI workflows — is large relative to zero users.

**Evidence.** 10 Spring Boot JVMs + gateway + 9 Postgres + Kafka + Redis + MinIO + ClamAV + observability. Measured idle footprint ~7.7 GiB (benchmarks/reports/p221). Recommended host 8 vCPU/24 GB (runtime-sizing.md).

**Why it matters.** Every operational task (backup, upgrade, incident) is multiplied by ~10; VPS cost floor is high; onboarding cost is high.

**Failure / abuse scenario.** A solo operator burns beta time nursing 25+ containers instead of shipping product.

**Hosted-beta impact.** Runs, but leaves little headroom on affordable VPS tiers; sizing doc is honest about this.

**Public-production impact.** For production the topology is defensible only with managed data services; consolidation of low-traffic services (analytics+ai-validation+gamification) is the documented cheaper path.

**Recommended remediation.** No action now (repo already documents consolidation candidates); revisit after beta metrics.

**Validation criteria.** Decision record after beta: consolidate or keep, with load data attached.

---

## ARCH-002 — Infrastructure plumbing is copy-pasted across all services by design; drift risk

- **Domain:** Architecture / maintainability
- **Severity:** MEDIUM · **Confidence:** Confirmed · **Status:** Accepted risk
- **Estimated effort:** M · **Suggested owner:** Backend lead
- **Dependencies:** none
- **Files:** `services/*/src/main/java/**/infrastructure/messaging/*OutboxRelay.java`, `services/*/src/main/java/**/infrastructure/lifecycle/RetentionCleanupJob.java`, `platform/parkio-platform`

**Description.** settings.gradle.kts documents 'no shared domain modules on purpose'. Domain isolation is correct, but transport-level plumbing duplication means a bug fix (e.g. the SEC-001 posture, a DLQ edge case) must be applied nine times.

**Evidence.** OutboxRelay, RetentionCleanupJob, GatewayAuthFilter, CorrelationIdFilter, inbox adapters are near-identical in 9 services (auth vs media OutboxRelay differ by ~52 diff lines, mostly renames). platform/parkio-platform holds only 7 shared transport/tracing classes.

**Why it matters.** Nine chances to miss a fix; slow security patching; review fatigue.

**Failure / abuse scenario.** A poison-message handling fix lands in 7 of 9 relays; the other two dead-letter differently and page the operator months later.

**Hosted-beta impact.** Tolerable at current churn.

**Public-production impact.** Growing team/production cadence will want the plumbing (not domain) promoted into parkio-platform.

**Recommended remediation.** Move service-agnostic plumbing (relay skeleton, retention job, gateway-auth filter) into platform module while keeping event payloads/domain service-owned.

**Validation criteria.** One implementation of each plumbing class; services configure, not copy.

---

## DX-001 — Developer machine npm configuration disables TLS to the package registry

- **Domain:** Developer experience / supply chain
- **Severity:** MEDIUM · **Confidence:** Confirmed · **Status:** Open
- **Estimated effort:** XS · **Suggested owner:** Developer
- **Dependencies:** none
- **Files:** `~/.npmrc (outside repository)`

**Description.** Package metadata and any dependency resolution outside the lockfile happen over plaintext HTTP on this machine. Lockfile sha512 integrity pins existing deps, but new/updated dependency resolution is exposed to tampering.

**Evidence.** ~/.npmrc (user home, not the repo) sets registry=http://registry.npmjs.org/ and strict-ssl=false; observed when pnpm audit hit the registry over HTTP (426 response).

**Why it matters.** The repo's lockfile discipline is undermined at the workstation that produces the lockfile.

**Failure / abuse scenario.** On-path attacker on the dev network serves a poisoned tarball for a newly added package; its hash lands in the committed lockfile.

**Hosted-beta impact.** Not a repo defect; fix the workstation.

**Public-production impact.** Same.

**Recommended remediation.** Delete the registry/strict-ssl lines from ~/.npmrc (defaults are HTTPS + strict TLS).

**Validation criteria.** pnpm config get registry returns https://registry.npmjs.org/; strict-ssl true.

---

## MOB-001 — Mobile device runtime smoke never executed for the release line; store-readiness blockers open

- **Domain:** Mobile
- **Severity:** MEDIUM · **Confidence:** Confirmed · **Status:** Open
- **Estimated effort:** S · **Suggested owner:** Mobile engineer
- **Dependencies:** Physical device, Hosted or local stack
- **Files:** `docs/releases/KNOWN-ISSUES.md`, `docs/certification/FINAL-FRONTEND-CERTIFICATION.md`, `docs/beta/mobile-release.md`

**Description.** The Expo app has strong unit coverage (SecureStore tokens, refresh flow, RBAC parity) but no recorded login+upload smoke on a physical device for the RC.

**Evidence.** KNOWN-ISSUES HB-01 and FINAL-FRONTEND-CERTIFICATION state device smoke was not run for FFINAL (emulator present, dev-client session not recorded); play-store listing artwork/owner blockers tracked in docs/beta/.

**Why it matters.** Mobile-specific runtime failures (push, camera, secure store on real hardware) would surface first on testers' phones.

**Failure / abuse scenario.** A beta tester installs the preview APK; camera capture or push registration fails in a way emulators/unit tests did not exercise.

**Hosted-beta impact.** Run the documented device smoke before widening mobile beta (repo's own caveat).

**Public-production impact.** Store submission additionally blocked on artwork/account ownership tasks.

**Recommended remediation.** Execute docs/beta/mobile-release.md smoke on a physical device and record evidence; close listing blockers.

**Validation criteria.** Recorded device smoke: login, spot create with camera photo, push receipt, logout.

---

## OPS-001 — Alert delivery, uptime monitoring and on-call are operator-TODO; alerts route to null by default

- **Domain:** Operations
- **Severity:** MEDIUM · **Confidence:** Confirmed · **Status:** Open
- **Estimated effort:** XS · **Suggested owner:** Operator
- **Dependencies:** none
- **Files:** `docker/alertmanager/alertmanager.yml:5`, `docker/prometheus/alerts.yml`, `docs/releases/KNOWN-ISSUES.md`

**Description.** The observability stack is provisioned (Prometheus/Grafana/Loki/Tempo/Alertmanager, dashboards, blackbox exporter config), but out of the box nothing pages a human.

**Evidence.** alertmanager.yml default receiver is 'null' (render-config.sh injects a real webhook only if env is set); 42 Prometheus alert rules exist; no synthetic/uptime check of the public domain in repo; PP-05 documents on-call as partial.

**Why it matters.** 42 well-formed alerts that reach no receiver equal zero alerts.

**Failure / abuse scenario.** Cert renewal fails silently; beta domain serves an expired certificate for days until a tester complains.

**Hosted-beta impact.** Wire the webhook (env) and an external uptime ping before inviting testers.

**Public-production impact.** Production needs a real on-call rota and escalation.

**Recommended remediation.** Set PARKIO_ALERT_WEBHOOK_URL at deploy (preflight could enforce non-empty for hosted-beta), add a free external uptime monitor for the three domains.

**Validation criteria.** Test alert reaches the operator's channel; uptime monitor alarms on synthetic downtime.

---

## PERF-001 — Write-path performance is unmeasured; read path has real evidence

- **Domain:** Performance
- **Severity:** MEDIUM · **Confidence:** Confirmed · **Status:** Open
- **Estimated effort:** M · **Suggested owner:** Performance engineer
- **Dependencies:** Docker-capable host
- **Files:** `benchmarks/reports/p221/REPORT.md`, `benchmarks/k6/http-load.js`

**Description.** Uploads traverse ClamAV scan + image re-encode + MinIO write + outbox; spot creation fans out Kafka events. None of this is load-tested.

**Evidence.** benchmarks/reports/p221 records a genuine multi-user read benchmark (1,182 req/s, p95 ~6 ms, 0 errors, never saturated) and states plainly that upload/spot-create writes were not exercised.

**Why it matters.** The most expensive path (media) is exactly the untested one.

**Failure / abuse scenario.** Beta announcement drives 50 concurrent uploads; ClamAV (1 GiB RAM, single container) becomes the bottleneck; uploads time out.

**Hosted-beta impact.** Low concurrency expected in closed beta; media rate limit (2 rps replenish) caps exposure.

**Public-production impact.** Public production requires a write-path load test with media in the mix (repo already lists this as a blocker, PP-06).

**Recommended remediation.** Extend the k6 harness with an upload/spot-create scenario against a disposable stack; measure ClamAV throughput.

**Validation criteria.** Recorded write-path benchmark with stated limits and headroom.

---

## PRIV-002 — Per-user location behavior logs grow forever (search + view logs, no retention)

- **Domain:** Privacy / data
- **Severity:** MEDIUM · **Confidence:** Confirmed · **Status:** Open
- **Estimated effort:** S · **Suggested owner:** Backend engineer
- **Dependencies:** PRIV-003 privacy page content
- **Files:** `services/parking-service/src/main/resources/db/migration/V5__create_parking_spot_view_logs.sql`, `services/parking-service/src/main/resources/db/migration/V6__create_parking_spot_search_logs.sql`, `services/parking-service/src/main/java/com/parkio/parking/infrastructure/lifecycle/RetentionCleanupJob.java`

**Description.** Fine-grained user movement/interest data accumulates indefinitely with no retention policy, no aggregation, and no deletion path — while the project's stated principle is location minimization.

**Evidence.** parking_spot_search_logs stores searcher_user_id + exact lat/lng + radius per search; parking_spot_view_logs stores viewer_user_id per spot view. The parking RetentionCleanupJob deletes only outbox/inbox rows.

**Why it matters.** Contradicts the privacy posture claimed in README/startup docs; grows into a sensitive dataset and a liability; also unbounded table growth.

**Failure / abuse scenario.** A year of beta produces a precise history of where each user searched for parking; a breach or subpoena exposes movement patterns.

**Hosted-beta impact.** Low volume in closed beta, but the policy gap should be closed before external users.

**Public-production impact.** Public launch with indefinite raw location logs invites regulatory and reputational risk.

**Recommended remediation.** Add a retention window (e.g. 90 days) to the existing RetentionCleanupJob pattern, or aggregate to daily counts and drop raw rows; document it on the privacy page.

**Validation criteria.** Rows older than the window are provably deleted by the scheduled job; privacy page states the window.

---

## PRIV-003 — Legal privacy/terms pages are explicit placeholders; waitlist has no deletion/retention procedure

- **Domain:** Privacy / legal
- **Severity:** MEDIUM · **Confidence:** Confirmed · **Status:** Open
- **Estimated effort:** S · **Suggested owner:** Founder + frontend
- **Dependencies:** none
- **Files:** `frontend/apps/web/src/pages/LegalPage.tsx:13`, `services/gateway-service/src/main/resources/db/migration (V1 waitlist_interest)`

**Description.** The public site collects email PII (waitlist) under placeholder legal text, and there is no way to honor a waitlist removal request except manual SQL.

**Evidence.** LegalPage.tsx labels both pages 'hosted-beta placeholder'; waitlist_interest stores raw email with consent_timestamp but no retention or deletion mechanism exists.

**Why it matters.** Collecting real emails under placeholder terms is a compliance and trust problem the moment collection opens to the public.

**Failure / abuse scenario.** A signee requests removal citing KVKK; there is no endpoint, admin surface, or runbook for it.

**Hosted-beta impact.** Real waitlist collection should not open until minimally accurate legal text exists and a deletion procedure (even a documented SQL runbook) is in place.

**Public-production impact.** Blocks public launch; final legal review is explicitly out of scope for engineering docs.

**Recommended remediation.** Have counsel (or founder with template review) finalize beta-scope privacy/terms; add a waitlist-removal runbook or admin endpoint; define waitlist retention.

**Validation criteria.** Legal pages no longer say placeholder; a removal request can be executed and evidenced.

---

## SEC-001 — Edge rate limiting likely fails open when Redis is down (framework default)

- **Domain:** Security
- **Severity:** MEDIUM · **Confidence:** Low confidence / requires runtime verification · **Status:** Open
- **Estimated effort:** S · **Suggested owner:** Backend engineer
- **Dependencies:** INFRA-002 alert wiring
- **Files:** `services/gateway-service/src/main/resources/application.yml:38`, `services/gateway-service/src/main/java/com/parkio/gateway/infrastructure/config/RateLimitConfig.java`

**Description.** Spring Cloud Gateway's built-in Redis token bucket allows requests through when the Redis call fails. Parkio does not customize this, so a Redis outage silently removes brute-force and abuse throttling at the edge (auth-service's own Redis-backed login lockout would also be down, see SEC-002).

**Evidence.** All gateway routes use Spring Cloud Gateway RequestRateLimiter with RedisRateLimiter (application.yml routes; RateLimitConfig key resolver). Stock RedisRateLimiter treats Redis errors as 'allow' — no override or custom error handling exists in gateway code.

**Why it matters.** The credential-stuffing and enumeration defense on /auth/login evaporates exactly during a Redis incident.

**Failure / abuse scenario.** Attacker (or bad luck) coincides with Redis outage; login endpoint accepts unthrottled credential-stuffing traffic while the account-lockout tracker is equally unavailable.

**Hosted-beta impact.** Moderate: closed beta has few accounts; still worth an alert on redis_up.

**Public-production impact.** For production, decide the posture explicitly (fail-open with alerting vs fail-closed on auth routes) and test it.

**Recommended remediation.** Verify behavior with Redis stopped (chaos test exists in repo scaffolding); add an Alertmanager rule on Redis down; consider a deny-fallback for /auth/** routes.

**Validation criteria.** Chaos run with Redis stopped shows the chosen posture and pages the operator.

---

## SEC-002 — Redis is an availability single point of failure for login and waitlist

- **Domain:** Security / resilience
- **Severity:** MEDIUM · **Confidence:** High confidence · **Status:** Open
- **Estimated effort:** S · **Suggested owner:** Backend engineer
- **Dependencies:** SEC-001 posture decision
- **Files:** `services/auth-service/src/main/java/com/parkio/auth/infrastructure/security/RedisLoginFailureTracker.java`, `services/gateway-service/src/main/java/com/parkio/gateway/infrastructure/ratelimit/waitlist/RedisWaitlistRateLimiter.java`

**Description.** Security-relevant limiters correctly fail closed, but that makes a Redis outage a full login outage (fail-closed is the safer default; the trade-off is availability).

**Evidence.** RedisLoginFailureTracker and RedisWaitlistRateLimiter propagate Redis exceptions (no fallback/catch); login and waitlist requests error out when Redis is unreachable.

**Why it matters.** Single Redis container down -> no user can log in, no waitlist signup succeeds, and gateway session-epoch/user-status caches also degrade.

**Failure / abuse scenario.** Redis OOM or restart during beta demo; every login 500s until Redis returns.

**Hosted-beta impact.** Acceptable if monitored; operators must know Redis is on the critical path.

**Public-production impact.** Production needs managed/HA Redis or an explicit degraded-mode decision.

**Recommended remediation.** Document the posture; alert on Redis health; consider bounded in-memory fallback only if the posture decision calls for it.

**Validation criteria.** Runbook documents Redis-down behavior; alert fires in chaos drill.

---

## SUP-001 — GitHub Actions are tag-pinned, not commit-SHA-pinned

- **Domain:** Supply chain
- **Severity:** MEDIUM · **Confidence:** Confirmed · **Status:** Open
- **Estimated effort:** XS · **Suggested owner:** DevOps
- **Dependencies:** none
- **Files:** `.github/workflows/backend-ci.yml:33`, `.github/workflows/security-ci.yml`, `.github/workflows/release.yml`

**Description.** A compromised or force-moved action tag executes attacker code in CI with access to workflow permissions (mostly contents:read, but release jobs hold packages:write/id-token:write).

**Evidence.** All workflows reference actions like actions/checkout@v4, github/codeql-action@v4, docker/login-action@v3 by mutable tag.

**Why it matters.** Tag-pinning is the main residual CI supply-chain exposure in an otherwise disciplined setup (least-privilege permissions, gitleaks, Trivy, SBOMs, draft-gated publish).

**Failure / abuse scenario.** Upstream action tag is retargeted maliciously; next release workflow run exfiltrates the GHCR token or poisons images.

**Hosted-beta impact.** Low direct beta impact; images are built from these pipelines.

**Public-production impact.** Production-grade supply chain expects SHA pinning plus Dependabot actions updates.

**Recommended remediation.** Pin every third-party action to a full commit SHA (Dependabot keeps them fresh); one PR.

**Validation criteria.** grep 'uses:' shows only sha-pinned refs; Dependabot config covers github-actions ecosystem.

---

## TEST-001 — All Docker-dependent verification was BLOCKED in this audit environment

- **Domain:** Testing / verification
- **Severity:** MEDIUM · **Confidence:** Confirmed · **Status:** Blocked from verification
- **Estimated effort:** XS · **Suggested owner:** Any engineer with Docker
- **Dependencies:** none
- **Files:** `.github/workflows/backend-integration.yml:62`, `.github/workflows/backup-restore-drill.yml:33`, `buildSrc/src/main/kotlin/parkio.spring-service.gradle.kts:85`

**Description.** Unit-level evidence is fresh and green (712 backend tests re-executed; 293 web/api-client vitest). Integration/e2e/restore claims rest on CI history and certification docs, not on this audit's own runs.

**Evidence.** Docker Desktop daemon unreachable from WSL session (docker.exe: cannot connect to //./pipe/docker_engine). integrationTest, docker compose config, real-stack Playwright, restore drill, chaos scripts all need it. CI runs these (backend-integration.yml with requireDocker=true, weekly backup-restore-drill.yml), but they were not re-executed locally by this audit.

**Why it matters.** The audit cannot independently confirm Kafka/Postgres/MinIO integration behavior or compose validity today.

**Failure / abuse scenario.** A regression that only integration suites catch would be invisible to this audit.

**Hosted-beta impact.** Start Docker Desktop and run: ./gradlew integrationTest -Pparkio.integrationTest.requireDocker=true; docker compose -f docker/docker-compose.yml -f docker/docker-compose.apps.yml config; scripts/validate-hosted-beta-compose.sh.

**Public-production impact.** Same commands gate production claims.

**Recommended remediation.** Re-run the blocked commands on a Docker-capable host and attach results to the test-evidence doc.

**Validation criteria.** All listed commands recorded PASS on the audited commit.

---

## WEB-001 — Uncommitted landing/waitlist rework failed its own unit test at audit snapshot (fixed later the same day, still uncommitted)

- **Domain:** Web frontend
- **Severity:** MEDIUM · **Confidence:** Confirmed · **Status:** Partially mitigated
- **Estimated effort:** XS · **Suggested owner:** Frontend engineer
- **Dependencies:** none
- **Files:** `frontend/apps/web/src/pages/LandingPage.test.tsx:55`, `frontend/apps/web/src/pages/landing/WaitlistForm.tsx`, `frontend/apps/web/src/main.tsx`

**Description.** Working-tree (uncommitted) changes split waitlist constants into waitlistShared.ts, dynamic-import the API call at submit time, add a VITE_WAITLIST_INTAKE_MODE=disabled static mode, and add /privacy + /terms standalone bootstrapping. The happy-path waitlist submit test now fails deterministically. parkio-hostinger-landing.zip and dist/ were built from this tree.

**Evidence.** At the audit evidence snapshot (2026-07-12 ~16:30): 'LandingPage > submits the real waitlist flow successfully' timed out at the 5s test budget, reproduced deterministically in isolation. Re-checked at ~22:00 after the maintainer continued working on the tree: the file now passes 7/7. The fix remains uncommitted, and the previously packaged parkio-hostinger-landing.zip predates it. Original mechanism: waitFor timeout raised to 10s exceeded the 5s per-test budget; submit path lazy-imports waitlistService inside the handler.

**Why it matters.** The landing artifact just packaged for Hostinger deployment carries an unverified waitlist submit path; the suite is red so CI would reject this work.

**Failure / abuse scenario.** If the dynamic import chain fails or stalls in the deployed bundle, a visitor clicks 'Join beta waitlist' and the form spins forever; the signup is silently lost.

**Hosted-beta impact.** Landing page deploys should wait until the suite is green or intake mode is 'disabled' (static mode renders without the submit path).

**Public-production impact.** Same as beta; also erodes trust in the test gate if committed red.

**Recommended remediation.** Commit the fix, re-run the FULL web suite green, and rebuild parkio-hostinger-landing.zip from the fixed tree before deploying; verify one live submit against a running gateway.

**Validation criteria.** corepack pnpm --filter @parkio/web test passes on the committed tree; the deployed artifact is rebuilt from that commit; a manual submit records 202.

---

## BE-001 — Notification NEARBY_PARKING fan-out is a TODO (documented accepted risk AR-01)

- **Domain:** Backend
- **Severity:** LOW · **Confidence:** Confirmed · **Status:** Accepted risk
- **Estimated effort:** M · **Suggested owner:** Backend engineer
- **Dependencies:** user location opt-in model
- **Files:** `services/notification-service/src/main/java/com/parkio/notification/application/NotificationApplicationService.java:92`

**Description.** A documented product gap, not a defect: nearby drivers are not notified of new spots.

**Evidence.** Single TODO in main code: NotificationApplicationService:92 — nearby-user fan-out not implemented; inbox/push per-user paths work.

**Why it matters.** Feature value gap in beta; core notification plumbing unaffected.

**Failure / abuse scenario.** Users expect nearby alerts (marketing copy mentions freshness); they never arrive.

**Hosted-beta impact.** Set expectations in beta comms.

**Public-production impact.** Implement before marketing the feature.

**Recommended remediation.** Implement geo-fanout (parking event -> user-service nearby query -> notification), respecting privacy minimization.

**Validation criteria.** Nearby user receives push in integration test.

---

## BE-002 — Analytics snapshot reads are unbounded findAll() (slow-growing tables)

- **Domain:** Backend
- **Severity:** LOW · **Confidence:** Confirmed · **Status:** Open
- **Estimated effort:** XS · **Suggested owner:** Backend engineer
- **Dependencies:** none
- **Files:** `services/analytics-service/src/main/java/com/parkio/analytics/application/AnalyticsApplicationService.java:127`

**Description.** Years of operation would make admin analytics responses large; not a beta problem.

**Evidence.** AnalyticsApplicationService returns dailySnapshots.findAll()/parkingSnapshots.findAll() with no pagination; tables grow ~1 row/day/dimension.

**Why it matters.** Slow admin dashboard someday.

**Failure / abuse scenario.** Admin endpoint returns multi-MB payload after years.

**Hosted-beta impact.** None.

**Public-production impact.** Minor.

**Recommended remediation.** Add date-range parameters (already natural for snapshots).

**Validation criteria.** Endpoints accept from/to and default to a bounded window.

---

## DATA-001 — No database CHECK constraints on latitude/longitude ranges

- **Domain:** Database
- **Severity:** LOW · **Confidence:** High confidence · **Status:** Open
- **Estimated effort:** XS · **Suggested owner:** Backend engineer
- **Dependencies:** none
- **Files:** `services/parking-service/src/main/resources/db/migration/V2__create_parking_spots.sql`

**Description.** Schema admits lat=999; the PostGIS trigger would compute a garbage geography point rather than reject it.

**Evidence.** parking_spots migration defines lat/lng DOUBLE PRECISION NOT NULL without range CHECKs; validation happens in application code only.

**Why it matters.** Defense-in-depth gap; bad data via any non-API write path (manual fix, future bug) persists silently.

**Failure / abuse scenario.** A buggy backfill writes swapped lat/lng; nearby search silently misses those spots.

**Hosted-beta impact.** Negligible.

**Public-production impact.** Cheap hardening.

**Recommended remediation.** Add CHECK (latitude BETWEEN -90 AND 90) and (longitude BETWEEN -180 AND 180) in a new migration.

**Validation criteria.** Constraint present; insert of out-of-range values fails.

---

## DOC-001 — KNOWN-ISSUES OP-06 ('backup restore not automated in CI') is stale — a weekly CI restore drill exists

- **Domain:** Documentation
- **Severity:** LOW · **Confidence:** Confirmed · **Status:** Open
- **Estimated effort:** XS · **Suggested owner:** Docs owner
- **Dependencies:** none
- **Files:** `.github/workflows/backup-restore-drill.yml:33`, `docs/releases/KNOWN-ISSUES.md`

**Description.** Documentation understates the project here (rare direction). CI drill covers script correctness, not the live host's volumes — that nuance belongs in the doc.

**Evidence.** backup-restore-drill.yml runs weekly (cron 23 4 * * 1) restoring real dumps against real migrations; OP-06 still claims restore is not automated.

**Why it matters.** Operators may skip a drill that exists, or over-trust CI coverage of their specific host.

**Failure / abuse scenario.** Confusion during incident about what restore evidence exists.

**Hosted-beta impact.** Update the row to distinguish CI drill (exists) from live-host drill (operator duty).

**Public-production impact.** None.

**Recommended remediation.** Doc fix.

**Validation criteria.** KNOWN-ISSUES row reflects the workflow accurately.

---

## INFRA-002 — Alertmanager placeholder receiver ships by default (see OPS-001) and infra/ IaC is a stub

- **Domain:** Infrastructure
- **Severity:** LOW · **Confidence:** Confirmed · **Status:** Accepted risk
- **Estimated effort:** L · **Suggested owner:** DevOps
- **Dependencies:** AWS decision
- **Files:** `infra/README.md`, `docs/releases/KNOWN-ISSUES.md`

**Description.** No Terraform/Kubernetes automation exists; the hosted beta path is deliberately script-driven.

**Evidence.** infra/ contains only README/CLAUDE placeholders (HB-03 documents this); deployment is compose + operator scripts by design for beta.

**Why it matters.** Environment rebuilds are manual and rely on runbook fidelity.

**Failure / abuse scenario.** VPS must be rebuilt; operator follows HOSTED-BETA-RUNBOOK.md by hand; drift possible.

**Hosted-beta impact.** Fine for one host.

**Public-production impact.** Production/AWS needs at least minimal IaC for the network/data tier.

**Recommended remediation.** Defer until AWS phase; then Terraform the VPC/RDS/ECS skeleton.

**Validation criteria.** Environment reproducible from code.

---

## OSS-002 — One moderate npm advisory: transitive uuid <11.1.1 via Expo xcode tooling

- **Domain:** Dependencies
- **Severity:** LOW · **Confidence:** Confirmed · **Status:** Open
- **Estimated effort:** XS · **Suggested owner:** Mobile engineer
- **Dependencies:** none
- **Files:** `frontend/pnpm-lock.yaml`

**Description.** Vulnerable uuid is used by Expo's Xcode project generator during prebuild, not in the runtime bundle.

**Evidence.** pnpm audit --prod (HTTPS): 1 moderate (GHSA-w5hq-g745-h8pq), 281 paths, all under @expo/cli config-plugins > xcode > uuid@7.0.3 — build-time tooling, not shipped app code.

**Why it matters.** Negligible runtime exposure; hygiene item.

**Failure / abuse scenario.** None realistic.

**Hosted-beta impact.** None.

**Public-production impact.** None.

**Recommended remediation.** Track Expo SDK updates; add a pnpm audit gate to frontend CI if desired.

**Validation criteria.** pnpm audit --prod reports 0 known vulnerabilities.

---

## PRIV-004 — Waitlist HMAC hash secret defaults to reusing the gateway internal secret

- **Domain:** Privacy / crypto hygiene
- **Severity:** LOW · **Confidence:** Confirmed · **Status:** Open
- **Estimated effort:** XS · **Suggested owner:** Backend engineer
- **Dependencies:** none
- **Files:** `services/gateway-service/src/main/resources/application.yml:257`

**Description.** One secret serving two purposes (service-mesh auth + PII pseudonymization) couples their rotation and blast radius; otherwise the waitlist design (raw IP never stored, HMAC-SHA256, >=32-char enforcement) is good.

**Evidence.** application.yml: parkio.waitlist.hash-secret defaults to ${PARKIO_GATEWAY_INTERNAL_SECRET:} when PARKIO_WAITLIST_HASH_SECRET is unset.

**Why it matters.** Rotating the gateway secret silently changes waitlist hashes, breaking dedupe/rate-limit continuity; leaking one secret weakens the other's purpose.

**Failure / abuse scenario.** Gateway secret rotation day: every prior email_hash no longer matches, unique constraint lets duplicates in.

**Hosted-beta impact.** Set both env vars distinctly (hosted-beta env example may already; verify at deploy).

**Public-production impact.** Set distinct secrets everywhere.

**Recommended remediation.** Require PARKIO_WAITLIST_HASH_SECRET explicitly in hosted profiles (preflight check).

**Validation criteria.** Preflight fails when the two secrets are identical or waitlist secret unset.

---

## SEC-003 — Local dev docker/.env holds a real RSA private key in plaintext (gitignored, untracked)

- **Domain:** Security hygiene
- **Severity:** LOW · **Confidence:** Confirmed · **Status:** Open
- **Estimated effort:** XS · **Suggested owner:** Developer
- **Dependencies:** none
- **Files:** `docker/.env (local, untracked)`, `.gitignore:35`, `.gitleaks.toml`

**Description.** Standard local-dev practice, but the key doubles as the dev JWT signing key; any process/user on the workstation can read it.

**Evidence.** docker/.env (verified untracked and ignored via .gitignore:35, git ls-files empty) contains a full PKCS#8 private key and local-dev passwords. Committed *.env.example files hold only CHANGE_ME placeholders; gitleaks + push protection + preflight CHANGE_ME checks guard the repo boundary.

**Why it matters.** If this dev key were ever reused in a hosted environment, its exposure would be an auth compromise; runbooks already forbid reuse (OP-03).

**Failure / abuse scenario.** Dev machine malware exfiltrates the key; harmless only as long as it never signs non-local tokens.

**Hosted-beta impact.** None if key stays local-only.

**Public-production impact.** None if rotation discipline holds.

**Recommended remediation.** Keep dev keys clearly labeled, never reuse in hosted envs (already documented); optionally move hosted-beta secrets to an encrypted store on the VPS.

**Validation criteria.** Hosted env uses a freshly generated key (preflight already enforces non-placeholder).

---

## SUP-002 — Container base images are tag-pinned, not digest-pinned

- **Domain:** Supply chain
- **Severity:** LOW · **Confidence:** Confirmed · **Status:** Open
- **Estimated effort:** XS · **Suggested owner:** DevOps
- **Dependencies:** none
- **Files:** `services/auth-service/Dockerfile`, `frontend/apps/web/Dockerfile`

**Description.** Tags are mutable; a rebuild can silently pull different base content. Trivy scanning mitigates known-CVE risk but not substitution.

**Evidence.** Dockerfiles use eclipse-temurin:21-jdk/-jre, node:22-bookworm-slim, nginx:1.27-alpine by tag.

**Why it matters.** Two builds of the same commit may not be identical at the base layer despite the reproducible-jar work.

**Failure / abuse scenario.** Registry-side tag repoint changes the runtime base under a hotfix rebuild.

**Hosted-beta impact.** Minor for beta.

**Public-production impact.** Digest-pin plus automated digest bumps is the production norm.

**Recommended remediation.** Pin FROM lines to sha256 digests; let Dependabot/Renovate bump them.

**Validation criteria.** All FROM lines carry digests; CI green.

---

## TEST-002 — One flaky mobile test under slow I/O (SmartReturnScreen search flow)

- **Domain:** Testing
- **Severity:** LOW · **Confidence:** Confirmed · **Status:** Open
- **Estimated effort:** XS · **Suggested owner:** Mobile engineer
- **Dependencies:** none
- **Files:** `frontend/apps/mobile/src/features/smart-return/presentation/__tests__/SmartReturnScreen.test.tsx`

**Description.** The test depends on async search results appearing within default timeouts, which slow environments miss during full-suite runs.

**Evidence.** Full-suite jest --runInBand run failed 'searches, selects a home area, and saves the settings' (testID smartReturn.home.result.g1 not found, suite took 85.6s on WSL /mnt/c); the same file passes 10/10 in isolation (96s). Timing-sensitive async/debounce under constrained I/O.

**Why it matters.** Flaky tests erode trust in the gate and train people to re-run red CI.

**Failure / abuse scenario.** A legitimate Smart Return regression is dismissed as 'the flaky one'.

**Hosted-beta impact.** None functional.

**Public-production impact.** None functional.

**Recommended remediation.** Raise the specific waitFor/test timeout or await the debounce deterministically (fake timers).

**Validation criteria.** Full suite passes 3x consecutively in a constrained environment.

---

## WEB-002 — Five ESLint warnings in the web app (react-refresh export patterns)

- **Domain:** Web frontend
- **Severity:** LOW · **Confidence:** Confirmed · **Status:** Open
- **Estimated effort:** XS · **Suggested owner:** Frontend engineer
- **Dependencies:** none
- **Files:** `frontend/apps/web (lint output)`

**Description.** Cosmetic; affects HMR ergonomics only.

**Evidence.** pnpm -r lint exits 0 with 5 warnings in apps/web (react-refresh/only-export-components).

**Why it matters.** None.

**Failure / abuse scenario.** None.

**Hosted-beta impact.** None.

**Public-production impact.** None.

**Recommended remediation.** Split mixed component/constant exports or accept and silence with rationale.

**Validation criteria.** Lint reports 0 warnings.

---

## DOC-002 — Documentation maturity claims are calibrated and evidence-backed (verified strength)

- **Domain:** Documentation
- **Severity:** INFORMATIONAL · **Confidence:** Confirmed · **Status:** False positive rejected
- **Estimated effort:** XS · **Suggested owner:** —
- **Dependencies:** none
- **Files:** `README.md`, `docs/releases/KNOWN-ISSUES.md`, `docs/certification/FINAL-PRODUCTION-CERTIFICATION.md`, `benchmarks/reports/p221/REPORT.md`

**Description.** Documentation truthfulness is unusually high; the audit found only one stale claim (DOC-001) — stale in the self-critical direction.

**Evidence.** README/CHANGELOG/KNOWN-ISSUES/certifications consistently distinguish 'certified for hosted-beta preparation' from 'live deployment not proven'; production and OSS are self-declared NO-GO; startup docs deny users/revenue/funding claims. Sampled claims (712 backend tests vs 'tests pass', moderation ADMIN gating, analytics ownership, fail-closed secrets, benchmark honesty about untested writes) all verified in code.

**Why it matters.** Positive finding.

**Failure / abuse scenario.** n/a

**Hosted-beta impact.** n/a

**Public-production impact.** n/a

**Recommended remediation.** None — maintain.

**Validation criteria.** n/a

---

## SEC-004 — Verified security strengths (recorded for due diligence)

- **Domain:** Security
- **Severity:** INFORMATIONAL · **Confidence:** Confirmed · **Status:** False positive rejected
- **Estimated effort:** XS · **Suggested owner:** —
- **Dependencies:** none
- **Files:** `services/gateway-service/src/main/java/com/parkio/gateway/infrastructure/security/`, `services/auth-service/src/main/java/com/parkio/auth/`, `docker/caddy/Caddyfile`, `docker/docker-compose.hosted-beta.yml`

**Description.** The security architecture matches its documentation at every point this audit sampled.

**Evidence.** Code-verified: gateway strips inbound X-User-*/X-Gateway-Auth before stamping; RS256+JWKS with audience/issuer/skew enforcement; fail-closed user-status + session-epoch checks (503 on dependency loss); refresh tokens stored as SHA-256 of high-entropy values with family revocation and reuse detection bumping session epoch; BCrypt passwords; constant-time secret comparison (MessageDigest.isEqual) with rotation window support; Redis login lockout; media pipeline: content-type sniffing, pixel/size caps, EXIF handling with re-encode, ClamAV, private bucket + presigned SigV4 GETs; edge CSP/HSTS/Permissions-Policy at Caddy; actuator limited to health,info,prometheus; web access token memory-only + HttpOnly refresh cookie; SW never caches API/auth; mobile tokens in SecureStore with allowBackup=false; non-root containers with mem/cpu/pids limits and !reset ports in hosted overlay; CORS deny-by-default allow-list failing fast on wildcard+credentials.

**Why it matters.** Positive finding: prior certification claims are substantiated.

**Failure / abuse scenario.** n/a

**Hosted-beta impact.** n/a

**Public-production impact.** n/a

**Recommended remediation.** None — maintain.

**Validation criteria.** n/a
