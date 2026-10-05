#!/usr/bin/env python3
"""Tests for select_container_scans.py: the container-scan selection fails closed."""
import io
import json
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

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

    def test_other_events_scan_everything_even_with_a_file_list(self):
        for event in ("push", "workflow_dispatch", "schedule"):
            self.assertEqual(sel.select(event, ["docs/a.md"], SERVICES)[0], ALL, event)

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

    def test_a_path_an_image_build_names_selects_that_image(self):
        refs = [("scripts", {"auth-service"}), ("docker/web/nginx.conf", {"web"})]
        self.assertEqual(sel.select("pull_request", ["scripts/build/fetch-base.sh"], SERVICES, refs)[0], ["auth-service"])
        self.assertEqual(sel.select("pull_request", ["docker/web/nginx.conf"], SERVICES, refs)[0], ["web"])
        self.assertEqual(sel.select("pull_request", ["docker/docker-compose.yml"], SERVICES, refs)[0], [])

    def test_a_named_path_must_match_whole_path_segments(self):
        refs = [("scripts/a.sh", {"auth-service"})]
        self.assertEqual(sel.select("pull_request", ["scripts/a.sh.bak"], SERVICES, refs)[0], [])
        self.assertEqual(sel.select("pull_request", ["scripts/a.sh"], SERVICES, refs)[0], ["auth-service"])

    def test_a_named_path_adds_to_its_own_rule(self):
        refs = [("docker/web-hosted-beta.release-bake.env", {"auth-service"})]
        self.assertEqual(sel.select("pull_request", ["docker/web-hosted-beta.release-bake.env"], SERVICES, refs)[0],
                         ["auth-service", "web"])

    def test_a_mix_scans_the_union(self):
        self.assertEqual(pick("docs/a.md", "services/auth-service/x", "frontend/apps/web/a.ts"), ["auth-service", "web"])

    def test_services_come_from_the_dockerfiles(self):
        with tempfile.TemporaryDirectory() as tmp:
            for name in ("b-service", "a-service"):
                (Path(tmp) / "services" / name).mkdir(parents=True)
                (Path(tmp) / "services" / name / "Dockerfile").write_text("FROM scratch\n")
            (Path(tmp) / "services" / "no-image").mkdir()
            self.assertEqual(sel.services(Path(tmp)), ["a-service", "b-service"])


class ImageInputRefsTest(unittest.TestCase):
    """N1 (#266 review): scripts/ and docker/ references in every build input, in every form."""

    def refs(self, files):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        tree = {"services/a-service/Dockerfile": "FROM x\n", "services/b-service/Dockerfile": "FROM x\n", **files}
        for name, text in tree.items():
            (root / name).parent.mkdir(parents=True, exist_ok=True)
            (root / name).write_text(text)
        return {prefix: sorted(owners) for prefix, owners in sel.image_input_refs(root)}

    def test_directory_forms_in_a_dockerfile(self):
        for line in ("COPY scripts/ /opt/scripts/", "COPY scripts /opt/scripts", "ADD ./scripts/ /opt/"):
            with self.subTest(line=line):
                self.assertEqual(self.refs({"services/a-service/Dockerfile": f"FROM x\n{line}\n"}),
                                 {"scripts": ["a-service"]})

    def test_a_build_stage_path_and_a_run_command(self):
        dockerfile = ("FROM x AS build\nRUN ./scripts/gen/codegen.sh\nFROM y\n"
                      "COPY --from=build /workspace/scripts/entrypoint.sh /app/\n")
        self.assertEqual(self.refs({"services/a-service/Dockerfile": dockerfile}),
                         {"scripts/entrypoint.sh": ["a-service"], "scripts/gen/codegen.sh": ["a-service"]})

    def test_a_docker_file_in_the_web_dockerfile_and_a_glob(self):
        refs = self.refs({"frontend/apps/web/Dockerfile": "FROM n\nCOPY docker/web/nginx.conf /etc/nginx/\n",
                          "services/b-service/Dockerfile": "FROM x\nCOPY scripts/build/*.sh /tmp/\n"})
        self.assertEqual(refs, {"docker/web/nginx.conf": ["web"], "scripts/build": ["b-service"]})

    def test_gradle_build_files_of_a_service_and_of_the_shared_build(self):
        refs = self.refs({
            "services/a-service/build.gradle.kts": 'tasks.processResources { from(rootProject.file("scripts/gen/x.txt")) }\n',
            "build.gradle.kts": 'val ca = file("docker/certs/ca.pem")\n',
            "platform/p/build.gradle.kts": 'exec { commandLine("bash", "scripts/platform.sh") }\n',
            "tools/t/build.gradle.kts": 'val d = rootProject.file("docker/tools/")\n',
            "buildSrc/src/main/kotlin/c.gradle.kts": 'val s = "scripts/convention.sh"\n',
        })
        self.assertEqual(refs, {
            "docker/certs/ca.pem": ["a-service", "b-service"],
            "docker/tools": ["a-service", "b-service"],
            "scripts/convention.sh": ["a-service", "b-service"],
            "scripts/gen/x.txt": ["a-service"],
            "scripts/platform.sh": ["a-service", "b-service"],
        })

    def test_comments_commands_and_lookalikes_are_not_references(self):
        refs = self.refs({
            "services/a-service/Dockerfile": ("# syntax=docker/dockerfile:1\n# COPY scripts/ /x\nFROM x\n"
                                              "RUN npm ci --ignore-scripts\nENTRYPOINT [\"/docker-entrypoint.sh\"]\n"),
            "services/a-service/build.gradle.kts": "// scraped by docker/prometheus\n/* scripts/old.sh */\n",
            "build.gradle.kts": 'val process = ProcessBuilder("docker", "info")\n',
        })
        self.assertEqual(refs, {})


class MainTest(unittest.TestCase):
    """N2 (#266 review): only a pull request selects from its diff; every other event scans everything."""

    def run_main(self, event, changes):
        calls = []

        def fake_changes(*args, **kwargs):
            calls.append(event)
            return changes

        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(sel, "pull_request_changes", fake_changes), \
                mock.patch.object(sel, "services", lambda root=sel.ROOT: list(SERVICES)), \
                mock.patch.object(sel, "image_input_refs", lambda root=sel.ROOT, known=None: []):
            out = Path(tmp) / "github-output"
            with redirect_stdout(io.StringIO()):
                self.assertEqual(sel.main(["--event", event, "--github-output", str(out)]), 0)
            values = dict(line.split("=", 1) for line in out.read_text().splitlines())
        return json.loads(values["images"]), values["any"], calls

    def test_other_events_scan_everything_without_reading_a_diff(self):
        for event in ("push", "workflow_dispatch", "schedule"):
            with self.subTest(event=event):
                self.assertEqual(self.run_main(event, ["docs/a.md"]), (ALL, "true", []))

    def test_a_pull_request_selects_from_its_diff(self):
        self.assertEqual(self.run_main("pull_request", ["docs/a.md"]), ([], "false", ["pull_request"]))
        self.assertEqual(self.run_main("pull_request", ["services/auth-service/A.java"])[0], ["auth-service"])

    def test_a_pull_request_without_a_file_list_scans_everything(self):
        self.assertEqual(self.run_main("pull_request", None)[:2], (ALL, "true"))


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

    def test_a_git_diff_error_gives_no_file_list(self):
        """N2: a failing diff must not look like a pull request that changes nothing."""
        def git(command):
            if "rev-list" in command:
                return subprocess.CompletedProcess(command, 0, "merge parent-1 parent-2\n", "")
            return subprocess.CompletedProcess(command, 128, "", "fatal: bad object HEAD^1")

        self.assertIsNone(sel.pull_request_changes(Path(self.repo), git))
        self.assertEqual(sel.select("pull_request", None, SERVICES)[0], ALL)


if __name__ == "__main__":
    unittest.main()
