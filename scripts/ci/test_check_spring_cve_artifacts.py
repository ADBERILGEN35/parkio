#!/usr/bin/env python3
"""Unit tests for scripts/ci/check_spring_cve_artifacts.py (#513). Fixtures are synthetic fat jars whose
"class files" carry the referenced names as bytes, which is what the check reads."""
from __future__ import annotations

import io
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_spring_cve_artifacts as check  # noqa: E402

WEBMVC_CONTROL = {
    "org/springframework/web/servlet/view/xslt/XsltView.class": b"org/springframework/web/servlet/view/xslt/XsltView",
    "org/springframework/web/servlet/view/FragmentsRendering.class": b"org/springframework/web/servlet/view/FragmentsRendering",
    "org/springframework/web/servlet/mvc/method/annotation/SseEmitter.class":
        b"org/springframework/web/servlet/mvc/method/annotation/SseEmitter",
}
WEBFLUX_CONTROL = {
    "org/springframework/web/reactive/result/view/Fragment.class": b"org/springframework/web/reactive/result/view/Fragment",
    "org/springframework/web/reactive/function/server/RouterFunctions.class":
        b"org/springframework/web/reactive/function/server/RouterFunction toHttpHandler toWebHandler",
}
SPRING_WEB = {"org/springframework/http/MediaType.class": b"text/event-stream org/springframework/http/codec/ServerSentEvent"}
REST_CONTROLLER = b"Lorg/springframework/web/bind/annotation/RestController;"


def nested(entries: dict[str, bytes]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as jar:
        for name, data in entries.items():
            jar.writestr(name, data)
    return buffer.getvalue()


class ArtifactCheck(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def fat_jar(self, app: dict[str, bytes], libs: dict[str, dict[str, bytes]]) -> str:
        path = Path(self.tmp.name) / f"app-{len(list(Path(self.tmp.name).iterdir()))}.jar"
        with zipfile.ZipFile(path, "w") as jar:
            for name, data in app.items():
                jar.writestr(name, data)
            for name, entries in libs.items():
                jar.writestr(check.LIB + name, nested(entries))
        return str(path)

    def servlet(self, app=None, libs=None, webmvc=None) -> str:
        base = {"spring-webmvc-6.2.19.jar": WEBMVC_CONTROL if webmvc is None else webmvc,
                "spring-web-6.2.19.jar": SPRING_WEB, "tomcat-embed-core-10.1.59.jar": {"org/apache/T.class": b"x"}}
        base.update(libs or {})
        return self.fat_jar({"BOOT-INF/classes/com/parkio/Api.class": REST_CONTROLLER, **(app or {})}, base)

    def gateway(self, app=None, libs=None) -> str:
        base = {"spring-webflux-6.2.19.jar": WEBFLUX_CONTROL, "spring-web-6.2.19.jar": SPRING_WEB,
                "reactor-netty-http-1.2.18.jar": {"reactor/netty/http/H.class": b"text/event-stream"}}
        base.update(libs or {})
        return self.fat_jar({"BOOT-INF/classes/com/parkio/gateway/Client.class":
                             b"org/springframework/web/reactive/function/client/WebClient", **(app or {})}, base)

    def rules(self, path: str, role: str) -> list[str]:
        findings, control = check.check_jar(path, role)
        self.assertEqual(control, [])
        return [f.split(": ")[1] for f in findings]

    def test_clean_jars_pass(self):
        self.assertEqual(self.rules(self.servlet(), "servlet"), [])
        self.assertEqual(self.rules(self.gateway(), "gateway"), [])
        self.assertEqual(check.main(["--role", "servlet", self.servlet()]), 0)

    def test_application_constructs_are_findings(self):
        cases = {
            "sse": b"org/springframework/web/servlet/mvc/method/annotation/SseEmitter",
            "xslt-view": b"org/springframework/web/servlet/view/xslt/XsltViewResolver",
            "servlet-view": b"org/springframework/web/servlet/ViewResolver",
            "model-and-view": b"org/springframework/web/servlet/ModelAndView",
            "non-rest-controller": b"Lorg/springframework/stereotype/Controller;",
            "view-controller": b"ViewControllerRegistry",
            "fragments": b"FragmentsRendering",
        }
        for rule, needle in cases.items():
            path = self.servlet(app={"BOOT-INF/classes/com/parkio/Web.class": needle})
            self.assertIn(rule, self.rules(path, "servlet"), rule)
        for needle in (b"org/springframework/web/reactive/function/server/RouterFunction", b"toHttpHandler",
                       b"org/springframework/http/server/reactive/HttpHandler"):
            path = self.gateway(app={"BOOT-INF/classes/com/parkio/gateway/Fn.class": needle})
            self.assertTrue(self.rules(path, "gateway"), needle)
        self.assertEqual(check.main(["--role", "servlet",
                                     self.servlet(app={"BOOT-INF/classes/X.class": b"text/event-stream"})]), 1)

    def test_web_application_type_counts_in_resources_not_classes(self):
        resource = self.servlet(app={"BOOT-INF/classes/application.yml": b"spring:\n  main:\n    web-application-type: reactive\n"})
        self.assertIn("web-application-type", self.rules(resource, "servlet"))
        guard = self.servlet(app={"BOOT-INF/classes/com/parkio/Guard.class": b"spring.main.web-application-type"})
        self.assertEqual(self.rules(guard, "servlet"), [])

    def test_library_needles_need_an_allowed_jar(self):
        unknown = self.servlet(libs={"new-streaming-lib-1.0.jar": {"n/S.class": b"text/event-stream"}})
        self.assertEqual(self.rules(unknown, "servlet"), ["event-stream"])
        xslt = self.servlet(libs={"spring-boot-3.5.15.jar": {"b/X.class": b"org.springframework.web.servlet.view.xslt.XsltView"}})
        self.assertEqual(self.rules(xslt, "servlet"), ["xslt-view"])
        allowed = self.servlet(libs={"spring-boot-autoconfigure-3.5.15.jar": {
            "a/R.class": b"org/springframework/web/reactive/function/server/RouterFunction",
            "META-INF/additional-spring-configuration-metadata.json": b"text/event-stream"}})
        self.assertEqual(self.rules(allowed, "servlet"), [])

    def test_roles_template_engines_and_xsl(self):
        self.assertIn("role", self.rules(self.servlet(libs={"spring-webflux-6.2.19.jar": WEBFLUX_CONTROL}), "servlet"))
        self.assertIn("role", self.rules(self.gateway(libs={"tomcat-embed-core-10.1.59.jar": {"T.class": b"x"}}), "gateway"))
        self.assertIn("role", self.rules(self.gateway(libs={"spring-webmvc-6.2.19.jar": WEBMVC_CONTROL}), "gateway"))
        self.assertIn("template-engine", self.rules(self.servlet(libs={"thymeleaf-3.1.3.jar": {"t/T.class": b"x"}}), "servlet"))
        self.assertIn("xsl", self.rules(self.servlet(app={"BOOT-INF/classes/report.xslt": b"<xsl/>"}), "servlet"))
        security = {"org/springframework/security/config/spring-security.xsl": b"<xsl/>"}
        self.assertEqual(self.rules(self.servlet(libs={"spring-security-config-6.5.11.jar": security}), "servlet"), [])
        self.assertIn("xsl", self.rules(self.servlet(libs={"other-1.0.jar": {"x/report.xsl": b"<xsl/>"}}), "servlet"))

    def test_a_blind_scan_fails_the_control(self):
        blind = self.servlet(webmvc={"org/springframework/web/servlet/DispatcherServlet.class": b"x"})
        findings, control = check.check_jar(blind, "servlet")
        self.assertTrue(control)
        self.assertEqual(check.main(["--role", "servlet", blind]), 2)


if __name__ == "__main__":
    unittest.main()
