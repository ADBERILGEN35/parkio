#!/usr/bin/env python3
"""Tests the Gradle download retry budget that gradle.properties sets (#608).

Each test starts a local Maven repository that answers chosen requests with an HTTP error, then runs
the wrapper's Gradle version, with this repository's gradle.properties, on a probe build that resolves
one artifact from it. Every run gets a fresh Gradle user home, so nothing comes from a cache.

    python3 scripts/ci/test_gradle_dependency_retry.py
"""

from __future__ import annotations

import glob
import os
import re
import shutil
import subprocess
import tempfile
import threading
import unittest
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GROUP_PATH = "repo/org/example"

PROBE_SETTINGS = 'rootProject.name = "dependency-retry-probe"\n'
PROBE_BUILD = """\
val probe by configurations.creating
repositories {
    maven {
        url = uri(providers.systemProperty("probe.repo").get())
        isAllowInsecureProtocol = true
    }
}
dependencies {
    probe(providers.systemProperty("probe.dependency").get())
}
tasks.register("resolveProbe") {
    val files = probe
    doLast { files.files.forEach { println("resolved " + it.name) } }
}
"""
POM = """\
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.example</groupId>
  <artifactId>{name}</artifactId>
  <version>1.0</version>
</project>
"""


def gradle_property(name: str) -> str:
    for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        key, sep, value = line.partition("=")
        if sep and key.strip() == name:
            return value.strip()
    raise AssertionError(f"gradle.properties does not set {name}")


def wrapper_gradle() -> str:
    """The launcher of the distribution that gradle/wrapper/gradle-wrapper.properties names."""
    subprocess.run([str(ROOT / "gradlew"), "--version", "--quiet"], cwd=ROOT, check=True,
                   stdout=subprocess.DEVNULL, timeout=600)
    url = next(line.split("=", 1)[1] for line in
               (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8").splitlines()
               if line.startswith("distributionUrl="))
    dist = url.rsplit("/", 1)[1].removesuffix(".zip")  # e.g. gradle-8.14.6-bin
    version = dist.removesuffix("-bin").removesuffix("-all")  # e.g. gradle-8.14.6
    user_home = os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))
    found = glob.glob(os.path.join(user_home, "wrapper", "dists", dist, "*", version, "bin", "gradle"))
    if not found:
        raise AssertionError(f"no {version} launcher under {user_home}/wrapper/dists/{dist}")
    return found[0]


class FaultRepository:
    """Serves the probe artifacts. The first ``count`` requests for a path get ``status`` instead."""

    def __init__(self, root: Path, faults: dict[str, tuple[int, int]]):
        self.root = root
        self.faults = {path: list(rule) for path, rule in faults.items()}
        self.requests: list[tuple[str, int]] = []
        self.lock = threading.Lock()
        repository = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802 (http.server API)
                repository.answer(self)

            def do_HEAD(self):  # noqa: N802
                repository.answer(self, body=False)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.server.server_address[1]}/repo"

    def answer(self, handler: BaseHTTPRequestHandler, body: bool = True) -> None:
        path = handler.path.lstrip("/")
        with self.lock:
            rule = self.faults.get(path)
            if rule and rule[1] != 0:
                rule[1] -= 1
                status, content = rule[0], b""
            elif (self.root / path).is_file():
                status, content = 200, (self.root / path).read_bytes()
            else:
                status, content = 404, b""
            self.requests.append((path, status))
        handler.send_response(status)
        handler.send_header("Content-Length", str(len(content)))
        handler.end_headers()
        if body and content:
            handler.wfile.write(content)

    def statuses(self, path: str) -> list[int]:
        with self.lock:
            return [status for requested, status in self.requests if requested == path]

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.server.shutdown()
        self.server.server_close()


@unittest.skipUnless(os.name == "posix", "runs the Unix Gradle launcher")
class GradleDependencyRetryTest(unittest.TestCase):
    gradle: str

    @classmethod
    def setUpClass(cls):
        cls.gradle = wrapper_gradle()

    def setUp(self):
        self.work = Path(tempfile.mkdtemp(prefix="gradle-retry-"))
        self.addCleanup(shutil.rmtree, self.work, True)
        self.repo_root = self.work / "repository"
        lib = self.repo_root / GROUP_PATH / "lib" / "1.0"
        lib.mkdir(parents=True)
        (lib / "lib-1.0.pom").write_text(POM.format(name="lib"), encoding="utf-8")
        with zipfile.ZipFile(lib / "lib-1.0.jar", "w") as jar:
            jar.writestr("probe.txt", "probe\n")
        self.project = self.work / "project"
        self.project.mkdir()
        (self.project / "settings.gradle.kts").write_text(PROBE_SETTINGS, encoding="utf-8")
        (self.project / "build.gradle.kts").write_text(PROBE_BUILD, encoding="utf-8")
        shutil.copyfile(ROOT / "gradle.properties", self.project / "gradle.properties")

    def resolve(self, repository: FaultRepository, dependency: str, *extra: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [self.gradle, "-p", str(self.project), "-g", str(self.work / "gradle-home"), "--no-daemon",
             "--console=plain", f"-Dprobe.repo={repository.url}", f"-Dprobe.dependency={dependency}",
             *extra, "resolveProbe"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=600)

    def test_a_429_burst_longer_than_gradles_default_budget_is_retried(self):
        # Gradle's default is 3 attempts, so 4 answers of 429 failed the build before #608.
        pom = f"{GROUP_PATH}/lib/1.0/lib-1.0.pom"
        with FaultRepository(self.repo_root, {pom: (429, 4)}) as repository:
            result = self.resolve(repository, "org.example:lib:1.0")
            self.assertEqual(result.returncode, 0, result.stdout[-3000:])
            self.assertIn("resolved lib-1.0.jar", result.stdout)
            self.assertEqual(repository.statuses(pom), [429, 429, 429, 429, 200])

    def test_the_retries_stop_after_the_configured_number_of_attempts(self):
        # The wait between attempts is shortened here; the number of attempts is the configured one.
        attempts = int(gradle_property("systemProp.org.gradle.internal.repository.max.tentatives"))
        pom = f"{GROUP_PATH}/lib/1.0/lib-1.0.pom"
        with FaultRepository(self.repo_root, {pom: (429, -1)}) as repository:
            result = self.resolve(repository, "org.example:lib:1.0",
                                  "-Dorg.gradle.internal.repository.initial.backoff=10")
            self.assertNotEqual(result.returncode, 0, result.stdout[-3000:])
            self.assertRegex(result.stdout, r"Received status code 429 from server")
            self.assertEqual(repository.statuses(pom), [429] * attempts)

    def test_a_missing_artifact_still_fails_at_once(self):
        pom = f"{GROUP_PATH}/missing/1.0/missing-1.0.pom"
        with FaultRepository(self.repo_root, {}) as repository:
            result = self.resolve(repository, "org.example:missing:1.0")
            self.assertNotEqual(result.returncode, 0, result.stdout[-3000:])
            self.assertTrue(re.search(r"Could not find org\.example:missing:1\.0", result.stdout),
                            result.stdout[-3000:])
            self.assertEqual(repository.statuses(pom), [404])


if __name__ == "__main__":
    unittest.main(verbosity=2)
