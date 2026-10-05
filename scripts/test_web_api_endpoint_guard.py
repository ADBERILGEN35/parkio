#!/usr/bin/env python3
"""Unit tests for scripts/lib/web_api_endpoint_guard.py (H2), with a fake docker that serves bundles.

The callers (the Civo wrapper and the hosted-beta deploy and rollback) are tested with the other web
guards in scripts/test-guard-web-synthetic-map-deploy.sh.
"""
from __future__ import annotations

import io
import json
import subprocess
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "lib"))
import web_api_endpoint_guard as guard  # noqa: E402

PROD = "https://api.parkio.dev/api/v1"
BETA = "https://api.beta.example.com/api/v1"


def bundle_js(api: str = PROD) -> str:
    return ('const e={BASE_URL:"/",MODE:"production",VITE_API_BASE_URL:"%s",VITE_APP_ENV:"hosted-beta",'
            'VITE_MAPTILER_KEY:"k"};export{e};' % api)


def web_root_tar(files: dict) -> bytes:
    """A `docker cp IMAGE:/usr/share/nginx/html -` stream holding the given {relative path: text}."""
    data = io.BytesIO()
    with tarfile.open(fileobj=data, mode="w") as archive:
        directory = tarfile.TarInfo("html")
        directory.type = tarfile.DIRTYPE
        archive.addfile(directory)
        for name, text in files.items():
            payload = text.encode()
            info = tarfile.TarInfo(f"html/{name}")
            info.size = len(payload)
            archive.addfile(info, io.BytesIO(payload))
    return data.getvalue()


class FakeDocker:
    """`create` gives a container for a known image, `cp` streams that image's web root."""

    def __init__(self, images: dict):
        self.images, self.calls, self.containers = images, [], {}

    def __call__(self, args):
        self.calls.append(list(args))
        if args[0] == "create":
            image = args[-1]
            if image not in self.images:
                return subprocess.CompletedProcess(args, 1, b"", b"Error: No such image")
            self.containers[f"cid-{len(self.containers)}"] = image
            return subprocess.CompletedProcess(args, 0, f"cid-{len(self.containers) - 1}\n".encode(), b"")
        if args[0] == "cp":
            container = args[1].split(":", 1)[0]
            stream = self.images[self.containers[container]]
            if stream is None:
                return subprocess.CompletedProcess(args, 1, b"", b"Error: Could not find the file")
            return subprocess.CompletedProcess(args, 0, stream, b"")
        if args[0] == "rm":
            return subprocess.CompletedProcess(args, 0, b"", b"")
        raise AssertionError(f"unexpected docker call {args}")


def model(image: str = "parkio/web:prod", api=PROD, domain=None) -> dict:
    web = {"image": image}
    if api is not None:
        web["build"] = {"args": {"VITE_API_BASE_URL": api}}
    services = {"web": web}
    if domain is not None:
        services["caddy"] = {"environment": {"PARKIO_DOMAIN": domain}}
    return {"name": "parkio", "services": services}


IMAGES = {
    "parkio/web:prod": web_root_tar({"assets/index-a1.js": bundle_js(PROD)}),
    "parkio/web:beta": web_root_tar({"assets/index-a1.js": bundle_js(BETA)}),
    "parkio/web:no-root": None,
    "parkio/web:no-env": web_root_tar({"assets/index-a1.js": 'console.log("no env");'}),
    "parkio/web:two-apis": web_root_tar({"assets/a.js": bundle_js(PROD), "assets/b.js": bundle_js(BETA)}),
}


class NormaliseTest(unittest.TestCase):
    def test_case_default_ports_and_a_trailing_slash_do_not_matter(self):
        self.assertEqual(guard.normalise("HTTPS://API.Parkio.dev:443/api/v1/"), PROD)
        self.assertEqual(guard.normalise("http://localhost:80/api"), "http://localhost/api")

    def test_another_port_path_or_scheme_does(self):
        self.assertEqual(guard.normalise("https://api.parkio.dev:8443/api/v1"), "https://api.parkio.dev:8443/api/v1")
        self.assertNotEqual(guard.normalise("https://api.parkio.dev/api/v2"), PROD)
        self.assertNotEqual(guard.normalise("http://api.parkio.dev/api/v1"), PROD)

    def test_what_is_not_an_http_url_with_a_host(self):
        for value in ("", "api.parkio.dev/api/v1", "ftp://api.parkio.dev", "https://", "https://u:p@api.parkio.dev",
                      "https://api.parkio.dev/api?x=1", "https://api.parkio.dev:notaport"):
            self.assertIsNone(guard.normalise(value), value)


class IntendedEndpointTest(unittest.TestCase):
    def test_from_the_web_build_argument_in_either_form(self):
        self.assertEqual(guard.intended_endpoint(model(api=PROD + "/")), PROD)
        listed = {"services": {"web": {"build": {"args": [f"VITE_API_BASE_URL={BETA}"]}}}}
        self.assertEqual(guard.intended_endpoint(listed), BETA)

    def test_a_missing_or_malformed_endpoint_is_refused(self):
        for api in (None, "", "  ", "api.parkio.dev"):
            with self.subTest(api=api), self.assertRaises(guard.Refused):
                guard.intended_endpoint(model(api=api))

    def test_the_endpoint_must_be_on_parkio_domain_when_the_model_sets_it(self):
        self.assertEqual(guard.intended_endpoint(model(api=PROD, domain="API.PARKIO.DEV")), PROD)
        with self.assertRaisesRegex(guard.Refused, "does not point at its PARKIO_DOMAIN api.beta.example.com"):
            guard.intended_endpoint(model(api=PROD, domain="api.beta.example.com"))


class WebImageTest(unittest.TestCase):
    def test_the_binding_wins_over_the_model(self):
        with tempfile.TemporaryDirectory() as tmp:
            binding = Path(tmp) / "web-binding.yml"
            binding.write_text(json.dumps({"services": {"web": {"image": "sha256:" + "a" * 64, "pull_policy": "never"}}}))
            self.assertEqual(guard.web_image(model(), binding), "sha256:" + "a" * 64)
            self.assertEqual(guard.web_image(model(), Path(tmp) / "absent.yml"), "parkio/web:prod")

    def test_a_build_only_web_uses_the_compose_name_and_no_image_is_refused(self):
        self.assertEqual(guard.web_image({"name": "parkio", "services": {"web": {"build": {"context": ".."}}}}, None), "parkio-web")
        with self.assertRaises(guard.Refused):
            guard.web_image({"services": {"web": {}}}, None)


class CheckTest(unittest.TestCase):
    def run_check(self, m, images=IMAGES):
        docker = FakeDocker(images)
        try:
            return guard.check(m, None, run=docker), docker
        except guard.Refused as refused:
            return refused, docker

    def assert_refused(self, result, text):
        self.assertIsInstance(result, guard.Refused)
        self.assertIn(text, str(result))

    def test_a_production_image_with_a_beta_env_is_refused(self):
        result, _ = self.run_check(model("parkio/web:prod", BETA, "api.beta.example.com"))
        self.assert_refused(result, f"parkio/web:prod was built to call {PROD}, but this deploy's env intends {BETA}")

    def test_a_beta_image_with_the_production_env_is_refused(self):
        result, _ = self.run_check(model("parkio/web:beta", PROD, "api.parkio.dev"))
        self.assert_refused(result, f"was built to call {BETA}, but this deploy's env intends {PROD}")

    def test_a_match_passes_and_the_image_is_never_run(self):
        result, docker = self.run_check(model("parkio/web:beta", BETA, "api.beta.example.com"))
        self.assertEqual(result, f"web-api-endpoint-guard: PASS: parkio/web:beta calls {BETA}, the API this deploy intends")
        create = next(call for call in docker.calls if call[0] == "create")
        self.assertIn("--pull", create)
        self.assertEqual(create[create.index("--network") + 1], "none")
        self.assertEqual([call[0] for call in docker.calls], ["create", "cp", "rm"])

    def test_an_unreadable_bundle_is_refused(self):
        self.assert_refused(self.run_check(model("parkio/web:no-root"))[0], "cannot copy /usr/share/nginx/html out of")
        self.assert_refused(self.run_check(model("parkio/web:absent"))[0], "cannot create a container from")
        self.assert_refused(self.run_check(model("parkio/web:no-env"))[0], "inlines no VITE_API_BASE_URL")
        self.assert_refused(self.run_check(model("parkio/web:two-apis"))[0], "ambiguous API base URL")

    def test_an_omitted_env_endpoint_is_refused_before_any_docker_call(self):
        result, docker = self.run_check(model("parkio/web:prod", api=None))
        self.assert_refused(result, "sets no VITE_API_BASE_URL")
        self.assertEqual(docker.calls, [])

    def test_a_model_without_web_passes(self):
        result, docker = self.run_check({"services": {"gateway-service": {"image": "gw"}}})
        self.assertIn("SKIP", result)
        self.assertEqual(docker.calls, [])

    def test_the_container_is_removed_when_the_copy_fails(self):
        _, docker = self.run_check(model("parkio/web:no-root"))
        self.assertEqual(docker.calls[-1][:2], ["rm", "-f"])


class MainTest(unittest.TestCase):
    def test_an_unreadable_model_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            broken = Path(tmp) / "model.json"
            broken.write_text("not json")
            self.assertEqual(guard.main(["--compose-config-json", str(broken)]), 1)


if __name__ == "__main__":
    unittest.main(verbosity=1)
