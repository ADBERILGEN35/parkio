# Security CI disposition — 2026-10-08 (new CRITICAL image findings against the accepted release candidate)

Owner decision 2026-10-08 (option A, verbatim scope): "If independent review confirms the vulnerable configurations are
absent, I authorize narrowly scoped exceptions for these two CVEs, expiring 2026-11-07, with owner, evidence and re-evaluation
triggers recorded. Do not extend the existing CVE exception. Apply through reviewed PRs on api/master. Keep all other security
gates unchanged and re-scan the exact accepted candidate with the current vulnerability database."

Trigger: the Trivy vulnerability database (scanner `aquasec/trivy:0.64.1`, unchanged) started reporting two CRITICAL findings
for Spring Framework 6.2.19 (Spring Boot 3.5.15). GHSA reviewed both on 2026-10-07T13:20Z; the CI caches the database per day.
The last green api container scans were Security CI run 37792481650 (push on api 6a7f263c, container jobs 14:26–14:30Z). The
Security CI run 37822536125 of PR #325 (18:12–18:21Z; images built from the master merge checkout) failed the critical gate on
every Java image. The accepted release candidate (Candidate images run 37788374271, source
`843ae7cb461bbb66e688964bf2441b4442d23f27`, scans 13:59–14:01Z, completed 14:07Z) predates that database content and carries
CVE-2026-47890 in every Java image and CVE-2026-47892 in the gateway image.

## Gate thresholds (unchanged by this change)

| Job | Trivy severity gate | `--ignore-unfixed` | Ignore file |
|---|---|---|---|
| Dependency vulnerability scan (`frontend/pnpm-lock.yaml`) | `HIGH,CRITICAL` | yes | `.trivyignore.yaml` |
| Container scan: critical image gate | `CRITICAL` | yes | `.trivyignore.yaml` |
| Container scan: library gate (api only) | `HIGH,CRITICAL`, `--pkg-types library` | yes | `.trivyignore.yaml` |
| Container scan: report step (artifact only) | `HIGH,CRITICAL`, exit 0 | yes | none: the report stays unfiltered |

`.trivyignore.yaml` gains two entries, and the platform build gains one lz4-java dependency constraint (§5). No workflow,
threshold or scanner version changes. The existing CVE-2026-47884 entry is not modified or extended and keeps its own expiry
(2026-11-05).

## Verification

An independent verifier (not the author of this change) checked both CVEs against the official advisories and the exact
candidate artifacts: the eleven image archives of run 37788374271, each verified against its SHA256SUMS, with `app/app.jar`
extracted from the top layer that contains it, plus the packaged configuration and the runtime configuration at `843ae7cb`
(Compose files and env examples). It used structural bytecode analysis (constant pools, annotations, method references) with a
`javap` cross-check and a positive control, Spring Boot's web-application-type deduction on the exact gateway classpath, and a
Trivy 0.64.1 re-scan of the archives. **All 20 CVE × image combinations are NOT APPLICABLE; none is "cannot rule out".**
The analysis is static: no service was started and no live host was inspected.

## 1. CVE-2026-47890 — "Spring Framework Server Sent Event stream corruption while rendering fragments"

| Field | Value |
|---|---|
| Vendor advisory | <https://spring.io/security/cve-2026-47890>, published 2026-08-20, severity **LOW**, CVSS 3.1 `AV:N/AC:H/PR:L/UI:R/S:U/C:N/I:L/A:N` = 2.6, CWE-93 |
| Vendor condition (verbatim) | "an application can be vulnerable when all the following are true: the application is using Spring MVC or Spring WebFlux; the application is sending view fragments to clients over Server Sent Events (SSE); the attacker must have control over data that will be streamed to other users of the application" |
| Affected (vendor) | 7.0.0 – 7.0.8; 6.2.0 – 6.2.19 (no older lines) |
| Fixed (vendor) | **7.0.9 (OSS)**; 7.0.8.1 and 6.2.20 Enterprise Support only. "No further mitigation steps are necessary." |
| GitHub advisory / NVD | GHSA-j9f9-w8pj-32f8, critical, CVSS 3.1 9.8 `AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H` copied from CISA-ADP; packages spring-webmvc and spring-webflux; no patched 6.2.x version. NVD: Analyzed; the only metric is CISA-ADP (Secondary) 9.8 |
| Scanner | Trivy 0.64.1: **CRITICAL** (`SeveritySource: ghsa`; VendorSeverity ghsa CRITICAL, redhat MEDIUM 6.5), fixed 7.0.9, PkgPath `app/app.jar/BOOT-INF/lib/spring-webmvc-6.2.19.jar` (nine servlet services) and `app/app.jar/BOOT-INF/lib/spring-webflux-6.2.19.jar` (gateway) |
| Severity discrepancy | Spring LOW 2.6 vs Trivy CRITICAL. The 9.8 is CISA-ADP's automated enrichment of a record whose CNA (VMware) gave no score: an unauthenticated, low-complexity attack with total C/I/A impact. It encodes none of the vendor preconditions (SSE plus view fragments, attacker data streamed to other users, integrity-only impact) that Spring's vector carries in `AC:H`, `PR:L`, `UI:R` and `C:N/I:L/A:N` |
| Parkio applicability | **Not applicable** in all ten Java images. spring-webmvc / spring-webflux 6.2.19 is present (the vulnerable code is present but unreachable). The application classes reference no SseEmitter, ResponseBodyEmitter, StreamingResponseBody, ServerSentEvent, FragmentsRendering, Fragment, Rendering, ModelAndView, View, ViewResolver or `text/event-stream`. Every application controller is a `@RestController`. No template engine is on any classpath, and no application-defined View or ViewResolver exists. Library defaults (Spring Boot's error view, springdoc's welcome controllers) render no fragments and no SSE. The Resilience4j SSE endpoints are not registered (no reactor adapter) and not exposed |
| Exception scope | `.trivyignore.yaml`: id CVE-2026-47890, paths `app/app.jar/BOOT-INF/lib/spring-webmvc-6.2.19.jar` and `app/app.jar/BOOT-INF/lib/spring-webflux-6.2.19.jar` (exactly the scanner's PkgPaths; the file name pins the version), `expired_at: 2026-11-07` (exclusive: suppressed through 2026-11-06 UTC) |
| Owner / decision | repository owner, 2026-10-08, option A conditional on the independent verification above |

## 2. CVE-2026-47892 — "Spring Framework Header Predicate Bypass in WebFlux Functional Endpoints"

| Field | Value |
|---|---|
| Vendor advisory | <https://spring.io/security/cve-2026-47892>, published 2026-08-20, severity **MEDIUM**, CVSS 3.1 `AV:N/AC:H/PR:N/UI:N/S:U/C:L/I:L/A:N` = 4.8, CWE-863 |
| Vendor condition (verbatim) | "A WebFlux application using functional endpoints and deployed with DispatcherServlet may be vulnerable to a header predicate bypass in a pre-flight request." The upstream fix (gh-37024) concerns functional endpoints served without DispatcherHandler (`RouterFunctions.toHttpHandler` / `toWebHandler`) |
| Affected (vendor) | 7.0.0 – 7.0.8; 6.2.0 – 6.2.19; 6.1.0 – 6.1.28; 6.0.0 – 6.0.30; 5.3.0 – 5.3.49; 5.2.5.RELEASE – 5.2.25.RELEASE |
| Fixed (vendor) | **7.0.9 (OSS)**; 7.0.8.1, 6.2.20, 6.1.29, 6.0.31, 5.3.50, 5.2.26 Enterprise Support only |
| GitHub advisory / NVD | GHSA-9qf2-26p9-2q2q, critical, CVSS 3.1 9.8 (the same CISA-ADP vector); package spring-webflux only; NVD: Analyzed; CISA-ADP 9.8 only |
| Scanner | Trivy 0.64.1: **CRITICAL** (`SeveritySource: ghsa`; VendorSeverity ghsa CRITICAL, redhat MEDIUM 6.5), fixed 7.0.9, PkgPath `app/app.jar/BOOT-INF/lib/spring-webflux-6.2.19.jar` (gateway only; the servlet images carry no spring-webflux) |
| Severity discrepancy | Spring MEDIUM 4.8 vs Trivy CRITICAL: the same CISA-ADP 9.8 generic vector, which ignores the deployment precondition (`AC:H`) and the bypass limited to a pre-flight request (`C:L/I:L/A:N`) |
| Parkio applicability | **Not applicable.** Absent from the nine servlet images. The gateway is Spring Cloud Gateway on reactor-netty: Spring Boot deduces REACTIVE; no DispatcherServlet, servlet container or spring-webmvc on its classpath; no WebFlux functional endpoints (RouterFunction / RequestPredicates); nothing deploys a RouterFunction through `toHttpHandler` / `toWebHandler`; requests are dispatched by DispatcherHandler; CORS pre-flight is handled by `CorsWebFilter` |
| Exception scope | `.trivyignore.yaml`: id CVE-2026-47892, path `app/app.jar/BOOT-INF/lib/spring-webflux-6.2.19.jar`, `expired_at: 2026-11-07` |
| Owner / decision | repository owner, 2026-10-08, option A conditional on the independent verification above |

## 3. Re-scan of the exact accepted candidate with the current database

The eleven image archives of run 37788374271 (SHA256SUMS verified) were re-scanned with `aquasec/trivy:0.64.1`, vulnerability DB
UpdatedAt 2026-10-08T15:33:46Z and Java DB 16:14:37Z, with Security CI's flags:

| Ignore file | Critical gate (`CRITICAL`, all packages) | Library gate (`HIGH,CRITICAL`, libraries; api only) |
|---|---|---|
| api's current file (CVE-2026-14257, CVE-2026-47884) | **fails** on all ten Java images: CVE-2026-47890, plus CVE-2026-47892 on the gateway; web passes | — |
| with the two entries of this change | **passes** on all eleven images; `--show-suppressed` lists exactly CVE-2026-47884 + CVE-2026-47890 (nine servlet images) and CVE-2026-47890 + CVE-2026-47892 (gateway), nothing else | **fails** on all ten Java images: CVE-2026-106451 (see §5); web passes |

## 4. Re-evaluation triggers (either entry is re-checked or dropped when one occurs)

1. Any handler that returns `SseEmitter`, `ResponseBodyEmitter`, `StreamingResponseBody`, a `Flux` / `Publisher`, `ServerSentEvent`, or any other `text/event-stream` producer.
2. Any `ModelAndView`, `View`, `FragmentsRendering` (MVC or WebFlux), `Fragment` or `Rendering` usage, or any non-REST `@Controller`.
3. Any template engine dependency (Thymeleaf, FreeMarker, Mustache, Groovy templates, JTE, Pebble templates, JSP/Jasper, …).
4. Any application `View` or `ViewResolver` bean, or any `spring.mvc.view.*`, `spring.thymeleaf.*`, `spring.freemarker.*` or `spring.webflux.*` setting.
5. Any WebFlux.fn or WebMvc.fn `RouterFunction` / `RequestPredicates` use; any `RouterFunctions.toHttpHandler` / `toWebHandler` call; any custom `HttpHandler` / `WebHandler` bean; any `spring.main.web-application-type` change.
6. spring-webflux appearing in a servlet image (for example for WebClient): it would be silently covered by the spring-webflux path.
7. spring-webmvc or a servlet container in the gateway, or a move to Spring Cloud Gateway Server MVC (built on WebMvc.fn and DispatcherServlet).
8. A new Java service or image: this shared file covers every image with the same PkgPath.
9. Any Spring Framework, Spring Boot or Spring Cloud Gateway change. Drop both entries at Spring Framework 7.0.9 or later, or when an open-source 6.2.x fix ships.
10. Actuator web-exposure changes that add SSE-capable endpoints, or adding `resilience4j-reactor` (registers the Resilience4j SSE stream endpoints).
11. Advisory updates: new spring.io history entries, changed GHSA package lists, new NVD or CISA-ADP data.
12. The expiry, 2026-11-07.

## 5. CVE-2026-106451 (lz4-java, HIGH): remediated at source by upgrade to 1.11.4 (commit `df0f7ef9`), no exception

With the current database the api library gate also reported CVE-2026-106451 / GHSA-mcr4-qmvw-px4g in all ten Java images of the
accepted candidate: `at.yawk.lz4:lz4-java` 1.10.1 (via kafka-clients 3.9.2), PkgPath `app/app.jar/BOOT-INF/lib/lz4-java-1.10.1.jar`,
HIGH (GHSA CVSS 4.0 7.3 `AV:L/AC:H/AT:P/PR:L/…`; Red Hat 7.0), fixed in 1.11.4 — a local temporary-file race in `Native.load()`
that lets another local user with access to the same shared temporary directory replace the extracted JNI library. The same
1.10.1 also carries five lower-severity CVEs (106450, 106452, 106453, 59949, 106449), all fixed in 1.11.4.

Owner decision 2026-10-08 (option 1): upgrade with the smallest Gradle change, no exception. `platform/parkio-platform/build.gradle.kts`
constrains `at.yawk.lz4:lz4-java` to 1.11.4; every service receives kafka-clients through this platform module, and no BOM
manages the artifact. Verification: `dependencyInsight` selects 1.11.4 (1.10.1 → 1.11.4) for all ten services; each boot jar's
`BOOT-INF/lib` differs from the accepted candidate image's only by `lz4-java-1.10.1.jar` → `lz4-java-1.11.4.jar`;
`Lz4CompressionCompatibilityTest` proves the resolved artifact is 1.11.4, Kafka LZ4 record batches round-trip, and the native
LZ4/xxHash implementations load and agree with the Java ones on linux/amd64. With this change the critical and library gates
pass on all images.

Consequence: the images of run 37788374271 still contain `lz4-java-1.10.1.jar` in all ten Java services, so they are not the
release artifact for those services. A new candidate is built and accepted from a source that contains `df0f7ef9`.

## 6. What this does not do

No exception for CVE-2026-106451 (it is fixed by the upgrade); the ten Java images are rebuilt in a new candidate after
this change; the web image of run 37788374271 is reused only if its build inputs are verified unchanged and its original
provenance is kept; no gate threshold, scanner version or workflow changes;
the CVE-2026-47884 entry is untouched; the findings remain in the unfiltered report step; the exceptions end on 2026-11-07
unless the owner re-decides with new evidence.
