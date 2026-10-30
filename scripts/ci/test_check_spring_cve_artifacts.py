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
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as jar:
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

    def test_bare_internal_names_and_constants_are_findings(self):
        # A class that implements View or WebHandler stores the bare internal name, not a descriptor.
        view = self.servlet(app={"BOOT-INF/classes/com/parkio/V.class": b"\x01\x00org/springframework/web/servlet/View\x07"})
        self.assertIn("servlet-view", self.rules(view, "servlet"))
        handler = self.gateway(app={"BOOT-INF/classes/com/parkio/gateway/H.class":
                                    b"\x01\x00org/springframework/web/server/WebHandler\x07"})
        self.assertIn("custom-handler", self.rules(handler, "gateway"))
        constant = self.servlet(app={"BOOT-INF/classes/com/parkio/S.class": b"TEXT_EVENT_STREAM"})
        self.assertIn("sse", self.rules(constant, "servlet"))

    def test_unreadable_jars_exit_2(self):
        broken = Path(self.tmp.name) / "broken.jar"
        broken.write_bytes(b"not a zip")
        self.assertEqual(check.main(["--role", "servlet", str(broken)]), 2)
        self.assertEqual(check.main(["--role", "servlet", str(Path(self.tmp.name) / "missing.jar")]), 2)
        # A corrupt deflated entry raises zlib.error, which is neither OSError nor BadZipFile.
        corrupt = Path(self.servlet(app={"BOOT-INF/classes/C.class": b"x" * 4096}))
        with zipfile.ZipFile(corrupt) as jar:
            info = jar.getinfo("BOOT-INF/classes/C.class")
        data = bytearray(corrupt.read_bytes())
        start = info.header_offset + 30 + len(info.filename.encode()) + len(info.extra)
        data[start:start + 8] = b"\xff" * 8
        corrupt.write_bytes(bytes(data))
        self.assertEqual(check.main(["--role", "servlet", str(corrupt)]), 2)

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
        # The autoconfigure allowance covers the metadata file only, not its classes.
        code = self.servlet(libs={"spring-boot-autoconfigure-3.5.15.jar": {"a/S.class": b"text/event-stream"}})
        self.assertEqual(self.rules(code, "servlet"), ["event-stream"])
        for rule, needle, allowed_jar in (("server-sent-event", b"org/springframework/http/codec/ServerSentEvent", "spring-web-6.2.19.jar"),
                                          ("model-and-view", b"org/springframework/web/servlet/ModelAndView", "spring-boot-3.5.15.jar")):
            self.assertEqual(self.rules(self.servlet(libs={"other-lib-1.0.jar": {"o/X.class": needle}}), "servlet"), [rule])
            self.assertEqual(self.rules(self.servlet(libs={allowed_jar: {"o/X.class": needle}}), "servlet"), [])

    def test_roles_template_engines_and_xsl(self):
        self.assertIn("role", self.rules(self.servlet(libs={"spring-webflux-6.2.19.jar": WEBFLUX_CONTROL}), "servlet"))
        self.assertIn("role", self.rules(self.gateway(libs={"tomcat-embed-core-10.1.59.jar": {"T.class": b"x"}}), "gateway"))
        self.assertIn("role", self.rules(self.gateway(libs={"spring-webmvc-6.2.19.jar": WEBMVC_CONTROL}), "gateway"))
        self.assertIn("template-engine", self.rules(self.servlet(libs={"thymeleaf-3.1.3.jar": {"t/T.class": b"x"}}), "servlet"))
        self.assertIn("xsl", self.rules(self.servlet(app={"BOOT-INF/classes/report.xslt": b"<xsl/>"}), "servlet"))
        security = {"org/springframework/security/config/spring-security.xsl": b"<xsl/>"}
        self.assertEqual(self.rules(self.servlet(libs={"spring-security-config-6.5.11.jar": security}), "servlet"), [])
        self.assertIn("xsl", self.rules(self.servlet(libs={"other-1.0.jar": {"x/report.xsl": b"<xsl/>"}}), "servlet"))

    def test_a_jar_without_its_framework_jar_is_a_finding(self):
        # The positive control needs the framework jar, so the role rule is the guard for thin or re-laid-out jars.
        thin = self.fat_jar({"BOOT-INF/classes/com/parkio/Api.class": REST_CONTROLLER}, {"spring-web-6.2.19.jar": SPRING_WEB})
        self.assertIn("role", self.rules(thin, "servlet"))
        self.assertIn("role", self.rules(thin, "gateway"))
        self.assertEqual(check.main(["--role", "servlet", thin]), 1)

    def test_a_blind_scan_fails_the_control(self):
        blind = self.servlet(webmvc={"org/springframework/web/servlet/DispatcherServlet.class": b"x"})
        findings, control = check.check_jar(blind, "servlet")
        self.assertTrue(control)
        self.assertEqual(check.main(["--role", "servlet", blind]), 2)


if __name__ == "__main__":
    unittest.main()
