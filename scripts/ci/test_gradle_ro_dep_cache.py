#!/usr/bin/env python3
"""Unit tests for scripts/ci/gradle_ro_dep_cache.py and for its wiring (#638, #653).

The probe runs against a recording fake docker. The wiring tests read the repository's own service
Dockerfiles and workflows. Gradle's own read-only cache behaviour (a cached dependency is not
downloaded, a missing one still fails) is tested on the wrapper's Gradle version in
scripts/ci/test_gradle_dependency_retry.py.

    python3 scripts/ci/test_gradle_ro_dep_cache.py
"""
from __future__ import annotations

import io
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gradle_ro_dep_cache as cache  # noqa: E402

ROOT = cache.ROOT
WORKFLOWS = ROOT / ".github" / "workflows"
CANDIDATE_ACTION = ROOT / ".github" / "actions" / "candidate-image-build" / "action.yml"
SERVICES = sorted(path.parent.name for path in (ROOT / "services").glob("*/Dockerfile"))
CONTEXT = '"gradle-ro-dep-cache=$RUNNER_TEMP/gradle-ro-dep-cache"'
STAGE_COMMAND = 'python3 scripts/ci/gradle_ro_dep_cache.py stage "$RUNNER_TEMP/gradle-ro-dep-cache"'
BACKEND_CI_PREFIX = "gradle-deps-backend-ci-${{ runner.os }}-"
# The jobs that ran Gradle on the runner on setup-java's `cache: gradle`, which saved an entry from pull
# requests too (#653), and the workflows whose api entries each one restores, in restore order.
RUNNER_GRADLE_JOBS = (
    ("auth-csrf-browser-acceptance.yml", "chromium", ("backend-ci.yml",)),
    ("auth-csrf-gateway-https-acceptance.yml", "gateway-https", ("backend-ci.yml",)),
    ("recovery-drill.yml", "drill", ("backend-ci.yml",)),
    ("restore-replay-harness.yml", "harness", ("backend-integration.yml", "backend-ci.yml")),
    ("staging-verification.yml", "structural", ("backend-ci.yml",)),
)


def jar(entries: dict) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    return buffer.getvalue()


def sbom(serial="urn:uuid:1", timestamp="2026-11-25T10:00:00Z", platform_hashes=None, order=("a", "b")):
    platform = {"name": "parkio-platform", "purl": "pkg:maven/com.parkio/parkio-platform@0.0.1-SNAPSHOT"}
    if platform_hashes:
        platform["hashes"] = platform_hashes
    components = [{"name": name} for name in order] + [platform]
    return json.dumps({"bomFormat": "CycloneDX", "serialNumber": serial,
                       "metadata": {"timestamp": timestamp, "component": {"name": "x"}}, "components": components})


def app_jar(serial="urn:uuid:1", timestamp="2026-11-25T10:00:00Z", platform_class=b"class-a", platform_hashes=None,
            order=("a", "b"), platform_date=(2026, 11, 25, 10, 0, 0)) -> bytes:
    platform = io.BytesIO()
    with zipfile.ZipFile(platform, "w") as archive:
        archive.writestr(zipfile.ZipInfo("com/parkio/A.class", date_time=platform_date), platform_class)
    return jar({"BOOT-INF/classes/application.yml": b"server.port: 8085\n",
                "BOOT-INF/lib/parkio-platform-0.0.1-SNAPSHOT.jar": platform.getvalue(),
                "BOOT-INF/lib/kafka-clients.jar": jar({"org/apache/kafka/A.class": b"kafka"}),
                "META-INF/sbom/bom.json": sbom(serial, timestamp, platform_hashes, order)})


def load_yaml(path: Path) -> dict:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def steps_of(path: Path, job: str = None) -> list:
    document = load_yaml(path)
    if job is None:
        return document["runs"]["steps"]
    return document["jobs"][job]["steps"]


def uses(step: dict) -> str:
    return (step.get("uses") or "").split("@")[0]


class StageTest(unittest.TestCase):
    def setUp(self):
        self.work = Path(tempfile.mkdtemp(prefix="ro-stage-test-"))
        self.addCleanup(lambda: subprocess.run(["rm", "-rf", str(self.work)], check=False))
        self.source = self.work / "home" / "caches" / "modules-2"
        artifact = self.source / "files-2.1" / "org.example" / "lib" / "1.0" / "abc" / "lib-1.0.jar"
        artifact.parent.mkdir(parents=True)
        artifact.write_bytes(b"jar")
        (self.source / "metadata-2.107").mkdir()
        (self.source / "metadata-2.107" / "module-metadata.bin").write_bytes(b"index")
        (self.source / "modules-2.lock").write_text("lock")
        (self.source / "metadata-2.107" / "journal.lock").write_text("lock")
        (self.source / "gc.properties").write_text("")

    def test_stages_modules_2_without_lock_files_and_gc_properties(self):
        dest = self.work / "ro"
        self.assertEqual(cache.stage(self.source, dest, self.work / "repository"), (1, 3))
        staged = sorted(str(path.relative_to(dest)) for path in dest.rglob("*") if path.is_file())
        self.assertEqual(staged, ["modules-2/files-2.1/org.example/lib/1.0/abc/lib-1.0.jar",
                                  "modules-2/metadata-2.107/module-metadata.bin"])
        # Hard links on the same file system: staging copies no bytes.
        self.assertEqual((dest / "modules-2/files-2.1/org.example/lib/1.0/abc/lib-1.0.jar").stat().st_ino,
                         (self.source / "files-2.1/org.example/lib/1.0/abc/lib-1.0.jar").stat().st_ino)

    def test_a_cache_miss_leaves_an_empty_context(self):
        dest = self.work / "ro"
        self.assertEqual(cache.stage(self.work / "absent" / "modules-2", dest, self.work / "repository"), (0, 0))
        self.assertTrue(dest.is_dir())
        self.assertEqual(list(dest.iterdir()), [])

    def test_a_previous_context_is_replaced(self):
        dest = self.work / "ro"
        (dest / "modules-2" / "stale").mkdir(parents=True)
        cache.stage(self.source, dest, self.work / "repository")
        self.assertFalse((dest / "modules-2" / "stale").exists())

    def test_refuses_the_repository_a_directory_inside_it_or_above_it(self):
        repository = self.work / "checkout" / "repository"
        (repository / "services").mkdir(parents=True)
        for dest in (repository, repository / "gradle-ro-dep-cache", repository.parent, self.work):
            with self.subTest(dest=dest):
                with self.assertRaisesRegex(ValueError, "the repository"):
                    cache.stage(self.source, dest, repository)
                self.assertTrue((repository / "services").is_dir())

    def test_refuses_the_gradle_cache_a_directory_inside_it_or_above_it(self):
        # `stage ~` or `stage ~/.gradle` would delete the cache it is about to copy.
        home = self.work / "home"
        for dest in (self.source, self.source / "files-2.1", self.source.parent, home):
            with self.subTest(dest=dest):
                with self.assertRaisesRegex(ValueError, "the Gradle cache"):
                    cache.stage(self.source, dest, self.work / "repository")
                self.assertTrue((self.source / "files-2.1").is_dir())

    def test_refuses_to_delete_a_directory_that_holds_more_than_a_staged_cache(self):
        # `stage "$RUNNER_TEMP"` would delete the other steps' files.
        dest = self.work / "runner-temp"
        (dest / "modules-2").mkdir(parents=True)
        (dest / "compose-model.json").write_text("{}")
        with self.assertRaisesRegex(ValueError, "holds more than a staged cache"):
            cache.stage(self.source, dest, self.work / "repository")
        self.assertTrue((dest / "compose-model.json").is_file())
        file_dest = self.work / "a-file"
        file_dest.write_text("x")
        with self.assertRaisesRegex(ValueError, "is not a directory"):
            cache.stage(self.source, file_dest, self.work / "repository")

    def test_the_command_fails_on_a_refused_destination(self):
        err = io.StringIO()
        with mock.patch.dict(os.environ, {"GRADLE_USER_HOME": str(self.work / "home")}), redirect_stderr(err):
            self.assertEqual(cache.main(["stage", str(self.work)]), 2)
        self.assertIn("FAIL", err.getvalue())
        self.assertTrue((self.source / "files-2.1").is_dir())

    def test_the_command_reads_the_gradle_user_home(self):
        out = io.StringIO()
        with mock.patch.dict(os.environ, {"GRADLE_USER_HOME": str(self.work / "home")}), redirect_stdout(out):
            self.assertEqual(cache.main(["stage", str(self.work / "ro")]), 0)
        self.assertIn("1 artifacts", out.getvalue())
        self.assertTrue((self.work / "ro" / "modules-2" / "files-2.1").is_dir())


class ComposeOverrideTest(unittest.TestCase):
    def test_only_builds_from_a_dockerfile_with_the_stage_get_the_context(self):
        model = {"services": {
            "auth-service": {"build": {"context": str(ROOT), "dockerfile": "services/auth-service/Dockerfile"}},
            "web": {"build": {"context": str(ROOT), "dockerfile": "frontend/apps/web/Dockerfile"}},
            "missing": {"build": {"context": str(ROOT), "dockerfile": "services/none/Dockerfile"}},
            "postgres-auth": {"image": "postgis/postgis:16-3.4"},
        }}
        self.assertEqual(cache.compose_override(model, "/runner/temp/gradle-ro-dep-cache"), {"services": {
            "auth-service": {"build": {"additional_contexts": {"gradle-ro-dep-cache": "/runner/temp/gradle-ro-dep-cache"}}},
        }})

    def test_the_command_writes_the_override(self):
        with tempfile.TemporaryDirectory() as tmp:
            model = Path(tmp, "model.json")
            model.write_text(json.dumps({"services": {s: {"build": {"context": str(ROOT),
                                                                   "dockerfile": f"services/{s}/Dockerfile"}}
                                                      for s in SERVICES}}))
            with redirect_stdout(io.StringIO()):
                self.assertEqual(cache.main(["compose-override", str(model), "/r/ro", "--out", f"{tmp}/o.json"]), 0)
            written = json.loads(Path(tmp, "o.json").read_text())
            self.assertEqual(sorted(written["services"]), SERVICES)


class CompareTest(unittest.TestCase):
    def test_two_builds_differ_only_in_what_differs_between_any_two_builds(self):
        # The SBOM's serial number and timestamp, list order, and the platform jar's timestamps.
        later = app_jar(serial="urn:uuid:2", timestamp="2026-11-25T11:00:00Z", order=("b", "a"),
                        platform_date=(2026, 11, 25, 11, 0, 0))
        self.assertNotEqual(app_jar(), later)
        self.assertEqual(cache.compare_jars(app_jar(), later), [])

    def test_the_platform_hashes_that_284_found_are_a_difference(self):
        problems = cache.compare_jars(app_jar(), app_jar(platform_hashes=[{"alg": "SHA-256", "content": "ab"}]))
        self.assertEqual(len(problems), 1)
        self.assertRegex(problems[0], r"^META-INF/sbom/bom\.json: the SBOM differs beyond serialNumber, "
                                      r"metadata\.timestamp \(first at components\[\d\]\.hashes\)$")

    def test_a_changed_entry_in_a_nested_jar_is_a_difference(self):
        self.assertEqual(cache.compare_jars(app_jar(), app_jar(platform_class=b"class-b")),
                         ["BOOT-INF/lib/parkio-platform-0.0.1-SNAPSHOT.jar!/com/parkio/A.class: different contents"])

    def test_an_entry_in_one_jar_only_is_a_difference(self):
        extra = jar({**{info.filename: zipfile.ZipFile(io.BytesIO(app_jar())).read(info)
                        for info in zipfile.ZipFile(io.BytesIO(app_jar())).infolist()},
                     "BOOT-INF/lib/extra.txt": b"x"})
        self.assertEqual(cache.compare_jars(app_jar(), extra),
                         ["BOOT-INF/lib/extra.txt: only in the build with the cache"])

    def test_image_configuration_and_history_must_match(self):
        image = {"Os": "linux", "Architecture": "amd64",
                 "Config": {"Entrypoint": ["java", "-jar", "/app/app.jar"], "User": "parkio",
                            "Labels": {"org.opencontainers.image.revision": "a" * 40}}}
        self.assertEqual(cache.compare_images(image, json.loads(json.dumps(image)), ["RUN x"], ["RUN x"]), [])
        other = json.loads(json.dumps(image))
        other["Config"]["User"] = "root"
        self.assertEqual(cache.compare_images(image, other, ["RUN x"], ["RUN y"]), [
            "image configuration User differs: 'parkio' != 'root'", "the final stage's history differs"])


class FakeDocker:
    """Records docker calls and answers them like a daemon that built the probe's two images."""

    def __init__(self, with_downloads=0, without_downloads=2, with_jar=None, fail_build=None):
        self.calls = []
        self.with_downloads = with_downloads
        self.without_downloads = without_downloads
        self.with_jar = with_jar if with_jar is not None else app_jar(serial="urn:uuid:2")
        self.fail_build = fail_build
        self.containers = {}
        self.staged_context = None

    def __call__(self, args):
        self.calls.append(list(args))
        ok = lambda out="": subprocess.CompletedProcess(args, 0, out, "")  # noqa: E731
        if args[0] == "pull":
            return ok()
        if args[0] == "build":
            tag = args[args.index("-t") + 1]
            if self.fail_build and self.fail_build in tag:
                return subprocess.CompletedProcess(args, 1, "", "ERROR: Received status code 429 from server")
            if "--build-context" in args:
                context_dir = Path(args[args.index("--build-context") + 1].split("=", 1)[1])
                self.staged_context = sorted(str(p.relative_to(context_dir)) for p in context_dir.rglob("*") if p.is_file())
            return ok()
        if args[0] == "create":
            container = f"c{len(self.containers)}"
            self.containers[container] = args[-1]
            return ok(container + "\n")
        if args[0] == "cp":
            container, path = args[1].split(":", 1)
            image, dest = self.containers[container], Path(args[2])
            if path == cache.APP_JAR:
                dest.write_bytes(self.with_jar if image.endswith(":with") else app_jar())
                return ok()
            downloads = self.with_downloads if image.endswith(":with-build") else self.without_downloads
            modules = dest / "modules-2"
            (modules / "metadata-2.107").mkdir(parents=True)
            (modules / "modules-2.lock").write_text("lock")
            for index in range(downloads):
                artifact = modules / "files-2.1" / "org.example" / f"lib{index}" / "1.0" / "h" / f"lib{index}.jar"
                artifact.parent.mkdir(parents=True)
                artifact.write_bytes(b"12345")
            return ok()
        if args[0] == "rm":
            return ok()
        if args[:2] == ["image", "inspect"]:
            return ok(json.dumps([{"Os": "linux", "Architecture": "amd64", "Config": {"User": "parkio"}}]))
        if args[:2] == ["image", "history"]:
            return ok("COPY app.jar\nRUN apt-get install curl\n")
        raise AssertionError(f"unexpected docker call {args}")


class ProbeTest(unittest.TestCase):
    DOCKERFILE = (ROOT / "services" / "gamification-service" / "Dockerfile").relative_to(ROOT).as_posix()

    def run_probe(self, docker: FakeDocker) -> dict:
        ticks = iter([0.0, 100.0, 200.0, 260.0])
        counts = iter([1_000, 501_000, 501_000, 621_000])
        cwd = os.getcwd()
        os.chdir(ROOT)
        self.addCleanup(os.chdir, cwd)
        return cache.probe(self.DOCKERFILE, ".", "a" * 40, run=docker, log_run=docker,
                           clock=lambda: next(ticks), counter=lambda: next(counts))

    def test_two_uncached_builds_the_second_with_the_first_builds_own_dependencies(self):
        docker = FakeDocker()
        report = self.run_probe(docker)
        self.assertEqual(report["result"], "pass", report["problems"])
        pulls = [call[2] for call in docker.calls if call[0] == "pull"]
        self.assertEqual(pulls, cache.base_images((ROOT / self.DOCKERFILE).read_text()))
        self.assertEqual(len(pulls), 2)
        builds = [call for call in docker.calls if call[0] == "build" and "--target" not in call]
        self.assertEqual([call[call.index("-t") + 1] for call in builds],
                         ["gradle-ro-dep-cache-probe:without", "gradle-ro-dep-cache-probe:with"])
        for call in builds:
            self.assertIn("--no-cache", call)
            self.assertIn(f"IMAGE_REVISION={'a' * 40}", call)
        self.assertNotIn("--build-context", builds[0])
        self.assertTrue(builds[1][builds[1].index("--build-context") + 1].startswith("gradle-ro-dep-cache="))
        # The second build's context is the first build's modules-2, without its lock file.
        self.assertEqual(docker.staged_context, [f"modules-2/files-2.1/org.example/lib{i}/1.0/h/lib{i}.jar"
                                                 for i in range(2)])
        self.assertEqual(report["builds"]["without"], {"seconds": 100.0, "received_bytes": 500_000,
                                                       "downloaded_artifacts": 2, "downloaded_bytes": 10,
                                                       "read_only_cache": {"artifacts": 2, "bytes": 10}})
        self.assertEqual(report["builds"]["with"], {"seconds": 60.0, "received_bytes": 120_000,
                                                    "downloaded_artifacts": 0, "downloaded_bytes": 0})
        self.assertEqual(report["saved"], {"seconds": 40.0, "received_bytes": 380_000})
        self.assertIn("same application: yes", cache.summary(report))

    def test_fails_when_the_build_with_the_cache_still_downloads(self):
        report = self.run_probe(FakeDocker(with_downloads=1))
        self.assertEqual(report["result"], "fail")
        self.assertEqual(report["problems"], ["the build with the cache still downloaded 1 artifact(s)"])

    def test_fails_when_the_baseline_downloaded_nothing(self):
        report = self.run_probe(FakeDocker(without_downloads=0))
        self.assertEqual(report["result"], "fail")
        self.assertIn("the build without the cache downloaded no artifact, so it measured nothing", report["problems"])

    def test_fails_when_the_cache_changes_the_application(self):
        report = self.run_probe(FakeDocker(with_jar=app_jar(platform_hashes=[{"alg": "SHA-256", "content": "ab"}])))
        self.assertEqual(report["result"], "fail")
        self.assertEqual(len(report["differences"]), 1)
        self.assertIn("META-INF/sbom/bom.json: the SBOM differs", report["differences"][0])

    def test_a_failed_build_stops_the_probe(self):
        with self.assertRaisesRegex(cache.ProbeError, r"the build without the read-only cache failed \(exit 1\)"):
            self.run_probe(FakeDocker(fail_build=":without"))

    def test_base_images_skip_scratch_and_earlier_stages(self):
        self.assertEqual(cache.base_images("FROM scratch AS gradle-ro-dep-cache\nFROM jdk@sha256:1 AS build\n"
                                           "FROM build AS test\nFROM --platform=linux/amd64 jre AS runtime\n"),
                         ["jdk@sha256:1", "jre"])

    def test_received_bytes_counts_the_default_route_interfaces(self):
        with tempfile.TemporaryDirectory() as tmp:
            dev, route = Path(tmp, "dev"), Path(tmp, "route")
            dev.write_text("Inter-|   Receive\n face |bytes\n    lo: 900 1 0 0 0 0 0 0 900 1\n"
                           "  eth0: 1234 5 0 0 0 0 0 0 99 1\ndocker0: 777 1 0 0 0 0 0 0 1 1\n")
            route.write_text("Iface\tDestination\tGateway\n eth0\t00000000\t010200C0\ndocker0\t000011AC\t00000000\n")
            self.assertEqual(cache.received_bytes(str(dev), str(route)), 1234)


class DockerfileTest(unittest.TestCase):
    """Every service image takes its dependencies from the read-only cache, and from nothing else."""

    RUN = ("RUN --mount=type=bind,from=gradle-ro-dep-cache,target=/gradle-ro-dep-cache \\\n"
           "    if [ -d /gradle-ro-dep-cache/modules-2 ]; then export GRADLE_RO_DEP_CACHE=/gradle-ro-dep-cache; fi \\\n"
           " && ./gradlew :services:{service}:bootJar --no-daemon\n")

    def test_the_gradle_step_mounts_the_empty_stage_read_only(self):
        for service in SERVICES:
            with self.subTest(service=service):
                text = (ROOT / "services" / service / "Dockerfile").read_text(encoding="utf-8")
                self.assertEqual(len(cache.STAGE_LINE.findall(text)), 1)
                self.assertLess(cache.STAGE_LINE.search(text).start(), text.index(" AS build\n"))
                self.assertEqual(text.count(self.RUN.format(service=service)), 1)
                self.assertNotIn("GRADLE_RO_DEP_CACHE", text.replace(self.RUN.format(service=service), ""))
                # No cache mount: #284 found that reusing Gradle's build cache changes the embedded SBOM.
                self.assertNotIn("type=cache", text)
                self.assertNotIn(",rw", text)
                runtime = text[text.index(" AS runtime\n"):]
                self.assertNotIn("gradle-ro-dep-cache", runtime)

    def test_the_build_stages_are_the_same_up_to_the_service_name(self):
        # The probe builds one service; this keeps it representative of all ten.
        def build_stage(service):
            text = (ROOT / "services" / service / "Dockerfile").read_text(encoding="utf-8")
            return text[:text.index("# ---- runtime stage ----")].replace(service, "<service>")

        stages = {service: build_stage(service) for service in SERVICES}
        self.assertEqual(len(SERVICES), 10)
        self.assertEqual(len(set(stages.values())), 1, stages)


class WorkflowWiringTest(unittest.TestCase):
    """Pull requests only restore Gradle dependency caches; api saves them; image builds read them."""

    @classmethod
    def setUpClass(cls):
        cls.backend_ci = steps_of(WORKFLOWS / "backend-ci.yml", "backend-build")

    def restore(self, steps: list) -> dict:
        found = [step for step in steps if uses(step) == "actions/cache/restore"]
        self.assertEqual(len(found), 1, "one actions/cache/restore step")
        return found[0]

    def test_gradle_dependency_caches_restore_and_save_in_separate_steps(self):
        # actions/cache saves in its post step on every event, pull requests included (#636 review).
        files = sorted(WORKFLOWS.glob("*.yml")) + sorted((ROOT / ".github" / "actions").glob("*/action.yml"))
        for path in files:
            document = load_yaml(path)
            jobs = document.get("jobs") or {"action": {"steps": (document.get("runs") or {}).get("steps") or []}}
            for job_id, job in jobs.items():
                for step in job.get("steps") or []:
                    key = str((step.get("with") or {}).get("key", ""))
                    with self.subTest(file=path.name, job=job_id, step=step.get("name")):
                        if key.startswith("gradle-deps-"):
                            self.assertIn(uses(step), ("actions/cache/restore", "actions/cache/save"))

    def test_backend_jobs_save_their_entry_only_on_api(self):
        for workflow, job, prefix in (("backend-ci.yml", "backend-build", "gradle-deps-backend-ci-"),
                                      ("backend-integration.yml", "integration-test", "gradle-deps-backend-integration-")):
            with self.subTest(workflow=workflow):
                steps = steps_of(WORKFLOWS / workflow, job)
                restore = self.restore(steps)
                saves = [step for step in steps if uses(step) == "actions/cache/save"]
                self.assertEqual(len(saves), 1)
                save = saves[0]
                self.assertTrue(restore["with"]["key"].startswith(prefix + "${{ runner.os }}-${{ hashFiles("))
                self.assertEqual(restore["with"]["restore-keys"].strip(), prefix + "${{ runner.os }}-")
                self.assertEqual(save["with"]["path"], restore["with"]["path"])
                self.assertEqual(save["with"]["key"], "${{ steps.%s.outputs.cache-primary-key }}" % restore["id"])
                condition = " ".join(save["if"].split())
                for clause in ("github.event_name != 'pull_request'", "github.ref == 'refs/heads/api'",
                               "steps.%s.outputs.cache-hit != 'true'" % restore["id"]):
                    self.assertIn(clause, condition)
                self.assertNotRegex(condition, r"always\(\)|failure\(\)|cancelled\(\)")
                # Saved after the job's Gradle run succeeded, so it holds what that run resolved.
                gradle = max(i for i, step in enumerate(steps) if "./gradlew" in (step.get("run") or ""))
                self.assertGreater(steps.index(save), gradle)

    def test_the_jobs_once_on_setup_javas_cache_restore_entries_that_only_api_saves(self):
        # setup-java's `cache: gradle` saved an entry from pull requests whenever its key had none (#653). These
        # jobs restore what the backend jobs save on api, with the same path list (a different one is another
        # cache version, which never matches), right after setup-java, so before any Gradle run. The harness
        # runs integration tests, so it takes Backend integration's entry first and Backend CI's without one.
        integration = steps_of(WORKFLOWS / "backend-integration.yml", "integration-test")
        saved = {"backend-ci.yml": self.restore(self.backend_ci), "backend-integration.yml": self.restore(integration)}
        for workflow, job, sources in RUNNER_GRADLE_JOBS:
            with self.subTest(workflow=workflow):
                steps = steps_of(WORKFLOWS / workflow, job)
                restore = self.restore(steps)
                self.assertEqual(restore["with"]["path"], saved[sources[0]]["with"]["path"])
                self.assertEqual(restore["with"]["key"], saved[sources[0]]["with"]["key"])
                self.assertEqual([line.strip() for line in restore["with"]["restore-keys"].splitlines() if line.strip()],
                                 [saved[source]["with"]["restore-keys"].strip() for source in sources])
                setup_java = [step for step in steps if uses(step) == "actions/setup-java"]
                self.assertEqual(len(setup_java), 1)
                self.assertNotIn("cache", setup_java[0].get("with") or {})
                self.assertIs(steps[steps.index(setup_java[0]) + 1], restore)
                saving = [step.get("name") for step in steps if uses(step) in ("actions/cache", "actions/cache/save")]
                self.assertEqual(saving, [])

    def check_image_build(self, steps: list, build_marker: str):
        restore = self.restore(steps)
        reference = self.restore(self.backend_ci)
        self.assertEqual(restore["with"]["path"], reference["with"]["path"])
        self.assertEqual(restore["with"]["restore-keys"].strip(), BACKEND_CI_PREFIX)
        self.assertEqual(restore["with"]["key"], reference["with"]["key"])
        stages = [i for i, step in enumerate(steps) if STAGE_COMMAND in (step.get("run") or "")]
        self.assertEqual(len(stages), 1, "one staging step")
        builds = [i for i, step in enumerate(steps) if build_marker in json.dumps(step)]
        self.assertTrue(builds, f"a build step with {build_marker}")
        self.assertLess(steps.index(restore), stages[0])
        self.assertLess(stages[0], min(builds))

    def test_the_container_scans_build_with_the_cache(self):
        steps = steps_of(WORKFLOWS / "security-ci.yml", "container-images")
        self.check_image_build(steps, "--build-context " + CONTEXT.replace('"', '\\"'))
        build = next(step for step in steps if step.get("name") == "Build image")
        self.assertIn(f'docker build --build-context {CONTEXT} -f "services/${{SERVICE}}/Dockerfile"', build["run"])

    def test_the_compose_jobs_build_with_the_cache(self):
        for workflow in ("runtime-validation", "chaos-validation", "performance-smoke"):
            with self.subTest(workflow=workflow):
                steps = steps_of(WORKFLOWS / f"{workflow}.yml", workflow)
                self.check_image_build(steps, "compose-gradle-ro-dep-cache.json")
                # One logical command per line: continuations joined, runs of blanks collapsed.
                script = re.sub(r"[ \t]+", " ", "\n".join(step.get("run") or "" for step in steps).replace("\\\n", " "))
                override = ('python3 scripts/ci/gradle_ro_dep_cache.py compose-override "$RUNNER_TEMP/compose-model.json" '
                            '"$RUNNER_TEMP/gradle-ro-dep-cache" --out "$RUNNER_TEMP/compose-gradle-ro-dep-cache.json"')
                self.assertTrue(override in script, f"{workflow} writes no compose override")
                # Every image build, by Compose or through compose_image_identity.py, reads the override.
                builds = [line for line in script.splitlines()
                          if re.search(r"(docker compose|compose_image_identity\.py) .*(?<=\s)build(?=\s|$)", line)]
                self.assertTrue(builds)
                for line in builds:
                    self.assertTrue('-f "$RUNNER_TEMP/compose-gradle-ro-dep-cache.json"' in line, line)

    def test_the_published_images_read_no_dependency_cache(self):
        # candidate-publish.yml publishes the candidate images, and release.yml publishes its own. Both
        # resolve every dependency themselves, from a fresh Gradle home: no Actions cache entry may reach the
        # bytes of a published image, because any job on api can save one and nothing verifies the jars
        # (#648 review, blocking 1; owner decision 6 allows no cache on the publication path).
        for path, steps in ((CANDIDATE_ACTION, steps_of(CANDIDATE_ACTION)),
                            (WORKFLOWS / "release.yml",
                             [step for job in load_yaml(WORKFLOWS / "release.yml")["jobs"].values()
                              for step in job.get("steps") or []])):
            with self.subTest(file=path.name):
                text = path.read_text(encoding="utf-8")
                for needle in ("gradle-deps-", "gradle-ro-dep-cache", "gradle_ro_dep_cache", "GRADLE_RO_DEP_CACHE"):
                    self.assertFalse(needle in text, f"{path.name} mentions {needle}")
                for step in steps:
                    self.assertFalse(str((step.get("with") or {}).get("key", "")).startswith("gradle-"), step.get("name"))
                    if uses(step) == "docker/build-push-action":
                        self.assertNotIn("build-contexts", step.get("with") or {})


class GateEvidenceTest(unittest.TestCase):
    def test_the_ci_gate_holds_this_suite_to_its_test_count(self):
        # The CI gate reads this suite's `Ran N tests` (.github/ci-gate-policy.json). With the floor at the test
        # count, a test that stops running fails the gate, and a new test raises the floor in the same change.
        policy = json.loads((ROOT / ".github" / "ci-gate-policy.json").read_text(encoding="utf-8"))
        (rule,) = [rule for rule in policy["evidence"] if rule["workflow"] == "image-build-dependency-cache.yml"]
        self.assertEqual(rule["job"], "Read-only dependency cache tests (no Docker)")
        self.assertEqual(rule["min"], unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__]).countTestCases())


if __name__ == "__main__":
    unittest.main(verbosity=2)
