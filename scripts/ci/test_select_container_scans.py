#!/usr/bin/env python3
"""Tests for select_container_scans.py: the container-scan selection fails closed."""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import select_container_scans as sel  # noqa: E402

SERVICES = ["auth-service", "gateway-service", "parking-service"]
ALL = sorted(SERVICES + ["web"])


def pick(*paths):
    return sel.select("pull_request", list(paths), SERVICES)[0]


class SelectionTest(unittest.TestCase):
    def test_every_event_but_a_pull_request_scans_everything(self):
        for event in ("push", "workflow_dispatch", "schedule"):
            self.assertEqual(sel.select(event, None, SERVICES)[0], ALL, event)

    def test_unknown_changed_files_scan_everything(self):
        self.assertEqual(sel.select("pull_request", None, SERVICES)[0], ALL)

    def test_a_service_change_scans_that_service(self):
        self.assertEqual(pick("services/parking-service/src/main/java/A.java"), ["parking-service"])
        self.assertEqual(pick("services/auth-service/Dockerfile"), ["auth-service"])

    def test_shared_build_files_lockfiles_and_tools_scan_every_service(self):
        for path in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
                     "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties",
                     "buildSrc/src/main/kotlin/x.kts", "platform/parkio-platform/build.gradle.kts",
                     "tools/dlt-redrive/build.gradle.kts"):
            self.assertEqual(pick(path), SERVICES, path)

    def test_the_web_inputs_scan_the_web_image(self):
        for path in ("frontend/pnpm-lock.yaml", "frontend/apps/web/Dockerfile", "frontend/apps/web/nginx.conf",
                     "frontend/apps/web/src/main.tsx", "docker/web-hosted-beta.release-bake.env"):
            self.assertEqual(pick(path), ["web"], path)

    def test_scan_inputs_and_the_build_context_filter_scan_everything(self):
        for path in ("scripts/ci/select_container_scans.py", ".github/workflows/security-ci.yml",
                     ".trivyignore.yaml", ".dockerignore"):
            self.assertEqual(pick(path), ALL, path)

    def test_paths_outside_every_image_scan_nothing(self):
        for path in ("docs/operations/x.md", "README.md", "agent-tools/a/b.log", ".github/workflows/frontend-ci.yml",
                     "scripts/deploy-hosted-beta.sh", "docker/docker-compose.yml", "docker/prometheus/alerts.yml",
                     "infra/terraform/main.tf", "web/marketing/index.html"):
            self.assertEqual(pick(path), [], path)

    def test_an_unclassified_path_scans_everything(self):
        for path in ("Makefile", "new-top-level-dir/file.txt", "services/new-service/Dockerfile"):
            self.assertEqual(pick(path), ALL, path)

    def test_a_script_an_image_build_reads_scans_everything(self):
        scripts = {"scripts/build/fetch-base.sh"}
        self.assertEqual(sel.select("pull_request", ["scripts/build/fetch-base.sh"], SERVICES, scripts)[0], ALL)
        self.assertEqual(sel.select("pull_request", ["scripts/deploy.sh"], SERVICES, scripts)[0], [])

    def test_build_read_scripts_are_found_in_dockerfiles_and_the_gradle_build(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "services" / "a-service").mkdir(parents=True)
            (root / "services" / "a-service" / "Dockerfile").write_text("FROM x\nCOPY scripts/build/fetch-base.sh /tmp/\n")
            (root / "build.gradle.kts").write_text('tasks.register<Exec>("x") { commandLine("bash", "scripts/gen/codegen.sh") }\n')
            (root / "frontend" / "apps" / "web").mkdir(parents=True)
            (root / "frontend" / "apps" / "web" / "Dockerfile").write_text("RUN node apps/web/scripts/validate.mjs\n")
            self.assertEqual(sel.build_scripts(root), {"scripts/build/fetch-base.sh", "scripts/gen/codegen.sh"})

    def test_a_mix_scans_the_union(self):
        self.assertEqual(pick("docs/a.md", "services/auth-service/x", "frontend/apps/web/a.ts"), ["auth-service", "web"])

    def test_services_come_from_the_dockerfiles(self):
        with tempfile.TemporaryDirectory() as tmp:
            for name in ("b-service", "a-service"):
                (Path(tmp) / "services" / name).mkdir(parents=True)
                (Path(tmp) / "services" / name / "Dockerfile").write_text("FROM scratch\n")
            (Path(tmp) / "services" / "no-image").mkdir()
            self.assertEqual(sel.services(Path(tmp)), ["a-service", "b-service"])


class ChangedFilesTest(unittest.TestCase):
    def git(self, *args):
        subprocess.run(["git", "-C", self.repo, "-c", "user.name=t", "-c", "user.email=t@example.invalid",
                        "-c", "commit.gpgsign=false", *args], check=True, capture_output=True)

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = self.tmp.name
        self.git("init", "-q", "-b", "api")
        Path(self.repo, "services", "auth-service").mkdir(parents=True)
        Path(self.repo, "services", "auth-service", "A.java").write_text("a\n")
        self.git("add", ".")
        self.git("commit", "-q", "-m", "base")

    def test_a_pull_request_merge_commit_lists_its_changes_including_moves(self):
        self.git("checkout", "-q", "-b", "feature")
        self.git("mv", "services/auth-service/A.java", "docs-A.java")
        self.git("commit", "-q", "-m", "move")
        self.git("checkout", "-q", "api")
        self.git("merge", "-q", "--no-ff", "--no-edit", "feature")
        changed = sel.pull_request_changes(Path(self.repo))
        self.assertIn("services/auth-service/A.java", changed)
        self.assertIn("docs-A.java", changed)

    def test_a_commit_that_is_not_a_merge_gives_no_file_list(self):
        Path(self.repo, "x.txt").write_text("x\n")
        self.git("add", "x.txt")
        self.git("commit", "-q", "-m", "x")
        self.assertIsNone(sel.pull_request_changes(Path(self.repo)))


if __name__ == "__main__":
    unittest.main()
