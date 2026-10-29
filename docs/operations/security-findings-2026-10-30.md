# Security CI disposition — 2026-10-30 (proposed renewal of the three Spring Framework exceptions, #513)

**Status: proposal. Owner decision pending in #513.** Nothing in this record takes effect until the owner accepts the
risk in #513 and this change is reviewed and merged. Without a decision, the entries expire as recorded today:
CVE-2026-47884 on 2026-11-05 and CVE-2026-47890 / CVE-2026-47892 on 2026-11-07 (`expired_at` is exclusive).

Proposed change: the same three entries (same ids, same version-pinned paths), `expired_at: 2026-12-01` (exclusive:
suppressed through 2026-11-30 UTC), with refreshed evidence references. Maximum duration of this renewal: until
2026-12-01. Any further renewal needs a new evidence package and a new owner decision, and should not exceed 30 days.
CVE-2026-14257 is not renewed: on api it no longer matches anything (brace-expansion resolves to 1.1.21) and lapses on
2026-10-31.

## Why there is no patch instead

| Line | Status (api.spring.io generations and releases; GitHub release tags; Maven Central BOMs, read 2026-10-29) |
|---|---|
| Spring Framework 6.2.x | OSS support ended 2026-06-30. Newest OSS release 6.2.19 (2026-06-08). 6.2.20 is Enterprise Support only; there is no public v6.2.20 tag |
| Spring Boot 3.5.x | OSS support ended 2026-06-30. 3.5.16 (2026-06-25) still manages Spring Framework 6.2.19 |
| Spring Cloud 2025.0.x (Gateway 4.3.x) | OSS support ended 2026-06-30 (marked end of life on the Spring Cloud project page). Newest: 2025.0.3 / Gateway 4.3.5 |
| Spring Framework 7.0.x | OSS fix in 7.0.9 (2026-08-20); current 7.0.10 (2026-10-22). OSS support until 2027-07-31 |
| Spring Boot 4.1.x | 4.1.2 (2026-10-22) manages Framework 7.0.10 and Security 7.1.2. OSS support until 2027-07-31 |
| Spring Boot 4.0.x | 4.0.9 (2026-10-22) manages Framework 7.0.10 and Security 7.0.8. OSS support ends 2026-12-31 |
| Spring Cloud 2025.1.x | 2025.1.4 (2026-10-23) brings Gateway 5.0.4; compatible with Boot 4.0.x and, from 2025.1.2, 4.1.x |

No supported scoped fix exists for the current line. Overriding spring-webmvc/spring-webflux to 7.0.x under Spring Boot 3.5
is unsupported. Neither jar can be dropped: every servlet service needs spring-webmvc and the gateway needs spring-webflux.
Patching or stripping framework classes would ship a modified framework artifact that the scanner still reports by
version. The supported fix is the Spring Boot 4 line; the migration is tracked in #515.

## Gate thresholds (unchanged by this change)

| Job | Trivy severity gate | `--ignore-unfixed` | Ignore file |
|---|---|---|---|
| Dependency vulnerability scan (`frontend/pnpm-lock.yaml`) | `HIGH,CRITICAL` | yes | `.trivyignore.yaml` |
| Container scan: critical image gate | `CRITICAL` | yes | `.trivyignore.yaml` |
| Container scan: library gate (api only) | `HIGH,CRITICAL`, `--pkg-types library` | yes | `.trivyignore.yaml` |
| Container scan: report step (artifact only) | `HIGH,CRITICAL`, exit 0 | yes | none: the report stays unfiltered |
| Candidate image build (`candidate-image-build` action) | the same critical and library gates | yes | the source's `.trivyignore.yaml` |

No workflow, threshold, scanner version or ignore pattern changes. Only `expired_at` and the statements change.

## Verification (2026-10-29)

- **Official sources.** The three spring.io advisories (published 2026-08-20; history: initial publication only), the
  vendor fix commits, GHSA, NVD, api.spring.io support and release metadata, GitHub release tags and the Maven Central
  BOM POMs.
- **Current source.** `git grep` over `services/*/src/main` at api `399b5dd7`, at the pinned revision `fdce6ba0`, at
  master `f8973747` and at every revision that runs in production (`971ac1d2`, `504b9cb4`, `4a9ba218`, `f888a81b`).
  None references any construct named in §1–§3, no service declares a template engine, and no service ships an
  .xsl/.xslt file. All are on Spring Boot 3.5.15 / Spring Cloud 2025.0.3. This is string absence, not proof by itself.
- **Exact artifacts.** `/app/app.jar` from the ten Java images of the current pins (source `fdce6ba0`, publish run
  39470264655) and from the four registry-pinned production images (auth `2091de2e`, gateway `655219f5`, media
  `62f49d04`, parking `3888df05`). The images were created locally and never started. The analysis is a static scan of
  class constant pools and resource bytes in the application classes and in every nested jar except spring-webmvc and
  spring-webflux themselves. It covers internal (slash) and dotted (reflection or configuration) names, with a positive
  control: the same scan finds the XsltView, FragmentsRendering, SseEmitter, Fragment, `toHttpHandler`, `toWebHandler`
  and RouterFunctionWebHandler references inside spring-webmvc and spring-webflux.
- **Scanner.** Security CI run 40917895160 (push, api `399b5dd7`, success, fresh database). The unfiltered report lists
  CVE-2026-47884 and CVE-2026-47890 on spring-webmvc 6.2.19 in the nine servlet images, and CVE-2026-47890 and
  CVE-2026-47892 on spring-webflux 6.2.19 in the gateway. With `.trivyignore.yaml`, the critical and library gates
  report 0 results on all eleven images, so these three entries are the only suppressions in use.
- **Runtime configuration.** Packaged `application*.yml` and Compose expose only `health,info,prometheus` and set no
  web-application-type, view, template or WebFlux property. A read-only check of the running production containers
  showed the same exposure and none of those properties.

## 1. CVE-2026-47884 — "Spring Framework Improper Path Limitation in XsltView"

| Field | Value |
|---|---|
| Vendor advisory | <https://spring.io/security/cve-2026-47884>, MEDIUM, CVSS 3.1 `AV:N/AC:L/PR:N/UI:N/S:C/C:L/I:N/A:N` = 5.8, CWE-22/CWE-918 |
| Vendor condition (verbatim) | "Use of XsltView in a Spring MVC application can result in SSRF and RCE attack if the application has an "/**" mapping that results in view rendering, and where the view name is not explicitly specified." |
| Fix | gh-37029, commit `692dbc91`: `XsltView.getStylesheetSource()` normalizes and checks the stylesheet location. 7.0.9 OSS; 6.2.20 Enterprise Support only |
| GHSA / NVD | GHSA-pc63-qcmh-9cmg, critical 9.8 (CISA-ADP vector), package spring-webmvc, no patched 6.2.x version |
| Images | spring-webmvc 6.2.19 in the nine servlet images; not in the gateway |
| Applicability | **Not applicable (static, exact artifacts).** No class or resource outside spring-webmvc references or names the XsltView package in any scanned image. The application classes reference no View, ViewResolver, ViewControllerRegistry or UrlFilenameViewController. No `@Controller` (non-REST) exists. No template engine is on the classpath. The only shipped .xsl is spring-security.xsl in auth-service's spring-security-config jar, which is not a view. Spring Boot does not auto-configure XsltView |

## 2. CVE-2026-47890 — "Spring Framework Server Sent Event stream corruption while rendering fragments"

| Field | Value |
|---|---|
| Vendor advisory | <https://spring.io/security/cve-2026-47890>, LOW, CVSS 3.1 `AV:N/AC:H/PR:L/UI:R/S:U/C:N/I:L/A:N` = 2.6, CWE-93 |
| Vendor conditions (all required) | "the application is using Spring MVC or Spring WebFlux; the application is sending view fragments to clients over Server Sent Events (SSE); the attacker must have control over data that will be streamed to other users of the application" |
| Fix | gh-37061, commit `1994e0eb`: escapes CR/LF in rendered view fragments (MVC `ResponseBodyEmitterReturnValueHandler`, WebFlux `ViewResolutionResultHandler`). 7.0.9 OSS; 6.2.20 Enterprise Support only |
| GHSA / NVD | GHSA-j9f9-w8pj-32f8, critical 9.8 (CISA-ADP vector), packages spring-webmvc and spring-webflux |
| Images | spring-webmvc 6.2.19 (nine servlet images) and spring-webflux 6.2.19 (gateway) |
| Applicability | **Not applicable (static, exact artifacts).** The application classes reference no SseEmitter, ResponseBodyEmitter, StreamingResponseBody, ServerSentEvent, FragmentsRendering, Fragment, ModelAndView, View or `text/event-stream`. No class outside spring-webmvc/spring-webflux references FragmentsRendering, Fragment, SseEmitter or ResponseBodyEmitter. No template engine is on any classpath, so no view can be rendered. The only SSE producers are the Resilience4j circuit-breaker event streams (ai-validation, parking). They stream JSON, not view fragments, and they are not exposed. No servlet image carries spring-webflux |

## 3. CVE-2026-47892 — "Spring Framework Header Predicate Bypass in WebFlux Functional Endpoints"

| Field | Value |
|---|---|
| Vendor advisory | <https://spring.io/security/cve-2026-47892>, MEDIUM, CVSS 3.1 `AV:N/AC:H/PR:N/UI:N/S:U/C:L/I:L/A:N` = 4.8, CWE-863 |
| Vendor condition (verbatim) | "A WebFlux application using functional endpoints and deployed with DispatcherServlet may be vulnerable to a header predicate bypass in a pre-flight request." |
| Fix | gh-37024 ("Handle pre-flight requests in functional endpoint setup without DispatcherHandler"), commit `07cbd482`: `RouterFunctions.RouterFunctionWebHandler` answers pre-flight requests with 403 instead of invoking the handler. 7.0.9 OSS; 6.2.20 Enterprise Support only |
| GHSA / NVD | GHSA-9qf2-26p9-2q2q, critical 9.8 (CISA-ADP vector), package spring-webflux only |
| Images | spring-webflux 6.2.19 in the gateway only |
| Applicability | **Not applicable (static, exact artifacts).** Both gateways (pinned `fdce6ba0`, production `971ac1d2`) are Spring Cloud Gateway 4.3.5 on reactor-netty. Neither has a DispatcherServlet, a servlet container or spring-webmvc. The application classes reference no RouterFunction, RequestPredicates, HttpHandler or WebHandler. Nothing outside spring-webflux references `toHttpHandler`, `toWebHandler` or RouterFunctionWebHandler, so the patched class is never created. The other RouterFunction users are Spring Boot classes (error handler, welcome page, actuator mappings), all dispatched by DispatcherHandler. CORS pre-flight is handled by the application's `CorsWebFilter` |

The advisory text says "deployed with DispatcherServlet", while the fix concerns a setup *without DispatcherHandler*. The
gateway has neither a DispatcherServlet nor a RouterFunctionWebHandler deployment, so the verdict holds under both readings.

## 4. Evidence gaps

1. Six production services (user, gamification, notification, moderation, analytics, ai-validation) run images built on
   the host at `f888a81b`. They have no registry digest, so their jars were not inspected. Evidence for them is
   source-level (`f888a81b`), the Boot 3.5.15 build and the running containers' configuration. The gap closes when the
   inspected `fdce6ba0` pins are deployed (a separately authorized release).
2. The analysis is static. No application context was started, so there is no bean-graph proof. A class created
   reflectively from a name built at runtime would not show; no such name appears in any jar.
3. The GHSA/NVD severity (CISA-ADP 9.8) still disagrees with the vendor. The scanner keeps reporting CRITICAL until the
   jars change.

## 5. Re-evaluation triggers (any one re-opens the decision; the entry is dropped when its cause is gone)

1. Any SSE or streaming handler, `ModelAndView`/`View`/fragment rendering, any non-REST `@Controller`, XsltView or a
   ViewResolver.
2. Any template engine dependency, an .xsl/.xslt resource, or view settings.
3. Any WebFlux.fn or WebMvc.fn `RouterFunction`/`RequestPredicates`, `toHttpHandler`/`toWebHandler`, or a custom
   `HttpHandler`/`WebHandler`.
4. A `spring.main.web-application-type` change, or a move to Spring Cloud Gateway Server MVC.
5. spring-webflux in a servlet image, or spring-webmvc or a servlet container in the gateway.
6. A new Java service or image.
7. Any Spring Framework, Spring Boot or Spring Cloud Gateway change: drop the entries at Framework 7.0.9 or later, or
   when an OSS 6.2.x fix ships.
8. Actuator exposure changes that add SSE-capable endpoints, or adding `resilience4j-reactor`.
9. Advisory updates (a new spring.io history entry, changed GHSA package lists, new NVD/CISA-ADP data or any exploitation
   report).
10. A candidate or deployed image whose artifact check (the static scan above) finds any construct of §1–§3.
11. The expiry, 2026-12-01.

## 6. What this does not do

- No new CVE, path or pattern.
- No threshold, scanner version or workflow change.
- No change to the unfiltered report step.
- No change to CVE-2026-14257.
- No image build, publication or deployment.
- master carries the same three entries. Its Security CI runs only on master pushes and PRs (the scheduled scans run on
  api), so a master renewal is a separate, scoped master PR, needed only if a master change is planned before the
  entries lapse there.
