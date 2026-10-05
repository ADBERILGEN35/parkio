#!/usr/bin/env python3
"""Unit tests for scripts/ci/build_cache_trial.py with a fake docker that builds jars from the context."""
from __future__ import annotations

import io
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_cache_trial as trial  # noqa: E402

BASELINE = """# syntax=docker/dockerfile:1

# ---- build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x ./gradlew
RUN ./gradlew :services:user-service:bootJar --no-daemon

FROM eclipse-temurin:21-jre AS runtime
COPY --from=build /workspace/services/user-service/build/libs/*.jar app.jar
"""
TRIAL = BASELINE.replace(
    "RUN ./gradlew :services:user-service:bootJar --no-daemon\n",
    "# Build-cache trial: a comment the comparison ignores.\n"
    "RUN --mount=type=cache,id=parkio-gradle-home,target=/root/.gradle \\\n"
    "    ./gradlew :services:user-service:bootJar --no-daemon\n")


def zipped(entries: dict, when=(1980, 2, 1, 0, 0, 0)) -> bytes:
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as archive:
        for name, content in entries.items():
            archive.writestr(zipfile.ZipInfo(name, date_time=when), content)
    return data.getvalue()


def sbom(serial="urn:uuid:1", timestamp="2026-10-05T10:00:00Z", reverse=False, version="1.0") -> str:
    """A CycloneDX SBOM like the one the services embed: a serial number and timestamp per build, varying order."""
    components = [{"bom-ref": "pkg:maven/a/a@1.0", "version": "1.0"}, {"bom-ref": "pkg:maven/b/b@" + version, "version": version}]
    if reverse:
        components.reverse()
    return json.dumps({"bomFormat": "CycloneDX", "serialNumber": serial,
                       "metadata": {"timestamp": timestamp, "tools": [{"name": "cyclonedx-gradle-plugin"}]},
                       "components": components})


def jar(resource: str, extra: bool = False, platform_time=(1980, 2, 1, 0, 0, 0), platform_class: bytes = b"A",
        bom: str = "") -> bytes:
    """A bootJar with application.yml, an SBOM and a nested platform jar, like the services' (whose parts vary per build)."""
    entries = {trial.JAR_RESOURCE: resource, trial.SBOM: bom or sbom(),
               "BOOT-INF/lib/parkio-platform.jar": zipped({"com/parkio/A.class": platform_class}, platform_time)}
    if extra:
        entries["BOOT-INF/extra"] = "x"
    return zipped(entries)


class FakeDocker:
    """`build` makes a jar from the context's application.yml; `stale_cache` makes a mounted build ignore it."""

    def __init__(self, context: Path, stale_cache: bool = False, extra_with_cache: bool = False):
        self.context, self.stale_cache, self.extra_with_cache = context, stale_cache, extra_with_cache
        self.images, self.containers, self.calls, self.first = {}, {}, [], {}

    def __call__(self, args):
        self.calls.append(list(args))
        ok = subprocess.CompletedProcess(args, 0, "", "")
        if args[0] in ("pull", "rm") or args[:2] == ["builder", "prune"] or args[:2] == ["image", "rm"]:
            return ok
        if args[0] == "build":
            dockerfile = Path(args[args.index("-f") + 1]).read_text()
            tag = args[args.index("-t") + 1]
            resource = (self.context / "services/user-service" / trial.RESOURCE).read_text()
            cached = "--mount=type=cache" in dockerfile
            if cached and self.stale_cache:
                resource = self.first.setdefault("resource", resource)
            self.images[tag] = jar(resource, extra=cached and self.extra_with_cache)
            return subprocess.CompletedProcess(args, 0, f"built {tag}", "")
        if args[0] == "create":
            self.containers[args[args.index("--name") + 1]] = self.images[args[-1]]
            return ok
        if args[0] == "cp":
            name = args[1].split(":", 1)[0]
            Path(args[2]).write_bytes(self.containers[name])
            return ok
        raise AssertionError(f"unexpected docker call {args}")


def context_with(dockerfile: str) -> tempfile.TemporaryDirectory:
    tmp = tempfile.TemporaryDirectory()
    service = Path(tmp.name) / "context/services/user-service"
    (service / "src/main/resources").mkdir(parents=True)
    (service / "Dockerfile").write_text(dockerfile)
    (service / trial.RESOURCE).write_text("spring: {}\n")
    return tmp


class DockerfileTest(unittest.TestCase):
    def test_the_trial_differs_from_the_baseline_only_by_its_cache_mount(self):
        self.assertIsNone(trial.check_baseline(TRIAL, BASELINE))
        self.assertEqual(trial.instructions(trial.without_cache_mounts(TRIAL)), trial.instructions(BASELINE))

    def test_any_other_difference_is_refused(self):
        changed = TRIAL.replace("--no-daemon", "--no-daemon --offline")
        self.assertIn("more than its cache mount", trial.check_baseline(changed, BASELINE))
        self.assertIn("no cache mount", trial.check_baseline(BASELINE, BASELINE))

    def test_base_images_are_the_external_from_images(self):
        self.assertEqual(trial.base_images(TRIAL), ["eclipse-temurin:21-jdk", "eclipse-temurin:21-jre"])
        self.assertEqual(trial.base_images("FROM a AS one\nFROM one AS two\nFROM scratch\n"), ["a"])


class EvaluateTest(unittest.TestCase):
    MARKER = "# changed"

    def jars(self, **changes):
        jars = {"baseline-cold": jar("spring: {}\n"), "cache-cold": jar("spring: {}\n"),
                "cache-warm": jar(f"spring: {{}}\n{self.MARKER}\n"), "baseline-warm": jar(f"spring: {{}}\n{self.MARKER}\n")}
        jars.update(changes)
        return jars

    def test_equivalent_builds_pass(self):
        self.assertEqual(trial.evaluate(self.jars(), self.MARKER), [])

    def test_a_cached_build_that_hides_the_change_fails(self):
        problems = trial.evaluate(self.jars(**{"cache-warm": jar("spring: {}\n")}), self.MARKER)
        self.assertIn("the changed input is not in the cache-warm jar: the cache hid the change", problems)
        self.assertIn("the warm builds give jars with different contents for the same source: "
                      + trial.JAR_RESOURCE, problems)
        self.assertIn("between the cold and the warm cached build, no entry changed, not only "
                      + trial.JAR_RESOURCE, problems)

    def test_different_contents_for_the_same_source_fail(self):
        problems = trial.evaluate(self.jars(**{"cache-cold": jar("spring: {}\n", extra=True)}), self.MARKER)
        self.assertIn("the cold builds give jars with different contents for the same source: BOOT-INF/extra", problems)

    def test_nested_jar_timestamps_do_not_count_but_their_contents_do(self):
        later = jar("spring: {}\n", platform_time=(2026, 10, 5, 12, 0, 0))
        self.assertNotEqual(later, jar("spring: {}\n"))
        self.assertEqual(trial.evaluate(self.jars(**{"cache-cold": later}), self.MARKER), [])

        changed = jar("spring: {}\n", platform_class=b"B")
        problems = trial.evaluate(self.jars(**{"cache-cold": changed}), self.MARKER)
        self.assertIn("the cold builds give jars with different contents for the same source: "
                      "BOOT-INF/lib/parkio-platform.jar!com/parkio/A.class", problems)

    def test_the_sbom_serial_number_timestamp_and_order_do_not_count_but_its_components_do(self):
        rebuilt = jar("spring: {}\n", bom=sbom(serial="urn:uuid:2", timestamp="2026-10-05T11:00:00Z", reverse=True))
        self.assertEqual(trial.evaluate(self.jars(**{"cache-cold": rebuilt}), self.MARKER), [])

        upgraded = jar("spring: {}\n", bom=sbom(version="2.0"))
        problems = trial.evaluate(self.jars(**{"cache-cold": upgraded}), self.MARKER)
        self.assertIn("the cold builds give jars with different contents for the same source: " + trial.SBOM, problems)

    def test_an_sbom_that_is_not_json_is_compared_by_its_bytes(self):
        self.assertEqual(trial.sbom_digest(b"not json"), trial.sbom_digest(b"not json"))
        self.assertNotEqual(trial.sbom_digest(b"not json"), trial.sbom_digest(b"not json either"))

    def test_another_entry_changing_with_the_input_fails(self):
        problems = trial.evaluate(self.jars(**{
            "cache-warm": jar(f"spring: {{}}\n{self.MARKER}\n", platform_class=b"B"),
            "baseline-warm": jar(f"spring: {{}}\n{self.MARKER}\n", platform_class=b"B")}), self.MARKER)
        self.assertEqual(problems, ["between the cold and the warm cached build, ['BOOT-INF/classes/application.yml', "
                                    "'BOOT-INF/lib/parkio-platform.jar!com/parkio/A.class'] changed, not only "
                                    + trial.JAR_RESOURCE])

    def test_an_unreadable_jar_fails(self):
        problems = trial.evaluate(self.jars(**{"baseline-cold": b"not a jar"}), self.MARKER)
        self.assertEqual(problems, ["the baseline-cold jar is not readable"])


class TrialTest(unittest.TestCase):
    def run_trial(self, dockerfile=TRIAL, baseline=BASELINE, **fake):
        tmp = context_with(dockerfile)
        self.addCleanup(tmp.cleanup)
        context, out = Path(tmp.name) / "context", Path(tmp.name) / "out"
        out.mkdir()
        docker = FakeDocker(context, **fake)
        runner = trial.Trial(context, out, "a" * 40, prune=True, run=docker)
        report = runner.service("user-service", baseline)
        runner.cleanup()
        return report, docker, context

    def test_four_builds_in_order_with_the_cache_mounts_emptied_before_the_cold_cache_build(self):
        report, docker, context = self.run_trial()

        self.assertEqual(report["problems"], [])
        self.assertEqual(list(report["builds"]), list(trial.BUILDS))
        builds = [c for c in docker.calls if c[0] in ("build", "builder")]
        self.assertEqual([c[0] if c[0] == "builder" else c[c.index("-t") + 1].split(":")[1] for c in builds],
                         ["baseline-cold", "builder", "cache-cold", "cache-warm", "baseline-warm"])
        self.assertEqual(["--no-cache" in c for c in builds if c[0] == "build"], [True, True, False, False])
        self.assertTrue(all("--mount=type=cache" not in Path(c[c.index("-f") + 1]).read_text()
                            for c in builds if c[0] == "build" and "baseline" in c[c.index("-t") + 1]))
        self.assertEqual((context / "services/user-service" / trial.RESOURCE).read_text(), "spring: {}\n")
        self.assertTrue(any(c[:2] == ["image", "rm"] for c in docker.calls))
        self.assertTrue(report["cache_cold_is_cold"])

    def test_a_stale_cache_is_caught(self):
        report, _, context = self.run_trial(stale_cache=True)

        self.assertIn("the changed input is not in the cache-warm jar: the cache hid the change", report["problems"])
        self.assertEqual((context / "services/user-service" / trial.RESOURCE).read_text(), "spring: {}\n")

    def test_a_cache_that_changes_the_jar_is_caught(self):
        report, _, _ = self.run_trial(extra_with_cache=True)

        self.assertIn("the cold builds give jars with different contents for the same source: BOOT-INF/extra",
                      report["problems"])

    def test_a_dockerfile_with_other_changes_is_refused_before_any_build(self):
        report, docker, _ = self.run_trial(dockerfile=TRIAL.replace("COPY . .", "COPY services services"))

        self.assertIn("the trial Dockerfile differs from the baseline by more than its cache mount", report["problems"])
        self.assertFalse(any(c[0] == "build" for c in docker.calls))


class MainTest(unittest.TestCase):
    def test_an_output_directory_inside_the_context_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(trial.main(["--service", "user-service", "--context", tmp, "--out", f"{tmp}/out"]), 2)
            self.assertFalse(Path(tmp, "out").exists())


if __name__ == "__main__":
    unittest.main(verbosity=1)
