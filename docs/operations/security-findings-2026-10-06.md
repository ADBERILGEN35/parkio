# Security CI disposition — 2026-10-06 (scheduled full scan)

**Triaged run:** scheduled Security CI [`37450120538`](https://github.com/ADBERILGEN35/parkio/actions/runs/37450120538)
(dispatched by [`37450111187`](https://github.com/ADBERILGEN35/parkio/actions/runs/37450111187),
cron `23 3 * * *`, api `520acbfd1b8062114340896c81b457a681683841`)

**Owner:** Parkio release engineering

**Scope:** nine of the eleven container scans failed on one CRITICAL finding:
`CVE-2026-47884` in `spring-webmvc` 6.2.19. The other checks in the run passed:
- the gateway and web container scans;
- secret scan, CodeQL and the dependency scan.

The finding is not caused by a code change:
- api `5cf30253`'s container scans passed on 2026-10-05 (run `37378486596`).
- The GHSA record was last updated at 2026-10-05T23:21Z.
- Since 2026-10-06 the scans fail, and Trivy reports a fixed version (7.0.9), which
  `--ignore-unfixed` does not filter out.

## Gate thresholds (for context)

| Job | Trivy severity gate | `--ignore-unfixed` | Ignore file |
|---|---|---|---|
| Dependency vulnerability scan (`frontend/pnpm-lock.yaml`) | `HIGH,CRITICAL` | yes | `.trivyignore.yaml` |
| Container scan: critical image gate | `CRITICAL` | yes | `.trivyignore.yaml` (**since this change**) |
| Container scan: library gate | `HIGH,CRITICAL` | yes | `.trivyignore.yaml` |
| Container scan: report step (artifact only) | `HIGH,CRITICAL`, exit 0 | yes | none: the report stays unfiltered |

**The critical image gate did not pass an ignore file before this change.** Trivy loads only
`.trivyignore` by default, not `.trivyignore.yaml`, so no reviewed exception could reach that
gate. The owner decided on 2026-10-06 to add `--ignorefile .trivyignore.yaml` to that step only.
The severity, `--ignore-unfixed` and `--exit-code 1` are unchanged. The report step still lists
the finding in the uploaded artifact.

## 1. spring-webmvc XsltView path limitation — CRITICAL (scanner) / MEDIUM (vendor) — **narrow suppression with expiry**

| Field | Value |
|---|---|
| Finding | `CVE-2026-47884` / `GHSA-pc63-qcmh-9cmg`, "Spring Framework Improper Path Limitation in XsltView" (CWE-22) |
| Vendor advisory | <https://spring.io/security/cve-2026-47884>, published 2026-08-20, severity **MEDIUM** |
| Vendor condition | "Use of `XsltView` in a Spring MVC application can result in SSRF and RCE attack if the application has an `"/**"` mapping that results in view rendering, and where the view name is not explicitly specified." |
| Vendor mitigation | "Users of affected versions should upgrade to the corresponding fixed version. No further mitigation steps are necessary." |
| Affected (vendor) | 7.0.0 - 7.0.8, 6.2.0 - 6.2.19, 6.1.0 - 6.1.28, 6.0.0 - 6.0.30, 5.3.0 - 5.3.49, 5.2.25.RELEASE and earlier |
| Fixed (vendor) | **7.0.9 (OSS)**. 7.0.8.1, 6.2.20, 6.1.29, 6.0.31, 5.3.50 and 5.2.26 are **Enterprise Support only** |
| GitHub advisory | `GHSA-pc63-qcmh-9cmg`, severity **critical**, CVSS 3.1 `AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H` 9.8. It lists no patched 6.2.x version |
| Scanner | Trivy (`aquasec/trivy:0.64.1`) reports **CRITICAL**, `SeveritySource: ghsa`, vendor severities `ghsa: 4`, `redhat: 4`, fixed version 7.0.9 |
| Severity discrepancy | Spring rates it MEDIUM because exploitation needs `XsltView` plus a view-rendering `"/**"` mapping without an explicit view name. GHSA, Red Hat and Trivy carry the unconditional CVSS 9.8 and rate it CRITICAL. The gate follows the scanner. |
| Classification | **accepted, time-bounded exception**: no OSS fix on the 6.2 line, and the vulnerable view is not used |
| Owner | Parkio release engineering |
| Expiry | **2026-11-05**, encoded as `expired_at` in `.trivyignore.yaml`. The finding fails both container gates again once the date passes. |
| Remediation | migrate to Spring Framework 7 / Spring Boot 4 (a separate decision), or take an OSS 6.2.x fix if one ships |

### Affected artifacts

Trivy 0.64.1 reports the same package path in each of the nine images, built from api `520acbfd`.
The path is inside the Spring Boot fat jar:

| Image | Package | Installed | `PkgPath` |
|---|---|---|---|
| `auth-service` | `org.springframework:spring-webmvc` | 6.2.19 | `app/app.jar/BOOT-INF/lib/spring-webmvc-6.2.19.jar` |
| `user-service` | same | 6.2.19 | same |
| `parking-service` | same | 6.2.19 | same |
| `media-service` | same | 6.2.19 | same |
| `moderation-service` | same | 6.2.19 | same |
| `gamification-service` | same | 6.2.19 | same |
| `notification-service` | same | 6.2.19 | same |
| `analytics-service` | same | 6.2.19 | same |
| `ai-validation-service` | same | 6.2.19 | same |

Not affected:
- `gateway-service` is Spring WebFlux and ships no `spring-webmvc`.
- `web` is nginx with the SPA and has no JVM.

### Why the vulnerable path is not reachable

The vendor condition needs an `XsltView`. None of the nine services configures or uses one,
directly or indirectly. The evidence below is for api `520acbfd`.

- **Source.** Under `services/`, there are no references to any of these:
  - `XsltView`, `AbstractXsltView`, `XsltViewResolver`, or any `ViewResolver` type or bean;
  - `configureViewResolvers`, `addViewController`, `ModelAndView`, `setViewName`, or `org.springframework.web.servlet.view`;
  - `TransformerFactory` or `javax.xml.transform`.
- **Files and configuration.** There are no `.xsl`/`.xslt` files. There are no
  `spring.mvc.view.*`, template-engine or static-path properties in any `application*.yml`; the
  only `template:` keys are `spring.kafka.template`.
- **Controllers.** Every controller is a `@RestController`; there is no `@Controller` class. Handler
  return values go through message converters, not view resolution.
- **Dependencies.** No template engine is declared: no Thymeleaf, FreeMarker, Mustache, Groovy
  templates, JSP/Jasper/JSTL, Xalan or Saxon. The only web additions are
  `spring-boot-starter-web` and `springdoc-openapi-starter-webmvc-ui`.
- **Shipped classpath.** In each image's `app.jar` (117–150 jars in `BOOT-INF/lib`), the only
  class files that reference the `org.springframework.web.servlet.view.xslt` package are inside
  `spring-webmvc-6.2.19.jar` itself. This covers the application classes, `spring-boot-autoconfigure`
  and `springdoc`.
- **Spring Boot 3.5.15** auto-configures `InternalResourceViewResolver`, `BeanNameViewResolver`,
  `ContentNegotiatingViewResolver` and the whitelabel `error` view. It never configures `XsltView`.
- **Swagger UI** (springdoc) does not reference the XSLT view package (see the shipped-classpath
  check above). It is also disabled in every hosted environment: `scripts/preflight-hosted-beta.sh`
  requires `PARKIO_OPENAPI_ENABLED=false`.

### Scope of the suppression

The suppression is one CVE ID, scoped to one package path, with an expiry date.

- **The path pins the version.** It contains the version (`spring-webmvc-6.2.19.jar`), so
  another `spring-webmvc` version, or the same jar anywhere else in an image, is reported again.
- **Other CVEs still report.** A different CVE on the same jar is still reported, because the
  entry matches by ID.
- **No other image is covered.** The path exists only in the nine servlet images. Gateway and
  web have no such path.
- **The existing entry is inert in images.** `CVE-2026-14257` (`frontend/pnpm-lock.yaml`) matches
  nothing in any of the eleven images, so applying the ignore file to the critical image gate
  changes no other result.

## Verification

Every check ran with the pinned scanner, `aquasec/trivy:0.64.1`
(`sha256:a8ca29078522f30393bdb34225e4c0994d38f37083be81a42da3a2a7e1488e9e`). The flags were the
workflow's own, against images built the way the workflow builds them. The results, commands and
outputs are in `agent-tools/parkio-trivyignore-cve-2026-47884/` (SHA256SUMS):

| Check | Result |
|---|---|
| Critical image gate, api flags (no ignore file) | exit 1 on the nine servlet images; exit 0 on gateway and web |
| Critical image gate, this change (`--ignorefile .trivyignore.yaml`) | exit 0 on all eleven images |
| Library gate (unchanged) | exit 0 on all eleven images |
| Report step (unfiltered) | still lists `CVE-2026-47884` for the nine servlet images |
| What the ignore file suppresses (`--show-suppressed`, every severity, fixed and unfixed) | only `CVE-2026-47884` at `app/app.jar/BOOT-INF/lib/spring-webmvc-6.2.19.jar` in the nine servlet images; nothing in gateway or web; `CVE-2026-14257` matches nothing in any image |
| Expiry: the same file with `expired_at: 2026-10-01` | the critical image gate and the library gate exit 1, reporting `CVE-2026-47884` at the approved path |
| Unrelated CRITICAL: `log4j-core-2.14.1.jar` added to an image | the critical image gate exits 1 (`CVE-2021-44228`, `CVE-2021-45046`), with the ignore file in place |
| Same CVE outside the approved path: `spring-webmvc-6.2.19.jar` copied to `/opt/probe` | exits 1, reporting `CVE-2026-47884` at `opt/probe/spring-webmvc-6.2.19.jar` only |

S3 gate 3 stays **open** until a scheduled full scan succeeds on api.
