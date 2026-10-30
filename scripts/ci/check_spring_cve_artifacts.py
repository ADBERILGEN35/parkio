#!/usr/bin/env python3
"""Artifact check for the three Spring Framework exceptions in .trivyignore.yaml (#513).

The exceptions for CVE-2026-47884, CVE-2026-47890 and CVE-2026-47892 hold only while no image uses
XsltView, view or fragment rendering, Server-Sent Events, or WebFlux functional endpoints served without
DispatcherHandler. This check reads one Spring Boot fat jar (`/app/app.jar` copied out of an image that is
never started) and fails when the jar contains such a construct. Record:
docs/operations/security-findings-2026-10-30.md, invalidation triggers 5, 6 and 10. CI does not run it;
run it on every Java image before it is published or deployed while the exceptions exist.

  1. Libraries: a servlet image has spring-webmvc and no spring-webflux; the gateway has spring-webflux and
     no spring-webmvc and no servlet container. No image has a template engine.
  2. Application entries (everything outside BOOT-INF/lib: application classes and resources, and the
     loader) reference none of APP_CONSTRUCTS; application resources also none of APP_RESOURCE_CONSTRUCTS.
  3. Library entries (every nested jar except spring-webmvc and spring-webflux, classes and resources)
     reference LIB_NEEDLES only where LIB_ALLOWED lists the jar. Those are Spring Boot, Spring Web, Spring
     Security, springdoc, Netty and Resilience4j classes that were reviewed for #513.
  4. The only .xsl/.xslt file is spring-security.xsl in spring-security-config.
  5. Positive control: the same needles must be found inside spring-webmvc or spring-webflux itself, or the
     scan would be blind (exit 2).

Usage: check_spring_cve_artifacts.py --role servlet|gateway <app.jar> [...]
Exit 0: no finding. Exit 1: findings, listed as `<jar>: <rule>: <entry>`. Exit 2: unreadable jar or failed
control.
"""
from __future__ import annotations

import argparse
import io
import re
import sys
import zipfile

LIB = "BOOT-INF/lib/"
WEBMVC = re.compile(r"^spring-webmvc-\d[^/]*\.jar$")
WEBFLUX = re.compile(r"^spring-webflux-\d[^/]*\.jar$")
SERVLET_CONTAINER = re.compile(r"^(tomcat-embed-core|jetty-server|jetty-ee\d*-servlet|undertow-core)-\d[^/]*\.jar$")
TEMPLATE_ENGINE = re.compile(
    r"^(thymeleaf|freemarker|mustache|jmustache|groovy-templates|jte|pebble|velocity|handlebars|jstl"
    r"|jakarta\.servlet\.jsp|tomcat-embed-jasper)[^/]*\.jar$", re.I)
ALLOWED_XSL = re.compile(r"^spring-security-config-\d[^/]*\.jar!org/springframework/security/config/spring-security\.xsl$")

# Application entries: any of these is a finding.
APP_CONSTRUCTS = {
    "xslt-view": rb"org/springframework/web/servlet/view/xslt/|org\.springframework\.web\.servlet\.view\.xslt|XsltView",
    "servlet-view": rb"org/springframework/web/servlet/(View;|ViewResolver|view/)|org\.springframework\.web\.servlet\.view\.",
    "view-controller": rb"ViewControllerRegistry|UrlFilenameViewController|ParameterizableViewController",
    "model-and-view": rb"org/springframework/web/servlet/ModelAndView",
    "non-rest-controller": rb"Lorg/springframework/stereotype/Controller;",
    "fragments": rb"FragmentsRendering|org/springframework/web/reactive/result/view/|org\.springframework\.web\.reactive\.result\.view\.",
    "sse": rb"SseEmitter|ResponseBodyEmitter|StreamingResponseBody|ServerSentEvent|text/event-stream|TEXT_EVENT_STREAM",
    "functional-endpoint": rb"org/springframework/web/reactive/function/server/|org/springframework/web/servlet/function/"
                           rb"|RequestPredicates|toHttpHandler|toWebHandler|RouterFunctionWebHandler",
    "custom-handler": rb"org/springframework/http/server/reactive/HttpHandler|org/springframework/web/server/WebHandler;",
}
# Application resources (not classes): the web application type must not be set. auth-service's recovery
# command reads the property only to refuse a web server, so classes are covered by the source check instead.
APP_RESOURCE_CONSTRUCTS = {
    "web-application-type": rb"web-application-type|WEB_APPLICATION_TYPE|webApplicationType",
}

# Library entries: these needles are allowed only in the jars LIB_ALLOWED names.
LIB_NEEDLES = {
    "xslt-view": rb"org/springframework/web/servlet/view/xslt/|org\.springframework\.web\.servlet\.view\.xslt|XsltView",
    "mvc-fragments": rb"org/springframework/web/servlet/view/FragmentsRendering|org\.springframework\.web\.servlet\.view\.FragmentsRendering",
    "flux-fragment": rb"org/springframework/web/reactive/result/view/Fragment|org\.springframework\.web\.reactive\.result\.view\.Fragment",
    "sse-emitter": rb"org/springframework/web/servlet/mvc/method/annotation/(SseEmitter|ResponseBodyEmitter)",
    "server-sent-event": rb"org/springframework/http/codec/ServerSentEvent",
    "model-and-view": rb"org/springframework/web/servlet/ModelAndView",
    "event-stream": rb"text/event-stream",
    "router-function": rb"org/springframework/web/reactive/function/server/(RouterFunction|RequestPredicates)",
    "functional-handler": rb"toHttpHandler|toWebHandler|RouterFunctionWebHandler",
}
LIB_ALLOWED = {
    "model-and-view": r"(spring-boot|spring-boot-autoconfigure|spring-boot-actuator-autoconfigure|spring-security-config"
                      r"|springdoc-openapi-starter-webmvc-api)-\d",
    "server-sent-event": r"(spring-web|resilience4j-spring-boot3)-\d",
    "event-stream": r"(spring-web|spring-boot-autoconfigure|netty-codec-http|reactor-netty-http|resilience4j-spring-boot3)-\d",
    "router-function": r"(spring-boot-autoconfigure|spring-boot-actuator)-\d",
}

# The positive control: each of these needles must be found in the framework jar itself.
CONTROL = {
    "servlet": ("spring-webmvc", ["xslt-view", "mvc-fragments", "sse-emitter"]),
    "gateway": ("spring-webflux", ["flux-fragment", "router-function", "functional-handler"]),
}

APP_PATTERNS = {name: re.compile(pattern) for name, pattern in APP_CONSTRUCTS.items()}
APP_RESOURCE_PATTERNS = {name: re.compile(pattern) for name, pattern in APP_RESOURCE_CONSTRUCTS.items()}
LIB_PATTERNS = {name: re.compile(pattern) for name, pattern in LIB_NEEDLES.items()}


def files(jar: zipfile.ZipFile):
    return [name for name in jar.namelist() if not name.endswith("/")]


def check_jar(path: str, role: str) -> tuple[list[str], list[str]]:
    """Return (findings, control_failures) for one fat jar."""
    findings: list[str] = []
    outer = zipfile.ZipFile(path)
    libs = sorted(n for n in files(outer) if n.startswith(LIB) and n.endswith(".jar"))
    short_names = [n[len(LIB):] for n in libs]

    webmvc = [n for n in short_names if WEBMVC.match(n)]
    webflux = [n for n in short_names if WEBFLUX.match(n)]
    containers = [n for n in short_names if SERVLET_CONTAINER.match(n)]
    if role == "servlet":
        if len(webmvc) != 1:
            findings.append(f"libraries: role: expected one spring-webmvc jar, found {webmvc}")
        if webflux:
            findings.append(f"libraries: role: spring-webflux in a servlet image: {webflux}")
    else:
        if len(webflux) != 1:
            findings.append(f"libraries: role: expected one spring-webflux jar, found {webflux}")
        if webmvc:
            findings.append(f"libraries: role: spring-webmvc in the gateway: {webmvc}")
        if containers:
            findings.append(f"libraries: role: servlet container in the gateway: {containers}")
    findings += [f"libraries: template-engine: {name}" for name in short_names if TEMPLATE_ENGINE.match(name)]

    for name in files(outer):
        if name.startswith(LIB) and name.endswith(".jar"):
            continue
        if name.lower().endswith((".xsl", ".xslt")):
            findings.append(f"app: xsl: {name}")
        data = outer.read(name)
        patterns = APP_PATTERNS if name.endswith(".class") else {**APP_PATTERNS, **APP_RESOURCE_PATTERNS}
        findings += [f"app: {rule}: {name}" for rule, pattern in patterns.items() if pattern.search(data)]

    framework, control_rules = CONTROL[role]
    control_found: set[str] = set()
    for lib, short in zip(libs, short_names):
        inner = zipfile.ZipFile(io.BytesIO(outer.read(lib)))
        own_framework = short.startswith(framework + "-") and bool(WEBMVC.match(short) or WEBFLUX.match(short))
        skip = bool(WEBMVC.match(short) or WEBFLUX.match(short))
        for name in files(inner):
            if name.lower().endswith((".xsl", ".xslt")) and not ALLOWED_XSL.match(f"{short}!{name}"):
                findings.append(f"{short}: xsl: {name}")
            if name.endswith(".jar"):
                continue
            data = inner.read(name)
            hits = [rule for rule, pattern in LIB_PATTERNS.items() if pattern.search(data)]
            if own_framework:
                control_found.update(hits)
            if skip:
                continue
            for rule in hits:
                allowed = LIB_ALLOWED.get(rule)
                if not (allowed and re.match(allowed, short)):
                    findings.append(f"{short}: {rule}: {name}")

    control_failures = []
    present = webmvc if role == "servlet" else webflux
    missing = [rule for rule in control_rules if rule not in control_found]
    if present and missing:
        control_failures.append(f"control: {framework} shows no {', '.join(missing)}; the scan is blind")
    return findings, control_failures


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--role", choices=sorted(CONTROL), required=True)
    parser.add_argument("jars", nargs="+")
    args = parser.parse_args(argv)
    status = 0
    for path in args.jars:
        try:
            findings, control_failures = check_jar(path, args.role)
        except (OSError, zipfile.BadZipFile) as error:
            print(f"{path}: cannot read: {error}")
            return 2
        for line in control_failures + findings:
            print(f"{path}: {line}")
        if control_failures:
            status = 2
        elif findings and status == 0:
            status = 1
        if not findings and not control_failures:
            print(f"{path}: no finding ({args.role})")
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
