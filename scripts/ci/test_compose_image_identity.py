#!/usr/bin/env python3
"""Unit tests for scripts/ci/compose_image_identity.py with a recording fake docker."""
from __future__ import annotations

import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compose_image_identity as identity  # noqa: E402

SHA = "a" * 40
MODEL = {
    "name": "parkio-ci-1-1",
    "services": {
        "auth-service": {"build": {"context": ".."}, "image": "parkio/auth-service:ci-runtime"},
        "user-service": {"build": {"context": ".."}},
        "postgres-auth": {"image": "postgis/postgis:16"},
    },
}


def image(image_id: str, revision=SHA, layers=("sha256:base", "sha256:app")) -> dict:
    labels = {} if revision is None else {identity.REVISION: revision}
    return {"Id": image_id, "Config": {"Labels": labels}, "RootFS": {"Type": "layers", "Layers": list(layers)}}


class FakeDocker:
    """Answers `image inspect`, `ps` and `inspect` from dictionaries and records every call."""

    def __init__(self, images=None, containers=None, served=None, ps_rc=0):
        self.images = images or {}
        self.containers = containers or {}
        self.served = served or {}
        self.ps_rc = ps_rc
        self.calls = []

    def __call__(self, args):
        self.calls.append(list(args))
        if args[:2] == ["image", "inspect"]:
            found = self.images.get(args[2])
            if found is None:
                return subprocess.CompletedProcess(args, 1, "", "Error: No such image")
            return subprocess.CompletedProcess(args, 0, json.dumps([found]), "")
        if args[0] == "ps":
            if self.ps_rc:
                return subprocess.CompletedProcess(args, self.ps_rc, "", "Cannot connect to the Docker daemon")
            service = next(a.split("=", 2)[2] for a in args if a.startswith("label=com.docker.compose.service="))
            return subprocess.CompletedProcess(args, 0, "\n".join(self.containers.get(service, [])) + "\n", "")
        if args[0] == "inspect":
            served = self.served.get(args[-1])
            return subprocess.CompletedProcess(args, 0 if served else 1, (served or "") + "\n", "")
        raise AssertionError(f"unexpected docker call {args}")


def both_built():
    return {"parkio/auth-service:ci-runtime": image("sha256:auth"), "parkio-ci-1-1-user-service": image("sha256:user")}


class BuiltServicesTest(unittest.TestCase):
    def test_only_built_services_with_compose_default_names(self):
        self.assertEqual(identity.built_services(MODEL), {
            "auth-service": "parkio/auth-service:ci-runtime",
            "user-service": "parkio-ci-1-1-user-service",
        })


class RecordTest(unittest.TestCase):
    def test_records_ids_layers_and_revision_and_pins_pulls_off(self):
        code, message, rec, override = identity.record(MODEL, SHA, FakeDocker(images=both_built()))

        self.assertEqual(code, 0, message)
        self.assertEqual(rec["project"], "parkio-ci-1-1")
        self.assertEqual(rec["services"]["user-service"],
                         {"image": "parkio-ci-1-1-user-service", "id": "sha256:user",
                          "layers": ["sha256:base", "sha256:app"], "revision": SHA})
        self.assertEqual(override, {"services": {"auth-service": {"pull_policy": "never"},
                                                 "user-service": {"pull_policy": "never"}}})

    def test_a_missing_image_fails(self):
        images = both_built()
        del images["parkio-ci-1-1-user-service"]

        code, message, _, _ = identity.record(MODEL, SHA, FakeDocker(images=images))

        self.assertEqual(code, 1)
        self.assertIn("user-service: image parkio-ci-1-1-user-service is not present", message)

    def test_another_revision_fails(self):
        images = both_built()
        images["parkio/auth-service:ci-runtime"] = image("sha256:auth", revision="unknown")

        code, message, _, _ = identity.record(MODEL, SHA, FakeDocker(images=images))

        self.assertEqual(code, 1)
        self.assertIn("auth-service: revision label 'unknown'", message)

    def test_an_image_without_a_revision_label_fails(self):
        images = both_built()
        images["parkio-ci-1-1-user-service"] = image("sha256:user", revision=None)

        code, message, rec, _ = identity.record(MODEL, SHA, FakeDocker(images=images))

        self.assertEqual(code, 1)
        self.assertIn(f"user-service: no revision label, expected {SHA}", message)
        self.assertIsNone(rec["services"]["user-service"]["revision"])

    def test_without_an_expected_revision_any_label_or_none_is_accepted(self):
        images = both_built()
        images["parkio/auth-service:ci-runtime"] = image("sha256:auth", revision="ci-runtime")
        images["parkio-ci-1-1-user-service"] = image("sha256:user", revision=None)

        code, message, _, _ = identity.record(MODEL, None, FakeDocker(images=images))

        self.assertEqual(code, 0, message)

    def test_a_model_without_builds_or_a_project_name_fails(self):
        for model in ({"name": "p", "services": {"db": {"image": "postgres"}}},
                      {"services": MODEL["services"]}):
            with self.subTest(model=model):
                code, _, _, _ = identity.record(model, SHA, FakeDocker(images=both_built()))
                self.assertEqual(code, 1)


class VerifyTest(unittest.TestCase):
    def setUp(self):
        _, _, self.rec, _ = identity.record(MODEL, SHA, FakeDocker(images=both_built()))

    def docker(self, **changes):
        settings = {
            "images": both_built(),
            "containers": {"auth-service": ["c-auth"], "user-service": ["c-user-1", "c-user-2"]},
            "served": {"c-auth": "sha256:auth", "c-user-1": "sha256:user", "c-user-2": "sha256:user"},
        }
        settings.update(changes)
        return FakeDocker(**settings)

    def test_every_container_runs_the_recorded_image(self):
        docker = self.docker()

        code, message = identity.verify(self.rec, docker)

        self.assertEqual(code, 0, message)
        self.assertIn("3 container(s) of 2 built service(s)", message)
        ps = [call for call in docker.calls if call[0] == "ps"]
        self.assertIn("label=com.docker.compose.project=parkio-ci-1-1", ps[0])

    def test_a_container_on_another_image_fails(self):
        code, message = identity.verify(self.rec, self.docker(
            served={"c-auth": "sha256:auth", "c-user-1": "sha256:user", "c-user-2": "sha256:pulled"}))

        self.assertEqual(code, 1)
        self.assertIn("user-service: container c-user-2 runs sha256:pulled, not sha256:user", message)

    def test_a_service_without_a_container_fails(self):
        code, message = identity.verify(self.rec, self.docker(containers={"auth-service": ["c-auth"]}))

        self.assertEqual(code, 1)
        self.assertIn("user-service: no container in project parkio-ci-1-1", message)

    def test_a_moved_reference_fails(self):
        images = both_built()
        images["parkio/auth-service:ci-runtime"] = image("sha256:other")

        code, message = identity.verify(self.rec, self.docker(images=images))

        self.assertEqual(code, 1)
        self.assertIn("auth-service: parkio/auth-service:ci-runtime now names sha256:other", message)

    def test_a_failing_container_listing_fails(self):
        code, message = identity.verify(self.rec, self.docker(ps_rc=1))

        self.assertEqual(code, 1)
        self.assertIn("auth-service: cannot list its containers", message)
        self.assertIn("user-service: cannot list its containers", message)

    def test_an_empty_record_fails(self):
        code, _ = identity.verify({"project": "p", "services": {}}, self.docker())

        self.assertEqual(code, 1)


class CompareLayersTest(unittest.TestCase):
    BASE = ["sha256:b1", "sha256:b2"]
    FIRST = BASE + ["sha256:curl", "sha256:user", "sha256:jar1"]

    def test_input_keyed_cache_passes(self):
        changed = self.BASE + ["sha256:curl", "sha256:user", "sha256:jar2"]
        self.assertEqual(identity.compare_layers(self.BASE, self.FIRST, list(self.FIRST), changed), [])

    def test_a_repeat_with_other_layers_fails(self):
        repeat = self.BASE + ["sha256:curl", "sha256:user", "sha256:jar9"]
        changed = self.BASE + ["sha256:curl", "sha256:user", "sha256:jar2"]
        problems = identity.compare_layers(self.BASE, self.FIRST, repeat, changed)
        self.assertIn("a repeat build with the same inputs gave different layers", problems)

    def test_a_hidden_input_change_fails(self):
        problems = identity.compare_layers(self.BASE, self.FIRST, list(self.FIRST), list(self.FIRST))
        self.assertIn("changing the input did not change any layer: the cache hid the change", problems)

    def test_a_changed_base_fails(self):
        changed = ["sha256:b1", "sha256:bX", "sha256:curl", "sha256:user", "sha256:jar2"]
        problems = identity.compare_layers(self.BASE, self.FIRST, list(self.FIRST), changed)
        self.assertIn("the changed build does not start with the base image's layers", problems)


class ProbeTest(unittest.TestCase):
    def test_the_input_file_is_restored_and_probe_images_removed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            dockerfile = root / "Dockerfile"
            dockerfile.write_text("FROM jdk AS build\nFROM jre AS runtime\n")
            source = root / "application.yml"
            source.write_text("spring: {}\n")
            built = {}
            context_seen = []

            def run(args):
                if args[0] == "pull":
                    return subprocess.CompletedProcess(args, 0, "", "")
                if args[0] == "build":
                    # The output lies inside the context here, as in CI: nothing may appear in it before
                    # the last build, or `COPY . .` would change between the builds.
                    context_seen.append(sorted(str(f.relative_to(root)) for f in root.rglob("*") if f.is_file()))
                    tag = args[args.index("-t") + 1]
                    jar = "sha256:jar-changed" if "probe: changed input" in source.read_text() else "sha256:jar"
                    built[tag] = image("sha256:" + tag[-7:], layers=("sha256:jre", jar))
                    return subprocess.CompletedProcess(args, 0, "build log", "")
                if args[:2] == ["image", "inspect"]:
                    found = {"jre": image("sha256:jre", layers=("sha256:jre",))}.get(args[2]) or built.get(args[2])
                    return subprocess.CompletedProcess(args, 0 if found else 1, json.dumps([found]) if found else "", "")
                if args[:2] == ["image", "rm"]:
                    built.pop(args[2], None)
                    return subprocess.CompletedProcess(args, 0, "", "")
                raise AssertionError(f"unexpected docker call {args}")

            code = identity.probe(dockerfile, root, source, SHA, root / "out" / "probe.json", run)

            report = json.loads((root / "out" / "probe.json").read_text())
            self.assertEqual(code, 0, report["message"])
            self.assertEqual(source.read_text(), "spring: {}\n")
            self.assertEqual(built, {})
            self.assertEqual(report["base"], "jre")
            self.assertEqual(report["changed_layer_indexes"], [1])
            self.assertEqual(context_seen[0], context_seen[1])
            self.assertEqual(len(context_seen), 3)
            self.assertTrue((root / "out" / "probe-build-first.log").is_file())


FIVE_BUILT = {
    "name": "parkio-ci-1-1",
    "services": {
        **{name: {"build": {"context": ".."}} for name in ("e-service", "a-service", "d-service", "b-service", "c-service")},
        "postgres-auth": {"image": "postgis/postgis:16"},
    },
}
COMPOSE = ["--env-file", "docker/.env.ci", "-f", "docker/docker-compose.yml", "-f", "docker/docker-compose.apps.yml"]
BUILD = ["compose", *COMPOSE, "--progress=plain", "build", "--build-arg", f"IMAGE_REVISION={SHA}"]


class FakeBuilder:
    """Records every build call and the tick it was given; batch N (1-based) exits with codes[N], default 0."""

    def __init__(self, codes=None):
        self.codes = codes or {}
        self.calls = []
        self.ticks = []

    def __call__(self, args, tick):
        self.calls.append(list(args))
        self.ticks.append(tick)
        return self.codes.get(len(self.calls), 0)


class BuildTest(unittest.TestCase):
    def setUp(self):
        self.diags = []

    def diag(self):
        self.diags.append(len(self.diags))

    def test_builds_every_built_service_two_at_a_time_with_the_revision(self):
        builder = FakeBuilder()

        with redirect_stdout(io.StringIO()):
            code, message = identity.build(FIVE_BUILT, COMPOSE, SHA, 2, self.diag, builder)

        self.assertEqual(code, 0, message)
        self.assertEqual(builder.calls, [BUILD + ["a-service", "b-service"], BUILD + ["c-service", "d-service"],
                                         BUILD + ["e-service"]])
        # Logged before the first batch and after each batch; the builder ticks the same logger meanwhile.
        self.assertEqual(len(self.diags), 4)
        self.assertTrue(all(tick == self.diag for tick in builder.ticks))
        self.assertIn("built 5 image(s), at most 2 at a time", message)

    def test_batch_size_one_builds_one_service_at_a_time(self):
        builder = FakeBuilder()

        with redirect_stdout(io.StringIO()):
            code, _ = identity.build(FIVE_BUILT, COMPOSE, SHA, 1, self.diag, builder)

        self.assertEqual(code, 0)
        self.assertEqual([call[len(BUILD):] for call in builder.calls],
                         [["a-service"], ["b-service"], ["c-service"], ["d-service"], ["e-service"]])

    def test_a_failed_batch_stops_the_build(self):
        builder = FakeBuilder(codes={2: 17})

        with redirect_stdout(io.StringIO()):
            code, message = identity.build(FIVE_BUILT, COMPOSE, SHA, 2, self.diag, builder)

        self.assertEqual(code, 1)
        self.assertIn("building c-service d-service exited with 17", message)
        self.assertEqual(len(builder.calls), 2)
        self.assertEqual(len(self.diags), 3)

    def test_a_model_without_builds_fails_before_building(self):
        builder = FakeBuilder()
        model = {"name": "parkio-ci-1-1", "services": {"postgres-auth": {"image": "postgis/postgis:16"}}}

        code, message = identity.build(model, COMPOSE, SHA, 2, self.diag, builder)

        self.assertEqual(code, 1)
        self.assertIn("the model builds no service", message)
        self.assertEqual(builder.calls, [])
        self.assertEqual(self.diags, [])


class RunnerDiagTest(unittest.TestCase):
    def test_runtime_validation_format(self):
        line = identity.runner_diag()

        self.assertRegex(line, r"^runner-diag utc=\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ nproc=\d+ ")
        self.assertRegex(line, r" disk_avail_kB=\d+$")
        if Path("/proc/meminfo").exists():
            self.assertRegex(line, r" load=[^ ]+ mem_available_kB=\d+ mem_total_kB=\d+ ")


class StubDocker:
    """A `docker` executable first on PATH: it appends its arguments to calls.log, writes a marker line to stdout
    and one to stderr when $STUB_MARK is set, sleeps, and exits with $STUB_EXIT."""

    def __init__(self, tmp: Path, sleep: str = "0"):
        self.dir = tmp / "bin"
        self.dir.mkdir()
        self.log = tmp / "calls.log"
        stub = self.dir / "docker"
        stub.write_text(f'#!/bin/sh\necho "$*" >> "{self.log}"\n'
                        'if [ -n "${STUB_MARK:-}" ]; then echo "stub stdout"; echo "stub stderr" >&2; fi\n'
                        f'sleep {sleep}\nexit "${{STUB_EXIT:-0}}"\n')
        stub.chmod(0o755)

    def env(self, exit_code: int = 0, mark: bool = False) -> dict:
        return {"PATH": f"{self.dir}{os.pathsep}{os.environ['PATH']}", "STUB_EXIT": str(exit_code),
                "STUB_MARK": "1" if mark else ""}

    def calls(self) -> list:
        return self.log.read_text().splitlines() if self.log.exists() else []


class StreamingRunnerTest(unittest.TestCase):
    def test_ticks_while_docker_runs_and_returns_its_exit_code(self):
        with tempfile.TemporaryDirectory() as tmp:
            docker = StubDocker(Path(tmp), sleep="0.5")
            ticks = []

            with mock.patch.dict(os.environ, docker.env(exit_code=3)):
                code = identity.run_docker_streaming(["compose", "build", "x-service"], lambda: ticks.append(1),
                                                     every=0.1)

            self.assertEqual(code, 3)
            self.assertGreaterEqual(len(ticks), 2)
            self.assertEqual(docker.calls(), ["compose build x-service"])

    def test_docker_output_goes_to_the_job_log(self):
        # A child interpreter, so that the stub's output reaches pipes this test can read.
        child = ("import sys; sys.path.insert(0, sys.argv[1]); import compose_image_identity as identity; "
                 "sys.exit(identity.run_docker_streaming(['compose', 'build'], lambda: None))")
        with tempfile.TemporaryDirectory() as tmp:
            docker = StubDocker(Path(tmp))

            done = subprocess.run([sys.executable, "-c", child, str(Path(__file__).resolve().parent)],
                                  env={**os.environ, **docker.env(mark=True)}, capture_output=True, text=True, timeout=60)

            self.assertEqual(done.returncode, 0, done.stderr)
            self.assertIn("stub stdout", done.stdout)
            self.assertIn("stub stderr", done.stderr)


class BuildMainTest(unittest.TestCase):
    def run_main(self, argv, exit_code=0):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            docker = StubDocker(root)
            model = root / "model.json"
            model.write_text(json.dumps(MODEL))
            diag = root / "out" / "runner-diag.txt"
            argv = [str(model) if arg == "MODEL" else str(diag) if arg == "DIAG" else arg for arg in argv]
            out, err = io.StringIO(), io.StringIO()
            with mock.patch.dict(os.environ, docker.env(exit_code)), redirect_stdout(out), redirect_stderr(err):
                try:
                    code = identity.main(argv)
                except SystemExit as stop:
                    code = f"usage error {stop.code}"
            return code, docker.calls(), diag.read_text().splitlines() if diag.exists() else [], out.getvalue()

    def test_builds_with_the_compose_arguments_after_the_separator_and_logs_diagnostics(self):
        code, calls, diag, out = self.run_main(
            ["build", "MODEL", "--revision", SHA, "--diag-out", "DIAG", "--", "--env-file", "e", "-f", "c.yml"])

        self.assertEqual(code, 0)
        self.assertEqual(calls, [f"compose --env-file e -f c.yml --progress=plain build --build-arg IMAGE_REVISION={SHA} "
                                 "auth-service user-service"])
        self.assertEqual(len(diag), 2)
        self.assertTrue(all(line.startswith("runner-diag utc=") for line in diag))
        self.assertIn(diag[0], out)

    def test_the_batch_size_option_bounds_each_build(self):
        code, calls, _, _ = self.run_main(["build", "MODEL", "--revision", SHA, "--batch-size", "1", "--", "-f", "c.yml"])

        self.assertEqual(code, 0)
        self.assertEqual([call.split()[-1] for call in calls], ["auth-service", "user-service"])
        self.assertEqual(len(calls), 2)

    def test_only_the_first_separator_ends_the_script_arguments(self):
        code, calls, _, _ = self.run_main(["build", "MODEL", "--revision", SHA, "--", "-f", "c.yml", "--", "x"])

        self.assertEqual(code, 0)
        self.assertTrue(calls[0].startswith("compose -f c.yml -- x --progress=plain build "), calls)

    def test_a_failing_build_fails(self):
        code, calls, _, _ = self.run_main(["build", "MODEL", "--revision", SHA, "--batch-size", "1", "--", "-f", "c.yml"],
                                          exit_code=1)

        self.assertEqual(code, 1)
        self.assertEqual(len(calls), 1)

    def test_usage_errors_build_nothing(self):
        for argv in (["build", "MODEL", "--revision", SHA],
                     ["build", "MODEL", "--revision", SHA, "--"],
                     ["build", "MODEL", "--revision", SHA, "--batch-size", "0", "--", "-f", "c.yml"],
                     ["build", "MODEL", "--", "-f", "c.yml"],
                     ["verify", "MODEL", "--", "-f", "c.yml"]):
            with self.subTest(argv=argv):
                code, calls, _, _ = self.run_main(argv)
                self.assertEqual(code, "usage error 2")
                self.assertEqual(calls, [])


if __name__ == "__main__":
    unittest.main(verbosity=1)
