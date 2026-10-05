#!/usr/bin/env python3
"""Unit tests for scripts/ci/compose_image_identity.py with a recording fake docker."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

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

    def __init__(self, images=None, containers=None, served=None):
        self.images = images or {}
        self.containers = containers or {}
        self.served = served or {}
        self.calls = []

    def __call__(self, args):
        self.calls.append(list(args))
        if args[:2] == ["image", "inspect"]:
            found = self.images.get(args[2])
            if found is None:
                return subprocess.CompletedProcess(args, 1, "", "Error: No such image")
            return subprocess.CompletedProcess(args, 0, json.dumps([found]), "")
        if args[0] == "ps":
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

    def test_an_image_without_a_revision_label_is_recorded_not_failed(self):
        images = both_built()
        images["parkio-ci-1-1-user-service"] = image("sha256:user", revision=None)

        code, message, rec, _ = identity.record(MODEL, SHA, FakeDocker(images=images))

        self.assertEqual(code, 0, message)
        self.assertIsNone(rec["services"]["user-service"]["revision"])

    def test_without_an_expected_revision_any_label_is_accepted(self):
        images = both_built()
        images["parkio/auth-service:ci-runtime"] = image("sha256:auth", revision="ci-runtime")

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


if __name__ == "__main__":
    unittest.main(verbosity=1)
