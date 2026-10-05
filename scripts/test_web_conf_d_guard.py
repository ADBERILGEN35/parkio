#!/usr/bin/env python3
"""B8b: scripts/lib/web_conf_d_guard.py refuses a web image that would lose its server config under a
tmpfs at /etc/nginx/conf.d. Docker is replaced by a recording fake; nothing is created or run."""
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

from lib import web_conf_d_guard as guard

TEMPLATE_CONTENT = b"SECRET-TEMPLATE-CONTENT server { listen 80; }\n"
MISSING = b"Error response from daemon: Could not find the file /etc/nginx/templates/default.conf.template in container cid-1\n"
TMPFS_MODEL = {"services": {"web": {"image": "web:old", "tmpfs": ["/etc/nginx/conf.d:size=1m,mode=755", "/tmp:size=8m"]}}}
PLAIN_MODEL = {"services": {"web": {"image": "web:old"}}}


def template_tar(content=TEMPLATE_CONTENT, directory=False):
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w") as tar:
        info = tarfile.TarInfo("default.conf.template")
        if directory:
            info.type = tarfile.DIRTYPE
            tar.addfile(info)
        else:
            info.size = len(content)
            tar.addfile(info, io.BytesIO(content))
    return buffer.getvalue()


class FakeDocker:
    """Records every docker call; `create` returns a container id, `cp` the configured outcome."""

    def __init__(self, create_rc=0, cp_rc=0, cp_stdout=b"", cp_stderr=b"", rm_rc=0):
        self.calls = []
        self.create_rc, self.cp_rc, self.cp_stdout, self.cp_stderr, self.rm_rc = create_rc, cp_rc, cp_stdout, cp_stderr, rm_rc

    def __call__(self, args):
        self.calls.append(list(args))
        if args[0] == "create":
            return subprocess.CompletedProcess(args, self.create_rc, b"cid-1\n" if self.create_rc == 0 else b"", b"Error: No such image\n")
        if args[0] == "cp":
            return subprocess.CompletedProcess(args, self.cp_rc, self.cp_stdout, self.cp_stderr)
        if args[0] == "rm":
            return subprocess.CompletedProcess(args, self.rm_rc, b"", b"")
        raise AssertionError(f"unexpected docker call: {args}")

    def verbs(self):
        return [call[0] for call in self.calls]


class WebConfDGuardTest(unittest.TestCase):
    def test_an_old_image_with_the_tmpfs_is_refused(self):
        docker = FakeDocker(cp_rc=1, cp_stderr=MISSING)

        code, message = guard.check(TMPFS_MODEL, "web:old", docker)

        self.assertEqual(code, 1)
        self.assertIn("built before #198", message)
        self.assertIn("move the web pin", message)
        self.assertEqual(docker.verbs(), ["create", "cp", "rm"])

    def test_a_new_image_with_the_tmpfs_is_allowed(self):
        docker = FakeDocker(cp_stdout=template_tar())

        code, message = guard.check(TMPFS_MODEL, "web:new", docker)

        self.assertEqual(code, 0)
        self.assertIn("PASS", message)
        self.assertEqual(docker.verbs(), ["create", "cp", "rm"])
        self.assertNotIn("SECRET-TEMPLATE-CONTENT", message)

    def test_a_model_without_the_tmpfs_is_skipped_without_docker(self):
        for model in (PLAIN_MODEL, {"services": {"gateway-service": {"image": "gw:1"}}}):
            docker = FakeDocker()

            code, message = guard.check(model, "web:old", docker)

            self.assertEqual(code, 0)
            self.assertIn("SKIP", message)
            self.assertEqual(docker.calls, [])

    def test_an_image_that_cannot_be_inspected_is_refused(self):
        cases = {
            "create fails (missing image)": FakeDocker(create_rc=1),
            "cp fails for another reason": FakeDocker(cp_rc=1, cp_stderr=b"Error response from daemon: permission denied\n"),
            "the template is empty": FakeDocker(cp_stdout=template_tar(content=b"")),
            "the template is a directory": FakeDocker(cp_stdout=template_tar(directory=True)),
            "the stream is not a tar archive": FakeDocker(cp_stdout=b"not a tar"),
        }
        for name, docker in cases.items():
            with self.subTest(name):
                code, message = guard.check(TMPFS_MODEL, "web:any", docker)

                self.assertEqual(code, 1)
                self.assertIn("cannot inspect", message)
                expected = ["create"] if name.startswith("create fails") else ["create", "cp", "rm"]
                self.assertEqual(docker.verbs(), expected)

    def test_the_image_is_created_never_started_and_removed(self):
        docker = FakeDocker(cp_stdout=template_tar())

        guard.check(TMPFS_MODEL, "web:new", docker)

        create, cp, rm = docker.calls
        self.assertEqual(create, ["create", "--pull", "never", "--network", "none", "--entrypoint",
                                  guard.NOOP_ENTRYPOINT, "web:new"])
        self.assertEqual(cp, ["cp", "cid-1:/etc/nginx/templates/default.conf.template", "-"])
        self.assertEqual(rm, ["rm", "-fv", "cid-1"])

    def test_a_failed_cleanup_warns_but_keeps_the_verdict(self):
        docker = FakeDocker(cp_rc=1, cp_stderr=MISSING, rm_rc=1)
        err = io.StringIO()

        with redirect_stderr(err):
            code, _ = guard.check(TMPFS_MODEL, "web:old", docker)

        self.assertEqual(code, 1)
        self.assertIn("could not remove inspection container cid-1", err.getvalue())

    def test_the_tmpfs_is_found_in_every_form_compose_renders(self):
        forms = [
            {"tmpfs": "/etc/nginx/conf.d:size=1m"},
            {"tmpfs": ["/etc/nginx/conf.d/"]},
            {"volumes": [{"type": "tmpfs", "target": "/etc/nginx/conf.d", "tmpfs": {"size": 1048576}}]},
        ]
        for web in forms:
            with self.subTest(web):
                self.assertTrue(guard.mounts_conf_d_tmpfs({"services": {"web": {"image": "w", **web}}}))
        self.assertFalse(guard.mounts_conf_d_tmpfs({"services": {"web": {"image": "w", "tmpfs": ["/etc/nginx/conf.d.bak:size=1m"]}}}))
        self.assertIsNone(guard.mounts_conf_d_tmpfs({"version": "3"}))

    def test_an_unreadable_model_is_refused(self):
        docker = FakeDocker()

        code, message = guard.check({"services": []}, "web:any", docker)

        self.assertEqual(code, 1)
        self.assertIn("unreadable", message)
        self.assertEqual(docker.calls, [])


class CliTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def write(self, name, value):
        path = Path(self.dir.name) / name
        path.write_text(value if isinstance(value, str) else json.dumps(value), encoding="utf-8")
        return str(path)

    def run_main(self, argv, docker):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = guard.main(argv, docker)
        return code, out.getvalue(), err.getvalue()

    def test_the_binding_override_names_the_image_to_inspect(self):
        model = self.write("model.json", TMPFS_MODEL)
        binding = self.write("binding.json", {"services": {"web": {"image": "sha256:" + "a" * 64, "pull_policy": "never"}}})
        docker = FakeDocker(cp_stdout=template_tar())

        code, out, err = self.run_main(["--config-json", model, "--binding", binding], docker)

        self.assertEqual(code, 0)
        self.assertEqual(docker.calls[0][-1], "sha256:" + "a" * 64)
        self.assertNotIn("SECRET-TEMPLATE-CONTENT", out + err)

    def test_an_unreadable_binding_is_refused_when_the_tmpfs_is_mounted(self):
        model = self.write("model.json", TMPFS_MODEL)
        binding = self.write("binding.json", "{not json")
        docker = FakeDocker()

        code, _, err = self.run_main(["--config-json", model, "--binding", binding], docker)

        self.assertEqual(code, 1)
        self.assertIn("binding override is unreadable", err)
        self.assertEqual(docker.calls, [])

    def test_an_unreadable_model_file_is_refused(self):
        code, _, err = self.run_main(["--config-json", os.path.join(self.dir.name, "missing.json"), "--image", "w"], FakeDocker())

        self.assertEqual(code, 1)
        self.assertIn("unreadable", err)

    def test_the_model_image_is_inspected_when_there_is_no_binding(self):
        """F4: with the map guard skipped by its own break-glass, the image the model names is checked."""
        model = self.write("model.json", TMPFS_MODEL)
        docker = FakeDocker(cp_rc=1, cp_stderr=MISSING)

        code, _, err = self.run_main(["--config-json", model, "--model-image"], docker)

        self.assertEqual(code, 1)
        self.assertEqual(docker.calls[0][-1], "web:old")
        self.assertIn("built before #198", err)

    def test_the_model_image_mode_skips_a_model_without_the_tmpfs_or_web(self):
        for value in (PLAIN_MODEL, {"services": {"gateway-service": {"image": "gw:1"}}}):
            with self.subTest(model=value):
                docker = FakeDocker()
                code, out, _ = self.run_main(["--config-json", self.write("model.json", value), "--model-image"], docker)
                self.assertEqual(code, 0)
                self.assertIn("SKIP", out)
                self.assertEqual(docker.calls, [])

    def test_the_model_image_mode_refuses_a_web_service_without_an_image(self):
        model = self.write("model.json", {"services": {"web": {"build": ".", "tmpfs": ["/etc/nginx/conf.d"]}}})
        docker = FakeDocker()

        code, _, err = self.run_main(["--config-json", model, "--model-image"], docker)

        self.assertEqual(code, 1)
        self.assertIn("no web image to inspect", err)
        self.assertEqual(docker.calls, [])

    def test_usage_errors_exit_2(self):
        with redirect_stderr(io.StringIO()):
            self.assertEqual(guard.main(["--config-json", "m.json"], FakeDocker()), 2)


if __name__ == "__main__":
    unittest.main()
