# Security CI disposition — 2026-10-30 (temporary renewal of the three Spring Framework exceptions, #513)

**Status: temporary risk acceptance by the repository owner in #513 (2026-10-30). This is not a remediation.** The
permanent fix is the Spring Boot 4.1 migration, tracked in #515.

| | |
|---|---|
| Scope | Only the three existing entries, with the same ids and the same version-pinned paths: CVE-2026-47884 (`app/app.jar/BOOT-INF/lib/spring-webmvc-6.2.19.jar`), CVE-2026-47890 (the same spring-webmvc path and `app/app.jar/BOOT-INF/lib/spring-webflux-6.2.19.jar`) and CVE-2026-47892 (the same spring-webflux path). No new id, path, pattern or package type |
| Expiry | `expired_at: 2026-11-29`. The owner set the absolute ceiling at 2026-11-29, or 30 days after the merge if that is earlier. The merge cannot be earlier than 2026-10-30, so the ceiling applies. `expired_at` is exclusive: Trivy applies the entries until 2026-11-29 00:00 UTC, and from then on the gates report the findings again |
| Renewal | None is automatic. Anything after the expiry needs a new evidence package and a new owner decision |
| What it accepts | The documented residual uncertainty for these three CVEs (§4). It does not establish that any other advisory is not applicable |
| Previous expiry | CVE-2026-47884 on 2026-11-05; CVE-2026-47890 and CVE-2026-47892 on 2026-11-07 |

CVE-2026-14257 is not renewed. On api it no longer matches anything, because brace-expansion resolves to 1.1.21, and it
lapses on 2026-10-31.

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

## Verification

- **Official sources, rechecked 2026-10-30.** The three spring.io advisories are unchanged from the 2026-10-29 copies
  (published 2026-08-20; history: initial publication only). So are their GHSA records (GHSA-pc63-qcmh-9cmg,
  GHSA-j9f9-w8pj-32f8, GHSA-9qf2-26p9-2q2q: same package ranges, no patched 6.2.x version, last updated 2026-10-05/07)
  and their NVD records (same status, last-modified date and metrics). The 2026-10-29 package also covered the vendor
  fix commits, api.spring.io support and release metadata, GitHub release tags and the Maven Central BOM POMs.
- **Source.** `git grep` over `services/*/src/main` at api `399b5dd7`, at the pinned revision `fdce6ba0`, at master
  `f8973747` and at every revision that runs in production (`971ac1d2`, `504b9cb4`, `4a9ba218`, `f888a81b`). None
  references any construct named in §1–§3, no service declares a template engine, and no service ships an .xsl/.xslt
  file. All are on Spring Boot 3.5.15 / Spring Cloud 2025.0.3. On its own this shows only that the names are absent;
  the artifact checks below are the main evidence.
- **Artifacts.** See the table below. For each image, the check reads `/app/app.jar`. It is a static scan of class
  constant pools and resource bytes in the application classes and in every nested jar except spring-webmvc and
  spring-webflux themselves, using both internal (slash) and dotted (reflection or configuration) names. A positive
  control shows the same scan finds the XsltView, FragmentsRendering, SseEmitter, Fragment, `toHttpHandler`,
  `toWebHandler` and RouterFunctionWebHandler references inside spring-webmvc and spring-webflux.
- **Scanner.** Security CI run 40917895160 (push, api `399b5dd7`, success, fresh database). The unfiltered report lists
  CVE-2026-47884 and CVE-2026-47890 on spring-webmvc 6.2.19 in the nine servlet images, and CVE-2026-47890 and
  CVE-2026-47892 on spring-webflux 6.2.19 in the gateway. With `.trivyignore.yaml`, the critical and library gates
  report 0 results on all eleven images, so these three entries are the only suppressions in use.
- **Runtime configuration.** Packaged `application*.yml` and Compose expose only `health,info,prometheus` and set no
  web-application-type, view, template or WebFlux property. A read-only check of the running production containers
  (`docker inspect`, allow-listed non-secret keys only) showed the same exposure and none of those properties.

### Which images were inspected

No image was rebuilt locally for this evidence. A local rebuild would not be byte-identical to the deployed artifact, so
it would not count as an inspected production image.

| Image set | Services | How `/app/app.jar` was obtained | Inspected |
|---|---|---|---|
| Production, registry images | auth (`sha256:2091de2e…`, source `504b9cb4`), gateway (`sha256:655219f5…`, `971ac1d2`), media (`sha256:62f49d04…`, `4a9ba218`), parking (`sha256:3888df05…`, `504b9cb4`) | Pulled from GHCR by the exact digest that the running production container uses (the host runs the containerd image store, where that digest is the image's content address). The jar copied read-only from each running container has the same SHA-256 as the jar in the pulled image | Yes (2026-10-29) |
| Production, host-built images | user, gamification, notification, moderation, analytics, ai-validation (built on the host at `f888a81b`, no registry digest) | Copied read-only from each running production container (`docker cp <container>:/app/app.jar -`; no exec, nothing written on the host). The SHA-256 of each copy is recorded with the #513 evidence | Yes (2026-10-30) |
| Candidate pins, not deployed | the ten Java images of the current pins (source `fdce6ba0`, publish run 39470264655) | Pulled from GHCR by digest; a container was created to copy the jar and was never started | Yes (2026-10-29) |

All ten production Java services were inspected: the images running on 2026-10-30. Any image deployed later is covered
only by the artifact check in §5. The six host-built jars have the same library set (same jar names and versions) and
the same results as the matching `fdce6ba0` pin images.

## 1. CVE-2026-47884 — "Spring Framework Improper Path Limitation in XsltView"

| Field | Value |
|---|---|
| Vendor advisory | <https://spring.io/security/cve-2026-47884>, MEDIUM, CVSS 3.1 `AV:N/AC:L/PR:N/UI:N/S:C/C:L/I:N/A:N` = 5.8, CWE-22/CWE-918 |
| Vendor condition (verbatim) | "Use of XsltView in a Spring MVC application can result in SSRF and RCE attack if the application has an "/**" mapping that results in view rendering, and where the view name is not explicitly specified." |
| Fix | gh-37029, commit `692dbc91`: `XsltView.getStylesheetSource()` normalizes and checks the stylesheet location. 7.0.9 OSS; 6.2.20 Enterprise Support only |
| GHSA / NVD | GHSA-pc63-qcmh-9cmg, critical 9.8 (CISA-ADP vector), package spring-webmvc, no patched 6.2.x version |
| Images | spring-webmvc 6.2.19 in the nine servlet images; not in the gateway |
| Applicability | **Not applicable (static, exact artifacts).** No class or resource outside spring-webmvc references or names the XsltView package in any inspected image. The application classes reference no View, ViewResolver, ViewControllerRegistry or UrlFilenameViewController. No `@Controller` (non-REST) exists. No template engine is on the classpath. The only shipped .xsl is spring-security.xsl in auth-service's spring-security-config jar, which is not a view. Spring Boot does not auto-configure XsltView |

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

## 4. Residual uncertainty (accepted by the owner)

1. The analysis is static. No application context was started, so there is no bean-graph proof. A class created
   reflectively from a name built at runtime would not show; no such name appears in any jar.
2. The checks cover the images running on 2026-10-30 and the `fdce6ba0` pins. A later image is covered only by the
   artifact check in §5.
3. The GHSA/NVD severity (CISA-ADP 9.8) still disagrees with the vendor. The scanner keeps reporting CRITICAL until the
   jars change.

On 2026-10-29 the six host-built production images had not been inspected. That gap closed on 2026-10-30 (see the table
above), and the result did not change.

## 5. Invalidation triggers

Any trigger below stops the exception for the CVEs it concerns. In the change that causes it, remove the affected entry
(the gates then report the finding) and ask the owner for a new decision in #513 or its successor. Do not widen the
entries or move their dates instead.

| # | Trigger | How it is detected | Action |
|---|---|---|---|
| 1 | Any SSE or streaming handler, `ModelAndView`/`View`/fragment rendering, a non-REST `@Controller`, XsltView or a ViewResolver | Check A below on every change to `services/*/src/main`, in review | Remove the entries for CVE-2026-47884 and CVE-2026-47890 |
| 2 | A template engine dependency, an .xsl/.xslt resource, or view settings | Checks B, C and D in review of build files and resources | Remove the entries for CVE-2026-47884 and CVE-2026-47890 |
| 3 | A WebFlux.fn or WebMvc.fn `RouterFunction`/`RequestPredicates`, `toHttpHandler`/`toWebHandler`, or a custom `HttpHandler`/`WebHandler` | Check E in review | Remove the entry for CVE-2026-47892 |
| 4 | A `spring.main.web-application-type` setting, or a move to Spring Cloud Gateway Server MVC | Check F in review | Remove all three entries |
| 5 | spring-webflux in a servlet image, or spring-webmvc or a servlet container in the gateway | The artifact check (`BOOT-INF/lib` listing) and the unfiltered Security CI report, which names the image of each finding | Remove all three entries |
| 6 | A new Java service or image | Every candidate build. The artifact check runs on its `/app/app.jar` before publication, with the positive control | A failed check is trigger 10 |
| 7 | Any Spring Framework, Spring Boot or Spring Cloud Gateway change | A version change makes the version-pinned path stop matching, so the scanner reports the new jar on its own. Review of `gradle/libs.versions.toml` and `gradle.properties` catches a Boot or Gateway change on the same Framework version | Drop the entries in the change that reaches Framework 7.0.9 or later (#515) |
| 8 | Actuator web exposure beyond `health,info,prometheus`, or adding `resilience4j-reactor` | Check G in review; the read-only runtime probe for production | Remove the entry for CVE-2026-47890 |
| 9 | Advisory updates: a new spring.io history entry, changed GHSA package ranges, new NVD or CISA-ADP data, or an exploitation report | Re-fetch the three spring.io advisories, their GHSA records and NVD records and compare them with the 2026-10-30 copies. Do this on 2026-11-06, 11-13, 11-20 and 11-27, and before any change to these entries | Stop and re-decide if a condition, range or exploitation status changed |
| 10 | A failed artifact check on a candidate or deployed image | The artifact check (§ Verification), run by whoever builds or deploys the image | Remove the entries that the finding concerns; do not publish or deploy the image under this exception |
| 11 | The expiry, 2026-11-29 | Automatic: from 2026-11-29 00:00 UTC Trivy reports the findings again and the gates fail | No automatic renewal |

Checks for review. Each must print nothing. Run them from the repository root:

```sh
# A: SSE, streaming, views, non-REST controllers
git grep -nE 'SseEmitter|ResponseBodyEmitter|StreamingResponseBody|ServerSentEvent|FragmentsRendering|ModelAndView|XsltView|ViewResolver|text/event-stream|org\.springframework\.web\.(servlet|reactive\.result)\.view|@Controller\b' -- 'services/*/src/main'
# B: template engines
git grep -nEi 'thymeleaf|freemarker|mustache|groovy-templates|gg\.jte|pebble' -- 'services/*/build.gradle.kts' gradle/libs.versions.toml buildSrc
# C: XSL resources
git ls-files services | grep -Ei '\.xslt?$'
# D: view and template settings
git grep -nE 'spring\.(mvc|webflux)\.view|spring\.(thymeleaf|freemarker|mustache)' -- 'services/*/src/main/resources'
# E: functional endpoints and custom handlers
git grep -nE 'RouterFunction|RequestPredicates|toHttpHandler|toWebHandler|implements (HttpHandler|WebHandler)\b' -- 'services/*/src/main'
# F: web application type and Gateway Server MVC
git grep -nE 'web-application-type|WEB_APPLICATION_TYPE|webApplicationType|gateway-server-mvc|server-webmvc|gateway-mvc' -- 'services/*/src/main/resources' 'services/*/build.gradle.kts' docker gradle buildSrc
# G: resilience4j-reactor (exposure is checked separately: every include line must be health,info,prometheus)
git grep -nE 'resilience4j-reactor' -- 'services/*/build.gradle.kts' gradle/libs.versions.toml
```

On 2026-10-30 every check printed nothing at api `399b5dd7`, at `fdce6ba0` and at every production revision. Check F
leaves out `services/*/src/main/java` on purpose: auth-service's recovery-replay command reads
`spring.main.web-application-type` only to refuse to run with a web server.

## 6. What this does not do

- No new CVE, path or pattern.
- No threshold, scanner version or workflow change.
- No change to the unfiltered report step.
- No change to CVE-2026-14257.
- No image build, publication or deployment.
- It does not remediate the CVEs and does not cover any other advisory. The remediation is #515.
- master carries the same three entries. They are renewed there only through a separate, scoped master PR with the same
  ids, paths, expiry and record.
